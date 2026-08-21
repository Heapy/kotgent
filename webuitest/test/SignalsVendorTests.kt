package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate for vendoring `@preact/signals`.
 *
 * Export compatibility is a source question and is answered in `test/transport/WebUiServingTest.kt`.
 * This class answers the one question no source scan can: the adapter drives Preact through *mangled*
 * property names (`options.__b`/`__r`/`__e`/`__h`, and `vnode.__`, `.__c`, `.__e`, `.__v`, `.__np` on
 * the values it walks), so it only works if the vendored Preact build was minified with the same
 * official mangle mapping. Nothing but execution proves that, so this is a browser test.
 *
 * The probe is built and torn down inside the page. No throwaway signal UI is added to
 * `resources/webui/`, and the live app keeps rendering underneath it — which is itself part of the
 * proof, since the adapter patches the one shared `options` object the app already renders through.
 */
class SignalsVendorTests {

    @Test
    fun theVendoredAdapterDrivesTheVendoredPreactThroughItsMangledInternals() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("signals-vendor") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.addInitScript(ERROR_RECORDER)
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#empty-sessions")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
                        val errorsBeforeTheProbe = page.recordedErrors()

                        val adapterUrl = page.evaluate(MOUNT_PROBE) as String
                        assertTrue(
                            adapterUrl.endsWith("/vendor/signals.module.js"),
                            "the probe imported the served adapter, was '$adapterUrl'",
                        )

                        // The import map resolved, the adapter loaded, and a plain Preact tree carrying
                        // signals rendered — the `__b`/`diffed` attribute path and `useComputed` included.
                        assertThat(page.locator(CHILD)).hasText("child-one")
                        assertThat(page.locator(READ)).hasText("read-one")
                        assertThat(page.locator(DERIVED)).hasText("READ-ONE")
                        assertThat(page.locator(LINE)).hasAttribute("data-child", "child-one")
                        assertEquals(1, page.renderCount(), "mounting the probe rendered it exactly once")

                        // A signal placed *in* the tree updates the DOM through the adapter's own text
                        // and attribute bindings, which reach `vnode.__e` and the component chain via
                        // `.__`/`.__c`. A mangle mismatch cannot produce this: the component never re-runs.
                        page.evaluate(WRITE_CHILD)
                        assertThat(page.locator(CHILD)).hasText("child-two")
                        assertThat(page.locator(LINE)).hasAttribute("data-child", "child-two")
                        assertThat(page.locator(READ)).hasText("read-one")
                        assertEquals(
                            1,
                            page.renderCount(),
                            "a signal rendered into the tree updates the DOM without re-rendering its owner",
                        )

                        // Reading `.value` in the render body subscribes the component itself, which the
                        // adapter implements through `options.__r` and the `__$u` updater it hangs on the
                        // instance. This is the path the app's own state modules will use.
                        page.evaluate(WRITE_READ)
                        assertThat(page.locator(READ)).hasText("read-two")
                        assertThat(page.locator(DERIVED)).hasText("READ-TWO")
                        assertThat(page.locator(CHILD)).hasText("child-two")
                        assertEquals(
                            2,
                            page.renderCount(),
                            "a subscribed read re-renders the owner exactly once per write",
                        )

                        // Unmounting exercises the adapter's `unmount` hook, which disposes the bindings
                        // it stored on the DOM node and the instance.
                        page.evaluate(UNMOUNT_PROBE)
                        assertThat(page.locator(HOST)).hasCount(0)

                        assertThat(page.locator("#empty-sessions")).isVisible()
                        assertEquals(
                            errorsBeforeTheProbe,
                            page.recordedErrors(),
                            "the probe raised no uncaught error, including inside the adapter's " +
                                "microtask-batched effects",
                        )
                    }
                }
            }
        }
    }

    private fun Page.renderCount(): Int =
        (evaluate("() => window.__kotgentSignalProbe.renders") as Number).toInt()

    private fun Page.recordedErrors(): String =
        evaluate("() => window.__kotgentPageErrors.join(' | ')") as String
}

private const val HOST = "#signals-probe"
private const val LINE = "#signals-probe-line"
private const val CHILD = "#signals-probe-child"
private const val READ = "#signals-probe-read"
private const val DERIVED = "#signals-probe-derived"

// Installed before navigation so it precedes the app's own modules. Resource load errors do not bubble
// to window in this phase, so only genuine uncaught exceptions and rejections are recorded.
private val ERROR_RECORDER: String = """
    (() => {
      window.__kotgentPageErrors = [];
      window.addEventListener("error", (event) => {
        window.__kotgentPageErrors.push(String(event.message));
      });
      window.addEventListener("unhandledrejection", (event) => {
        window.__kotgentPageErrors.push(String(event.reason));
      });
    })();
""".trimIndent()

// The URLs come from the page's own import map, so this imports exactly what the daemon serves and what
// the browser would resolve `"preact"` and `"@preact/signals"` to inside the app's own modules. The
// adapter's internal bare imports resolve through the same map, so it patches the same Preact instance.
private val MOUNT_PROBE: String = """
    async () => {
      const tag = document.querySelector('script[type="importmap"]');
      if (!tag) throw new Error("the served page carries no import map");
      const imports = JSON.parse(tag.textContent).imports;
      const preactUrl = imports["preact"];
      const adapterUrl = imports["@preact/signals"];
      if (!preactUrl || !adapterUrl) {
        throw new Error("the import map is missing preact or @preact/signals: " + tag.textContent);
      }
      const preact = await import(preactUrl);
      const signals = await import(adapterUrl);

      const host = document.createElement("div");
      host.id = "signals-probe";
      document.body.appendChild(host);

      const probe = {
        child: signals.signal("child-one"),
        read: signals.signal("read-one"),
        renders: 0,
        host: host,
        render: preact.render,
      };
      window.__kotgentSignalProbe = probe;

      function Probe() {
        probe.renders += 1;
        const derived = signals.useComputed(() => probe.read.value.toUpperCase());
        return preact.h(
          "p",
          { id: "signals-probe-line", "data-child": probe.child },
          preact.h("span", { id: "signals-probe-child" }, probe.child),
          preact.h("span", { id: "signals-probe-read" }, probe.read.value),
          preact.h("span", { id: "signals-probe-derived" }, derived.value),
        );
      }

      preact.render(preact.h(Probe, null), host);
      return adapterUrl;
    }
""".trimIndent()

private val WRITE_CHILD: String = """
    () => { window.__kotgentSignalProbe.child.value = "child-two"; }
""".trimIndent()

private val WRITE_READ: String = """
    () => { window.__kotgentSignalProbe.read.value = "read-two"; }
""".trimIndent()

private val UNMOUNT_PROBE: String = """
    () => {
      const probe = window.__kotgentSignalProbe;
      probe.render(null, probe.host);
      probe.host.remove();
    }
""".trimIndent()
