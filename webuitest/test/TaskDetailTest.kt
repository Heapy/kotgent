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

class TaskDetailTest {

    private val focus = "local:3"
    private val focusRoute = "/tasks/local%3A3"
    private val detailApi = "/api/v1/tasks/local%3A3"

    private val firstPaintMs = 15_000.0

    private val injectedName = "the-session-that-holds-that-id-now"

    @Test
    fun theActivityFeedArrivesWithTheTaskAndRendersEveryRowIncludingOneNoSessionAnswersFor() {
        onTheTaskDetail("feed-loads") { harness, page ->
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

            assertThat(page.panel(".task-sessions a[href=\"/s/s-detail-1\"]")).hasCount(1)
            assertThat(page.panel(".task-sessions a[href=\"/s/s-detail-1\"]")).hasText(injectedName)

            val orphan = page.panel(".task-activity-row", Page.LocatorOptions().setHasText("s-detail-1"))
            assertThat(orphan).hasCount(1)
            assertThat(orphan).hasAttribute("data-kind", "comment")
            assertThat(orphan.locator("strong")).hasText("s-detail-1")
            assertThat(orphan).not().containsText(injectedName)
        }
    }

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
            assertThat(page.panel(".task-activity-row")).hasCount(rowsBefore + 2)
            assertThat(page.panel(".task-activity-row[data-kind=\"transition\"]")).hasCount(2)
        }
    }

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

    @Test
    fun theDependencyEditorNamesBothDirectionsAndBlockedFollowsTheEdges() {
        onTheTaskDetail("dependencies") { harness, page ->
            page.openFocusTask(harness.baseUrl)

            assertThat(page.panel(".task-dep-count")).hasText("2")
            assertThat(page.panel(".task-deps li")).hasCount(2)
            assertThat(page.panel(".task-deps li a[href=\"/tasks/local%3A1\"]")).hasCount(1)
            assertThat(page.panel(".task-deps li a[href=\"/tasks/local%3A2\"]")).hasCount(1)
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

    @Test
    fun aTaskDeletedElsewhereSaysSoInsteadOfOfferingEditors() {
        onTheTaskDetail("deleted-elsewhere") { harness, page ->
            page.openFocusTask(harness.baseUrl)
            page.awaitLiveTaskRows()

            harness.send("task-del $focus")

            assertThat(page.locator("#task-detail-gone")).isVisible()
            assertThat(page.locator("#task-detail-form")).hasCount(0)
            assertThat(page.panel(".task-deps")).hasCount(0)
            assertThat(page.locator("#task-detail-close")).isVisible()
        }
    }

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

    @Test
    fun thePanelStacksOneColumnAndGivesEveryBlockTheSameGutter() {
        onTheTaskDetail("panel-layout") { harness, page ->
            page.openFocusTask(harness.baseUrl)

            val panel = page.locator(".task-detail").box()
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

    private fun Page.openFocusTask(baseUrl: String) {
        navigate(baseUrl + focusRoute)
        assertThat(locator("#task-detail-form"))
            .isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(firstPaintMs))
    }

    private fun Page.awaitLiveTaskRows() {
        locator(".task-card").first()
            .waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
    }

    private fun Page.panelContentWidth(): Double =
        (locator(".task-detail").evaluate("el => el.clientWidth") as Number).toDouble()

    private fun Page.panel(selector: String): Locator = locator(".task-detail $selector")

    private fun Page.panel(selector: String, options: Page.LocatorOptions): Locator =
        locator(".task-detail $selector", options)

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

    private fun recordRequests(page: Page): List<String> {
        val seen = CopyOnWriteArrayList<String>()
        page.onRequest { request -> seen.add(request.method() + " " + request.url()) }
        return seen
    }

    private fun String.isDetailRequest(method: String): Boolean =
        startsWith("$method ") && (endsWith(detailApi) || endsWith("/api/v1/tasks/$focus"))

    private fun Locator.box(): BoundingBox =
        boundingBox() ?: error("no box for a locator that must be laid out: $this")

    private fun BoundingBox.right(): Double = x + width

    private fun BoundingBox.bottom(): Double = y + height

    private fun BoundingBox.overlaps(other: BoundingBox): Boolean =
        x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom()
}
