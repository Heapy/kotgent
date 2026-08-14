package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse

class ProjectDialogTest {

    @Test
    fun anArchivedCreateResponseStaysInTheFormAndNeverAnnouncesSuccess() =
        onProjects(
            "project-create-racing-delete",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    if (route.request().method() == "POST") {
                        route.fulfill(
                            Route.FulfillOptions()
                                .setStatus(200)
                                .setContentType("application/json")
                                .setBody(projectJson(RACING_PROJECT, "Racing Fixture", "/repo/racing", archived = true)),
                        )
                    } else {
                        route.resume()
                    }
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("new project", "New project")
            page.locator("#new-project-path").fill("/repo/racing")
            page.locator("#new-project-form button[type=submit]").click()

            val error = page.locator("#new-project-dialog .form-error")
            assertThat(error).containsText("was deleted again")
            assertThat(error).containsText("Restore it")
            assertThat(page.locator("#new-project-dialog")).isVisible()
            assertFalse(
                page.locator("#board-status").textContent().contains("is ready"),
                "the archived DTO must prevent Board from overwriting the refusal with success",
            )
        }

    @Test
    fun anArchivedRestoreResponseKeepsTheDialogOpenWithRecoveryGuidance() =
        onProjects(
            "project-restore-racing-delete",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API/$DELETED_PROJECT/restore") { route ->
                    route.fulfill(
                        Route.FulfillOptions()
                            .setStatus(200)
                            .setContentType("application/json")
                            .setBody(projectJson(DELETED_PROJECT, "Gamma Fixture", "/repo/gamma", archived = true)),
                    )
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("restore a deleted", "Restore a deleted project")
            page.restoreRow(DELETED_PROJECT).click()

            val error = page.locator("#restore-project-error")
            assertThat(error).containsText("was deleted again")
            assertThat(error).containsText("Restore it again")
            assertThat(page.locator("#restore-project-dialog")).isVisible()
            assertThat(page.locator("#restore-project-close")).isEnabled()
            assertFalse(
                page.locator("#board-status").textContent().contains("Restored Gamma Fixture"),
                "a 200 response that still carries the tombstone is not a successful restore",
            )
        }

    @Test
    fun aRefreshRequestedAfterDeleteDiscardsTheOlderProjectsResponse() {
        val holdNext = AtomicBoolean(false)
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-refresh-newest-request-wins",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    val isLiveRead = route.request().method() == "GET" && !route.request().url().contains('?')
                    if (isLiveRead && holdNext.compareAndSet(true, false)) {
                        held.set(route)
                    } else {
                        route.resume()
                    }
                }
            },
        ) { harness, page ->
            assertThat(page.projectRow(SELECTED_PROJECT)).isVisible()
            holdNext.set(true)
            page.requestProjectRefresh()
            page.waitForCondition { held.get() != null }
            val stale = held.get()!!.fetch()

            harness.send("project-del $SELECTED_PROJECT")
            page.requestProjectRefresh()
            held.get()!!.fulfill(Route.FulfillOptions().setResponse(stale))

            assertThat(page.projectRow(SELECTED_PROJECT)).hasCount(0)
            assertThat(page.projectRow(SPARE_PROJECT)).hasClass("project-row active")
            assertThat(page.locator(".board-project")).hasText("Beta Fixture")
        }
    }

    @Test
    fun aSuccessfulDeleteRemovesTheSelectedProjectWhenItsLiveRereadFails() {
        val failNextLiveRead = AtomicBoolean(false)
        onProjects(
            "project-delete-failed-reread",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    val isLiveRead = route.request().method() == "GET" && !route.request().url().contains('?')
                    if (isLiveRead && failNextLiveRead.compareAndSet(true, false)) {
                        route.fulfill(
                            Route.FulfillOptions()
                                .setStatus(500)
                                .setContentType("text/plain")
                                .setBody("projects unavailable"),
                        )
                    } else {
                        route.resume()
                    }
                }
            },
        ) { _, page ->
            assertThat(page.projectRow(SELECTED_PROJECT)).hasClass("project-row active")
            failNextLiveRead.set(true)

            page.openPalette()
            page.runFirstMatch("delete project", "Delete project")
            page.locator("#delete-project-submit").click()

            assertThat(page.projectRow(SELECTED_PROJECT)).hasCount(0)
            assertThat(page.projectRow(SPARE_PROJECT)).hasClass("project-row active")
            assertThat(page.locator(".board-project")).hasText("Beta Fixture")
            assertThat(page.locator("#board-status"))
                .containsText("The project list could not be re-read — reload the page.")
        }
    }

    @Test
    fun deleteRefusesEveryDismissalWhileItsRequestIsInFlight() {
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-delete-busy-close",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API/$SELECTED_PROJECT") { route ->
                    if (route.request().method() == "DELETE" && held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("delete project", "Delete project")
            page.locator("#delete-project-submit").click()
            page.waitForCondition { held.get() != null }

            val dialog = page.locator("#delete-project-dialog")
            val close = page.locator("#delete-project-close")
            assertThat(close).isDisabled()
            assertThat(page.locator("#delete-project-cancel")).isDisabled()
            close.evaluate("button => button.click()")
            assertThat(dialog).isVisible()
            // closedby="none" is what refuses this; preventDefault on the key or on cancel is ignored.
            page.keyboard().press("Escape")
            assertThat(dialog).isVisible()

            held.get()!!.resume()
            assertThat(dialog).hasCount(0)
            assertThat(page.locator("#board-status")).containsText("Deleted Alpha Fixture")
        }
    }

    /** The refusal is scoped to the request: an idle dialog must still answer Escape. */
    @Test
    fun escapeClosesTheDeleteDialogBeforeItIsSubmitted() =
        onProjects("project-delete-escape-idle") { _, page ->
            page.openPalette()
            page.runFirstMatch("delete project", "Delete project")
            assertThat(page.locator("#delete-project-dialog")).isVisible()

            page.keyboard().press("Escape")

            assertThat(page.locator("#delete-project-dialog")).hasCount(0)
            assertThat(page.projectRow(SELECTED_PROJECT)).isVisible()
        }

    @Test
    fun restoreRefusesEveryDismissalWhileItsRequestIsInFlight() {
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-restore-busy-close",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API/$DELETED_PROJECT/restore") { route ->
                    if (held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("restore a deleted", "Restore a deleted project")
            page.restoreRow(DELETED_PROJECT).click()
            page.waitForCondition { held.get() != null }

            val dialog = page.locator("#restore-project-dialog")
            val close = page.locator("#restore-project-close")
            assertThat(close).isDisabled()
            assertThat(page.locator("#restore-project-cancel")).isDisabled()
            close.evaluate("button => button.click()")
            assertThat(dialog).isVisible()
            page.keyboard().press("Escape")
            assertThat(dialog).isVisible()

            held.get()!!.resume()
            assertThat(dialog).hasCount(0)
            assertThat(page.locator("#board-status")).containsText("Restored")
        }
    }

    private fun onProjects(
        trace: String,
        beforeLoad: (Harness, BrowserContext) -> Unit = { _, _ -> },
        block: (Harness, Page) -> Unit,
    ) {
        Harness(BOARD_PROJECTS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    beforeLoad(harness, context)
                    context.traced(trace) {
                        val page = context.newPage()
                        page.navigate(harness.baseUrl + "/tasks")
                        page.awaitBoard()
                        assertThat(page.locator(".board-project")).hasText("Alpha Fixture")
                        block(harness, page)
                    }
                }
            }
        }
    }

    private fun Page.projectRow(id: String) = locator("#project-list .project-row[data-id=\"$id\"]")

    private fun Page.restoreRow(id: String) = locator("#restore-project-list .dialog-list-row[data-id=\"$id\"]")

    private fun Page.requestProjectRefresh() {
        evaluate("() => window.dispatchEvent(new Event('focus'))")
    }

    private companion object {
        private const val PROJECTS_API = "/api/v1/projects"
        private const val SELECTED_PROJECT = "33333333-3333-4333-8333-333333333333"
        private const val SPARE_PROJECT = "44444444-4444-4444-8444-444444444444"
        private const val DELETED_PROJECT = "55555555-5555-4555-8555-555555555555"
        private const val RACING_PROJECT = "66666666-6666-4666-8666-666666666666"

        private fun projectJson(id: String, name: String, path: String, archived: Boolean): String =
            """{"id":"$id","name":"$name","path":"$path","updatedAt":0,"archived":$archived}"""
    }
}
