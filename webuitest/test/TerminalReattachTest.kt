package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PageAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalReattachTest {

    @Test
    fun aDaemonRestartReopensTheTerminalSocketAndItsBytesComeFromANewChild() {
        withRestartPage("reattach-after-daemon-restart") { harness, page ->
            openSession(page, SESSION_A)
            val host = page.locator(TERMINAL_HOST)
            containsSoon(host, BANNER)

            assertEquals(200, postTerminalInput(page, SESSION_A, MARKER))
            containsSoon(host, MARKER)

            harness.send("restart")

            awaitSocketGeneration(page, TERMINAL_SOCKET, 2)
            containsSoon(host, BANNER)
            lacksSoon(host, MARKER)
        }
    }

    @Test
    fun aDroppedTerminalSocketIsReattachedWhileTheDaemonStaysUp() {
        withRestartPage("reattach-after-socket-drop") { _, page ->
            openSession(page, SESSION_A)
            val host = page.locator(TERMINAL_HOST)
            containsSoon(host, BANNER)

            closeNewestSocket(page, TERMINAL_SOCKET)

            awaitSocketGeneration(page, TERMINAL_SOCKET, 2)
            containsSoon(host, BANNER)
            hasCountSoon(page.locator(TERMINAL_HINT), 0)
        }
    }

    @Test
    fun anUnreachableDaemonKeepsTheCandidateAndTheRecoveredEventsSocketSpendsIt() {
        withRestartPage("reattach-events-socket-recovery") { _, page ->
            val attempts = AtomicInteger()
            val mode = AtomicReference(LIVENESS_ABORT)
            interceptLiveness(page, SESSION_A, mode, attempts)

            openSession(page, SESSION_A)
            val host = page.locator(TERMINAL_HOST)
            containsSoon(host, BANNER)

            closeNewestSocket(page, TERMINAL_SOCKET)

            hasTextSoon(page.locator(TERMINAL_HINT), DETACHED_HINT)
            assertEquals(1, attempts.get(), "the close spent the attachment's grant on exactly one attempt")
            assertEquals(
                1,
                socketCount(page, TERMINAL_SOCKET),
                "a failed attempt opens no replacement terminal socket",
            )

            mode.set(LIVENESS_PASS)
            val eventsBefore = socketCount(page, EVENTS_SOCKET)
            closeNewestSocket(page, EVENTS_SOCKET)
            awaitSocketGeneration(page, EVENTS_SOCKET, eventsBefore + 1)

            awaitSocketGeneration(page, TERMINAL_SOCKET, 2)
            containsSoon(host, BANNER)
            assertEquals(2, attempts.get(), "the recovered events socket granted exactly one more attempt")
            hasCountSoon(page.locator(TERMINAL_HINT), 0)
        }
    }

    @Test
    fun aDefiniteAnswerAboutTheSessionDestroysTheReattachCandidate() {
        withRestartPage("reattach-definite-answer") { _, page ->
            val attempts = AtomicInteger()
            val mode = AtomicReference(LIVENESS_GONE)
            interceptLiveness(page, SESSION_A, mode, attempts)

            openSession(page, SESSION_A)
            val host = page.locator(TERMINAL_HOST)
            containsSoon(host, BANNER)

            closeNewestSocket(page, TERMINAL_SOCKET)
            hasTextSoon(page.locator(TERMINAL_HINT), DETACHED_HINT)
            assertEquals(1, attempts.get())

            mode.set(LIVENESS_PASS)
            val eventsBefore = socketCount(page, EVENTS_SOCKET)
            closeNewestSocket(page, EVENTS_SOCKET)
            awaitSocketGeneration(page, EVENTS_SOCKET, eventsBefore + 1)
            settleQueuedWork(page)

            assertEquals(1, attempts.get(), "a destroyed candidate re-asks the doomed question no more")
            assertEquals(1, socketCount(page, TERMINAL_SOCKET), "and opens no replacement socket")
            hasCountSoon(page.locator("$TERMINAL_HOST .xterm"), 0)
            hasTextSoon(page.locator(TERMINAL_HINT), DETACHED_HINT)
        }
    }

    @Test
    fun switchingToTheOtherSessionDestroysTheCandidateAndChangesTheRoute() {
        withRestartPage("reattach-switch-session") { harness, page ->
            val attempts = AtomicInteger()
            val mode = AtomicReference(LIVENESS_ABORT)
            interceptLiveness(page, SESSION_A, mode, attempts)

            openSession(page, SESSION_A)
            containsSoon(page.locator(TERMINAL_HOST), BANNER)
            assertThat(page).hasURL(
                harness.baseUrl + "/s/" + SESSION_A,
                PageAssertions.HasURLOptions().setTimeout(WAIT_MS),
            )

            closeNewestSocket(page, TERMINAL_SOCKET)
            hasTextSoon(page.locator(TERMINAL_HINT), DETACHED_HINT)
            assertEquals(1, attempts.get())

            openSession(page, SESSION_B)

            assertThat(page).hasURL(
                harness.baseUrl + "/s/" + SESSION_B,
                PageAssertions.HasURLOptions().setTimeout(WAIT_MS),
            )
            assertThat(page.locator(sessionRow(SESSION_B)))
                .hasAttribute("aria-current", "true", LocatorAssertions.HasAttributeOptions().setTimeout(WAIT_MS))

            mode.set(LIVENESS_PASS)
            val eventsBefore = socketCount(page, EVENTS_SOCKET)
            closeNewestSocket(page, EVENTS_SOCKET)
            awaitSocketGeneration(page, EVENTS_SOCKET, eventsBefore + 1)
            settleQueuedWork(page)

            assertEquals(
                1,
                attempts.get(),
                "selecting another session discarded the candidate, so no later grant re-asks about it",
            )
            assertThat(page).hasURL(
                harness.baseUrl + "/s/" + SESSION_B,
                PageAssertions.HasURLOptions().setTimeout(WAIT_MS),
            )
        }
    }
}


private const val SESSION_A = "s-restart-a"
private const val SESSION_B = "s-restart-b"

private const val BANNER = "KOTGENT-RESTART-READY"

private const val MARKER = "MARKER-BEFORE-RESTART"

private const val DETACHED_HINT = "Terminal detached."

private const val TERMINAL_HOST = "#terminal-host"
private const val TERMINAL_HINT = "#terminal-hint"
private const val TERMINAL_SOCKET = "/terminal"
private const val EVENTS_SOCKET = "/events"

private const val LIVENESS_ABORT = "abort"
private const val LIVENESS_GONE = "gone"
private const val LIVENESS_PASS = "pass"

private const val WAIT_MS = 20_000.0

// Keeping closed sockets turns each reconnect into an observable generation count.
private val RECORD_SOCKETS = """
    (() => {
      const Native = window.WebSocket;
      window.__kotgentSockets = [];
      class RecordingWebSocket extends Native {
        constructor(...args) {
          super(...args);
          window.__kotgentSockets.push(this);
        }
      }
      window.WebSocket = RecordingWebSocket;
    })()
""".trimIndent()

private fun withRestartPage(traceName: String, body: (Harness, Page) -> Unit) {
    Harness(RESTART_SCENARIO).use { harness ->
        onChromium { browser ->
            browser.touchContext().use { context ->
                context.setDefaultTimeout(WAIT_MS)
                context.addInitScript(RECORD_SOCKETS)
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                context.traced(traceName) {
                    val page = context.newPage()
                    // Reconnect grants check visibilityState and ignore a background page.
                    page.bringToFront()
                    page.navigate(harness.baseUrl + "/")
                    body(harness, page)
                }
            }
        }
    }
}

private fun sessionRow(id: String) = "li.session-row[data-id='$id']"

private fun openSession(page: Page, id: String) {
    page.locator("#drawer-toggle").click()
    page.locator(sessionRow(id)).click()
}

private fun postTerminalInput(page: Page, id: String, text: String): Int {
    val status = page.evaluate(
        """
        async ([id, text]) => {
          const resp = await fetch("/api/v1/sessions/" + encodeURIComponent(id) + "/input", {
            method: "POST",
            credentials: "same-origin",
            body: text,
          });
          return resp.status;
        }
        """.trimIndent(),
        listOf(id, text),
    )
    return (status as Number).toInt()
}

private fun socketCount(page: Page, kind: String): Int {
    val count = page.evaluate(
        "(kind) => window.__kotgentSockets.filter((s) => s.url.includes(kind)).length",
        kind,
    )
    return (count as Number).toInt()
}

private fun awaitSocketGeneration(page: Page, kind: String, generation: Int) {
    page.waitForFunction(
        """
        ([kind, want]) => {
          const list = window.__kotgentSockets.filter((s) => s.url.includes(kind));
          return list.length >= want && list[list.length - 1].readyState === 1;
        }
        """.trimIndent(),
        listOf(kind, generation),
        Page.WaitForFunctionOptions().setTimeout(WAIT_MS),
    )
}

private fun closeNewestSocket(page: Page, kind: String) {
    page.evaluate(
        """
        (kind) => {
          const list = window.__kotgentSockets.filter((s) => s.url.includes(kind));
          const socket = list[list.length - 1];
          if (!socket) throw new Error("no socket matching " + kind);
          socket.close();
        }
        """.trimIndent(),
        kind,
    )
}

private fun settleQueuedWork(page: Page) {
    // open fires after readyState changes; timer -> daemon round trip -> timer drains both queued stages.
    page.evaluate(
        """
        async () => {
          await new Promise((resolve) => setTimeout(resolve, 0));
          await fetch("/api/v1/version", { credentials: "same-origin" }).catch(() => {});
          await new Promise((resolve) => setTimeout(resolve, 0));
        }
        """.trimIndent(),
    )
}

private fun interceptLiveness(
    page: Page,
    id: String,
    mode: AtomicReference<String>,
    attempts: AtomicInteger,
) {
    val suffix = "/api/v1/sessions/$id"
    page.route({ url: String -> url.endsWith(suffix) }) { intercepted ->
        if (!intercepted.request().method().equals("GET", ignoreCase = true)) {
            intercepted.resume()
        } else {
            attempts.incrementAndGet()
            when (mode.get()) {
                LIVENESS_ABORT -> intercepted.abort()
                LIVENESS_GONE -> intercepted.fulfill(
                    Route.FulfillOptions()
                        .setStatus(404)
                        .setContentType("text/plain")
                        .setBody("no such session $id"),
                )
                else -> intercepted.resume()
            }
        }
    }
}

private fun containsSoon(locator: Locator, text: String) {
    assertThat(locator).containsText(text, LocatorAssertions.ContainsTextOptions().setTimeout(WAIT_MS))
}

private fun lacksSoon(locator: Locator, text: String) {
    assertThat(locator).not()
        .containsText(text, LocatorAssertions.ContainsTextOptions().setTimeout(WAIT_MS))
}

private fun hasTextSoon(locator: Locator, text: String) {
    assertThat(locator).hasText(text, LocatorAssertions.HasTextOptions().setTimeout(WAIT_MS))
}

private fun hasCountSoon(locator: Locator, count: Int) {
    assertThat(locator).hasCount(count, LocatorAssertions.HasCountOptions().setTimeout(WAIT_MS))
}
