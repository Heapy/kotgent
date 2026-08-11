package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
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

class BoardTest {


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

    @Test
    fun aFrameForANewCardLandsAtItsRankRatherThanAtTheEndOfTheList() =
        onTheBoard("aFrameForANewCardLandsAtItsRankRatherThanAtTheEndOfTheList") { harness, page ->
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))

            harness.send("task-add $INSERTED_REF $INSERTED_POSITION")
            assertThat(page.locator(".task-card")).hasCount(SEEDED_CARDS + 1)

            assertEquals(
                listOf("local:1", "local:2", INSERTED_REF, "local:3", "local:4", "local:10"),
                page.refsIn("todo"),
                "the new card belongs at its rank; last would mean the board renders arrival order",
            )
        }

    @Test
    fun theBlockedCardCarriesTheMarkerAndItsDependencyCount() =
        onTheBoard("theBlockedCardCarriesTheMarkerAndItsDependencyCount") { _, page ->
            val blocked = page.card("local:10")
            assertThat(blocked.locator(".task-blocked")).hasText("blocked")
            assertThat(blocked.locator(".task-dep-count")).containsText("1")
            assertThat(page.locator(".task-blocked")).hasCount(1)
            assertThat(page.locator(".task-dep-count")).hasCount(1)
        }

    @Test
    fun aProjectWithNoTasksStillRendersItsFourEmptyColumns() =
        onTheBoard(
            "aProjectWithNoTasksStillRendersItsFourEmptyColumns",
            scenario = BOARD_EMPTY_SCENARIO,
            open = false,
        ) { harness, page ->
            page.addInitScript(FRAME_RECORDER)
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".board-columns")).isVisible()
            page.waitForFunction(SAW_TASKS_SNAPSHOT_JS)

            assertThat(page.locator(".board-project")).hasText(EMPTY_PROJECT)
            assertThat(page.locator(".board-project-path")).hasText("/repo/empty")
            assertThat(page.locator(".board-column")).hasCount(4)
            assertThat(page.locator(".task-card")).hasCount(0)
            for (state in COLUMN_STATES) {
                assertThat(page.column(state).locator(".board-column-head span")).hasText("0")
            }
            assertThat(page.locator(".board-new-task")).isEnabled()
        }


    @Test
    fun aPressUnderTheSlopOrOnTheWrongButtonNeverBecomesADrag() =
        onTheBoard("aPressUnderTheSlopOrOnTheWrongButtonNeverBecomesADrag") { _, page ->
            val writes = page.recordTaskWrites()
            page.watchDragClaims()
            val handle = page.handleOf("local:1")
            val start = handle.centre()

            page.mouse().move(start.first, start.second)
            page.mouse().down()
            page.mouse().move(start.first + 4, start.second + 2)
            page.mouse().up()

            page.mouse().move(start.first, start.second)
            page.mouse().down(Mouse.DownOptions().setButton(MouseButton.RIGHT))
            page.mouse().move(start.first + 60, start.second + 30, Mouse.MoveOptions().setSteps(6))
            page.mouse().up(Mouse.UpOptions().setButton(MouseButton.RIGHT))

            page.mouse().move(start.first, start.second)
            page.mouse().down()
            page.mouse().move(start.first + 60, start.second + 30, Mouse.MoveOptions().setSteps(6))
            assertThat(page.locator(".task-card.is-dragging")).hasCount(1)
            page.releaseOverNothing()

            assertEquals(1, page.dragClaims(), "only the primary-button press past the slop claimed a drag")
            assertEquals(emptyList<String>(), writes.snapshot(), "a press that is not a drag writes nothing")
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
            page.proveTheWriteRecorderWasLive(writes, "local:1", "in_progress")
        }

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

            page.releaseOverNothing()
            assertThat(page.locator(".board-drop-target")).hasCount(0)
            assertThat(page.locator(".task-card.is-dragging")).hasCount(0)
            assertEquals(emptyList<String>(), writes.snapshot(), "a release outside every column drops nothing")
            assertEquals(listOf("local:1", "local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
            page.proveTheWriteRecorderWasLive(writes, "local:1", "in_progress")
        }

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
            assertThat(page.column("in_progress").locator(".task-card").last())
                .hasAttribute("data-ref", "local:1")
            assertThat(page.column("in_progress").locator(".task-card")).hasCount(3)
            assertEquals(listOf("local:5", "local:6", "local:1"), page.refsIn("in_progress"))
            assertEquals(listOf("local:2", "local:3", "local:4", "local:10"), page.refsIn("todo"))
            assertThat(page.column("todo").locator(".board-column-head span")).hasText("4")
        }

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

            for (other in page.context().pages()) {
                if (other != page) other.close()
            }
            page.bringToFront()
            title.click()
            page.waitForURL("**/tasks/local%3A1")
            assertThat(title).hasAttribute("aria-current", "true")
        }


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

    @Test
    fun noCardCarriesActionControlsBecauseTaskActionsLiveInTheDetailPane() =
        // The card's markup no longer varies by viewport, so the desktop board stands for the phone
        // too — and it renders every seeded card, including the blocked and session-linked shapes.
        onTheBoard("noCardCarriesActionControlsBecauseTaskActionsLiveInTheDetailPane") { _, page ->
            val cards = page.locator(".task-card")
            assertThat(cards).hasCount(SEEDED_CARDS)
            // Count hidden matches as well: the menu this replaces kept its buttons, and its own
            // group role, inside a closed <details> that an accessibility-tree query would miss.
            val alsoHidden = Locator.GetByRoleOptions().setIncludeHidden(true)
            assertThat(cards.getByRole(AriaRole.BUTTON, alsoHidden)).hasCount(0)
            assertThat(cards.getByRole(AriaRole.GROUP, alsoHidden)).hasCount(0)
        }

    @Test
    fun theGestureReservationIsScopedToTheCardHandleAlone() =
        onTheBoard("theGestureReservationIsScopedToTheCardHandleAlone", phone = true) { _, page ->
            assertEquals("none", page.handleOf("local:1").touchAction(), "the handle reserves the gesture")
            assertEquals("auto", page.card("local:1").touchAction(), "the card leaves the column scrollable")
            assertEquals("auto", page.column("todo").touchAction(), "and so does the column itself")
        }


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
            assertThat(page.locator("#task-detail-project")).isVisible()
            assertThat(page.locator("#task-detail-loading")).hasCount(0)

            assertEquals(moved, page.slotOf("local:3"), "the stale response lost to the newer frame")
        }


    private fun Page.card(ref: String): Locator = locator(".task-card[data-ref=\"$ref\"]")

    private fun Page.column(state: String): Locator = locator(".board-column[data-state=\"$state\"]")

    private fun Page.handleOf(ref: String): Locator = card(ref).locator(".task-card-handle")

    private fun Page.refsIn(state: String): List<String> {
        val refs = column(state).locator(".task-card")
            .evaluateAll("cards => cards.map((card) => card.getAttribute('data-ref'))")
        return (refs as List<*>).map { it as String }
    }

    private fun Page.slotOf(ref: String): String = evaluate(CARD_SLOT_JS, ref) as String

    private fun Locator.touchAction(): String = evaluate("el => getComputedStyle(el).touchAction") as String


    private fun Locator.centre(): Pair<Double, Double> =
        boundingBox().let { it.x + it.width / 2 to it.y + it.height / 2 }

    private fun Locator.bottomInside(): Pair<Double, Double> =
        boundingBox().let { it.x + it.width / 2 to it.y + it.height - 16 }

    private fun Page.pressHandleOf(ref: String) {
        val start = handleOf(ref).centre()
        mouse().move(start.first, start.second)
        mouse().down()
        mouse().move(start.first, start.second + 20, Mouse.MoveOptions().setSteps(4))
    }

    private fun Page.travelTo(point: Pair<Double, Double>) =
        mouse().move(point.first, point.second, Mouse.MoveOptions().setSteps(8))

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

    private fun MutableList<String>.snapshot(): List<String> = synchronized(this) { toList() }

    private fun Page.proveTheWriteRecorderWasLive(writes: MutableList<String>, ref: String, into: String) {
        dragToBottomOf(ref, into)
        waitForCondition { writes.snapshot().size >= 2 }
        val encoded = ref.replace(":", "%3A")
        assertEquals(
            listOf("PATCH /tasks/$encoded", "POST /tasks/$encoded/move"),
            writes.snapshot(),
            "the recorder's whole log must be this drop's two calls — which says both that it CAN see a " +
                "task write and that the gestures above added none",
        )
    }

    private fun Page.watchDragClaims() = evaluate(DRAG_CLAIM_WATCHER_JS)

    private fun Page.dragClaims(): Int = (evaluate("() => window.__kotgentDragClaims") as Number).toInt()

    private fun Page.watchPointerIds() = evaluate(POINTER_ID_WATCHER_JS)

    private fun Page.lastPointerDownId(): Int =
        (evaluate("() => window.__kotgentPointerId") as Number).toInt()


    private fun onTheBoard(
        name: String,
        scenario: String = BOARD_SCENARIO,
        phone: Boolean = false,
        // False lets a caller install interception before the SPA's first request.
        open: Boolean = true,
        block: (Harness, Page) -> Unit,
    ) {
        Harness(scenario).use { harness ->
            onChromium { browser ->
                val context = if (phone) {
                    browser.touchContext()
                } else {
                    browser.newContext(Browser.NewContextOptions().setViewportSize(DESKTOP_WIDTH, DESKTOP_HEIGHT))
                }
                context.use {
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
                }
            }
        }
    }

    private companion object {
        private const val BOARD_PROJECT = "Board Fixture"
        private const val EMPTY_PROJECT = "Empty Fixture"
        private const val API_PREFIX = "/api/v1"

        private const val DESKTOP_WIDTH = 1400
        private const val DESKTOP_HEIGHT = 900

        private const val PHONE_WIDTH = 390

        private const val SEEDED_CARDS = 10
        private const val SEEDED_TODO_CARDS = 5

        private const val INSERTED_REF = "local:11"
        private const val INSERTED_POSITION = "2.5"

        private val COLUMN_STATES = listOf("todo", "in_progress", "review", "done")

        private const val SAW_TASKS_SNAPSHOT_JS = """
          () => (window.__kotgentFrames || []).some((f) => f.indexOf("\"type\":\"tasks_snapshot\"") >= 0)
        """

        private const val COLUMN_CLASS = "board-column"
        private const val DROP_TARGET_CLASS = "board-column board-drop-target"

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
