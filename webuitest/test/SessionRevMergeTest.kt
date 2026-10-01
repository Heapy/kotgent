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

    // Equal-revision frames still retry mark-read. Replay the daemon's own frame on the real socket
    // because the fixture cannot make the daemon emit the same revision twice.
    @Test
    fun aRedeliveredFrameRetriesAReadPostThatAlreadyFailed() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("session-read-redelivery") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.addInitScript(FRAME_RECORDER)
                        // Observe body consumption, then the next task: apiRequest's rejection and
                        // deliverRead's catch must drain their microtasks before we replay the frame.
                        // A route counter (or response headers) only observes transport arrival.
                        page.addInitScript("""
                            (() => {
                              window.__kotgentReadFailures = 0;
                              const fetch = window.fetch.bind(window);
                              window.fetch = async (...args) => {
                                const response = await fetch(...args);
                                if (new URL(response.url).pathname === "$READ_PATH" && response.status === 409) {
                                  const readText = response.text.bind(response);
                                  response.text = async () => {
                                    const text = await readText();
                                    setTimeout(() => { window.__kotgentReadFailures++; }, 0);
                                    return text;
                                  };
                                }
                                return response;
                              };
                            })();
                        """.trimIndent())

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
                        page.waitForFunction("() => window.__kotgentReadFailures > 0")
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

    // The socket can lag behind HTTP: a pin's answer may be the first sight of a revision that also moved
    // the session into attention, and the frames that follow at that revision are stale.
    @Test
    fun anHttpRowThatFirstShowsAttentionRingsOnceAndItsLateFramesRingNothing() {
        Harness(ATTENTION_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("session-rev-merge-attention") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.addInitScript(NOTIFICATION_RECORDER)
                        page.addInitScript(SESSION_FRAME_GATE)
                        page.addInitScript(FRAME_RECORDER)

                        page.navigate("${harness.baseUrl}/")
                        val row = page.locator("#session-list .session-row[data-id='$QUIET']")
                        val pin = row.locator(".row-adhd")
                        assertThat(row.locator(".badge")).hasText(READY)
                        assertThat(page.locator("#attention-section")).hasCount(0)

                        page.evaluate("() => window.__kotgentHoldSessionFrames()")
                        harness.send("emit $QUIET $NEWER_STATE")
                        page.waitForCondition { heldSessionFrames(page) >= 1 }
                        row.hover()
                        pin.click()

                        assertThat(pin).hasAttribute("aria-pressed", "true")
                        assertThat(page.locator("#attention-num")).hasText("1")
                        assertEquals(
                            listOf("kotgent-attn-$QUIET"),
                            page.notificationTags(),
                            "the HTTP answer was the first sight of the false → true edge, so it rang",
                        )

                        page.evaluate("() => window.__kotgentReleaseSessionFrames()")
                        page.waitForCondition { heldSessionFrames(page) == 0 && sawAdhdFrame(page) }

                        assertEquals(
                            listOf("kotgent-attn-$QUIET"),
                            page.notificationTags(),
                            "the frames at those revisions arrived stale and rang nothing more",
                        )
                    }
                }
            }
        }
    }

    private fun heldSessionFrames(page: Page): Int =
        (page.evaluate("() => window.__kotgentHeldSessionFrames()") as Number).toInt()

    private fun sawAdhdFrame(page: Page): Boolean = page.evaluate(
        """
        () => (window.__kotgentFrames || []).some((f) =>
          f.indexOf('"sessionId":"$QUIET"') >= 0 && f.indexOf('"adhd":true') >= 0)
        """.trimIndent(),
    ) as Boolean

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

        const val QUIET = "s-quiet"
    }
}

// Holds session frames once armed, so an HTTP answer can reach the page before them. Installed before
// FRAME_RECORDER, which reads the gate when the events socket is constructed.
private val SESSION_FRAME_GATE: String = """
    (() => {
      const held = [];
      let holding = false;
      window.__kotgentHeldSessionFrames = () => held.length;
      window.__kotgentHoldSessionFrames = () => { holding = true; };
      window.__kotgentReleaseSessionFrames = () => {};
      window.__kotgentFrameGate = {
        hold: (event) => {
          if (!holding) return false;
          const data = event.data;
          if (data.indexOf('"type":"session_update"') < 0 && data.indexOf('"type":"session_row"') < 0) return false;
          event.stopImmediatePropagation();
          held.push(data);
          return true;
        },
        arm: (socket) => {
          window.__kotgentReleaseSessionFrames = () => {
            holding = false;
            for (const data of held.splice(0)) {
              socket.dispatchEvent(new MessageEvent("message", { data }));
            }
          };
        },
      };
    })();
""".trimIndent()

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
