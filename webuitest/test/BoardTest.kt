package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.KeyboardModifier
import com.microsoft.playwright.options.MouseButton
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The kanban board in a real browser: what the four columns draw, and the pointer drag that moves a
 * card between them.
 *
 * This replaces `test/transport/WebUiBoardTest.kt`, which asserted the same subjects by GETting
 * `Board.js` / `TaskCard.js` off the daemon and grepping the source it was handed — the only thing a
 * `macosArm64` test binary can do about a screen. Everything that file could only spell is a behaviour
 * here: `DRAG_SLOP_PX = 8` is a press that travels four pixels and stays a press, `setPointerCapture` is
 * a highlight that keeps following a pointer which has left the card it started on, and "the PATCH is
 * issued before the move" is the two requests the daemon actually received, in the order it received
 * them. Two of that file's tests stay behind in Kotlin because they are not browser-observable at all:
 * the frozen CSS class vocabulary shared with `style.css`, and the `<input maxlength>` bound to the
 * daemon's own `PROJECT_NAME_MAX_LENGTH` (a native root-module constant this JVM module cannot see).
 *
 * **Chromium only**, for the reason the light-dismiss tests are (plan fact 4): WebKit delivered nothing
 * whatsoever to a touched element, and every gesture below is pointer-driven.
 *
 * The seeded `board` scenario is the fixture's: `local:1..10` over todo `1,2,3,4,10` · in_progress
 * `5,6` · review `7` · done `8,9`, with `local:10` blocked on `local:5`.
 */
class BoardTest {

    // --- what the board draws --------------------------------------------------------------------

    /**
     * Four columns over ONE project, each holding exactly the seeded refs in their position order.
     *
     * There is deliberately no "all projects" mode to test for: `position` is a project-wide gap rank,
     * so a combined view could not be reordered by any move the API can express. The board filters the
     * one list `app.js` holds by the selected project and never fetches a task itself, so what is on
     * screen here arrived over the `/events` socket's `tasks_snapshot` — the head naming the project is
     * the only part that came from `GET /projects`.
     *
     * The column head counts EVERY entry, not the slice a capped `done` renders; with two done cards the
     * two numbers coincide, which is why the count is asserted against the seeded totals rather than
     * against the rendered cards.
     */
    @Test
    fun theBoardRendersEverySeededColumnInItsPositionOrder() =
        onTheBoard("theBoardRendersEverySeededColumnInItsPositionOrder") { _, page ->
            assertThat(page.locator(".board-project")).hasText(BOARD_PROJECT)
            assertThat(page.locator(".board-project-path")).hasText("/repo/board")
            assertThat(page.locator(".board-column")).hasCount(4)

            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
            assertEquals(listOf("local:5", "local:6"), page.refsIn("in_progress"))
            assertEquals(listOf("local:7"), page.refsIn("review"))
            assertEquals(listOf("local:8", "local:9"), page.refsIn("done"))

            for ((state, count) in listOf("todo" to "5", "in_progress" to "2", "review" to "1", "done" to "2")) {
                assertThat(page.column(state).locator(".board-column-head span")).hasText(count)
            }
        }

    /**
     * `blocked` is the daemon's derivation, never the board's: the card renders the flag the
     * `BacklogEntryDto` carries, and the dependency count comes off the same row. `local:10` is the one
     * seeded task waiting on another (`local:5`), so it is the one card that may carry the marker — a
     * second one would mean the browser had started deriving blockedness of its own.
     */
    @Test
    fun theBlockedCardCarriesTheMarkerAndItsDependencyCount() =
        onTheBoard("theBlockedCardCarriesTheMarkerAndItsDependencyCount") { _, page ->
            val blocked = page.card("local:10")
            assertThat(blocked.locator(".task-blocked")).hasText("blocked")
            assertThat(blocked.locator(".task-dep-count")).containsText("1")
            assertThat(page.locator(".task-blocked")).hasCount(1)
            assertThat(page.locator(".task-dep-count")).hasCount(1)
        }

    /**
     * An adopted directory with no backlog yet is still a board: four empty columns, its name and path in
     * the head, and a "New task" button that is enabled because a project IS selected (the button is
     * disabled only while `projectId` is null, which is what a daemon with no projects at all produces).
     */
    @Test
    fun aProjectWithNoTasksStillRendersItsFourEmptyColumns() =
        onTheBoard("aProjectWithNoTasksStillRendersItsFourEmptyColumns", scenario = BOARD_EMPTY_SCENARIO) { _, page ->
            assertThat(page.locator(".board-project")).hasText(EMPTY_PROJECT)
            assertThat(page.locator(".board-project-path")).hasText("/repo/empty")
            assertThat(page.locator(".board-column")).hasCount(4)
            assertThat(page.locator(".task-card")).hasCount(0)
            for (state in COLUMN_STATES) {
                assertThat(page.column(state).locator(".board-column-head span")).hasText("0")
            }
            assertThat(page.locator(".board-new-task")).isEnabled()
        }

    // --- the drag ----------------------------------------------------------------------------------

    /**
     * Three presses on the handle, one claim between them.
     *
     * A press that travels less than the slop in BOTH axes is still a press — without that, a click on
     * the handle would become a drag and drop the card wherever the pointer happened to twitch to. A
     * press on any button but the primary one is not a drag either: a right-button press is a context
     * menu the browser owns, and the gesture must not start under it.
     *
     * The observable is a counter rather than an assertion per arm, because "no drag was claimed" has no
     * moment at which it can be observed: the third arm is what proves the counter can move at all, and
     * reading it only after that claim is visible is what makes the first two arms' zero meaningful. It
     * counts `false → true` transitions of `.task-card.is-dragging` through a `MutationObserver`, so a
     * claim that appeared and was undone within one frame is still counted.
     */
    @Test
    fun aPressUnderTheSlopOrOnTheWrongButtonNeverBecomesADrag() =
        onTheBoard("aPressUnderTheSlopOrOnTheWrongButtonNeverBecomesADrag") { _, page ->
            val writes = page.recordTaskWrites()
            page.watchDragClaims()
            val handle = page.handleOf("local:1")
            val start = handle.centre()

            // Four pixels across and two down: under `DRAG_SLOP_PX` in both axes.
            page.mouse().move(start.first, start.second)
            page.mouse().down()
            page.mouse().move(start.first + 4, start.second + 2)
            page.mouse().up()

            // Far past the slop, but on the secondary button.
            page.mouse().move(start.first, start.second)
            page.mouse().down(Mouse.DownOptions().setButton(MouseButton.RIGHT))
            page.mouse().move(start.first + 60, start.second + 30, Mouse.MoveOptions().setSteps(6))
            page.mouse().up(Mouse.UpOptions().setButton(MouseButton.RIGHT))

            // The control: the same travel on the primary button IS a drag.
            page.mouse().move(start.first, start.second)
            page.mouse().down()
            page.mouse().move(start.first + 60, start.second + 30, Mouse.MoveOptions().setSteps(6))
            assertThat(page.locator(".task-card.is-dragging")).hasCount(1)
            page.releaseOverNothing()

            assertEquals(1, page.dragClaims(), "only the primary-button press past the slop claimed a drag")
            assertEquals(emptyList<String>(), writes.snapshot(), "a press that is not a drag writes nothing")
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
        }

    /**
     * A claimed drag keeps tracking the pointer after it has left the card — and after the re-render its
     * own highlight causes.
     *
     * Both halves are `setPointerCapture`. Without it the `pointermove`s over another column would be
     * delivered to THAT column, which is not an ancestor of the handle, so the handle's handler would
     * simply stop hearing about the gesture and the highlight would freeze on the column the press
     * started in. And every move sets a new drop target, which re-renders the board — the same hazard
     * the light-dismiss gesture documents: a captured pointer has to survive the repaint it causes.
     *
     * Crossing TWO foreign columns is what separates the two failures: a highlight that reached
     * `in_progress` and stuck there is a capture that was taken and then lost.
     */
    @Test
    fun aClaimedDragKeepsTrackingWhileThePointerIsOverAnotherColumn() =
        onTheBoard("aClaimedDragKeepsTrackingWhileThePointerIsOverAnotherColumn") { _, page ->
            val writes = page.recordTaskWrites()
            page.pressHandleOf("local:1")

            page.travelTo(page.column("in_progress").centre())
            assertThat(page.column("in_progress")).hasClass(DROP_TARGET_CLASS)
            assertThat(page.column("todo")).hasClass(COLUMN_CLASS)
            assertThat(page.card("local:1")).hasClass("task-card is-dragging")

            page.travelTo(page.column("review").centre())
            assertThat(page.column("review")).hasClass(DROP_TARGET_CLASS)
            assertThat(page.column("in_progress")).hasClass(COLUMN_CLASS)

            page.travelTo(page.column("done").bottomInside())
            assertThat(page.column("done")).hasClass(DROP_TARGET_CLASS)
            assertThat(page.column("review")).hasClass(COLUMN_CLASS)

            // Released where no column is: the release position is the drop, and there is none here.
            page.releaseOverNothing()
            assertThat(page.locator(".board-drop-target")).hasCount(0)
            assertThat(page.locator(".task-card.is-dragging")).hasCount(0)
            assertEquals(emptyList<String>(), writes.snapshot(), "a release outside every column drops nothing")
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
        }

    /**
     * A gesture the platform took away is not a drop: `pointercancel` ends the drag and writes nothing.
     *
     * The absence of a request cannot be observed at a moment either, so the drop that follows is the
     * barrier: it issues two requests that MUST arrive, and the assertion is that they are the first two
     * — anything the cancelled gesture had sent would sit in front of them. Both gestures are the same
     * card, so a request from the cancelled one would be indistinguishable from the drop's own except by
     * its position in the list, which is exactly what is being asserted.
     *
     * The cancel itself is injected with `dispatchEvent`, because no automation API produces a genuine
     * one: a browser fires it when it takes a gesture away (an incoming call, a system gesture), which
     * is not something Playwright can stage. What the injection therefore proves is this handler's own
     * contract — that the cancel branch does not reach `applyDrop` — while the press, the travel, the
     * claim and the capture around it are all real input. The pointer id is read off the real
     * `pointerdown` rather than assumed, so the handler's `event.pointerId !== gesture.pointerId` guard
     * is answered with the id the browser actually used.
     */
    @Test
    fun aCancelledDragMutatesNothingAndTheDropAfterItIsTheFirstWrite() =
        onTheBoard("aCancelledDragMutatesNothingAndTheDropAfterItIsTheFirstWrite") { _, page ->
            val writes = page.recordTaskWrites()
            page.watchPointerIds()

            page.pressHandleOf("local:1")
            page.travelTo(page.column("in_progress").centre())
            assertThat(page.column("in_progress")).hasClass(DROP_TARGET_CLASS)

            val pointerId = page.lastPointerDownId()
            page.handleOf("local:1").dispatchEvent("pointercancel", mapOf("pointerId" to pointerId))
            page.mouse().up()

            assertThat(page.locator(".task-card.is-dragging")).hasCount(0)
            assertThat(page.locator(".board-drop-target")).hasCount(0)
            assertThat(page.card("local:1")).hasCount(1)
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))

            page.dragToBottomOf("local:1", "in_progress")
            page.waitForCondition { writes.snapshot().size >= 2 }
            assertEquals(
                listOf("PATCH /tasks/local%3A1", "POST /tasks/local%3A1/move"),
                writes.snapshot(),
                "the drop's PATCH is the FIRST request the page issued — the cancelled drag added none",
            )
        }

    /**
     * A drop that changes both the column and the rank is two requests, in one order.
     *
     * `/move` takes no state and `PATCH` takes no position, so the state has to land before the rank is
     * resolved against the column the card is joining. Each answer is merged as it arrives, which is why
     * a failing second request still leaves the first one's committed row on the board.
     *
     * The landing is asserted too, not just the calls: dropped below every card in `in_progress`, the
     * card names the last of them (`{ after: local:6 }`) rather than falling back to the backlog's end,
     * and the column's rendered order is what proves the rank the daemon committed.
     */
    @Test
    fun aCrossColumnDropPatchesTheStateThenMovesTheRank() =
        onTheBoard("aCrossColumnDropPatchesTheStateThenMovesTheRank") { _, page ->
            val writes = page.recordTaskWrites()

            page.dragToBottomOf("local:1", "in_progress")

            page.waitForCondition { writes.snapshot().size >= 2 }
            assertEquals(
                listOf("PATCH /tasks/local%3A1", "POST /tasks/local%3A1/move"),
                writes.snapshot(),
                "the PATCH carries the state, the move carries the rank, and the PATCH goes first",
            )
            // The PATCH alone already puts the card in the column, at whatever rank its untouched
            // `position` gives it — so the settled read has to wait for the MOVE, and the last card
            // being the dragged one is that. Only then is the order below a reading of both requests.
            assertThat(page.column("in_progress").locator(".task-card").last())
                .hasAttribute("data-ref", "local:1")
            assertThat(page.column("in_progress").locator(".task-card")).hasCount(3)
            assertEquals(listOf("local:5", "local:6", "local:1"), page.refsIn("in_progress"))
            assertEquals(listOf("local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
            assertThat(page.column("todo").locator(".board-column-head span")).hasText("4")
        }

    /**
     * A modified click on a card title belongs to the browser — new tab, new window, download — and the
     * router must keep its hands off it. The title is a real `<a href="/tasks/{ref}">` precisely so that
     * those gestures work against a path the daemon really serves, and `isPlainClick` is what leaves
     * them alone.
     *
     * `location.pathname` is read out of the renderer rather than from `page.url()`: the router's
     * `pushState` runs synchronously inside the click handler, so a read the renderer answers after the
     * click was processed cannot miss a navigation that happened. The plain click that follows is the
     * control — it proves the same title, in the same state, does navigate.
     */
    @Test
    fun aModifiedClickOnACardTitleIsLeftToTheBrowser() =
        onTheBoard("aModifiedClickOnACardTitleIsLeftToTheBrowser") { _, page ->
            val title = page.card("local:1").locator(".task-card-title")

            title.click(Locator.ClickOptions().setModifiers(listOf(KeyboardModifier.CONTROLORMETA)))
            assertEquals(
                "/tasks",
                page.evaluate("() => location.pathname"),
                "the SPA did not navigate: the modified click is the browser's",
            )

            // The browser may well have opened a tab for it; this one is the subject.
            page.bringToFront()
            title.click()
            page.waitForURL("**/tasks/local%3A1")
            assertThat(title).hasAttribute("aria-current", "true")
        }

    // --- the phone ---------------------------------------------------------------------------------

    /**
     * At 390 px the four tracks collapse to one, so the board renders exactly one column and the
     * switcher is the only way to the other three. Rendering all four and letting them stack was the
     * degraded fallback; this is the branch that avoids it.
     */
    @Test
    fun thePhoneRendersOneColumnAndReachesTheOthersThroughItsSwitcher() =
        onTheBoard("thePhoneRendersOneColumnAndReachesTheOthersThroughItsSwitcher", phone = true) { _, page ->
            assertThat(page.locator(".board-column")).hasCount(1)
            assertThat(page.column("todo")).isVisible()
            assertThat(page.locator(".board-column-switch button")).hasCount(4)
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))

            val box = page.column("todo").boundingBox()
            assertTrue(
                box.width > 300 && box.x + box.width <= PHONE_WIDTH,
                "the one column takes the phone's width instead of a quarter of it (${box.width})",
            )

            page.locator(".board-column-switch button[data-state=\"review\"]").click()
            assertThat(page.locator(".board-column")).hasCount(1)
            assertThat(page.column("review")).isVisible()
            assertEquals(listOf("local:7"), page.refsIn("review"))
            assertThat(page.locator(".task-card")).hasCount(1)
        }

    /**
     * Dragging between columns cannot exist when only one is on screen, so the card menu carries the
     * moves there — and only there. It is the phone's whole answer to the drag, so it is asserted the
     * same way: the request the daemon receives, and the card arriving in the column that was named.
     */
    @Test
    fun thePhoneMovesACardThroughTheCardMenuInsteadOfADrag() =
        onTheBoard("thePhoneMovesACardThroughTheCardMenuInsteadOfADrag", phone = true) { _, page ->
            val writes = page.recordTaskWrites()

            page.card("local:1").locator(".task-card-menu summary").click()
            page.card("local:1").locator(".task-card-menu button")
                .filter(Locator.FilterOptions().setHasText("Move to In progress"))
                .click()

            page.waitForCondition { writes.snapshot().isNotEmpty() }
            assertEquals(
                listOf("PATCH /tasks/local%3A1"),
                writes.snapshot(),
                "a menu move is the state alone — there is no rank to resolve against a column off screen",
            )
            assertThat(page.card("local:1")).hasCount(0)

            page.locator(".board-column-switch button[data-state=\"in_progress\"]").click()
            assertThat(page.column("in_progress").locator(".task-card[data-ref=\"local:1\"]")).hasCount(1)
        }

    /**
     * The gesture reservation is the HANDLE's alone.
     *
     * `touch-action: none` is what makes the drag possible at all — a vertical drag the browser has
     * already claimed for scrolling never reaches `pointermove` — but reserving it on the card, or on
     * the column, would cost a phone the scroll of the one column it can see. So the property is
     * asserted in both directions, on the device where it decides anything.
     */
    @Test
    fun theGestureReservationIsScopedToTheCardHandleAlone() =
        onTheBoard("theGestureReservationIsScopedToTheCardHandleAlone", phone = true) { _, page ->
            assertEquals("none", page.handleOf("local:1").touchAction(), "the handle reserves the gesture")
            assertEquals("auto", page.card("local:1").touchAction(), "the card leaves the column scrollable")
            assertEquals("auto", page.column("todo").touchAction(), "and so does the column itself")
        }

    // --- newest-rev-wins ---------------------------------------------------------------------------

    /**
     * A stale REST body cannot roll back a card a fresher frame already moved.
     *
     * Every observation of a task — the detail's `GET` answer and the socket's `task_update` alike —
     * carries the store's global `rev`, and both go into the ONE list through the same rev-aware upsert.
     * That is what makes them mergeable in any arrival order, and this is the order that used to lose:
     * the response was in flight while the change happened, so it describes a task that has since moved.
     *
     * The detail `GET` for `local:3` is intercepted and its real answer fetched but not delivered, which
     * freezes it at the pre-race revision. `task-race` then moves the card over the socket. Only then is
     * the held body handed to the browser — and the card must not follow it back. `local:3` is the
     * fixture's edge-free task precisely so nothing else can move it.
     *
     * The barrier for the "did not move back" half is the detail panel itself: it renders from the very
     * body being released (`#task-detail-loading` is replaced only when `setDetail` runs), and the row is
     * published to the shared list in the same tick, so a panel that has stopped loading is proof the
     * stale row was already offered to the board and refused.
     */
    @Test
    fun aStaleDetailResponseCannotRollBackACardTheFrameAlreadyMoved() =
        onTheBoard(
            "aStaleDetailResponseCannotRollBackACardTheFrameAlreadyMoved",
            open = false,
        ) { harness, page ->
            val held = AtomicReference<Route?>(null)
            page.route(
                Predicate<String> { url -> url.endsWith("$API_PREFIX/tasks/local%3A3") },
                Consumer<Route> { route -> if (!held.compareAndSet(null, route)) route.resume() },
            )

            page.navigate(harness.baseUrl + "/tasks/local%3A3")
            page.waitForCondition { held.get() != null }
            assertThat(page.locator(".task-card")).hasCount(SEEDED_CARDS)
            assertThat(page.locator("#task-detail-loading")).isVisible()

            val before = page.slotOf("local:3")
            val stale = held.get()!!.fetch()

            harness.send("task-race local:3")
            page.waitForFunction(CARD_MOVED_JS, mapOf("ref" to "local:3", "before" to before))
            val moved = page.slotOf("local:3")
            assertNotEquals(before, moved, "the frame moved the card")

            held.get()!!.fulfill(Route.FulfillOptions().setResponse(stale))
            // `#task-detail-project` belongs to the loaded arm alone: "loading is over" would also be
            // true of the deleted and the failed arms, neither of which publishes a row at all.
            assertThat(page.locator("#task-detail-project")).isVisible()
            assertThat(page.locator("#task-detail-loading")).hasCount(0)

            assertEquals(moved, page.slotOf("local:3"), "the stale response lost to the newer frame")
        }

    // --- the board's own vocabulary ----------------------------------------------------------------

    private fun Page.card(ref: String): Locator = locator(".task-card[data-ref=\"$ref\"]")

    private fun Page.column(state: String): Locator = locator(".board-column[data-state=\"$state\"]")

    private fun Page.handleOf(ref: String): Locator = card(ref).locator(".task-card-handle")

    /** The `data-ref` of every card [state]'s column renders, in the order it renders them. */
    private fun Page.refsIn(state: String): List<String> {
        val refs = column(state).locator(".task-card")
            .evaluateAll("cards => cards.map((card) => card.getAttribute('data-ref'))")
        return (refs as List<*>).map { it as String }
    }

    /** `<column state>#<index among that column's cards>` — where a card is, in one comparable string. */
    private fun Page.slotOf(ref: String): String = evaluate(CARD_SLOT_JS, ref) as String

    private fun Locator.touchAction(): String = evaluate("el => getComputedStyle(el).touchAction") as String

    // --- driving the pointer -----------------------------------------------------------------------

    private fun Locator.centre(): Pair<Double, Double> =
        boundingBox().let { it.x + it.width / 2 to it.y + it.height / 2 }

    /** A point inside [this] element but below everything in it — where a drop lands at the end. */
    private fun Locator.bottomInside(): Pair<Double, Double> =
        boundingBox().let { it.x + it.width / 2 to it.y + it.height - 16 }

    private fun Page.pressHandleOf(ref: String) {
        val start = handleOf(ref).centre()
        mouse().move(start.first, start.second)
        mouse().down()
        // Past the slop in one step, so the gesture is claimed and captured before it goes anywhere.
        mouse().move(start.first, start.second + 20, Mouse.MoveOptions().setSteps(4))
    }

    private fun Page.travelTo(point: Pair<Double, Double>) =
        mouse().move(point.first, point.second, Mouse.MoveOptions().setSteps(8))

    /** Release over the board's head, which no column contains: a claimed drag that drops nothing. */
    private fun Page.releaseOverNothing() {
        travelTo(locator(".board-identity").centre())
        mouse().up()
    }

    private fun Page.dragToBottomOf(ref: String, state: String) {
        pressHandleOf(ref)
        travelTo(column(state).centre())
        travelTo(column(state).bottomInside())
        assertThat(column(state)).hasClass(DROP_TARGET_CLASS)
        mouse().up()
    }

    // --- observing the page ------------------------------------------------------------------------

    /** Every task mutation the page issues, newest last, as `"<METHOD> <path under /api/v1>"`. */
    private fun Page.recordTaskWrites(): MutableList<String> {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        onRequest { request ->
            val url = request.url()
            if (url.contains("$API_PREFIX/tasks") && request.method() != "GET") {
                calls.add(request.method() + " " + url.substringAfter(API_PREFIX))
            }
        }
        return calls
    }

    /** A copy taken under the list's own lock — the recorder is written from the Playwright thread. */
    private fun MutableList<String>.snapshot(): List<String> = synchronized(this) { toList() }

    private fun Page.watchDragClaims() = evaluate(DRAG_CLAIM_WATCHER_JS)

    private fun Page.dragClaims(): Int = (evaluate("() => window.__kotgentDragClaims") as Number).toInt()

    private fun Page.watchPointerIds() = evaluate(POINTER_ID_WATCHER_JS)

    private fun Page.lastPointerDownId(): Int =
        (evaluate("() => window.__kotgentPointerId") as Number).toInt()

    // --- harness -------------------------------------------------------------------------------------

    /**
     * One harness, one Chromium, one fresh context (the session cookie is not bound to a port, so a
     * reused context would carry the previous harness's), logged in through the real `/auth` form.
     *
     * The board is opened and SETTLED before the block runs. Settling is not the project name: that
     * comes from `GET /projects` over HTTP and can land before the socket's `tasks_snapshot`, which is
     * where every card comes from. So the barrier is the seeded card count — every card of one snapshot
     * renders in one pass, and below the breakpoint only the active column's are drawn.
     *
     * [open] is false for the one test that must install a route before the SPA's first request.
     */
    private fun onTheBoard(
        name: String,
        scenario: String = BOARD_SCENARIO,
        phone: Boolean = false,
        open: Boolean = true,
        block: (Harness, Page) -> Unit,
    ) {
        Harness(scenario).use { harness ->
            Playwright.create().use { playwright ->
                val browser = touchChromium(playwright)
                val context = if (phone) {
                    browser.touchContext()
                } else {
                    browser.newContext(Browser.NewContextOptions().setViewportSize(DESKTOP_WIDTH, DESKTOP_HEIGHT))
                }
                try {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(name) {
                        val page = context.newPage()
                        if (open) {
                            page.navigate(harness.baseUrl + "/tasks")
                            assertThat(page.locator(".board-columns")).isVisible()
                            val empty = scenario == BOARD_EMPTY_SCENARIO
                            assertThat(page.locator(".board-project"))
                                .hasText(if (empty) EMPTY_PROJECT else BOARD_PROJECT)
                            val cards = when {
                                empty -> 0
                                phone -> SEEDED_TODO_CARDS
                                else -> SEEDED_CARDS
                            }
                            assertThat(page.locator(".task-card")).hasCount(cards)
                        }
                        block(harness, page)
                    }
                } finally {
                    context.close()
                    browser.close()
                }
            }
        }
    }

    /**
     * The fixture's seeded facts and this file's own geometry, in a companion rather than at the
     * top level: every wave-3 test class shares the `io.kotgent.webuitest` package, and a name as
     * ordinary as `API_PREFIX` has no business competing for it.
     */
    private companion object {
        private const val BOARD_SCENARIO = "board"
        private const val BOARD_EMPTY_SCENARIO = "board-empty"
        private const val BOARD_PROJECT = "Board Fixture"
        private const val EMPTY_PROJECT = "Empty Fixture"
        private const val API_PREFIX = "/api/v1"

        /** Wide enough for four real columns beside the sidebar; the phone half runs in `touchContext()`. */
        private const val DESKTOP_WIDTH = 1400
        private const val DESKTOP_HEIGHT = 900

        /** `touchContext()`'s width, which the plan's single-column checkbox names. */
        private const val PHONE_WIDTH = 390

        /** The `board` scenario's ten tasks, and the five of them the phone's first column holds. */
        private const val SEEDED_CARDS = 10
        private const val SEEDED_TODO_CARDS = 5

        private val COLUMN_STATES = listOf("todo", "in_progress", "review", "done")

        /** The class attribute of a column, with and without the drag highlight — exact, because it is built as
         *  `"board-column" + (over ? " board-drop-target" : "")` and nothing else contributes to it. */
        private const val COLUMN_CLASS = "board-column"
        private const val DROP_TARGET_CLASS = "board-column board-drop-target"

        /** Where a card is: its column's state and its index among that column's cards. */
        private const val CARD_SLOT_JS = """
          (ref) => {
            const card = document.querySelector('.task-card[data-ref="' + ref + '"]');
            if (!card) return "gone";
            const column = card.closest(".board-column");
            if (!column) return "loose";
            const cards = Array.prototype.slice.call(column.querySelectorAll(".task-card"));
            return column.getAttribute("data-state") + "#" + cards.indexOf(card);
          }
        """

        /** The same reading, as a predicate: has the card left the slot it was in? */
        private const val CARD_MOVED_JS = """
          (args) => {
            const card = document.querySelector('.task-card[data-ref="' + args.ref + '"]');
            if (!card) return false;
            const column = card.closest(".board-column");
            if (!column) return false;
            const cards = Array.prototype.slice.call(column.querySelectorAll(".task-card"));
            const slot = column.getAttribute("data-state") + "#" + cards.indexOf(card);
            return slot !== args.before;
          }
        """

        /**
         * Count `false → true` transitions of "some card is being dragged".
         *
         * A `MutationObserver` rather than a poll, because a claim that is made and undone inside one frame is
         * still a claim, and a poll would miss it — which is precisely the failure the slop test is written to
         * catch. It observes the whole document so it survives every re-render the board does under a drag.
         */
        private const val DRAG_CLAIM_WATCHER_JS = """
          () => {
            window.__kotgentDragClaims = 0;
            let dragging = false;
            const check = () => {
              const now = !!document.querySelector(".task-card.is-dragging");
              if (now && !dragging) window.__kotgentDragClaims += 1;
              dragging = now;
            };
            new MutationObserver(check).observe(document.documentElement, {
              subtree: true, childList: true, attributes: true, attributeFilter: ["class"],
            });
            check();
          }
        """

        /** The pointer id of the last real `pointerdown`, so an injected cancel can name the same pointer. */
        private const val POINTER_ID_WATCHER_JS = """
          () => {
            window.__kotgentPointerId = null;
            document.addEventListener("pointerdown", (event) => {
              window.__kotgentPointerId = event.pointerId;
            }, true);
          }
        """
    }
}
