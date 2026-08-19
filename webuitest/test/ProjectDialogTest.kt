package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun aDismissedNewProjectFormReportsItsLateRefusalInTheBoardStatus() {
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-create-dismissed-late-refusal",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    if (route.request().method() == "POST" && held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("new project", "New project")
            page.locator("#new-project-path").fill("/repo/racing")
            page.locator("#new-project-form button[type=submit]").click()
            page.waitForCondition { held.get() != null }

            page.keyboard().press("Escape")
            assertThat(page.locator("#new-project-dialog")).hasCount(0)
            held.get()!!.fulfill(
                Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(projectJson(RACING_PROJECT, "Racing Fixture", "/repo/racing", archived = true)),
            )

            val status = page.locator("#board-status")
            assertThat(status).containsText("was deleted again")
            assertThat(status).containsText("Restore it")
        }
    }

    @Test
    fun aDismissedNewProjectFormReportsItsLateSuccessInTheBoardStatus() {
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-create-dismissed-late-success",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    if (route.request().method() == "POST" && held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("new project", "New project")
            page.locator("#new-project-path").fill("/repo/alpha")
            page.locator("#new-project-form button[type=submit]").click()
            page.waitForCondition { held.get() != null }

            page.keyboard().press("Escape")
            assertThat(page.locator("#new-project-dialog")).hasCount(0)
            held.get()!!.resume()

            assertThat(page.locator("#board-status")).containsText("Project Alpha Fixture is ready.")
        }
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
    fun aDismissedDeleteDialogReportsItsLateRefusalInTheBoardStatus() {
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-delete-dismissed-late-refusal",
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

            page.keyboard().press("Escape")
            assertThat(page.locator("#delete-project-dialog")).hasCount(0)
            held.get()!!.fulfill(
                Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(projectJson(SELECTED_PROJECT, "Alpha Fixture", "/repo/alpha", archived = false)),
            )

            val status = page.locator("#board-status")
            assertThat(status).containsText("was restored again")
            assertThat(status).containsText("Delete it again")
        }
    }

    @Test
    fun foregroundingTheBoardRequestsProjectsOnce() {
        val foregrounding = AtomicBoolean(false)
        val holdNext = AtomicBoolean(false)
        val held = AtomicReference<Route?>(null)
        val liveReads = AtomicInteger(0)
        val refreshedRows =
            "[${projectJson(SELECTED_PROJECT, "Foreground Fixture", "/repo/alpha", archived = false)}]"
        onProjects(
            "project-refresh-foreground-once",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    val isLiveRead = route.request().method() == "GET" && !route.request().url().contains('?')
                    if (!isLiveRead) {
                        route.resume()
                        return@route
                    }
                    liveReads.incrementAndGet()
                    if (!foregrounding.get()) {
                        route.resume()
                    } else if (holdNext.compareAndSet(true, false)) {
                        held.set(route)
                    } else {
                        route.fulfill(
                            Route.FulfillOptions()
                                .setStatus(200)
                                .setContentType("application/json")
                                .setBody(refreshedRows),
                        )
                    }
                }
            },
        ) { _, page ->
            assertEquals(1, liveReads.get(), "board entry must issue its initial projects request")
            liveReads.set(0)
            foregrounding.set(true)
            holdNext.set(true)

            page.leaveBoard()
            page.showBoard()
            page.waitForCondition { held.get() != null }
            page.focusBoard()
            held.get()!!.fulfill(
                Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(refreshedRows),
            )

            assertThat(page.locator(".board-project")).hasText("Foreground Fixture")
            assertEquals(1, liveReads.get(), "one foregrounding must issue one live projects request")
        }
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
    fun aStalledDeleteTimesOutWithAnUnconfirmedOutcomeAndUnlocksTheDialog() {
        val held = AtomicReference<Route?>(null)
        onProjects(
            "project-delete-timeout",
            beforeLoad = { _, context ->
                context.addInitScript(API_TIMEOUT_CONTROL)
                context.route("**$PROJECTS_API/$SELECTED_PROJECT") { route ->
                    if (route.request().method() == "DELETE" && held.compareAndSet(null, route)) return@route
                    route.resume()
                }
            },
        ) { _, page ->
            page.evaluate(ARM_SHORT_API_TIMEOUT, SHORT_API_TIMEOUT_MILLIS)
            page.openPalette()
            page.runFirstMatch("delete project", "Delete project")

            val dialog = page.locator("#delete-project-dialog")
            val submit = page.locator("#delete-project-submit")
            page.locator("#delete-project-submit").click()
            page.waitForCondition { held.get() != null }
            assertThat(dialog).isVisible()
            assertThat(submit).isDisabled()

            page.waitForTimeout(TIMEOUT_OBSERVATION_MILLIS)
            val timeoutErrors = page.locator("#delete-project-error").count()
            val stillBusy = submit.isDisabled
            assertTrue(
                !stillBusy && timeoutErrors == 1,
                "held DELETE observation after ${TIMEOUT_OBSERVATION_MILLIS.toLong()}ms: " +
                    "stillBusy=$stillBusy, timeoutErrors=$timeoutErrors",
            )

            val error = page.locator("#delete-project-error")
            assertThat(error).containsText("timed out after 60 seconds")
            assertThat(error).containsText("outcome is unconfirmed")
            assertThat(error).containsText("Reload")
            assertThat(dialog).isVisible()
            assertThat(submit).isEnabled()
            assertThat(submit).hasText("Delete project")

            val requested = (page.evaluate("() => window.__kotgentApiTimeoutCalls.slice()") as List<*>)
                .map { (it as Number).toInt() }
            assertTrue(requested.isNotEmpty(), "the request must use AbortSignal.timeout")
            assertEquals(
                setOf(60_000),
                requested.toSet(),
                "every request after the harness override was armed must ask for the 60-second default",
            )
        }
    }

    @Test
    fun escapeDismissesABusyDeleteAndItsOutcomeReachesTheBoardStatus() {
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

            val close = page.locator("#delete-project-close")
            val headerCloseEnabled = close.isEnabled
            val footerClose = page.locator("#delete-project-cancel")
            val footerCloseEnabled = footerClose.isEnabled
            val footerCloseLabel = footerClose.textContent().trim()
            page.keyboard().press("Escape")
            val dismissedByEscape = page.dialogWasDismissed("delete-project-dialog")

            held.get()!!.resume()
            assertThat(page.locator("#board-status")).containsText("Deleted Alpha Fixture")
            assertEquals(
                "Close",
                footerCloseLabel,
                "a busy delete dismissal does not cancel the delete request",
            )
            assertTrue(
                headerCloseEnabled && footerCloseEnabled && dismissedByEscape,
                "busy delete dismissal contract: headerCloseEnabled=$headerCloseEnabled, " +
                    "footerCloseEnabled=$footerCloseEnabled, dismissedByEscape=$dismissedByEscape",
            )
        }
    }

    @Test
    fun escapeDismissesAnIdleDeleteWithoutSubmittingIt() {
        val deletes = AtomicInteger(0)
        onProjects(
            "project-delete-escape-idle",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API/$SELECTED_PROJECT") { route ->
                    if (route.request().method() == "DELETE") deletes.incrementAndGet()
                    route.resume()
                }
            },
        ) { _, page ->
            page.openPalette()
            page.runFirstMatch("delete project", "Delete project")
            assertThat(page.locator("#delete-project-dialog")).isVisible()

            page.keyboard().press("Escape")

            assertThat(page.locator("#delete-project-dialog")).hasCount(0)
            assertThat(page.projectRow(SELECTED_PROJECT)).isVisible()
            assertEquals(0, deletes.get(), "dismissing the idle confirmation must not submit its delete")
        }
    }

    @Test
    fun escapeDismissesABusyRestoreAndItsOutcomeReachesTheBoardStatus() {
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

            val close = page.locator("#restore-project-close")
            val headerCloseEnabled = close.isEnabled
            val footerClose = page.locator("#restore-project-cancel")
            val footerCloseEnabled = footerClose.isEnabled
            val footerCloseLabel = footerClose.textContent().trim()
            page.keyboard().press("Escape")
            val dismissedByEscape = page.dialogWasDismissed("restore-project-dialog")

            held.get()!!.resume()
            assertThat(page.locator("#board-status")).containsText("Restored")
            assertEquals(
                "Close",
                footerCloseLabel,
                "a busy restore dismissal does not cancel the restore request",
            )
            assertTrue(
                headerCloseEnabled && footerCloseEnabled && dismissedByEscape,
                "busy restore dismissal contract: headerCloseEnabled=$headerCloseEnabled, " +
                    "footerCloseEnabled=$footerCloseEnabled, dismissedByEscape=$dismissedByEscape",
            )
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

    private fun Page.dialogWasDismissed(id: String): Boolean =
        evaluate(
            "(id) => { const dialog = document.getElementById(id); return !dialog || !dialog.open; }",
            id,
        ) == true

    private fun Page.leaveBoard() {
        evaluate(
            """
            () => {
              Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
              window.dispatchEvent(new Event("blur"));
              document.dispatchEvent(new Event("visibilitychange"));
            }
            """.trimIndent(),
        )
    }

    private fun Page.showBoard() {
        evaluate(
            """
            () => {
              Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
              document.dispatchEvent(new Event("visibilitychange"));
            }
            """.trimIndent(),
        )
    }

    private fun Page.focusBoard() {
        evaluate("() => window.dispatchEvent(new Event('focus'))")
    }

    private fun Page.requestProjectRefresh() {
        leaveBoard()
        showBoard()
        focusBoard()
    }

    private companion object {
        private const val PROJECTS_API = "/api/v1/projects"
        private const val SELECTED_PROJECT = "33333333-3333-4333-8333-333333333333"
        private const val SPARE_PROJECT = "44444444-4444-4444-8444-444444444444"
        private const val DELETED_PROJECT = "55555555-5555-4555-8555-555555555555"
        private const val RACING_PROJECT = "66666666-6666-4666-8666-666666666666"

        private const val SHORT_API_TIMEOUT_MILLIS = 100.0
        private const val TIMEOUT_OBSERVATION_MILLIS = 1_000.0

        private val API_TIMEOUT_CONTROL = """
            (() => {
              const nativeTimeout = AbortSignal.timeout.bind(AbortSignal);
              window.__kotgentApiTimeoutCalls = [];
              window.__kotgentApiTimeoutOverrideMs = null;
              AbortSignal.timeout = (milliseconds) => {
                window.__kotgentApiTimeoutCalls.push(milliseconds);
                const override = window.__kotgentApiTimeoutOverrideMs;
                return nativeTimeout(override === null ? milliseconds : override);
              };
            })();
        """.trimIndent()

        private val ARM_SHORT_API_TIMEOUT = """
            (milliseconds) => {
              window.__kotgentApiTimeoutCalls.length = 0;
              window.__kotgentApiTimeoutOverrideMs = milliseconds;
            }
        """.trimIndent()

        private fun projectJson(id: String, name: String, path: String, archived: Boolean): String =
            """{"id":"$id","name":"$name","path":"$path","updatedAt":0,"archived":$archived}"""
    }
}
