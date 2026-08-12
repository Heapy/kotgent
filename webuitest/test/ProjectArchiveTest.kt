package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitUntilState
import kotlin.test.Test

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
            assertThat(page.locator(".task-card")).hasCount(DELETED_CARDS)

            harness.send("project-del $SELECTED_PROJECT")
            page.openPalette()
            page.runFirstMatch(RESTORE_QUERY, RESTORE_PROJECT)

            val reread = page.restoreRows()
            assertThat(reread).hasCount(1)
            assertThat(reread.first()).hasAttribute("data-id", SELECTED_PROJECT)
            assertThat(reread.first()).containsText("Alpha Fixture")
        }

    /** Revisit through history so route change, not initial fetch ordering, is the only selection trigger. */
    @Test
    fun aDeepLinkToADeletedProjectsCardOpensItWithoutPaintingABacklogTheHeaderCannotName() =
        onProjects(BOARD_PROJECTS_SCENARIO, "project-deleted-deep-link") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks/" + DELETED_CARD)
            page.awaitBoard()
            assertThat(page.locator("#task-detail-title")).hasText(DELETED_CARD)

            page.locator("#task-detail-close").click()
            assertThat(page.locator("section.task-detail")).hasCount(0)
            assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
            page.goBack(Page.GoBackOptions().setWaitUntil(WaitUntilState.COMMIT))

            assertThat(page.locator("#task-detail-title")).hasText(DELETED_CARD)
            assertThat(page.locator("#task-detail-project")).containsText("Gamma Fixture")
            assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
            assertThat(page.locator(".task-card")).hasCount(SELECTED_CARDS)
            assertThat(page.projectRow(SELECTED_PROJECT)).hasClass(ACTIVE_PROJECT_ROW)
            assertThat(page.locator(".board-new-task")).isEnabled()
        }

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
        // Duplicated from webuicheck because constants cannot cross the native/JVM boundary.
        const val SELECTED_PROJECT = "33333333-3333-4333-8333-333333333333"
        const val SPARE_PROJECT = "44444444-4444-4444-8444-444444444444"
        const val DELETED_PROJECT = "55555555-5555-4555-8555-555555555555"

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
