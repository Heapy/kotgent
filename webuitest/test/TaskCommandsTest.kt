package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        "Link this session to a task",
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
    fun linkTaskExplainsEveryUnavailableSessionAndSerializesWithOtherActions() {
        val heldInterrupt = AtomicReference<Route?>(null)
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "link-task-availability",
            beforeLoad = { _, context ->
                context.route("**/api/v1/sessions/link-live/interrupt") { route ->
                    if (route.request().method() == "POST" && heldInterrupt.compareAndSet(null, route)) {
                        return@route
                    }
                    route.resume()
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()
            page.assertLinkTaskRefused("no session is selected")

            page.navigate(harness.baseUrl + "/s/link-stopped")
            page.awaitSelectedSession()
            page.assertLinkTaskRefused("not running")

            page.navigate(harness.baseUrl + "/s/link-linked")
            page.awaitSelectedSession()
            page.assertLinkTaskRefused("already linked to local:4")

            page.navigate(harness.baseUrl + "/s/link-no-project")
            page.awaitSelectedSession()
            page.assertLinkTaskRefused("has no project")

            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSelectedSession()
            page.openPalette()
            assertThat(page.leaderRow(LINK_TASK_COMMAND)).not().hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyI")
            page.waitForCondition { heldInterrupt.get() != null }

            page.assertLinkTaskRefused("another action is still in progress")
            heldInterrupt.get()!!.resume()
            assertThat(page.locator("#status-line")).containsText("Interrupt completed")
        }
    }

    @Test
    fun linkTaskPickerLoadsSortsSearchesAndLinksThroughPostThenTargetedGet() =
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "link-task-success",
            beforeLoad = { _, context -> context.addInitScript(TASK_SNAPSHOT_GATE) },
        ) { harness, page ->
            val writes = CopyOnWriteArrayList<Pair<String, String>>()
            val targetedReads = AtomicInteger(0)
            page.onRequest { request ->
                val url = request.url()
                if (request.method() == "POST" && url.contains("/api/v1/tasks/") && url.endsWith("/link")) {
                    writes.add(url to request.postData().orEmpty())
                }
                if (request.method() == "GET" && url == harness.baseUrl + "/api/v1/sessions/link-live") {
                    targetedReads.incrementAndGet()
                }
            }

            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.waitForFunction("() => window.__kotgentHeldTasksSnapshot === true")
            page.evaluate("() => { window.__kotgentLinkWitness = 1; }")

            page.openPalette()
            val command = page.leaderRow(LINK_TASK_COMMAND)
            assertThat(command).hasCount(1)
            assertThat(command).not().hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyL")

            assertThat(page.locator("#link-task-dialog")).isVisible()
            assertThat(page.locator("#link-task-status")).hasText("Reading open tasks…")
            assertThat(page.locator("#link-task-query")).isFocused()
            assertThat(page.locator("#link-task-query"))
                .not().hasAttribute("aria-controls", "link-task-list")
            page.evaluate("() => window.__kotgentReleaseTasksSnapshot()")

            val options = page.locator(".link-picker-option")
            assertThat(options).hasCount(5)
            assertThat(page.locator("#link-task-query"))
                .hasAttribute("aria-controls", "link-task-list")
            assertEquals(
                listOf("local:2", "local:1", "local:3", "local:4", "local:5"),
                options.all().map { it.getAttribute("data-ref") },
                "open tasks use the board's state, position, creation-time and ref order; done and " +
                    "other-project rows never enter the picker",
            )
            assertThat(
                page.locator(".link-picker-option[data-ref='local:3'] .link-picker-blocked"),
            ).hasText("Blocked")

            val query = page.locator("#link-task-query")
            query.fill("local:4")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("Continue the index")
            query.fill("parser")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("local:5")
            query.fill("")
            assertThat(options).hasCount(5)
            assertThat(options.first()).hasClass(ACTIVE_OPTION)

            query.press("ArrowDown")
            assertThat(options.nth(1)).hasClass(ACTIVE_OPTION)
            harness.send("task local:7 in_progress")
            page.waitForFunction(
                """() => (window.__kotgentTaskFrames || []).some((frame) =>
                    frame.indexOf('"ref":"local:7"') >= 0)""".trimIndent(),
            )
            assertThat(options.nth(1)).hasClass(ACTIVE_OPTION)
            val readsBeforeSubmit = targetedReads.get()
            query.evaluate(
                """el => {
                  el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
                  el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
                }""".trimIndent(),
            )

            assertThat(page.locator("#link-task-dialog")).hasCount(0)
            page.waitForCondition { writes.size == 1 && targetedReads.get() > readsBeforeSubmit }
            assertTrue(
                writes.single().first.endsWith("/api/v1/tasks/local%3A2/link"),
                "Enter observes the ref synchronously selected by ArrowUp: ${writes.single().first}",
            )
            assertEquals(
                "{\"sessionId\":\"link-live\"}",
                writes.single().second,
                "the Web UI names the selected session explicitly instead of relying on a pane header",
            )

            val badge = page.locator("#terminal-task")
            assertThat(badge).isVisible()
            assertThat(badge).hasText("Beta same-rank task")
            assertThat(badge).hasAttribute("href", "/tasks/local%3A2")
            assertThat(page.locator("#status-line")).containsText("Linked linkable to local:2.")
            assertTrue(
                page.evaluate("() => window.__kotgentLinkWitness === 1") == true,
                "the badge arrived in the existing document through the targeted revision merge",
            )

            badge.click()
            assertThat(page).hasURL(Pattern.compile("/tasks/local%3A2$"))
            assertThat(page.locator("#task-detail-form")).isVisible()
            assertThat(page.locator("#task-detail-state")).hasValue("in_progress")
            val linked = page.locator(".task-activity-row[data-kind='linked']")
            assertThat(linked).hasCount(1)
            assertThat(linked.locator("strong")).hasText("link-live")
        }

    @Test
    fun linkTaskPickerShowsRequestErrorEmptyProjectAndSessionChangeStates() =
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "link-task-states",
            beforeLoad = { _, context ->
                context.route("**/api/v1/tasks/**/link") { route ->
                    route.fulfill(
                        Route.FulfillOptions()
                            .setStatus(503)
                            .setContentType("text/plain")
                            .setBody(LINK_FAILURE),
                    )
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.openLinkTaskPicker()
            val query = page.locator("#link-task-query")
            val options = page.locator(".link-picker-option")
            assertThat(options).hasCount(5)
            harness.send("emit link-live running")
            assertThat(
                page.locator("#session-list .session-row[data-id='link-live'] .badge"),
            ).hasText("running")
            assertThat(page.locator("#link-task-changed")).hasCount(0)
            assertThat(query).isEnabled()
            assertThat(options).hasCount(5)
            assertThat(options.first()).hasClass(ACTIVE_OPTION)
            query.press("Enter")
            assertThat(page.locator("#link-task-dialog")).isVisible()
            assertThat(page.locator("#link-task-error")).containsText(LINK_FAILURE)
            assertThat(page.locator("#link-task-error")).containsText("Could not link linkable")
            assertThat(query).isEnabled()
            assertThat(query).isFocused()

            query.press("ArrowDown")
            assertThat(options.nth(1)).hasClass(ACTIVE_OPTION)
            assertThat(page.locator("#link-task-error")).hasCount(0)
            query.fill("parser")
            assertThat(options).hasCount(1)
            assertThat(options.first()).hasClass(ACTIVE_OPTION)

            page.locator("#link-task-cancel").click()
            page.navigate(harness.baseUrl + "/s/link-empty")
            page.awaitSelectedSession()
            page.openLinkTaskPicker()
            assertThat(page.locator("#link-task-empty")).hasText(
                "No open tasks in this session's project.",
            )
            assertThat(page.locator("#link-task-query"))
                .not().hasAttribute("aria-controls", "link-task-list")
            assertThat(page.locator(".link-picker-option")).hasCount(0)

            page.locator("#link-task-cancel").click()
            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSelectedSession()
            page.openLinkTaskPicker()
            assertThat(page.locator(".link-picker-option")).hasCount(5)
            harness.send("emit link-live stopped")
            assertThat(page.locator("#link-task-changed")).containsText("selected session changed")
            assertThat(page.locator("#link-task-query")).isDisabled()
            assertThat(page.locator("#link-task-query"))
                .not().hasAttribute("aria-controls", "link-task-list")
            assertThat(page.locator(".link-picker-option")).hasCount(0)
        }

    @Test
    fun linkTaskPickerRejectsAProjectMissingFromTheLiveProjectList() =
        onScenario(TASK_LINK_PICKER_SCENARIO, "link-task-archived-project") { harness, page ->
            val writes = AtomicInteger(0)
            page.onRequest { request ->
                if (request.method() == "POST" && request.url().endsWith("/link")) {
                    writes.incrementAndGet()
                }
            }
            harness.send("project-del $LINK_PICKER_PROJECT_ID")

            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.openPalette()
            page.pressMnemonic("KeyL")

            assertThat(page.locator("#link-task-dialog")).isVisible()
            assertThat(page.locator("#link-task-project-missing")).containsText(
                "project is no longer active",
            )
            assertThat(page.locator("#link-task-query")).isDisabled()
            assertThat(page.locator("#link-task-query"))
                .not().hasAttribute("aria-controls", "link-task-list")
            assertThat(page.locator(".link-picker-option")).hasCount(0)
            assertEquals(0, writes.get(), "an archived project's retained tasks are never submitted")
        }

    // Finding app.js:388. Readiness used to be a boolean, so a failed GET /projects was indistinguishable
    // from one still in flight: the picker read "Reading open tasks…" with no error, no retry and no
    // timeout until a board round-trip or a reload repaired it.
    @Test
    fun aFailedProjectReadOffersARetryInThePickerAndTheRetryRecovers() {
        val readFails = AtomicBoolean(true)
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "link-task-projects-unread",
            beforeLoad = { _, context ->
                context.route("**$PROJECTS_API") { route ->
                    if (route.request().method() == "GET" && readFails.get()) {
                        route.fulfill(
                            Route.FulfillOptions()
                                .setStatus(503)
                                .setContentType("text/plain")
                                .setBody(PROJECTS_FAILURE),
                        )
                    } else {
                        route.resume()
                    }
                }
            },
        ) { harness, page ->
            val reads = AtomicInteger(0)
            page.onRequest { request ->
                if (request.method() == "GET" && request.url() == harness.baseUrl + PROJECTS_API) {
                    reads.incrementAndGet()
                }
            }

            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.openPalette()
            page.pressMnemonic("KeyL")

            assertThat(page.locator("#link-task-dialog")).isVisible()
            // The mount read, plus the one opening the picker asks for. Nothing else starts a read on
            // this screen, so what the assertions below settle on is the terminal state, and the retry
            // control cannot be pressed while a read it would refuse to duplicate is still running.
            page.waitForCondition { reads.get() >= 2 }
            val failure = page.locator("#link-task-failed")
            assertThat(failure).containsText(PROJECTS_FAILURE)
            assertThat(page.locator("#link-task-status")).hasCount(0)
            assertThat(page.locator("#link-task-query")).isDisabled()
            assertThat(page.locator(".link-picker-option")).hasCount(0)

            readFails.set(false)
            page.locator("#link-task-retry").click()

            assertThat(page.locator(".link-picker-option")).hasCount(5)
            assertThat(failure).hasCount(0)
            assertThat(page.locator("#link-task-query")).isEnabled()
        }
    }

    // Finding app.js:403. Project rows carry no WebSocket frame and only mount and board entry refresh
    // them, so the session screen judged every picker against the list it read at load: a project that
    // became live afterwards stayed "no longer active" until the page was reloaded. The harness has no
    // project-create command, and restoring an archived one exercises the same membership question.
    @Test
    fun aProjectRestoredAfterPageLoadIsLinkableWithoutAReload() =
        onScenario(TASK_LINK_PICKER_SCENARIO, "link-task-live-project-list") { harness, page ->
            harness.send("project-del $LINK_PICKER_PROJECT_ID")

            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.openPalette()
            page.pressMnemonic("KeyL")
            assertThat(page.locator("#link-task-project-missing")).containsText(
                "project is no longer active",
            )
            page.locator("#link-task-cancel").click()
            assertThat(page.locator("#link-task-dialog")).hasCount(0)

            harness.send("project-restore $LINK_PICKER_PROJECT_ID")

            page.openPalette()
            page.pressMnemonic("KeyL")
            assertThat(page.locator("#link-task-dialog")).isVisible()
            assertThat(page.locator(".link-picker-option")).hasCount(5)
            assertThat(page.locator("#link-task-project-missing")).hasCount(0)
            assertThat(page.locator("#link-task-query")).isEnabled()
        }

    @Test
    fun linkTaskHoldsItsLockThroughTheReadAndAStaleRowCannotWriteTheSuccessMessage() {
        val linkPosted = AtomicBoolean(false)
        val heldRead = AtomicReference<Route?>(null)
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "link-task-newest-wins",
            beforeLoad = { _, context ->
                context.route("**/api/v1/tasks/**/link") { route ->
                    if (route.request().method() == "POST") linkPosted.set(true)
                    route.resume()
                }
                context.route("**/api/v1/sessions/link-live") { route ->
                    if (route.request().method() == "GET" && linkPosted.get() &&
                        heldRead.compareAndSet(null, route)
                    ) {
                        return@route
                    }
                    route.resume()
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.openLinkTaskPicker()
            // Readiness detaches the placeholder; the first row is activated one effect later, and an
            // Enter arriving before that activation is silently dropped instead of linking.
            assertThat(page.locator(".link-picker-option").first()).hasClass(ACTIVE_OPTION)
            page.locator("#link-task-query").press("Enter")

            page.waitForCondition { heldRead.get() != null }
            val stale = heldRead.get()!!.fetch()
            assertThat(page.locator("#link-task-dialog")).hasCount(0)
            assertThat(page.locator("#status-line")).containsText("refreshing the session")

            // The badge re-read runs inside the link's own lock. Releasing the lock after the POST is
            // what let a second link start while the first was still settling and overwrite it, so
            // every session control — the link command among them — reads as busy until the read lands.
            page.openPalette()
            assertThat(page.leaderRow("Interrupt current session"))
                .hasAttribute("aria-disabled", "true")
            page.closePalette()
            page.assertLinkTaskRefused("another action is still in progress")

            val replacementStatus = (
                page.evaluate(
                    """async () => (await fetch('/api/v1/tasks/local%3A5/link', {
                      method: 'POST',
                      body: JSON.stringify({ sessionId: 'link-live' })
                    })).status""".trimIndent(),
                ) as Number
            ).toInt()
            assertTrue(replacementStatus in 200..299, "the racing replacement link was accepted")
            assertThat(page.locator("#terminal-task")).hasText("Review the parser")

            heldRead.get()!!.fulfill(Route.FulfillOptions().setResponse(stale))

            assertThat(page.locator("#status-line")).containsText("now linked to local:5")
            assertThat(page.locator("#terminal-task")).hasText("Review the parser")
            assertThat(page.locator("#terminal-task")).hasAttribute("href", "/tasks/local%3A5")
        }
    }

    @Test
    fun aReadOutsideAMutationHoldsNoLockAndBlocksNoControl() {
        val heldPreferences = AtomicReference<Route?>(null)
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "read-outside-a-mutation",
            beforeLoad = { _, context ->
                context.route("**/api/v1/preferences") { route ->
                    if (route.request().method() == "GET" && heldPreferences.compareAndSet(null, route)) {
                        return@route
                    }
                    route.resume()
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.waitForCondition { heldPreferences.get() != null }

            // Scope discipline, the other half of the decision above: only the six mutating flows take
            // the lock. A read that belongs to none of them disables nothing, so an unrelated control is
            // offered and runs to completion while that read is still stalled on its own transport.
            page.openPalette()
            assertThat(page.leaderRow("Interrupt current session"))
                .not().hasAttribute("aria-disabled", "true")
            assertThat(page.leaderRow(LINK_TASK_COMMAND)).not().hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyI")
            assertThat(page.locator("#status-line")).containsText("Interrupt completed")

            heldPreferences.get()!!.resume()
            page.openPalette()
            assertThat(page.leaderRow("Interrupt current session"))
                .not().hasAttribute("aria-disabled", "true")
            page.closePalette()
        }
    }

    @Test
    fun linkTaskPickerScrollsOnAShortPhoneAndMarksTheTappedTaskBusy() {
        val heldLink = AtomicReference<Route?>(null)
        onScenario(
            TASK_LINK_PICKER_SCENARIO,
            "link-task-compact-busy",
            viewportHeight = 360,
            beforeLoad = { _, context ->
                context.addInitScript(SCROLL_INTO_VIEW_WATCHER)
                context.route("**/api/v1/tasks/**/link") { route ->
                    if (route.request().method() == "POST" && heldLink.compareAndSet(null, route)) {
                        return@route
                    }
                    route.resume()
                }
            },
        ) { harness, page ->
            page.navigate(harness.baseUrl + "/s/link-live")
            page.awaitSessionView()
            page.awaitSelectedSession()
            page.openLinkTaskPicker()
            // The one-line placeholder never overflows; only the five rows make the port's geometry
            // a statement about the task list.
            assertThat(page.locator(".link-picker-option")).hasCount(5)

            val results = page.locator(".link-picker-results")
            assertEquals(
                "auto",
                results.evaluate("el => getComputedStyle(el).overflowY"),
                "the compact picker exposes its results as a scroll port",
            )
            val clientHeight = (results.evaluate("el => el.clientHeight") as Number).toDouble()
            val scrollHeight = (results.evaluate("el => el.scrollHeight") as Number).toDouble()
            assertTrue(
                scrollHeight > clientHeight + 1,
                "five tasks overflow the compact ${clientHeight}px results port (${scrollHeight}px content)",
            )
            val scrollTop = (
                results.evaluate("el => { el.scrollTop = el.scrollHeight; return el.scrollTop; }") as Number
            ).toDouble()
            assertTrue(scrollTop > 0, "the lower task rows are reachable by scrolling the results port")

            val first = page.locator(".link-picker-option[data-ref='local:1']")
            val tapped = page.locator(".link-picker-option[data-ref='local:5']")
            val firstByBoardOrder = page.locator(".link-picker-option[data-ref='local:2']")
            assertThat(firstByBoardOrder).hasClass(ACTIVE_OPTION)
            val scrollCalls = (
                page.evaluate("() => window.__kotgentScrollIntoViewCalls") as Number
            ).toInt()
            tapped.hover()
            page.evaluate(
                """() => new Promise((resolve) => requestAnimationFrame(() =>
                  requestAnimationFrame(resolve)))""".trimIndent(),
            )
            assertThat(tapped).hasClass(ACTIVE_OPTION)
            assertEquals(
                scrollCalls,
                (page.evaluate("() => window.__kotgentScrollIntoViewCalls") as Number).toInt(),
                "pointer activation does not call scrollIntoView and cannot start a hover-scroll loop",
            )
            tapped.tap()
            page.waitForCondition { heldLink.get() != null }
            assertThat(tapped.locator(".link-picker-state")).hasText("Linking…")
            assertThat(first.locator(".link-picker-state")).hasText("To do")

            heldLink.get()!!.resume()
            assertThat(page.locator("#link-task-dialog")).hasCount(0)
        }
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
        viewportHeight: Int = 844,
        beforeLoad: (Harness, BrowserContext) -> Unit = { _, _ -> },
        block: (Harness, Page) -> Unit,
    ) {
        Harness(scenario).use { harness ->
            onChromium { browser ->
                browser.touchContext(height = viewportHeight).use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    beforeLoad(harness, context)
                    context.traced(trace) { block(harness, context.newPage()) }
                }
            }
        }
    }

    private fun Page.leaderRow(title: String): Locator =
        locator(".command-palette-leader-command").filter(Locator.FilterOptions().setHasText(title))

    private fun Page.assertLinkTaskRefused(reason: String) {
        openPalette()
        val command = leaderRow(LINK_TASK_COMMAND)
        assertThat(command).hasCount(1)
        assertThat(command).hasAttribute("aria-disabled", "true")
        pressMnemonic("KeyL")
        assertThat(locator(".command-palette-footer")).containsText(reason)
        closePalette()
    }

    private fun Page.openLinkTaskPicker() {
        openPalette()
        pressMnemonic("KeyL")
        assertThat(locator("#link-task-dialog")).isVisible()
        assertThat(locator("#link-task-query")).isFocused()
        // The placeholder holds until both the task and the project read land. Without waiting for it
        // to detach, a caller's first key or geometry read observes "Reading open tasks…", not rows.
        // Row count is not the barrier: an empty project is ready with no rows at all.
        assertThat(locator("#link-task-status")).hasCount(0)
    }

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
        const val PROJECTS_API = "/api/v1/projects"
        const val PROJECTS_FAILURE = "The project list read failed on purpose."
        const val LATE_FAILURE = "Task creation failed after dismissal."
        const val LINK_FAILURE = "Task link failed on purpose."
        const val LINK_TASK_COMMAND = "Link this session to a task"
        const val LINK_PICKER_PROJECT_ID = "66666666-6666-4666-8666-666666666666"
        const val SHOW_DONE_QUERY = "hide done"

        val SCROLL_INTO_VIEW_WATCHER = """
            (() => {
              const nativeScrollIntoView = Element.prototype.scrollIntoView;
              window.__kotgentScrollIntoViewCalls = 0;
              Element.prototype.scrollIntoView = function (...args) {
                window.__kotgentScrollIntoViewCalls += 1;
                return nativeScrollIntoView.apply(this, args);
              };
            })();
        """.trimIndent()

        val TASK_SNAPSHOT_GATE = """
            (() => {
              const Native = window.WebSocket;
              window.__kotgentHeldTasksSnapshot = false;
              window.__kotgentTaskFrames = [];
              window.__kotgentReleaseTasksSnapshot = () => {};
              const Gated = function (url, protocols) {
                const socket = protocols === undefined ? new Native(url) : new Native(url, protocols);
                if (String(url).indexOf("/api/v1/events") >= 0) {
                  let released = false;
                  const held = [];
                  socket.addEventListener("message", (event) => {
                    if (typeof event.data === "string" &&
                        event.data.indexOf('"type":"task_update"') >= 0) {
                      window.__kotgentTaskFrames.push(event.data);
                    }
                    if (released || typeof event.data !== "string" ||
                        event.data.indexOf('"type":"tasks_snapshot"') < 0) return;
                    event.stopImmediatePropagation();
                    held.push(event.data);
                    window.__kotgentHeldTasksSnapshot = true;
                  });
                  window.__kotgentReleaseTasksSnapshot = () => {
                    released = true;
                    for (const data of held.splice(0)) {
                      socket.dispatchEvent(new MessageEvent("message", { data }));
                    }
                  };
                }
                return socket;
              };
              Gated.prototype = Native.prototype;
              Gated.CONNECTING = Native.CONNECTING;
              Gated.OPEN = Native.OPEN;
              Gated.CLOSING = Native.CLOSING;
              Gated.CLOSED = Native.CLOSED;
              window.WebSocket = Gated;
            })();
        """.trimIndent()
    }
}
