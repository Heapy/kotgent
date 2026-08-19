package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskCommandsTest {

    private val sessionCommandTitles = listOf(
        "Interrupt current session",
        "Resume this session",
        "Attach current terminal",
        "Detach current terminal",
        "Stop current session",
        "Done current session",
        "Copy tmux command",
        "Upload files to current folder",
        "Open this session's task",
    )

    @Test
    fun theBoardIsReachableFromThePaletteAndTheSameLetterLeadsBackOut() =
        onScenario(BOARD_SCENARIO, "board-round-trip") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()
            page.evaluate("() => { window.__kotgentPaletteWitness = 1; }")

            page.openPalette()
            assertThat(page.leaderRow("Open the task board")).hasCount(1)
            assertThat(page.leaderRow("Back to sessions")).hasCount(0)
            page.pressMnemonic("KeyO")

            assertThat(page.locator("#command-palette")).hasCount(0)
            assertThat(page).hasURL(Pattern.compile("/tasks$"))
            page.awaitBoard()
            assertThat(page.taskCard("local:1")).isVisible()

            page.openPalette()
            assertThat(page.leaderRow("Back to sessions")).hasCount(1)
            assertThat(page.leaderRow("Open the task board")).hasCount(0)
            page.pressMnemonic("KeyO")

            assertThat(page).hasURL(Pattern.compile("/$"))
            page.awaitSessionView()

            page.goBack()
            page.awaitBoard()
            assertTrue(
                page.evaluate("() => window.__kotgentPaletteWitness === 1") == true,
                "the whole trip was client-side routing: a reload anywhere in it would have cleared the " +
                    "witness, taken the events socket and the terminal down with it, and still passed " +
                    "every URL assertion above",
            )
        }

    @Test
    fun theSessionGroupAndTheShowDoneToggleAreBuiltOnlyForTheScreenThatShowsASession() =
        onScenario(TASK_LINKED_SESSION_SCENARIO, "session-group-per-screen") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            for (title in sessionCommandTitles) {
                assertThat(page.leaderRow(title)).hasCount(1)
            }
            page.searchFor(SHOW_DONE_QUERY)
            assertThat(page.paletteOptions()).hasCount(1)
            assertThat(page.paletteOptions().first()).containsText("Show or hide done sessions")
            page.closePalette()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()

            page.openPalette()
            for (title in sessionCommandTitles) {
                assertThat(page.leaderRow(title)).hasCount(0)
            }
            assertThat(page.leaderRow("Back to sessions")).hasCount(1)
            assertThat(page.leaderRow("New task")).hasCount(1)
            val field = page.searchFor("interrupt")
            assertThat(page.paletteOptions()).hasCount(0)
            field.fill("new task")
            assertThat(page.paletteOptions()).hasCount(1)
            field.fill(SHOW_DONE_QUERY)
            assertThat(page.paletteOptions()).hasCount(0)
        }

    @Test
    fun theSessionRowsSurviveOnTheBoardBecauseTheyAreNavigation() =
        onScenario(TASK_LINKED_SESSION_SCENARIO, "session-rows-on-the-board") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()

            page.openPalette()
            val query = page.searchMode()
            val first = page.paletteOptions().first()
            assertThat(first).hasClass(ACTIVE_OPTION)
            query.press("Enter")

            assertThat(page).hasURL(Pattern.compile("/s/s-linked-[123]$"))
            page.awaitSessionView()
        }

    @Test
    fun newTaskOpensTheBoardsCreateFormEveryTimeItIsAskedAndNeverUnasked() =
        onScenario(BOARD_SCENARIO, "new-task-command") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()

            page.openPalette()
            page.pressMnemonic("KeyW")
            assertThat(page).hasURL(Pattern.compile("/tasks$"))
            assertThat(page.locator("#new-task-dialog")).isVisible()
            assertThat(page.locator("#new-task-title-input")).isVisible()

            page.keyboard().press("Escape")
            assertThat(page.locator("#new-task-dialog")).hasCount(0)
            page.awaitBoard()

            page.openPalette()
            page.pressMnemonic("KeyW")
            assertThat(page.locator("#new-task-dialog")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.locator("#new-task-dialog")).hasCount(0)

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitSessionView()
            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()
            assertThat(page.taskCard("local:1")).isVisible()
            assertThat(page.locator("#new-task-dialog")).hasCount(0)

            page.openPalette()
            page.pressMnemonic("KeyW")
            assertThat(page.locator("#new-task-dialog")).isVisible()
        }

    @Test
    fun aDismissedNewTaskFormReportsItsLateFailureInTheBoardStatus() {
        val held = AtomicReference<Route?>(null)
        onScenario(
            BOARD_SCENARIO,
            "new-task-dismissed-late-failure",
            beforeLoad = { _, context ->
                context.route("**$TASKS_API") { route ->
                    if (route.request().method() == "POST" && held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            page.awaitBoard()
            page.locator(".board-new-task").click()
            page.locator("#new-task-title-input").fill("Late failure")
            page.locator("#new-task-form button[type=submit]").click()
            page.waitForCondition { held.get() != null }
            val dismissalLabel = page.locator(
                "#new-task-form .dialog-actions button[type=button]",
            ).textContent().trim()

            page.keyboard().press("Escape")
            assertThat(page.locator("#new-task-dialog")).hasCount(0)
            held.get()!!.fulfill(
                Route.FulfillOptions()
                    .setStatus(500)
                    .setContentType("text/plain")
                    .setBody(LATE_FAILURE),
            )

            assertThat(page.locator("#board-status")).containsText(LATE_FAILURE)
            assertEquals(
                "Close",
                dismissalLabel,
                "a busy task form dismisses without cancelling its still-running request",
            )
        }
    }

    @Test
    fun aLateTaskSuccessLeavesItsReplacementFormOpenAndAnnouncesTheCreatedTask() {
        val held = AtomicReference<Route?>(null)
        onScenario(
            BOARD_SCENARIO,
            "new-task-replacement-survives-late-success",
            beforeLoad = { _, context ->
                context.route("**$TASKS_API") { route ->
                    if (route.request().method() == "POST" && held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            page.awaitBoard()
            page.locator(".board-new-task").click()
            page.locator("#new-task-title-input").fill("First task")
            page.locator("#new-task-form button[type=submit]").click()
            page.waitForCondition { held.get() != null }

            page.keyboard().press("Escape")
            assertThat(page.locator("#new-task-dialog")).hasCount(0)
            page.locator(".board-new-task").click()
            page.locator("#new-task-title-input").fill("Replacement draft")
            held.get()!!.resume()

            assertThat(page.locator("#board-status")).containsText("Created local:11.")
            assertThat(page.locator("#new-task-dialog")).isVisible()
            assertThat(page.locator("#new-task-title-input")).hasValue("Replacement draft")
        }
    }

    @Test
    fun theBoardsNewProjectFormIsReachableFromTheSearchListWithoutAMnemonic() =
        onScenario(BOARD_SCENARIO, "new-project-command") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()

            page.openPalette()
            assertThat(page.leaderRow("New project")).hasCount(0)
            assertThat(page.leaderRow("New task")).hasCount(1)

            page.runFirstMatch("new project", "New project")

            assertThat(page).hasURL(Pattern.compile("/tasks$"))
            assertThat(page.locator("#new-project-dialog")).isVisible()
            assertThat(page.locator("#new-project-path")).isVisible()
            assertThat(page.locator("#new-task-dialog")).hasCount(0)
        }

    @Test
    fun openingThisSessionsTaskIsRefusedAloudForASessionThatCarriesNoTask() =
        onScenario(TASK_LINKED_SESSION_SCENARIO, "open-session-task") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-2")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            val refused = page.leaderRow("Open this session's task")
            assertThat(refused).hasCount(1)
            assertThat(refused).hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyJ")
            assertThat(page.locator(".command-palette-footer")).containsText("not linked to a task")
            assertThat(page).hasURL(Pattern.compile("/s/s-linked-2$"))
            page.closePalette()

            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            val offered = page.leaderRow("Open this session's task")
            assertThat(offered).hasCount(1)
            assertThat(offered).not().hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyJ")
            assertThat(page).hasURL(Pattern.compile("/tasks/local%3A1$"))
            assertThat(page.locator("#task-detail-title")).hasText("local:1")
            assertThat(page.locator("#task-detail-form")).isVisible()
        }

    @Test
    fun everyMnemonicTheGridDrawsIsUniqueAndKIsLeftToTheWayBackToSearch() =
        onScenario(TASK_LINKED_SESSION_SCENARIO, "leader-mnemonics") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            page.assertMnemonicsAreDistinct("the session view")
            page.pressMnemonic("KeyK")
            assertThat(page.searchQuery()).isVisible()
            page.closePalette()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()

            page.openPalette()
            page.assertMnemonicsAreDistinct("the board")
            page.pressMnemonic("KeyK")
            assertThat(page.searchQuery()).isVisible()
        }


    private fun onScenario(
        scenario: String,
        trace: String,
        beforeLoad: (Harness, BrowserContext) -> Unit = { _, _ -> },
        block: (Harness, Page) -> Unit,
    ) {
        Harness(scenario).use { harness ->
            onChromium { browser ->
                browser.touchContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    beforeLoad(harness, context)
                    context.traced(trace) { block(harness, context.newPage()) }
                }
            }
        }
    }

    private fun Page.leaderRow(title: String): Locator =
        locator(".command-palette-leader-command").filter(Locator.FilterOptions().setHasText(title))

    private fun Page.taskCard(ref: String): Locator = locator(".task-card[data-ref=\"$ref\"]")

    private fun Page.assertMnemonicsAreDistinct(screen: String) {
        assertThat(locator(".command-palette-leader-grid")).isVisible()
        val rowLocators = locator(".command-palette-leader-command").all()
        assertTrue(rowLocators.isNotEmpty(), "$screen offers leader mnemonics at all")
        val keys = rowLocators.map { row ->
            val drawn = row.locator(".command-palette-leader-key").allTextContents()
            assertEquals(
                1,
                drawn.size,
                "a row of $screen's grid draws ${drawn.size} keys ($drawn) instead of exactly one",
            )
            drawn.first().trim()
        }
        for (key in keys) {
            assertTrue(
                key.length == 1 && (key[0] in 'a'..'z' || key[0] in 'A'..'Z'),
                "'$key' on $screen is one ASCII letter, or `\"Key\" + chord.toUpperCase()` names no " +
                    "physical code and the row it draws can never be pressed",
            )
            assertTrue(
                !key.equals("k", ignoreCase = true),
                "$screen leaves 'k' to the grid's own way back to search",
            )
        }
        val distinct = keys.map { it.lowercase() }.toSet()
        assertEquals(
            keys.size,
            distinct.size,
            "no letter is claimed twice on $screen — the second claimant would be a visible grid row its " +
                "own key can never reach",
        )
    }

    private fun Page.awaitSelectedSession() {
        assertThat(locator("#terminal-title")).not().hasText(
            "No session selected",
            LocatorAssertions.HasTextOptions().setTimeout(BOOT_TIMEOUT_MS),
        )
    }

    private companion object {
        const val TASKS_API = "/api/v1/tasks"
        const val LATE_FAILURE = "Task creation failed after dismissal."
        const val SHOW_DONE_QUERY = "hide done"
    }
}
