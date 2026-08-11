package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
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


    private fun onScenario(scenario: String, trace: String, block: (Harness, Page) -> Unit) {
        Harness(scenario).use { harness ->
            onChromium { browser ->
                browser.touchContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(trace) { block(harness, context.newPage()) }
                }
            }
        }
    }

    private fun Page.openPalette(): Locator {
        // Wait for the previous dialog node to unmount before toggling its state again.
        assertThat(locator("#command-palette")).hasCount(0)
        keyboard().press(PALETTE_OPENER)
        val shell = locator(".command-palette-shell.leader")
        assertThat(shell).isVisible()
        assertThat(shell).isFocused()
        return shell
    }

    private fun Page.closePalette() {
        val query = searchQuery()
        // Chromium consumes the first Escape in a non-empty search input to clear the field.
        if (query.count() > 0) query.fill("")
        keyboard().press("Escape")
        assertThat(locator("#command-palette")).hasCount(0)
    }

    private fun Page.pressMnemonic(code: String) {
        keyboard().press(code)
    }

    private fun Page.searchMode(): Locator {
        pressMnemonic("KeyK")
        val query = searchQuery()
        assertThat(query).isVisible()
        assertThat(query).isFocused()
        return query
    }

    private fun Page.searchQuery(): Locator = locator("#command-palette-query")

    private fun Page.searchFor(query: String): Locator {
        val field = searchMode()
        field.fill(query)
        return field
    }

    private fun Page.runFirstMatch(query: String, expected: String) {
        val field = searchFor(query)
        val options = paletteOptions()
        assertThat(options).hasCount(1)
        assertThat(options.first()).containsText(expected)
        assertThat(options.first()).hasClass(ACTIVE_OPTION)
        field.press("Enter")
    }

    private fun Page.paletteOptions(): Locator = locator(".command-palette-option")

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

    private fun Page.awaitSessionView() {
        assertThat(locator("#terminal-pane")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
        assertThat(locator("main.board")).hasCount(0)
    }

    private fun Page.awaitBoard() {
        assertThat(locator("main.board")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
        assertThat(locator("#terminal-pane")).hasCount(0)
    }

    private fun Page.awaitSelectedSession() {
        assertThat(locator("#terminal-title")).not().hasText(
            "No session selected",
            LocatorAssertions.HasTextOptions().setTimeout(BOOT_TIMEOUT_MS),
        )
    }

    private fun visibleWithin(millis: Double): LocatorAssertions.IsVisibleOptions =
        LocatorAssertions.IsVisibleOptions().setTimeout(millis)

    private companion object {
        const val SHOW_DONE_QUERY = "hide done"

        val ACTIVE_OPTION: Pattern = Pattern.compile("\\bactive\\b")

        const val BOOT_TIMEOUT_MS = 15_000.0
    }
}
