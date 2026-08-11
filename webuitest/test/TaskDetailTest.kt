package io.kotgent.webuitest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.BoundingBox
import com.microsoft.playwright.options.WaitForSelectorState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One task in full, in a real browser: the activity feed, the dependency editor and its derived
 * `blocked` marker, the handoff into the one launch path, and the head's ×.
 *
 * ## What this replaces, and why it is a browser test now
 * The predecessor (`test/transport/WebUiTaskDetailTest.kt`) read `components/TaskDetail.js` as TEXT and
 * asserted the substrings a correct implementation happens to contain — `await fetchTaskDetail(taskRef)`,
 * `class="task-activity"`, `if (reload) await load();`. Every one of those assertions passes on source
 * that never renders, and fails on a refactor that renames a local while behaving identically. What that
 * file was really trying to state is behaviour, and behaviour is what is checked here instead: the
 * request the panel issues, the rows that appear, the marker that clears, the pixel the × occupies.
 *
 * Three contracts from that file are deliberately NOT reproduced here, and are recorded rather than
 * quietly dropped: the frozen "Board CSS vocabulary" class whitelist (a source-level lock between two
 * agents writing markup and stylesheet concurrently — it has no browser-observable half, and the board's
 * end of it survives in `test/transport/WebUiServingTest.kt`), the generation guard on a superseded read
 * (it needs a held
 * response and a route change inside it, which is a request-interception test of a race no fixture
 * produces on its own), and the newest-rev-wins merge of the panel's two observations, which the plan
 * gives to the board's `task-race` test.
 *
 * ## The three decisions this file exists to pin
 *
 * **The feed rides HTTP, so it is only ever as fresh as the last read.** `TaskDetailDto` is ONE response
 * — entry, deps, sessions and activity together — and only the entry half also travels on the `/events`
 * socket. That is a deliberate trade (an unbounded feed read by one open panel must not be broadcast to
 * every connected tab), and it has an observable consequence a source grep cannot state: a transition
 * made from outside moves the head immediately and leaves the feed exactly as it was until something
 * re-reads it. Both halves are asserted below, in that order, and the reload afterwards is what stops
 * the negative half from being vacuous.
 *
 * **`blocked` is derived by the daemon — `state == todo` and some dependency not `done`.** Neither half
 * is stored, so both are testable from the panel: moving the column clears the marker without touching
 * an edge, and dropping the edges clears it without touching the column.
 *
 * **There is no comment box, and that is the design.** An activity row must be attributable, and
 * `POST /tasks/{ref}/comment` therefore requires a session identity the browser does not have (no pane
 * header, no session id) — a control that could only ever fail. Comments come from an agent or the CLI;
 * the browser reads them. So the "a comment is added" half of this screen's contract is checked as the
 * two things that ARE true of it: the seeded comment rows render (including one whose author names a
 * session that no longer exists — a feed outlives its sessions), and a write made HERE grows the feed,
 * which for this panel means the state `<select>` and its `transition` row.
 *
 * The fixture is the `task-detail` scenario: focus `local:3` depends on `local:1` and `local:2` (so it is
 * blocked), `local:4` depends on it, and its feed carries a `created` row plus two comments.
 *
 * Every selector below is scoped to `.task-detail`, because `task-blocked`, `task-dep-count`,
 * `task-sessions` and `task-activity*` are SHARED with the board card — and on a phone the board is
 * `display: none` behind this panel, not absent from the DOM.
 */
class TaskDetailTest {

    /** The focus task of the `task-detail` scenario, and the API path that carries its whole detail. */
    private val focus = "local:3"
    private val focusRoute = "/tasks/local%3A3"
    private val detailApi = "/api/v1/tasks/local%3A3"

    /** The first paint waits on the harness boot, the SPA's module graph and one GET, so it gets room. */
    private val firstPaintMs = 15_000.0

    /** A display name no activity author carries, so the two renderings cannot be confused. */
    private val injectedName = "the-session-that-holds-that-id-now"

    @Test
    fun theActivityFeedArrivesWithTheTaskAndRendersEveryRowIncludingOneNoSessionAnswersFor() {
        onTheTaskDetail("feed-loads") { harness, page ->
            // The fixture's feed carries a comment by `s-detail-1` and its `sessions` list is empty, which
            // makes "the author was not resolved through the live list" unfalsifiable: a panel that DID
            // resolve authors would render the same thing against an empty list. So one session is
            // injected into the panel's own response under exactly that id and a DIFFERENT display name.
            // Now the two halves are distinguishable — the linked-sessions list must show the name, and
            // the feed row must still show the recorded id.
            page.route({ url: String -> url.endsWith(detailApi) }) { route ->
                val original = route.fetch()
                route.fulfill(
                    Route.FulfillOptions()
                        .setStatus(original.status())
                        .setContentType("application/json")
                        .setBody(withInjectedSession(original.text())),
                )
            }
            val seen = recordRequests(page)
            page.openFocusTask(harness.baseUrl)

            // One GET carries entry, deps, sessions AND activity. The claim worth checking is not the
            // count of that request but its shape: nothing goes out for a sub-resource, so there is no
            // second endpoint the feed could quietly start arriving on. Both spellings of the ref are
            // excluded, because the client percent-encodes the mandatory `:` and a browser is free to
            // report either — a negative that named only one of them would miss a whole family of URLs.
            assertTrue(
                seen.any { it.isDetailRequest("GET") },
                "the panel reads the task over HTTP; requests seen: $seen",
            )
            assertTrue(
                seen.none { it.contains("$detailApi/") || it.contains("/api/v1/tasks/$focus/") },
                "the feed is part of that one response, not a sub-resource of it; requests seen: $seen",
            )

            assertThat(page.panel(".task-activity-row[data-kind=\"created\"]")).hasCount(1)
            assertThat(page.panel(".task-activity-row[data-kind=\"comment\"]")).hasCount(2)
            assertThat(page.panel(".task-activity-row")).hasCount(3)

            // The live list DID resolve the injected session, so the panel demonstrably reads it.
            assertThat(page.panel(".task-sessions a[href=\"/s/s-detail-1\"]")).hasCount(1)
            assertThat(page.panel(".task-sessions a[href=\"/s/s-detail-1\"]")).hasText(injectedName)

            // …and the feed row for the same id is untouched by it. A feed outlives the sessions that
            // wrote it, so the author is the string that was RECORDED — resolving it through the live list
            // would blank an author whose session is gone or, as here, silently re-attribute the comment
            // to whoever holds that id now.
            val orphan = page.panel(".task-activity-row", Page.LocatorOptions().setHasText("s-detail-1"))
            assertThat(orphan).hasCount(1)
            assertThat(orphan).hasAttribute("data-kind", "comment")
            assertThat(orphan.locator("strong")).hasText("s-detail-1")
            assertThat(orphan).not().containsText(injectedName)
        }
    }

    /**
     * The feed is HTTP, the entry is the socket, and the panel shows both truths at their own freshness.
     *
     * The middle assertion is a negative, so the move that follows it is what stops it from being
     * vacuous: a write made HERE re-reads the detail and the feed grows at once, which is the whole
     * difference between "the feed cannot change" and "the feed changes on a read, and a frame is not
     * one". The negative is measured at the moment the frame has demonstrably landed — the head is
     * already showing the new state — so a re-read the panel does not do had every chance to happen.
     *
     * The typed title rides along because an external update re-renders the entire panel, and a field
     * whose value is re-seeded on every render would be emptied by somebody else's edit in another tab.
     */
    @Test
    fun anExternalTransitionMovesTheHeadWhileTheFeedWaitsForItsNextRead() {
        onTheTaskDetail("feed-rides-http") { harness, page ->
            page.openFocusTask(harness.baseUrl)
            page.awaitLiveTaskRows()
            val rowsBefore = page.panel(".task-activity-row").count()
            val title = page.locator("#task-detail-title-input")
            title.fill("a title nobody has saved")

            harness.send("task $focus review")

            assertThat(page.locator("#task-detail-state")).hasValue("review")
            assertThat(page.panel(".task-activity-row")).hasCount(rowsBefore)
            assertThat(title).hasValue("a title nobody has saved")

            page.locator("#task-detail-state").selectOption("done")

            assertThat(page.locator("#task-detail-state")).hasValue("done")
            // Exactly two more rows, not merely "a different number": the external `review` transition
            // that the feed could not see, plus the `done` one this click just made. `not().hasCount(n)`
            // would also be satisfied by a feed that came back EMPTY — a crashed read looks like progress.
            assertThat(page.panel(".task-activity-row")).hasCount(rowsBefore + 2)
            assertThat(page.panel(".task-activity-row[data-kind=\"transition\"]")).hasCount(2)
        }
    }

    /**
     * A write made here re-reads the whole detail, which is how the feed and the derived marker stay the
     * daemon's rather than this view's guess. Both consequences are visible in one move: the transition
     * row joins the feed, and `blocked` clears because the task left `todo` — with its two dependencies
     * still attached and still not done.
     */
    @Test
    fun aStateChangeMadeHereReReadsTheDetailAndItsRowNamesBothEnds() {
        onTheTaskDetail("write-rereads") { harness, page ->
            val seen = recordRequests(page)
            page.openFocusTask(harness.baseUrl)
            val rowsBefore = page.panel(".task-activity-row").count()
            assertThat(page.panel(".task-blocked")).isVisible()

            page.locator("#task-detail-state").selectOption("in_progress")

            val transition = page.panel(".task-activity-row[data-kind=\"transition\"]")
            assertThat(transition).hasCount(1)
            assertThat(transition).containsText("To do → In progress")
            assertThat(page.panel(".task-activity-row")).hasCount(rowsBefore + 1)

            // `blocked` is "todo AND a dependency is not done", so leaving the column clears it even
            // though both edges are untouched.
            assertThat(page.panel(".task-dep-count")).hasText("2")
            assertThat(page.panel(".task-blocked")).hasCount(0)

            val patchAt = seen.indexOfFirst { it.isDetailRequest("PATCH") }
            val lastReadAt = seen.indexOfLast { it.isDetailRequest("GET") }
            assertTrue(patchAt >= 0, "the column change is one PATCH; requests seen: $seen")
            assertTrue(
                lastReadAt > patchAt,
                "and the mutation re-reads the detail afterwards; requests seen: $seen",
            )
        }
    }

    /**
     * Both directions of the edge, and the marker following them.
     *
     * Only the FINAL removal is asserted to unblock: which of the two dependencies is the open one is the
     * fixture's business, but a task with no dependencies at all cannot be blocked by one.
     */
    @Test
    fun theDependencyEditorNamesBothDirectionsAndBlockedFollowsTheEdges() {
        onTheTaskDetail("dependencies") { harness, page ->
            page.openFocusTask(harness.baseUrl)

            assertThat(page.panel(".task-dep-count")).hasText("2")
            assertThat(page.panel(".task-deps li")).hasCount(2)
            assertThat(page.panel(".task-deps li a[href=\"/tasks/local%3A1\"]")).hasCount(1)
            assertThat(page.panel(".task-deps li a[href=\"/tasks/local%3A2\"]")).hasCount(1)
            // The reverse edge is read-only and lives in the same section: local:4 waits on this task.
            assertThat(page.locator("#task-detail-dependents a[href=\"/tasks/local%3A4\"]")).hasCount(1)
            assertThat(page.panel(".task-blocked")).isVisible()

            page.locator("button[aria-label=\"Remove the dependency on local:1\"]").click()
            assertThat(page.panel(".task-dep-count")).hasText("1")
            page.locator("button[aria-label=\"Remove the dependency on local:2\"]").click()

            assertThat(page.panel(".task-dep-count")).hasText("0")
            assertThat(page.panel(".task-deps li")).hasCount(0)
            assertThat(page.panel(".task-blocked")).hasCount(0)
        }
    }

    /**
     * A refused write is spoken, not swallowed.
     *
     * The board screen unmounts the sidebar, and the sidebar footer is where the app's other `role=status`
     * line lives — so without `#board-status` a refused dependency, a refused drag and a failed delete all
     * produced nothing at all and the click simply did not appear to happen. `local:999` is the cheapest
     * of the four refusals to provoke (an unknown ref); the panel keeps its edges and stays usable.
     */
    @Test
    fun aRefusedDependencyIsAnnouncedAndChangesNothing() {
        onTheTaskDetail("dependency-refused") { harness, page ->
            page.openFocusTask(harness.baseUrl)

            page.locator("#task-detail-dep-input").fill("local:999")
            page.locator("#task-detail-dep-add").click()

            assertThat(page.locator("#board-status")).containsText("Could not add the dependency")
            assertThat(page.panel(".task-dep-count")).hasText("2")
            assertThat(page.panel(".task-deps li")).hasCount(2)
        }
    }

    /**
     * "Start session" is a handoff, not a launch.
     *
     * There is exactly one launch path in the app, and it is the New-session dialog's single
     * `POST /api/v1/sessions`; this panel opens that dialog pre-filled with the project's path and the
     * ref, and the request assertion is the half that would catch a second path being added here.
     */
    @Test
    fun startSessionOpensTheOrdinaryNewSessionDialogRatherThanLaunchingAnything() {
        onTheTaskDetail("start-session-handoff") { harness, page ->
            val seen = recordRequests(page)
            page.openFocusTask(harness.baseUrl)
            assertThat(page.locator("#task-detail-project")).containsText("/repo/detail")

            page.locator("#task-detail-start").click()

            assertThat(page.locator("#new-session-dialog")).isVisible()
            assertThat(page.locator("#new-session-task-ref")).containsText(focus)
            assertThat(page.locator("#session-cwd")).hasValue("/repo/detail")
            // The positive control on the SAME list, so the absence below is this page's silence and not
            // the recorder's: the panel's own read has to be in it.
            assertTrue(
                seen.any { it.isDetailRequest("GET") },
                "the request log is empty, so nothing can be concluded from it; requests seen: $seen",
            )
            assertTrue(
                seen.none { it.startsWith("POST ") && it.endsWith("/api/v1/sessions") },
                "opening the dialog starts nothing on its own; requests seen: $seen",
            )
        }
    }

    /**
     * A task deleted somewhere else stops offering editors that could only ever answer 404.
     *
     * The evidence is the shared list, not the panel's own read: a row this screen HELD and then lost is
     * a deletion (a removal frame and a fresh baseline are the only two things that take one out, and
     * both mean gone), while a row it never held is a baseline that has not landed. So the live rows are
     * awaited first — without that barrier this test would assert the wrong branch for the right reason.
     */
    @Test
    fun aTaskDeletedElsewhereSaysSoInsteadOfOfferingEditors() {
        onTheTaskDetail("deleted-elsewhere") { harness, page ->
            page.openFocusTask(harness.baseUrl)
            page.awaitLiveTaskRows()

            harness.send("task-del $focus")

            assertThat(page.locator("#task-detail-gone")).isVisible()
            assertThat(page.locator("#task-detail-form")).hasCount(0)
            assertThat(page.panel(".task-deps")).hasCount(0)
            // Every one of the four head shapes keeps the × — the way out must not depend on the task.
            assertThat(page.locator("#task-detail-close")).isVisible()
        }
    }

    /**
     * The × is in the corner, and the corner is a measured place rather than a wish.
     *
     * The head is a GRID for exactly this: flowed, the × was the last item of a row that wraps as soon as
     * the identity beside it is wide — which, with a project path in it on a 390px panel, is always — so
     * the one control all four head shapes promise ended up somewhere under the launch button. Geometry
     * is therefore the only honest assertion: it is inset from the head's top-right, it shares its box
     * with neither the identity nor the toolbar, and it is inside the panel.
     */
    @Test
    fun theCloseButtonHoldsTheHeadsTopRightCornerAndTakesTheOperatorBackToTheBoard() {
        onTheTaskDetail("close-in-the-corner") { harness, page ->
            page.openFocusTask(harness.baseUrl)

            val panel = page.locator(".task-detail").box()
            val head = page.panel(".task-detail-head").box()
            val close = page.locator("#task-detail-close").box()
            val ident = page.locator("#task-detail-ident").box()
            val tools = page.locator("#task-detail-tools").box()

            val rightInset = head.right() - close.right()
            val topInset = close.y - head.y
            assertTrue(rightInset in 0.0..20.0, "the × hugs the head's right edge, inset $rightInset px")
            assertTrue(topInset in 0.0..20.0, "…and its top edge, inset $topInset px")
            assertTrue(close.right() <= panel.right() + 0.5, "and it never leaves the panel")
            assertTrue(!close.overlaps(ident), "the identity is beside the ×, never under it")
            assertTrue(!close.overlaps(tools), "and the toolbar is on its own row, never over the ×")

            page.locator("#task-detail-close").click()

            assertThat(page.locator(".task-detail")).hasCount(0)
            assertThat(page.locator(".board")).isVisible()
            assertThat(page).hasURL(harness.baseUrl + "/tasks")
        }
    }

    /**
     * One column, one gutter — checked as boxes, because a stylesheet that says the right words can still
     * lay the panel out wrongly.
     *
     * The regression this pins is recorded in `style.css`: every block used to invent its own inset (the
     * head 14px of padding, the textarea a 14px margin plus a hand-computed `width: calc(100% - 28px)`,
     * the sections a margin) while `#task-detail-form` had none — so the title input ran edge to edge
     * while the description directly beneath it sat 14px in. The two fields sharing both edges, and that
     * shared inset matching on the left and the right, is exactly what "one gutter" means in pixels.
     */
    @Test
    fun thePanelStacksOneColumnAndGivesEveryBlockTheSameGutter() {
        onTheTaskDetail("panel-layout") { harness, page ->
            page.openFocusTask(harness.baseUrl)

            val panel = page.locator(".task-detail").box()
            // The panel is its own scroller, so its CONTENT edge — not its border box — is what a block
            // inside it is inset from. They differ by a classic scroll bar's width on any platform that
            // reserves one, and reading `clientWidth` is the measurement that survives both.
            val contentWidth = page.panelContentWidth()
            val contentRight = panel.x + contentWidth
            val blocks = listOf(
                "the head" to page.panel(".task-detail-head").box(),
                "the form" to page.locator("#task-detail-form").box(),
                "the dependencies" to page.panel(".task-deps").box(),
                "the sessions" to page.panel(".task-sessions").box(),
                "the feed" to page.panel(".task-activity").box(),
            )

            for ((name, box) in blocks) {
                assertTrue(box.x >= panel.x - 0.5, "$name starts inside the panel")
                assertTrue(box.right() <= panel.right() + 0.5, "$name ends inside the panel")
            }
            for (i in 1 until blocks.size) {
                val (above, aboveBox) = blocks[i - 1]
                val (below, belowBox) = blocks[i]
                assertTrue(
                    belowBox.y >= aboveBox.bottom() - 1.0,
                    "$below stacks under $above rather than beside or over it",
                )
            }
            // The head is the sticky bar, so it spans the panel; the blocks under it take the gutter.
            assertTrue(
                abs(blocks[0].second.width - contentWidth) <= 1.0,
                "the head spans the panel's whole width",
            )

            val title = page.locator("#task-detail-title-input").box()
            val body = page.locator("#task-detail-body").box()
            assertTrue(abs(title.x - body.x) <= 1.0, "the title input and the description share a left edge")
            assertTrue(
                abs(title.right() - body.right()) <= 1.0,
                "…and a right edge: neither subtracts its own margins out of a percentage width",
            )
            val left = title.x - panel.x
            val right = contentRight - title.right()
            assertTrue(left >= 6.0, "the fields are inset from the panel's left edge, not flush with it")
            assertTrue(abs(left - right) <= 1.5, "and the inset is the same on both sides ($left / $right)")
        }
    }

    // --- fixture plumbing -------------------------------------------------------------------------

    /**
     * A harness, a browser, a fresh context (the cookie is not bound to the port, so reuse breaks the
     * login) and one page, torn down in that order. The trace and its screenshot are kept only if the
     * body throws.
     */
    private fun onTheTaskDetail(trace: String, body: (Harness, Page) -> Unit) {
        Harness(TASK_DETAIL_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.touchContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced("task-detail-$trace") { body(harness, context.newPage()) }
                }
            }
        }
    }

    /** Open `/tasks/local:3` and wait for the fetched detail to replace the loading paragraph. */
    private fun Page.openFocusTask(baseUrl: String) {
        navigate(baseUrl + focusRoute)
        assertThat(locator("#task-detail-form"))
            .isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(firstPaintMs))
    }

    /**
     * Wait until the events socket's task baseline has landed in the app's one list.
     *
     * Two tests below need it: "the app HELD a row for this ref" is what separates a deletion from a
     * baseline that has not arrived yet, and a frame that lands before the snapshot proves nothing about
     * either. The cards are `display: none` behind the panel on a phone, hence ATTACHED and not VISIBLE.
     */
    private fun Page.awaitLiveTaskRows() {
        locator(".task-card").first()
            .waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
    }

    /** The panel's content width — its box minus whatever its own scroll bar reserves. */
    private fun Page.panelContentWidth(): Double =
        (locator(".task-detail").evaluate("el => el.clientWidth") as Number).toDouble()

    /** Scope a shared board class to the open panel: the board is hidden behind it, never absent. */
    private fun Page.panel(selector: String): Locator = locator(".task-detail $selector")

    private fun Page.panel(selector: String, options: Page.LocatorOptions): Locator =
        locator(".task-detail $selector", options)

    /**
     * The same detail body with one linked session spliced into its (empty) `sessions` list, under the id
     * an activity row already names and a display name that id does NOT carry.
     *
     * The whole point is the mismatch: with the two apart, "the author is recorded text" and "the author
     * is resolved through the live list" render differently, which they cannot do against a fixture whose
     * `sessions` list is empty.
     */
    private fun withInjectedSession(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        val session = JsonObject().apply {
            addProperty("id", "s-detail-1")
            addProperty("name", injectedName)
            addProperty("agent", "claude")
            addProperty("state", "running")
            addProperty("needsAttention", false)
            addProperty("alive", true)
            addProperty("archived", false)
        }
        root.add("sessions", JsonArray().apply { add(session) })
        return root.toString()
    }

    /** Every request the page issues, in order, as `"<METHOD> <url>"`. */
    private fun recordRequests(page: Page): List<String> {
        val seen = CopyOnWriteArrayList<String>()
        page.onRequest { request -> seen.add(request.method() + " " + request.url()) }
        return seen
    }

    /**
     * Whether this recorded request is [method] against the focus task's detail endpoint.
     *
     * Both spellings are accepted because the ref's mandatory `:` is percent-encoded by the client and a
     * browser is free to hand it back either way — the test is about the endpoint, not about the escape.
     */
    private fun String.isDetailRequest(method: String): Boolean =
        startsWith("$method ") && (endsWith(detailApi) || endsWith("/api/v1/tasks/$focus"))

    private fun Locator.box(): BoundingBox =
        boundingBox() ?: error("no box for a locator that must be laid out: $this")

    private fun BoundingBox.right(): Double = x + width

    private fun BoundingBox.bottom(): Double = y + height

    /** Whether two boxes share a single pixel; touching edges are not an overlap. */
    private fun BoundingBox.overlaps(other: BoundingBox): Boolean =
        x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom()
}
