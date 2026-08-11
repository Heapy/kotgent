package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PageAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * The terminal reattachment lifecycle, driven through a real browser against a real daemon.
 *
 * A mobile browser commonly discards the terminal WebSocket while the page is suspended, and a daemon
 * restart drops every socket at once. The Web UI answers both with ONE reattach candidate and a strict
 * rule about who may spend it. Four sites grant an attempt — a fresh attachment (selecting a live session,
 * an explicit attach, a resume), a foreground transition, and the events socket's RE-open, which is the
 * only signal the daemon came back. The attempt then asks the daemon for this session's current liveness,
 * re-checks every local intent after the await, and on the way out obeys the distinction that
 * `isDefiniteAnswer` (`lib/api.js`) exists to draw: **explicit intent and a definitive answer destroy the
 * candidate; transient conditions keep it.** A `4xx` is the daemon answering about THIS session — gone, or
 * unreadable by this client — and re-asking it on every later grant would loop forever. An unreachable
 * daemon carries no status at all, so the candidate survives, and that is exactly what makes a restart
 * recover on the events socket's reconnect instead of waiting out a visibility change or the 10 s deadline.
 *
 * These tests replace `WebUiServingTest.theWebUiReattachesAClosedAliveTerminalAfterBackgroundingOrDaemonRestart`,
 * which asserted the same rules by grepping `app.js` for ref names, guard strings and their relative source
 * offsets. What is asserted here instead is the observable behaviour: which sockets exist and are open,
 * which bytes reach the screen, which liveness requests the daemon is actually asked for, and what the pane
 * says while it is detached.
 *
 * Two branches cannot be produced by the harness at all, and that is deliberate — they are CLIENT-side
 * decisions about an answer, so they are produced by intercepting the liveness request in the browser
 * (`page.route`) rather than by breaking the daemon: a `404` about the session, and a daemon the request
 * cannot reach.
 *
 * The `restart` scenario seeds two sessions, both alive across the restart, so there is somewhere to switch
 * to. `Harness.send("restart")` blocks until the second `READY`, unlike every other command.
 */
class TerminalReattachTest {

    /**
     * The load-bearing half of a restart: the socket RE-OPENED and new bytes flow.
     *
     * Not "the previous content came back" — `stop()` tears the terminal bridges down first and kills the
     * pty child, so a restart starts a NEW `/bin/sh` that reprints its banner. The unambiguous
     * discriminator is a marker written through the input route before the restart: `cat` echoes it onto
     * THIS child's screen, and nothing can carry it across a new child.
     */
    @Test
    fun aDaemonRestartReopensTheTerminalSocketAndItsBytesComeFromANewChild() {
        withRestartPage("reattach-after-daemon-restart") { harness, page ->
            openSession(page, SESSION_A)
            val host = page.locator(TERMINAL_HOST)
            containsSoon(host, BANNER)

            assertEquals(200, postTerminalInput(page, SESSION_A, MARKER))
            containsSoon(host, MARKER)

            harness.send("restart") // blocks until the second READY: the daemon is already back here

            // A second terminal socket, open: the attachment was rebuilt rather than merely repainted.
            awaitSocketGeneration(page, TERMINAL_SOCKET, 2)
            // New bytes: the fresh child reprinted the banner into a terminal that was emptied on teardown.
            containsSoon(host, BANNER)
            // And the marker did not survive the child that echoed it.
            lacksSoon(host, MARKER)
        }
    }

    /**
     * The plain case the whole machinery is built on: the daemon drops our socket while it stays up. The
     * close names the socket's OWN id (`closedRef.current(attachedId)` in `TerminalPane`), the candidate is
     * filled, the already-granted attempt reads fresh liveness and the terminal comes straight back — hint
     * and all cleared, because the session really is alive.
     */
    @Test
    fun aDroppedTerminalSocketIsReattachedWhileTheDaemonStaysUp() {
        withRestartPage("reattach-after-socket-drop") { _, page ->
            openSession(page, SESSION_A)
            val host = page.locator(TERMINAL_HOST)
            containsSoon(host, BANNER)

            closeNewestSocket(page, TERMINAL_SOCKET)

            awaitSocketGeneration(page, TERMINAL_SOCKET, 2)
            containsSoon(host, BANNER)
            // The replacement is attached, so the pane has nothing left to explain.
            hasCountSoon(page.locator(TERMINAL_HINT), 0)
        }
    }

    /**
     * An unreachable daemon is transient, so its failure KEEPS the candidate — and the events socket's
     * re-open is the grant that spends it. This is the daemon-restart recovery path with the timing under
     * the test's control instead of the operating system's: the liveness request is aborted (a network
     * failure carries no status, so `isDefiniteAnswer` is false), the terminal stays detached with nothing
     * scheduling a retry, and only when the events socket is closed from the page and comes back does the
     * app ask again — this time successfully.
     */
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

            // "Terminal detached." is the FAILED attempt's own copy — `detachedHint(null)`, deliberately
            // making no claim about liveness it could not read. The close callback's own hint names the
            // session, so this text appearing is proof the liveness read ran and failed.
            hasTextSoon(page.locator(TERMINAL_HINT), DETACHED_HINT)
            assertEquals(1, attempts.get(), "the close spent the attachment's grant on exactly one attempt")
            assertEquals(
                1,
                socketCount(page, TERMINAL_SOCKET),
                "a failed attempt opens no replacement terminal socket",
            )

            // The daemon is reachable again — but nothing has granted another attempt, so nothing happens
            // until the events socket comes back.
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

    /**
     * A `4xx` is the daemon's own answer ABOUT THIS SESSION, and it will not change on a retry — so the
     * candidate dies there. The proof is the later grant: the events socket comes back with the daemon
     * perfectly healthy, and the app asks nothing and reattaches nothing, because there is no longer a
     * candidate to spend. (Contrast the test above, whose only difference is the status of that one
     * answer.)
     */
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
            // The grant is made inside `socket.onopen` and its attempt is queued with a zero-delay timer,
            // so a later zero-delay task plus a full round trip to the daemon is a barrier that task
            // cannot still be behind.
            settleQueuedWork(page)

            assertEquals(1, attempts.get(), "a destroyed candidate re-asks the doomed question no more")
            assertEquals(1, socketCount(page, TERMINAL_SOCKET), "and opens no replacement socket")
            hasCountSoon(page.locator("$TERMINAL_HOST .xterm"), 0)
            hasTextSoon(page.locator(TERMINAL_HINT), DETACHED_HINT)
        }
    }

    /**
     * Selecting another session is explicit intent, and `showSession` spends it on `cancelReattach()`
     * before it attaches anything. Since the client router landed, that selection is also a NAVIGATION:
     * `location.pathname` becomes `/s/{id}`, which is what makes the choice survive a reload and a Back.
     *
     * The candidate here is a retained one (the liveness read was aborted, so it was kept), which is the
     * only interesting case: destroying an already-dead candidate would prove nothing. The later grant is
     * the proof — the events socket returns with the daemon healthy and the abandoned session is never
     * asked about again.
     */
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

            // Everything the abandoned candidate needed to succeed is now true — a reachable daemon and a
            // fresh grant — and it still must not be spent.
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

// --- fixture ------------------------------------------------------------------------------------------

private const val RESTART_SCENARIO = "restart"
private const val SESSION_A = "s-restart-a"
private const val SESSION_B = "s-restart-b"

/** The last thing the scenario's pty payload prints, so seeing it means the whole payload arrived. */
private const val BANNER = "KOTGENT-RESTART-READY"

/** Written through `POST /api/v1/sessions/{id}/input`; the pty echoes it, and no later child can. */
private const val MARKER = "MARKER-BEFORE-RESTART"

/** `detachedHint(null)` — what a FAILED liveness read says, as opposed to the close callback's own copy. */
private const val DETACHED_HINT = "Terminal detached."

private const val TERMINAL_HOST = "#terminal-host"
private const val TERMINAL_HINT = "#terminal-hint"
private const val TERMINAL_SOCKET = "/terminal"
private const val EVENTS_SOCKET = "/events"

private const val LIVENESS_ABORT = "abort"
private const val LIVENESS_GONE = "gone"
private const val LIVENESS_PASS = "pass"

/**
 * Generous, because the recovery path is deliberately unhurried: the events socket reconnects on a 2 s
 * cadence and a restart has to relink a whole server before it can answer.
 */
private const val WAIT_MS = 20_000.0

/**
 * Record every WebSocket the page builds, keeping the closed ones. Nothing in the app exposes its sockets,
 * and the plan's constraint 2 is that no daemon-side "drop the sockets without touching the port" command
 * exists — so the consumer of that gap is a page that can close its own socket. The array doubles as a
 * generation counter: a reattachment is observable as a SECOND terminal socket, not as a reopened one.
 */
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
        Playwright.create().use { playwright ->
            touchChromium(playwright).use { browser ->
                browser.touchContext().use { context ->
                    context.setDefaultTimeout(WAIT_MS)
                    context.addInitScript(RECORD_SOCKETS)
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(traceName) {
                        val page = context.newPage()
                        // Every grant re-checks `document.visibilityState`, so the page under test has to
                        // be the front one — an unfocused tab would make each attempt return early.
                        page.bringToFront()
                        page.navigate(harness.baseUrl + "/")
                        body(harness, page)
                    }
                }
            }
        }
    }
}

private fun sessionRow(id: String) = "li.session-row[data-id='$id']"

/**
 * Select a session the way an operator does. The phone layout keeps the sidebar in a drawer, and picking a
 * row closes it again — so each selection opens it first, which keeps the two selections of the switch
 * test symmetric.
 */
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

/** Wait until at least [generation] sockets of [kind] have existed and the newest of them is OPEN. */
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

/**
 * A barrier for the two NEGATIVE assertions. A grant made in `socket.onopen` queues its attempt with
 * `setTimeout(…, 0)` and the attempt issues its request synchronously at the top of that callback, so a
 * later zero-delay task followed by a full browser→daemon→browser round trip cannot still be ahead of it.
 */
private fun settleQueuedWork(page: Page) {
    page.evaluate(
        """
        async () => {
          await new Promise((resolve) => setTimeout(resolve, 0));
          await fetch("/api/v1/version", { credentials: "same-origin" }).catch(() => {});
        }
        """.trimIndent(),
    )
}

/**
 * Intercept the ONE request a reattach attempt makes: `GET /api/v1/sessions/{id}`. The suffix match is
 * exact, so the mark-read POST (`…/{id}/read`) and every other route pass through untouched, and a non-GET
 * on the same path is resumed rather than counted.
 */
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
                // No status at all: the daemon was not reached, which is the transient case that KEEPS
                // the candidate.
                LIVENESS_ABORT -> intercepted.abort()
                // The daemon's own answer about this session — definitive, so the candidate dies.
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
