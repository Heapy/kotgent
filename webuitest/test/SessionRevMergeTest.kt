package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.test.Test

/**
 * A stale REST body cannot roll a session row back past a fresher frame.
 *
 * Every observation of a session — a `SessionDto` out of an action's HTTP response and a
 * `session_update` frame off `/events` alike — carries the store's global `rev`, and both reach the ONE
 * list through the same rev-aware appliers in `lib/sessions.js`. That single rule is what makes HTTP
 * and the socket mergeable in any arrival order; it replaced "never merge per row, reload the whole
 * list" and the 15-second resync that went with it. It is also, until now, the one half of the protocol
 * with no behavioural test at all: `BoardTest`'s race covers the TASK list, and the session half was
 * three `contains(` greps in `WebUiServingTest` over the literal comparisons inside the two appliers —
 * which a renamed local broke, and an applier that stopped comparing did not.
 *
 * ## The order that used to lose
 * The response was in flight while the row changed, so it describes a session that has since moved on.
 * `POST /sessions/{id}/interrupt` is the vehicle because it is the shortest action with a DTO: the
 * daemon commits it and publishes its frame the moment it runs, entirely independently of when the
 * browser is allowed to read the answer — so holding the response manufactures the inversion without
 * any timing luck. Then one more, strictly newer, observation is pushed over the socket, and only then
 * is the held body handed over.
 *
 * The barrier for the "did not move back" half is the status line: `controlSession` says
 * "Interrupt completed for …" only after `await apiRequest(...)` has returned, i.e. after the stale DTO
 * was already offered to `upsertIfNewer` and refused. Asserting the badge before that would be
 * asserting that nothing had happened yet.
 */
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
                                // Only the FIRST interrupt is held; nothing else in this test issues one,
                                // and resuming any straggler keeps a retry from hanging the page.
                                if (!held.compareAndSet(null, route)) route.resume()
                            },
                        )

                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertThat(badge(page)).hasText(RUNNING)

                        // Select the row, then interrupt it from the palette. Selecting is what makes it
                        // the ACTIVE session, which is the only thing the Interrupt command acts on.
                        page.locator("#session-list .session-row[data-id='$SESSION']").click()
                        assertThat(page.locator("#terminal-host")).isVisible()
                        interruptFromPalette(page)

                        // `route.fetch()` is what actually sends the POST — an intercepted request has
                        // not reached the daemon at all until then. So the daemon runs the interrupt and
                        // publishes its frame HERE, while the browser is still blocked on a response it
                        // will not be handed until the end: the badge moves to `ready` over the socket,
                        // and the DTO now sitting in `stale` describes exactly that same observation.
                        page.waitForCondition { held.get() != null }
                        val stale = held.get()!!.fetch()
                        assertThat(badge(page)).hasText(READY)

                        // One strictly newer observation, over the socket.
                        harness.send("emit $SESSION $NEWER_STATE")
                        assertThat(badge(page)).hasText(NEWER_LABEL)

                        // …and now the older body arrives.
                        held.get()!!.fulfill(Route.FulfillOptions().setResponse(stale))
                        assertThat(page.locator("#status-line")).containsText(INTERRUPT_DONE)

                        assertThat(badge(page)).hasText(NEWER_LABEL)
                    }
                }
            }
        }
    }

    /** `⌘K i` — the only affordance for Interrupt; there is no button for it anywhere in the shell. */
    private fun interruptFromPalette(page: Page) {
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator("#command-palette")).isVisible()
        // The leader grid takes the keyboard in a post-paint effect; a mnemonic pressed before that is
        // delivered to the <dialog> and dropped in silence (`CommandPalette.js`).
        assertThat(page.locator(".command-palette-shell.leader")).isFocused()
        page.keyboard().press("KeyI")
        assertThat(page.locator("#command-palette")).hasCount(0)
    }

    private fun badge(page: Page) =
        page.locator("#session-list .session-row[data-id='$SESSION'] .badge")

    private companion object {
        /** The `sessions` scenario's `running` claude row — the one live session an Interrupt can reach. */
        const val SESSION = "s-alpha"
        const val INTERRUPT_PATH = "/api/v1/sessions/$SESSION/interrupt"

        /** `stateBadge` labels (`lib/sessions.js`), which is what the sidebar actually paints. */
        const val RUNNING = "running"
        const val READY = "ready"

        /**
         * The state the socket moves the row to, and the label it paints.
         *
         * It has to differ from BOTH the pre-interrupt `running` and the interrupt's own `ready`, or the
         * final assertion could not tell "the stale body lost" from "the stale body won".
         */
        const val NEWER_STATE = "needs_approval"
        const val NEWER_LABEL = "needs approval"

        /** `controlSession`'s completion line — said only once the held response has been consumed. */
        const val INTERRUPT_DONE = "Interrupt completed"
    }
}
