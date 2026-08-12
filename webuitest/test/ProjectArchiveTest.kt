package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitUntilState
import kotlin.test.Test

/**
 * Deleting a project is a tombstone the operator drives from the palette, so what a browser can prove
 * about it is which screen offers the two commands, what the sidebar and the selection do when one
 * runs, that the restore list is the daemon's answer rather than something the page remembered, and
 * that a restored project's backlog is on screen without a reload.
 */
class ProjectArchiveTest {

    @Test
    fun bothProjectCommandsAreBoardOnlyAndDeleteSaysWhyItCannotRunWithNothingSelected() =
        onProjects(EMPTY_SCENARIO, "project-commands-per-screen") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()

            page.openPalette()
            page.searchFor(PROJECT_QUERY)
            assertThat(page.paletteOption(NEW_PROJECT)).hasCount(1)
            assertThat(page.paletteOption(DELETE_PROJECT)).hasCount(0)
            assertThat(page.paletteOption(RESTORE_PROJECT)).hasCount(0)
            page.closePalette()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()
            assertThat(page.locator(".board-project")).hasText("No project")

            page.openPalette()
            page.searchFor(PROJECT_QUERY)
            assertThat(page.paletteOption(NEW_PROJECT)).hasCount(1)

            val delete = page.paletteOption(DELETE_PROJECT)
            assertThat(delete).hasCount(1)
            assertThat(delete).hasAttribute("aria-disabled", "true")
            assertThat(delete.locator(".command-palette-disabled-reason"))
                .hasText("no project is selected")

            // Never disabled: only the daemon knows whether anything was ever deleted.
            val restore = page.paletteOption(RESTORE_PROJECT)
            assertThat(restore).hasCount(1)
            assertThat(restore).not().hasAttribute("aria-disabled", "true")
        }

    @Test
    fun deletingTheSelectedProjectTakesItOutOfTheSidebarAndMovesTheSelectionOn() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-delete") { harness, page ->
            page.openBoard(harness, "Alpha Fixture")
            assertThat(page.projectRows()).hasCount(2)
            assertThat(page.projectRow(SELECTED_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
            assertThat(page.locator(".task-card")).hasCount(SELECTED_CARDS)

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
            assertThat(page.locator(".task-card")).hasCount(SPARE_CARDS)

            // A one-task project has its own grammar, and the sentence is read before a destructive act.
            page.openPalette()
            page.runFirstMatch(DELETE_QUERY, DELETE_PROJECT)
            assertThat(page.locator("#delete-project-facts")).containsText("Its 1 task is kept")
            page.locator("#delete-project-cancel").click()
            assertThat(page.locator("#delete-project-dialog")).hasCount(0)
            assertThat(page.projectRows()).hasCount(1)
        }

    @Test
    fun deletingTheOnlyProjectEmptiesTheSidebarAndDisablesTheCommandThatNeedsOne() =
        onProjects(BOARD_EMPTY_SCENARIO, "project-delete-last") { harness, page ->
            page.openBoard(harness, "Empty Fixture")
            assertThat(page.projectRows()).hasCount(1)

            page.openPalette()
            page.runFirstMatch(DELETE_QUERY, DELETE_PROJECT)
            assertThat(page.locator("#delete-project-facts"))
                .containsText("It has no tasks, so nothing in the backlog changes.")

            page.locator("#delete-project-submit").click()

            assertThat(page.locator("#delete-project-dialog")).hasCount(0)
            assertThat(page.projectRows()).hasCount(0)
            assertThat(page.locator(".board-project")).hasText("No project")

            // The selection is null now, and the command that reads one says so rather than doing nothing.
            page.openPalette()
            page.searchFor(DELETE_QUERY)
            val delete = page.paletteOption(DELETE_PROJECT)
            assertThat(delete).hasAttribute("aria-disabled", "true")
            assertThat(delete.locator(".command-palette-disabled-reason")).hasText("no project is selected")
        }

    @Test
    fun restoreBringsTheChosenProjectBackWithItsBacklogAndAsksTheDaemonOnEveryOpening() =
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
            // "The backlog comes back with it" is what the dialog promises, and no reload happened: the
            // opening tasks_snapshot has to have carried a deleted project's rows for these to be here.
            assertThat(page.locator(".task-card")).hasCount(DELETED_CARDS)

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

    /**
     * The baseline ships a deleted project's cards so this link keeps working — and that is exactly what
     * lets a card name a project the sidebar does not list. The board must not adopt it: selecting a
     * tombstoned uuid paints its backlog under a header that can only say "No project", with New task
     * enabled into a guaranteed 404 and every card draggable on a board that denies showing them.
     *
     * The assertions are made after leaving the card and coming back through history, because only then
     * is the route change the ONLY thing that can move the selection. On the cold load the two
     * `/projects` reads race the task baseline, and one landing last walks a wrong selection back by
     * accident — which would make this pass against the defect.
     */
    @Test
    fun aDeepLinkToADeletedProjectsCardOpensItWithoutPaintingABacklogTheHeaderCannotName() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-deleted-deep-link") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks/" + DELETED_CARD)
            page.awaitBoard()
            // The read stays open: the card is on screen and names the project it belongs to.
            assertThat(page.locator("#task-detail-title")).hasText(DELETED_CARD)

            page.locator("#task-detail-close").click()
            assertThat(page.locator("section.task-detail")).hasCount(0)
            assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
            page.goBack(Page.GoBackOptions().setWaitUntil(WaitUntilState.COMMIT))

            assertThat(page.locator("#task-detail-title")).hasText(DELETED_CARD)
            assertThat(page.locator("#task-detail-project")).containsText("Gamma Fixture")
            // The board behind it names the project whose cards it paints, and they are that project's.
            assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
            assertThat(page.locator(".task-card")).hasCount(SELECTED_CARDS)
            assertThat(page.projectRow(SELECTED_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
            assertThat(page.locator(".board-new-task")).isEnabled()
        }

    /** The other half of that rule: a LIVE project is still adopted, or the deep link selects nothing. */
    @Test
    fun aDeepLinkToALiveProjectsCardStillSelectsThatProject() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-live-deep-link") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks/" + SPARE_CARD)
            page.awaitBoard()

            assertThat(page.locator("#task-detail-title")).hasText(SPARE_CARD)
            assertThat(page.locator(".board-project")).hasText("Beta Fixture")
            assertThat(page.locator(".task-card")).hasCount(SPARE_CARDS)
            assertThat(page.projectRow(SPARE_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
        }

    @Test
    fun theRestoreDialogSaysSoWhenNothingWasEverDeleted() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-restore-empty") { harness, page ->
            page.openBoard(harness, "Alpha Fixture")
            // The command is never disabled, so the empty answer is a state the operator meets first.
            harness.send("project-restore $DELETED_PROJECT")

            page.openPalette()
            page.runFirstMatch(RESTORE_QUERY, RESTORE_PROJECT)

            assertThat(page.locator("#restore-project-empty")).isVisible()
            assertThat(page.locator("#restore-project-empty")).containsText("No deleted projects")
            assertThat(page.restoreRows()).hasCount(0)

            page.locator("#restore-project-cancel").click()
            assertThat(page.locator("#restore-project-dialog")).hasCount(0)
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

    private companion object {
        // Duplicated from webuicheck's BOARD_PROJECTS_*_ID, as COMMAND_ACK_PREFIX is: constants cannot
        // cross the native/JVM boundary. A sidebar row and a restore row are addressed by them.
        const val SELECTED_PROJECT = "33333333-3333-4333-8333-333333333333"
        const val SPARE_PROJECT = "44444444-4444-4444-8444-444444444444"
        const val DELETED_PROJECT = "55555555-5555-4555-8555-555555555555"

        // A card of the scenario's deleted project — the deep link the tombstone deliberately leaves
        // open — and one of the live project that is not the board's default selection.
        const val DELETED_CARD = "local:5"
        const val SPARE_CARD = "local:4"

        const val SELECTED_CARDS = 3
        const val SPARE_CARDS = 1
        const val DELETED_CARDS = 2

        const val NEW_PROJECT = "New project"
        const val DELETE_PROJECT = "Delete project"
        const val RESTORE_PROJECT = "Restore a deleted project"

        const val PROJECT_QUERY = "project"
        const val DELETE_QUERY = "delete project"
        const val RESTORE_QUERY = "restore a deleted"

        const val ACTIVE_PROJECT_ROW = "project-row active"
    }
}
