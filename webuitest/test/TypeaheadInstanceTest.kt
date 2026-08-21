package io.kotgent.webuitest

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one property of `components/Typeahead.js` that its own module header names as the defect class the
 * primitive exists to remove: the choice lives in a `useSignal` created per hook call, so two mounted
 * pickers do not share one active row. A module-level `signal()` there would pass every other tier in
 * this repository, because no screen in the app opens two pickers at once and nothing else mounts the
 * hook twice.
 *
 * The two instances are therefore mounted here, in the page realm, against the served module and the
 * import map the app itself resolves through — the same technique as `SignalsVendorTest`, for the same
 * reason: hooks only run inside a real render, and this claim is about what a second instance sees. The
 * lists deliberately share their keys, because a choice that is not in the other picker's list would be
 * discarded by `resolveActiveKey` and a shared signal would go unnoticed.
 *
 * Navigation is driven by real key presses on a real focused input, not by calling the handler.
 */
class TypeaheadInstanceTest {

    @Test
    fun twoMountedPickersDoNotShareOneActiveRow() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("typeahead-two-instances") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#empty-sessions")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))

                        val moduleUrl = page.evaluate(MOUNT_PICKERS) as String
                        assertTrue(
                            moduleUrl.endsWith("/components/Typeahead.js"),
                            "the probe mounted the served hook, was '$moduleUrl'",
                        )

                        assertThat(active("one", page)).hasText(FIRST)
                        assertThat(active("two", page)).hasText(FIRST)

                        page.locator("#typeahead-probe-input-one").press("ArrowDown")
                        assertThat(active("one", page)).hasText(SECOND)
                        assertThat(active("two", page)).hasText(
                            FIRST,
                        )

                        // And the second instance's own navigation composes from its own choice rather
                        // than from the first one's.
                        page.locator("#typeahead-probe-input-two").press("ArrowDown")
                        page.locator("#typeahead-probe-input-two").press("ArrowDown")
                        assertThat(active("two", page)).hasText(THIRD)
                        assertThat(active("one", page)).hasText(SECOND)

                        // Enter commits the row its own picker is showing, to its own callback.
                        page.locator("#typeahead-probe-input-one").press("Enter")
                        page.locator("#typeahead-probe-input-two").press("Enter")
                        assertEquals(
                            "one:$SECOND two:$THIRD",
                            page.evaluate("() => window.__kotgentTypeaheadProbe.commits.join(' ')"),
                            "each Enter committed the row its own picker had active",
                        )

                        page.evaluate(UNMOUNT_PICKERS)
                        assertThat(page.locator("#typeahead-probe")).hasCount(0)
                    }
                }
            }
        }
    }

    private fun active(id: String, page: com.microsoft.playwright.Page) =
        page.locator("#typeahead-probe-active-$id")

    private companion object {
        const val FIRST = "local:1"
        const val SECOND = "local:2"
        const val THIRD = "local:3"
    }
}

// Two independent pickers over the same option keys. Same keys on purpose: a shared choice signal would
// otherwise name a row the other list does not offer, which `resolveActiveKey` discards — and the bug
// would hide behind its own fallback.
private val MOUNT_PICKERS: String = """
    async () => {
      const tag = document.querySelector('script[type="importmap"]');
      if (!tag) throw new Error("the served page carries no import map");
      const preact = await import(JSON.parse(tag.textContent).imports["preact"]);
      const moduleUrl = new URL("/components/Typeahead.js", location.href).href;
      const { useTypeahead } = await import(moduleUrl);

      const host = document.createElement("div");
      host.id = "typeahead-probe";
      document.body.appendChild(host);

      const probe = { commits: [], host: host, render: preact.render };
      window.__kotgentTypeaheadProbe = probe;

      const KEYS = ["local:1", "local:2", "local:3"];

      function Picker(props) {
        const typeahead = useTypeahead({
          keys: KEYS,
          onCommit: (key) => { probe.commits.push(props.name + ":" + key); },
        });
        return preact.h(
          "div",
          null,
          preact.h("input", {
            id: "typeahead-probe-input-" + props.name,
            onKeyDown: typeahead.keyDown,
          }),
          preact.h(
            "p",
            { id: "typeahead-probe-active-" + props.name },
            String(typeahead.activeKey),
          ),
        );
      }

      preact.render(
        preact.h("div", null, preact.h(Picker, { name: "one" }), preact.h(Picker, { name: "two" })),
        host,
      );
      return moduleUrl;
    }
""".trimIndent()

private val UNMOUNT_PICKERS: String = """
    () => {
      const probe = window.__kotgentTypeaheadProbe;
      probe.render(null, probe.host);
      probe.host.remove();
    }
""".trimIndent()
