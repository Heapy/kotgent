package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRevMergeTest {

    @Test
    fun aStaleActionResponseCannotRollBackARowTheFrameAlreadyMoved() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("session-rev-merge") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()

                        val held = AtomicReference<Route?>(null)
                        page.route(
                            Predicate<String> { url -> url.endsWith(INTERRUPT_PATH) },
                            Consumer<Route> { route ->
                                if (!held.compareAndSet(null, route)) route.resume()
                            },
                        )

                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertThat(badge(page)).hasText(RUNNING)

                        page.locator("#session-list .session-row[data-id='$SESSION']").click()
                        assertThat(page.locator("#terminal-host")).isVisible()
                        interruptFromPalette(page)

                        page.waitForCondition { held.get() != null }
                        val stale = held.get()!!.fetch()
                        assertThat(badge(page)).hasText(READY)

                        harness.send("emit $SESSION $NEWER_STATE")
                        assertThat(badge(page)).hasText(NEWER_LABEL)

                        held.get()!!.fulfill(Route.FulfillOptions().setResponse(stale))
                        assertThat(page.locator("#status-line")).containsText(INTERRUPT_DONE)

                        assertThat(badge(page)).hasText(NEWER_LABEL)
                    }
                }
            }
        }
    }

    // The equal-revision fall-through in app.js's applySessionPatch. A redelivered frame merges into
    // nothing — the revision arithmetic declines it — and the path deliberately continues to the read
    // POST anyway, because when unread and seq never move, a redelivery is the only trigger a POST that
    // already failed will ever get. Re-adding an early `if (!changed) return;` there passes every other
    // test in this repository.
    //
    // The frame is redelivered by dispatching it on the app's own events socket. That is a synthesized
    // event, which docs/TESTING.md allows only with a reason: the rule exists because a synthesized
    // *gesture* proves a listener runs rather than that the platform routes the gesture, and there is no
    // gesture here. What is replayed is the daemon's own frame, byte for byte, on the real socket the
    // app is listening to — and no fixture can make a daemon send the same revision twice.
    @Test
    fun aRedeliveredFrameRetriesAReadPostThatAlreadyFailed() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("session-read-redelivery") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.addInitScript(FRAME_RECORDER)

                        // 409 rather than 500: a definite answer is terminal, so the two-second retry
                        // timer never arms and the second POST can only come from the redelivery.
                        val reads = AtomicInteger(0)
                        page.route(
                            Predicate<String> { url -> url.endsWith(READ_PATH) },
                            Consumer<Route> { route ->
                                reads.incrementAndGet()
                                route.fulfill(
                                    Route.FulfillOptions().setStatus(409).setBody("read refused"),
                                )
                            },
                        )

                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        page.locator("#session-list .session-row[data-id='$SESSION']").click()
                        assertThat(page.locator("#terminal-host")).isVisible()

                        // An appended event raises lastSeq, so the frame it emits carries unread work.
                        harness.send("append $SESSION Read")
                        page.waitForCondition { reads.get() >= 1 }
                        val afterFirstFailure = reads.get()

                        val redelivered = page.evaluate(REDELIVER_LAST_UPDATE) as String
                        assertTrue(
                            redelivered.contains("\"type\":\"session_update\"") &&
                                redelivered.contains("\"sessionId\":\"$SESSION\""),
                            "the redelivered frame is the daemon's own session_update, was '$redelivered'",
                        )

                        page.waitForCondition { reads.get() > afterFirstFailure }
                        assertEquals(
                            afterFirstFailure + 1,
                            reads.get(),
                            "one redelivery, one retry — the declined merge still reached the read POST",
                        )
                    }
                }
            }
        }
    }

    private fun interruptFromPalette(page: Page) {
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator("#command-palette")).isVisible()
        assertThat(page.locator(".command-palette-shell.leader")).isFocused()
        page.keyboard().press("KeyI")
        assertThat(page.locator("#command-palette")).hasCount(0)
    }

    private fun badge(page: Page) =
        page.locator("#session-list .session-row[data-id='$SESSION'] .badge")

    private companion object {
        const val SESSION = "s-alpha"
        const val INTERRUPT_PATH = "/api/v1/sessions/$SESSION/interrupt"
        const val READ_PATH = "/api/v1/sessions/$SESSION/read"

        const val RUNNING = "running"
        const val READY = "ready"

        const val NEWER_STATE = "needs_approval"
        const val NEWER_LABEL = "needs approval"

        const val INTERRUPT_DONE = "Interrupt completed"
    }
}

// The newest session_update the page actually received, put back on the socket that delivered it.
private val REDELIVER_LAST_UPDATE: String = """
    () => {
      const socket = window.__kotgentEventsSocket;
      if (!socket) throw new Error("no events socket was recorded");
      const frames = window.__kotgentFrames || [];
      const last = frames.slice().reverse().find(
        (frame) => frame.indexOf('"type":"session_update"') >= 0,
      );
      if (!last) throw new Error("no session_update frame was recorded");
      socket.dispatchEvent(new MessageEvent("message", { data: last }));
      return last;
    }
""".trimIndent()
