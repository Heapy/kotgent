package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.regex.Pattern
import kotlin.test.Test

/**
 * Deleting a project is a tombstone the operator drives from the palette, so what a browser can prove
 * about it is which screen offers the two commands, what the sidebar and the selection do when one
 * runs, and that the restore list is the daemon's answer rather than something the page remembered.
 */
class ProjectArchiveTest {

    @Test
    fun bothProjectCommandsAreBoardOnlyAndDeleteSaysWhyItCannotRunWithNothingSelected() =
        onProjects(EMPTY_SCENARIO, "project-commands-per-screen") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()

            page.openPalette()
            page.searchFor(PROJECT_QUERY)
            assertThat(page.option(NEW_PROJECT)).hasCount(1)
            assertThat(page.option(DELETE_PROJECT)).hasCount(0)
            assertThat(page.option(RESTORE_PROJECT)).hasCount(0)
            page.closePalette()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()
            assertThat(page.locator(".board-project")).hasText("No project")

            page.openPalette()
            page.searchFor(PROJECT_QUERY)
            assertThat(page.option(NEW_PROJECT)).hasCount(1)

            val delete = page.option(DELETE_PROJECT)
            assertThat(delete).hasCount(1)
            assertThat(delete).hasAttribute("aria-disabled", "true")
            assertThat(delete.locator(".command-palette-disabled-reason"))
                .hasText("no project is selected")

            // Never disabled: only the daemon knows whether anything was ever deleted.
            val restore = page.option(RESTORE_PROJECT)
            assertThat(restore).hasCount(1)
            assertThat(restore).not().hasAttribute("aria-disabled", "true")
        }

    @Test
    fun deletingTheSelectedProjectTakesItOutOfTheSidebarAndMovesTheSelectionOn() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-delete") { harness, page ->
            page.openBoard(harness, "Alpha Fixture")
            assertThat(page.projectRows()).hasCount(2)
            assertThat(page.projectRow(SELECTED_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
            assertThat(page.locator(".task-card")).hasCount(SEEDED_CARDS)

            page.openPalette()
            page.runFirstMatch(DELETE_QUERY, DELETE_PROJECT)

            val dialog = page.locator("#delete-project-dialog")
            assertThat(dialog).isVisible()
            assertThat(page.locator("#delete-project-name")).hasText("Alpha Fixture")
            assertThat(page.locator("#delete-project-path")).hasText("/repo/alpha")
            // The count is read off the live task list, so it is the board's own three cards.
            assertThat(page.locator("#delete-project-facts")).containsText("Its 3 tasks are kept")

            page.locator("#delete-project-submit").click()

            assertThat(dialog).hasCount(0)
            assertThat(page.projectRow(SELECTED_PROJECT)).hasCount(0)
            assertThat(page.projectRows()).hasCount(1)
            assertThat(page.projectRow(SPARE_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
            assertThat(page.locator(".board-project")).hasText("Beta Fixture")
            assertThat(page.locator(".board-project-path")).hasText("/repo/beta")
            assertThat(page.locator("#board-status")).containsText("Deleted Alpha Fixture")
            assertThat(page.locator(".task-card")).hasCount(0)
        }

    @Test
    fun restoreBringsTheChosenProjectBackSelectedAndAsksTheDaemonOnEveryOpening() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-restore") { harness, page ->
            page.openBoard(harness, "Alpha Fixture")
            assertThat(page.projectRows()).hasCount(2)
            assertThat(page.projectRow(DELETED_PROJECT)).hasCount(0)

            page.openPalette()
            page.runFirstMatch(RESTORE_QUERY, RESTORE_PROJECT)

            val offered = page.restoreRows()
            assertThat(offered).hasCount(1)
            assertThat(offered.first()).hasAttribute("data-id", DELETED_PROJECT)
            assertThat(offered.first()).containsText("Gamma Fixture")
            assertThat(offered.first()).containsText("/repo/gamma")

            offered.first().click()

            assertThat(page.locator("#restore-project-dialog")).hasCount(0)
            assertThat(page.projectRows()).hasCount(3)
            assertThat(page.projectRow(DELETED_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
            assertThat(page.locator(".board-project")).hasText("Gamma Fixture")
            assertThat(page.locator("#board-status")).containsText("Restored Gamma Fixture")

            // A second opening must read `?archived=true` again: the daemon has archived another
            // project since, and a dialog replaying its first answer would offer the wrong row.
            harness.send("project-del $SELECTED_PROJECT")
            page.openPalette()
            page.runFirstMatch(RESTORE_QUERY, RESTORE_PROJECT)

            val reread = page.restoreRows()
            assertThat(reread).hasCount(1)
            assertThat(reread.first()).hasAttribute("data-id", SELECTED_PROJECT)
            assertThat(reread.first()).containsText("Alpha Fixture")
        }


    private fun onProjects(scenario: String, trace: String, block: (Harness, Page) -> Unit) {
        Harness(scenario).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(trace) { block(harness, context.newPage()) }
                }
            }
        }
    }

    private fun Page.openBoard(harness: Harness, project: String) {
        navigate(harness.baseUrl + "/tasks")
        awaitBoard()
        assertThat(locator(".board-project")).hasText(project)
    }

    private fun Page.projectRows(): Locator = locator("#project-list .project-row")

    private fun Page.projectRow(id: String): Locator = locator("#project-list .project-row[data-id=\"$id\"]")

    private fun Page.restoreRows(): Locator = locator("#restore-project-list .dialog-list-row")

    private fun Page.option(title: String): Locator =
        locator(".command-palette-option").filter(Locator.FilterOptions().setHasText(title))


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

    private fun Page.searchQuery(): Locator = locator("#command-palette-query")

    private fun Page.searchFor(query: String): Locator {
        pressMnemonic("KeyK")
        val field = searchQuery()
        assertThat(field).isVisible()
        assertThat(field).isFocused()
        field.fill(query)
        return field
    }

    private fun Page.runFirstMatch(query: String, expected: String) {
        val field = searchFor(query)
        val options = locator(".command-palette-option")
        assertThat(options).hasCount(1)
        assertThat(options.first()).containsText(expected)
        assertThat(options.first()).hasClass(ACTIVE_OPTION)
        field.press("Enter")
    }

    private fun Page.awaitSessionView() {
        assertThat(locator("#terminal-pane")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
        assertThat(locator("main.board")).hasCount(0)
    }

    private fun Page.awaitBoard() {
        assertThat(locator("main.board")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
        assertThat(locator("#terminal-pane")).hasCount(0)
    }

    private fun visibleWithin(millis: Double): LocatorAssertions.IsVisibleOptions =
        LocatorAssertions.IsVisibleOptions().setTimeout(millis)

    private companion object {
        // The scenario's own uuids; a sidebar row and a restore row are addressed by them.
        const val SELECTED_PROJECT = "33333333-3333-4333-8333-333333333333"
        const val SPARE_PROJECT = "44444444-4444-4444-8444-444444444444"
        const val DELETED_PROJECT = "55555555-5555-4555-8555-555555555555"

        const val SEEDED_CARDS = 3

        const val NEW_PROJECT = "New project"
        const val DELETE_PROJECT = "Delete project"
        const val RESTORE_PROJECT = "Restore a deleted project"

        const val PROJECT_QUERY = "project"
        const val DELETE_QUERY = "delete project"
        const val RESTORE_QUERY = "restore a deleted"

        const val ACTIVE_PROJECT_ROW = "project-row active"

        val ACTIVE_OPTION: Pattern = Pattern.compile("\\bactive\\b")

        const val BOOT_TIMEOUT_MS = 15_000.0
    }
}
