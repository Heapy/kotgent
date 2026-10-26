package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

class ProjectRouteTest {
    @Test
    fun creationAndHistoryKeepTheAddressAndSelectedBacklogTogether() = withBoard { harness, page ->
        page.navigate(harness.baseUrl + "/tasks?project=$BETA")
        page.awaitBoard()
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        page.openPalette()
        page.runFirstMatch("new project", "New project")
        page.locator("#new-project-path").fill("/repo/alpha")
        page.locator("#new-project-form button[type=submit]").click()
        assertThat(page.locator("#new-project-dialog")).hasCount(0)
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$ALPHA")
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
        page.reload()
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
        page.goBack()
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$BETA")
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        page.goForward()
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
    }

    @Test
    fun unknownAndUnqualifiedRoutesReplaceTheirHistoryEntryAfterTheCatalogueLoads() = withBoard { harness, page ->
        page.navigate(harness.baseUrl + "/tasks?project=$BETA")
        page.awaitBoard()
        page.navigate(harness.baseUrl + "/tasks?project=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$ALPHA")
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
        page.goBack()
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$BETA")
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        page.navigate(harness.baseUrl + "/tasks")
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$ALPHA")
        page.reload()
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
    }

    @Test
    fun aDelayedOrFailedInitialCatalogueDoesNotEraseTheRequestedProject() = withBoard { harness, page ->
        val held = AtomicReference<Route?>(null)
        page.route("**/api/v1/projects") { route -> held.set(route) }
        page.navigate(harness.baseUrl + "/tasks?project=$BETA")
        page.waitForCondition { held.get() != null }
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$BETA")
        held.get()!!.fulfill(Route.FulfillOptions().setStatus(503).setBody("temporarily unavailable"))
        assertThat(page.locator(".board-project")).hasText("No project")
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$BETA")
        page.unroute("**/api/v1/projects")
        page.reload()
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$BETA")
    }

    @Test
    fun everyReturnEntryPointKeepsTheLastBoardProjectAndNewTasksBelongToIt() = withBoard { harness, page ->
        page.navigate(harness.baseUrl + "/tasks?project=$BETA")
        page.awaitBoard()
        for (entry in listOf("sidebar", "Open the task board", "New task", "New project")) {
            page.locator(".nav-switch a:text-is('Sessions')").click()
            page.awaitSessionView()
            val tasksLink = page.locator(".nav-switch a:text-is('Tasks')")
            assertThat(tasksLink).hasAttribute("href", "/tasks?project=$BETA")
            if (entry == "sidebar") tasksLink.click()
            else {
                page.openPalette()
                if (entry == "Open the task board") page.pressMnemonic("KeyO")
                else page.runFirstMatch(entry.lowercase(), entry)
            }
            assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$BETA")
            assertThat(page.locator(".board-project")).hasText("Beta Fixture")
            when (entry) {
                "New task" -> {
                    page.locator("#new-task-title-input").fill("Created after returning to Beta")
                    page.locator("#new-task-form button[type=submit]").click()
                    assertThat(page.locator("#new-task-dialog")).hasCount(0)
                    assertThat(page.locator(".task-card").filter(
                        Locator.FilterOptions().setHasText("Created after returning to Beta"))).hasCount(1)
                }
                "New project" -> {
                    assertThat(page.locator("#new-project-dialog")).isVisible()
                    page.keyboard().press("Escape")
                }
            }
        }
        page.reload()
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        assertThat(page.locator(".task-card").filter(
            Locator.FilterOptions().setHasText("Created after returning to Beta"))).hasCount(1)
        page.locator("#project-list .project-row[data-id='$ALPHA']").click()
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
        assertThat(page.locator(".task-card").filter(
            Locator.FilterOptions().setHasText("Created after returning to Beta"))).hasCount(0)
    }

    @Test
    fun historyUpdatesTheReturnHintAndArchivingItsProjectInvalidatesIt() = withBoard { harness, page ->
        page.navigate(harness.baseUrl + "/tasks?project=$BETA")
        page.awaitBoard()
        page.locator("#project-list .project-row[data-id='$ALPHA']").click()
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
        page.goBack()
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        page.locator(".nav-switch a:text-is('Sessions')").click()
        page.awaitSessionView()
        val tasksLink = page.locator(".nav-switch a:text-is('Tasks')")
        assertThat(tasksLink).hasAttribute("href", "/tasks?project=$BETA")
        harness.send("project-del $BETA")
        page.evaluate("""() => {
            window.dispatchEvent(new Event('blur'));
            window.dispatchEvent(new Event('focus'));
        }""")
        assertThat(tasksLink).hasAttribute("href", "/tasks")
        tasksLink.click()
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$ALPHA")
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
    }

    @Test
    fun anExplicitUnqualifiedRouteDoesNotUseTheReturnHint() = withBoard { harness, page ->
        page.navigate(harness.baseUrl + "/tasks?project=$BETA")
        page.awaitBoard()
        assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        page.locator(".nav-switch a:text-is('Sessions')").click()
        page.awaitSessionView()
        // Exercise a route change in the same document so the return hint still exists.
        page.evaluate("""() => {
            history.pushState(null, '', '/tasks');
            window.dispatchEvent(new PopStateEvent('popstate'));
        }""")
        assertThat(page).hasURL(harness.baseUrl + "/tasks?project=$ALPHA")
        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
    }

    private fun withBoard(block: (Harness, Page) -> Unit) {
        Harness(BOARD_PROJECTS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced("project-route") { block(harness, context.newPage()) }
                }
            }
        }
    }

    private companion object {
        const val ALPHA = "33333333-3333-4333-8333-333333333333"
        const val BETA = "44444444-4444-4444-8444-444444444444"
    }
}
