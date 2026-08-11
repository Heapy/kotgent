package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session→task badge and the two live signals that move it, in a real browser.
 *
 * This file replaces `test/transport/WebUiTaskBadgeTest.kt` (deleted whole) and the seven behaviour greps
 * of `test/transport/WebUiTaskStateTest.kt`. Those asserted the SOURCE TEXT of `lib/sessions.js`,
 * `lib/tasks.js`, `components/Sidebar.js` and `components/TerminalPane.js`, because the macosArm64 test
 * binary has no JavaScript engine. Everything they were protecting is a rendering or a wire contract, and
 * every one of them is checked here as the thing itself:
 *
 *  - **One builder, and the `tasks` prop reaching every row.** `taskBadge` lives in `lib/sessions.js` and
 *    both the sidebar row and the terminal header call it; the sidebar renders `SessionRow` from four
 *    places and a call site that forgot the prop rendered a badge that could only take the unknown arm —
 *    a bare `local:42` where every other row shows a title, with nothing failing. The observable is
 *    exactly that: a linked row's badge carries the task's TITLE, cross-checked against
 *    `GET /api/v1/tasks/{ref}`, and its class is the bare `task-badge` rather than the unknown one.
 *  - **`patchIfNewer` carries `taskRef`.** The daemon moves the badge by emitting a `session_update`; a
 *    patch applicator that dropped the field froze every badge until a reload. Both directions are driven
 *    live here — a `POST /tasks/local:2/link` brings a badge IN, and a task closed to `done` unlinks its
 *    holders and takes one AWAY — and each asserts that the document was never reloaded.
 *  - **The frozen class vocabulary.** `task-badge`, `task-badge-unknown` and `task-session-dot` are the
 *    only board words these two components may spell; the stylesheet was written against that list. They
 *    are asserted here as the classes actually rendered onto the element, not as text in a file.
 *  - **A dangling ref renders, as the bare ref.** `sessions.task_ref` is a REFERENCE and not a foreign
 *    key, so a task deleted while a link write was in flight leaves one dangling. Hiding the badge there
 *    would hide the anomaly; showing the ref names it. `s-linked-3` → `local:404` is the fixture's
 *    deliberately dangling link, and its tooltip is the one the unknown arm writes.
 *  - **The badge is a real `<a href>`**, which is what makes ⌘-click, middle-click and "copy link"
 *    behave; a plain left click is the only one it steals, handing it to the router. `stopPropagation` is
 *    load-bearing — the whole row is a click target that selects the session — and its absence is visible
 *    in the address bar, because the row's own handler navigates to `/s/{id}` after the badge navigated
 *    to `/tasks/{ref}`.
 *
 * ## What is asserted, and how it waits
 * Nothing here sleeps and nothing reads source text. DOM claims go through Playwright's web-first
 * assertions, which retry until the frame lands. Wire claims go through [awaitFrame]: an init script wraps
 * `WebSocket`, banks every text message the `/api/v1/events` socket delivers, and `waitForFunction` waits
 * on that bank.
 * The recorder's listener is registered before the application's own `onmessage`, so a frame the app has
 * already acted on is necessarily in the bank — which is what lets a DOM assertion and a wire assertion
 * about the same change be written in either order.
 *
 * ## Two deliberate choices
 * The viewport is a desktop one. Below 720px the sidebar is an overlay drawer, and every badge this file
 * looks at lives in a sidebar row; opening the drawer first would add a gesture that says nothing about
 * badges. And no session is ever SELECTED here: selecting an alive session attaches the terminal, and
 * `task-linked-session` is a link fixture with no terminal payload behind it. The one click this file
 * makes is on the badge, whose handler stops the event before the row can act on it — so a regression in
 * exactly that rule is what a stray attach would mean.
 */
class TaskBadgeTest {

    // --- the badge as rendered ------------------------------------------------------------------------

    @Test
    fun aLinkedSessionRendersItsTaskTitleAndAnUnlinkedOneRendersNothing() =
        browse(LINKED, "badge-linked-title") { _, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-1'] a.task-badge")
            assertThat(badge).hasCount(1)
            assertThat(badge).hasAttribute("href", "/tasks/local%3A1")
            // The exact attribute, not a substring: `known` is the whole reason the unknown class exists, and
            // a resolved ref must carry the base class ALONE.
            assertThat(badge).hasClass("task-badge")
            // The title is read from the daemon rather than pinned to a fixture string: what matters is that
            // the row resolved its ref through the task list it was handed, not which words that list holds.
            val title = page.taskTitle("local:1")
            assertTrue(
                title.isNotBlank() && title != "local:1",
                "the fixture's local:1 must carry a real title for this assertion to mean anything, got '$title'",
            )
            assertThat(badge).hasText(title)
            assertThat(badge.locator(".task-session-dot")).hasCount(1)

            // The ordinary case: a session linked to nothing renders no pill at all, not an empty one.
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-2']")).hasCount(1)
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-2'] a.task-badge")).hasCount(0)
        }

    @Test
    fun aDanglingRefRendersTheUnknownBadgeRatherThanBreakingTheRow() =
        browse(LINKED, "badge-dangling-ref") { _, page ->
            val row = page.locator("$SESSION_LIST li[data-id='s-linked-3']")
            val badge = row.locator("a.task-badge")
            assertThat(badge).hasCount(1)
            assertThat(badge).hasClass("task-badge task-badge-unknown")
            // The bare ref IS the label here — the fallback the "reference, not a foreign key" rule requires.
            assertThat(badge).hasText("local:404")
            assertThat(badge).hasAttribute("href", "/tasks/local%3A404")
            assertThat(badge).hasAttribute(
                "title",
                "local:404 — no such task (it may have just been deleted)",
            )
            // And the row around it is intact: a dangling link is an anomaly the UI names, not a broken row.
            assertThat(row.locator(".session-name")).hasCount(1)
            assertThat(row.locator(".badge")).hasCount(1)
        }

    @Test
    fun aPlainClickOnTheBadgeOpensTheTaskRouteInsteadOfSelectingTheRow() =
        browse(LINKED, "badge-plain-click") { _, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-1'] a.task-badge")
            assertThat(badge).hasCount(1)
            badge.click()

            // The sidebar keeps its head and swaps its body on the board, so the project list appearing (and
            // the session list going) is the screen change itself.
            assertThat(page.locator("#projects-section")).isVisible()
            assertThat(page.locator(SESSION_LIST)).hasCount(0)
            val url = page.url()
            assertTrue(url.contains("/tasks/"), "the badge routed to the task screen; the address bar says $url")
            // If `stopPropagation` were dropped, the row's own handler would run after the badge's and
            // navigate to /s/s-linked-1 — the last write to the address bar is what exposes it.
            assertTrue(
                !url.contains("/s/"),
                "the click stopped at the badge: the row underneath must not also select ($url)",
            )
            assertTrue(page.sameDocument(), "a plain click goes through the router, never a document load")
        }

    // --- the two live signals -------------------------------------------------------------------------

    @Test
    fun linkingASessionThroughTheApiBringsItsBadgeInWithoutAReload() =
        browse(LINKED, "badge-link-arrives") { _, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-2'] a.task-badge")
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-2']")).hasCount(1)
            assertThat(badge).hasCount(0)

            // The browser has no link control of its own — linking is a pane's action (`kotgent task claim`)
            // — so the link is staged through the same authenticated route that pane would use. It is posted
            // from the page so the session cookie and the same-origin `Origin` header ride along for free.
            val status = page.postStatus("/api/v1/tasks/local%3A2/link", "{\"sessionId\":\"s-linked-2\"}")
            assertEquals(200, status, "POST /api/v1/tasks/local:2/link should link s-linked-2 to local:2")

            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-linked-2\"", "\"taskRef\":\"local:2\"")
            assertThat(badge).hasCount(1)
            assertThat(badge).hasAttribute("href", "/tasks/local%3A2")
            assertThat(badge).hasClass("task-badge")
            assertTrue(page.sameDocument(), "the badge arrived on the patch, not on a reload")
        }

    @Test
    fun aTaskClosedToDoneUnlinksItsHolderAndTheBadgeLeavesLive() =
        browse(LINKED, "badge-unlink-on-done") { harness, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-1'] a.task-badge")
            assertThat(badge).hasCount(1)

            // Closing a task unlinks every session holding it and leaves them alive — that is what hands a
            // long-lived worker session back to `task next`. Two frames therefore describe one command: the
            // task's own state step, and the holder's authoritative `taskRef: null`.
            harness.send("task local:1 done")
            awaitFrame(page, TASK_UPDATE, "\"ref\":\"local:1\"", "\"state\":\"done\"")
            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-linked-1\"", "\"taskRef\":null")

            assertThat(badge).hasCount(0)
            // An unlink arrives as `taskRef: null` and must CLEAR the badge, not keep the last value it saw.
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-1']")).hasCount(1)
            assertTrue(page.sameDocument(), "the badge left on the patch, not on a reload")
        }

    @Test
    fun anEmitMovesTheSessionStateOnTheWireAndTheSidebarFollows() =
        browse(ATTENTION, "badge-state-emit") { harness, page ->
            val row = page.locator("$SESSION_LIST li[data-id='s-quiet']")
            assertThat(row).hasCount(1)
            // The fixture starts this session OUT of attention on purpose, so the emit below is a real edge.
            assertThat(page.locator("#attention-num")).hasText("0")
            assertThat(row.locator(".badge")).hasText("ready")

            harness.send("emit s-quiet needs_approval")
            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-quiet\"", "\"state\":\"needs_approval\"")

            assertThat(page.locator("#attention-num")).hasText("1")
            assertThat(page.locator("#attention-list li[data-id='s-quiet']")).hasCount(1)
            assertThat(row.locator(".badge")).hasText("needs approval")
            assertThat(row.locator(".attn-dot")).hasCount(1)
            assertTrue(page.sameDocument(), "a state change is a patch; the shell is never reloaded for one")
        }

    @Test
    fun theNotifyEdgeRingsOnceForOneFalseToTrueTransition() =
        browse(ATTENTION, "badge-notify-edge", extraInit = NOTIFICATION_RECORDER) { harness, page ->
                val row = page.locator("$SESSION_LIST li[data-id='s-quiet']")
                assertThat(row).hasCount(1)
                assertThat(page.locator("#attention-num")).hasText("0")
                assertEquals(
                    emptyList<String>(),
                    page.notificationTags(),
                    "a session that was already in the list, quiet, rings for nothing",
                )

                harness.send("emit s-quiet needs_approval")
                awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-quiet\"", "\"state\":\"needs_approval\"")
                assertThat(page.locator("#attention-num")).hasText("1")
                assertEquals(
                    listOf("kotgent-attn-s-quiet"),
                    page.notificationTags(),
                    "the false → true transition rang exactly once, keyed by session id",
                )

                // A SECOND attention state is not a second edge. `needs_answer` is chosen precisely because it
                // is observable in the DOM (the row's state badge relabels) while leaving `needsAttention`
                // true, so this waits on a real change rather than on nothing happening: a repeat of
                // `needs_approval` would be indistinguishable from a frame that never arrived, and — banked
                // newest-per-session by the daemon's conflating sender — might legitimately never arrive.
                harness.send("emit s-quiet needs_answer")
                awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-quiet\"", "\"state\":\"needs_answer\"")
                assertThat(row.locator(".badge")).hasText("needs answer")
                assertThat(page.locator("#attention-num")).hasText("1")
                assertEquals(
                    listOf("kotgent-attn-s-quiet"),
                    page.notificationTags(),
                    "still one: the row never LEFT attention, so there was no new edge to ring for",
                )
            }

        // --- harness --------------------------------------------------------------------------------------

        /**
         * One scenario, one fresh [com.microsoft.playwright.BrowserContext], one page already showing the
         * session list.
         *
         * The viewport is desktop-sized on purpose (see the class KDoc). A fresh context per test is not a
         * style choice: the login cookie is not bound to a port, so a reused one would carry a dead daemon's
         * credential into the next harness.
         */
        private fun browse(
            scenario: String,
            trace: String,
            extraInit: String? = null,
            block: (Harness, Page) -> Unit,
        ) {
            Playwright.create().use { playwright ->
                touchChromium(playwright).use { browser ->
                    Harness(scenario).use { harness ->
                        browser.touchContext(width = 1280, height = 900, deviceScaleFactor = 1.0, mobile = false)
                            .use { context ->
                                context.loginWithTicket(harness.ticket, harness.baseUrl)
                                // Added after the login page and before the first page of the test: init
                                // scripts apply to pages created afterwards, and the login flow has no use
                                // for either.
                                context.addInitScript(FRAME_RECORDER)
                                if (extraInit != null) context.addInitScript(extraInit)
                                // Named after the TEST, not the scenario: four tests share `task-linked-session`
                                // and a scenario-named trace means the first failure's evidence is overwritten
                                // by whichever of them runs last.
                                context.traced(trace) {
                                    val page = context.newPage()
                                    page.navigate(harness.baseUrl + "/")
                                    // The reload sentinel. It is set once, on the document the test starts
                                    // with; any full navigation replaces that document and takes the flag
                                    // with it.
                                    page.evaluate("() => { window.__kotgentSameDocument = true; }")
                                    block(harness, page)
                                }
                            }
                    }
                }
            }
        }

    /**
     * Wait until the `/events` socket has delivered a text frame containing every one of [needles].
     *
     * Substrings rather than a parsed shape: the wire is compact JSON with the kotlinx discriminator
     * first, so `"type":"session_update"` plus the id plus the field under test names one frame
     * unambiguously and reads as the frame does. The wait is `waitForFunction` over the page's own bank,
     * which is a genuine Playwright waiter — nothing here polls on a timer.
     */
    private fun awaitFrame(page: Page, vararg needles: String) {
        try {
            page.waitForFunction(
                "needles => (window.__kotgentFrames || []).some((f) => needles.every((n) => f.includes(n)))",
                needles.toList(),
            )
        } catch (failure: TimeoutError) {
            // Narrow on purpose. A broad `RuntimeException` here renamed every unrelated failure — a
            // closed page, a driver error, a bad evaluate — as "no frame carried…", which is the one
            // thing that had not happened. Counted defensively all the same: the page may already be
            // gone, and losing the real cause to a second failure inside the reporting path would be the
            // worst possible trade.
            val banked = runCatching { page.frameCount() }.getOrNull()
            throw AssertionError(
                "no /events frame carried all of ${needles.toList()}; " +
                    "the socket delivered ${banked ?: "an unreadable number of"} text frame(s)",
                failure,
            )
        }
    }

    /** How many text frames the recorder has banked — context for a failed [awaitFrame], nothing more. */
    private fun Page.frameCount(): Int =
        (evaluate("() => (window.__kotgentFrames || []).length") as Number).toInt()

    /** Whether the page is still the document the test started on (i.e. nothing reloaded the shell). */
    private fun Page.sameDocument(): Boolean =
        evaluate("() => window.__kotgentSameDocument === true") as Boolean

    /** The `tag` of every notification the page has raised, in order. */
    private fun Page.notificationTags(): List<String> {
        val raw = evaluate("() => (window.__kotgentNotifications || []).map((n) => n.tag)") as List<*>
        return raw.map { it?.toString().orEmpty() }
    }

    /** `POST` a JSON body from inside the page — the cookie and the same-origin `Origin` ride along. */
    private fun Page.postStatus(path: String, body: String): Int {
        val status = evaluate(
            """
            async ([p, b]) => {
              const res = await fetch(p, {
                method: "POST",
                credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: b,
              });
              return res.status;
            }
            """.trimIndent(),
            listOf(path, body),
        )
        return (status as Number).toInt()
    }

    /** The title the daemon holds for [ref], so a badge assertion never hard-codes fixture prose. */
    private fun Page.taskTitle(ref: String): String = evaluate(
        """
        async (r) => {
          const res = await fetch("/api/v1/tasks/" + encodeURIComponent(r), { credentials: "same-origin" });
          if (!res.ok) throw new Error("GET /api/v1/tasks/" + r + " answered " + res.status);
          return (await res.json()).task.title;
        }
        """.trimIndent(),
        ref,
    ) as String

    private companion object {

        /** `s-linked-1` → `local:1`, `s-linked-2` unlinked, `s-linked-3` → the dangling `local:404`. */
        const val LINKED = "task-linked-session"

        /** `s-quiet` starts OUT of attention, which is what makes an `emit` a real edge. */
        const val ATTENTION = "attention"

        /** Scoped to the full list: a needs-attention row is ALSO rendered in `#attention-list`. */
        const val SESSION_LIST = "#session-list"

        const val SESSION_UPDATE = "\"type\":\"session_update\""
        const val TASK_UPDATE = "\"type\":\"task_update\""

        /**
         * Bank every text message the `/events` socket delivers.
         *
         * The wrapper returns a genuine `WebSocket` (a constructor returning an object yields that
         * object), shares its prototype so `instanceof` and every property the app sets still behave, and
         * copies the readyState constants. Its `message` listener is registered before the application
         * gets the socket back, so a frame the app has already applied is necessarily banked.
         */
        val FRAME_RECORDER = """
            (() => {
              const frames = [];
              window.__kotgentFrames = frames;
              const Native = window.WebSocket;
              const Recording = function (url, protocols) {
                const socket = protocols === undefined ? new Native(url) : new Native(url, protocols);
                if (String(url).indexOf("/api/v1/events") >= 0) {
                  socket.addEventListener("message", (event) => {
                    if (typeof event.data === "string") frames.push(event.data);
                  });
                }
                return socket;
              };
              Recording.prototype = Native.prototype;
              Recording.CONNECTING = Native.CONNECTING;
              Recording.OPEN = Native.OPEN;
              Recording.CLOSING = Native.CLOSING;
              Recording.CLOSED = Native.CLOSED;
              window.WebSocket = Recording;
            })();
        """.trimIndent()

        /**
         * Make the in-tab notification path observable and deterministic.
         *
         * `notifyAttention` no-ops unless the per-device toggle is on, the API exists and permission is
         * granted, so all three are supplied here rather than clicked: the real toggle also starts a push
         * subscription, and this test is about the in-tab edge. The fourth condition — no active push
         * subscription — needs nothing: this daemon mounts no `/push` routes, and `lib/push.js` clears the
         * mirror flag before it ever tries, so the in-tab path stays live.
         */
        val NOTIFICATION_RECORDER = """
            (() => {
              const seen = [];
              window.__kotgentNotifications = seen;
              const Recording = function (title, options) {
                seen.push({
                  title: title,
                  tag: (options && options.tag) || "",
                  body: (options && options.body) || "",
                });
              };
              Recording.permission = "granted";
              Recording.requestPermission = () => Promise.resolve("granted");
              window.Notification = Recording;
              try { window.localStorage.setItem("kotgent.notifications.v1", "1"); } catch (e) { }
            })();
        """.trimIndent()
    }
}
