package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionDialogsTest {


    @Test
    fun theAgentPickerTakesTheFirstFocusAndOneClickAnswersIt() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-agent-picker") {
                        val page = signIn(context, harness)
                        openNewSession(page)

                        assertThat(page.locator(AGENT_RADIOS + ":checked")).hasCount(0)
                        assertThat(page.locator("#session-agent-claude")).isFocused()
                        assertThat(page.locator("#new-session-agent-hint")).isVisible()
                        assertEquals(
                            "new-session-agent-hint",
                            page.locator(".agent-picker").getAttribute("aria-describedby"),
                            "the group is described by its hint while the choice is unanswered",
                        )

                        agentCard(page, "codex").click()
                        val chosen = page.locator(AGENT_RADIOS + ":checked")
                        assertThat(chosen).hasCount(1)
                        assertThat(chosen).hasAttribute("id", "session-agent-codex")

                        assertThat(page.locator("#new-session-agent-hint")).hasCount(0)
                        assertNull(
                            page.locator(".agent-picker").getAttribute("aria-describedby"),
                            "an answered choice is not still described as missing",
                        )

                        page.waitForFunction(
                            "() => {" +
                                " const card = (v) => document.querySelector(" +
                                "   'label.agent-option:has(#session-agent-' + v + ')" +
                                " .agent-option-content');" +
                                " const bg = (v) => getComputedStyle(card(v)).backgroundColor;" +
                                " return bg('codex') !== bg('junie');" +
                                "}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theHiddenRadiosStayInTheKeyboardsArrowGroup() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-radio-keyboard") {
                        val page = signIn(context, harness)
                        openNewSession(page)

                        assertEquals(
                            "zero-sized and still laid out",
                            page.locator("#session-agent-claude").evaluate(RADIO_PAINT),
                            "the radio must paint nothing at all — the card behind it is the visible " +
                                "control — and must still be laid out, because the two ways to make a " +
                                "0x0 box by REMOVING the element take it out of the tab order and the " +
                                "arrow group this test walks below",
                        )

                        assertThat(page.locator("#session-agent-claude")).isFocused()
                        page.keyboard().press("Space")
                        assertThat(page.locator(AGENT_RADIOS + ":checked"))
                            .hasAttribute("id", "session-agent-claude")

                        for (expected in listOf("codex", "junie", "shell", "claude")) {
                            page.keyboard().press("ArrowDown")
                            val focused = page.locator(AGENT_RADIOS + ":checked")
                            assertThat(focused).hasAttribute("id", "session-agent-$expected")
                            assertThat(page.locator("#session-agent-$expected")).isFocused()
                        }
                        assertEquals(
                            4,
                            page.locator("$AGENT_RADIOS:not([disabled])").count(),
                            "the arrow group is exactly the radios that are not disabled",
                        )
                        assertThat(page.locator("#session-agent-cursor")).isDisabled()
                    }
                }
            }
        }
    }

    @Test
    fun aPlannedAgentIsAnnouncedWithoutBecomingChoosable() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-planned-agent") {
                        val page = signIn(context, harness)
                        openNewSession(page)

                        val card = agentCard(page, "cursor")
                        assertThat(card).isVisible()
                        assertThat(card).containsText("Cursor")
                        assertThat(card).containsText("Soon")
                        assertThat(page.locator("#session-agent-cursor")).isDisabled()

                        // Playwright otherwise waits for the deliberately disabled control to enable.
                        card.click(Locator.ClickOptions().setForce(true))
                        assertThat(page.locator("#session-agent-cursor")).not().isChecked()
                        assertThat(page.locator(AGENT_RADIOS + ":checked")).hasCount(0)
                        assertThat(page.locator("#new-session-agent-hint")).isVisible()
                    }
                }
            }
        }
    }

    @Test
    fun submittingWithNoAgentIsReportedInsteadOfQuietlyDoingNothing() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-no-agent") {
                        val page = signIn(context, harness)
                        val starts = countStartRequests(page)
                        openNewSession(page)
                        fillWorkingDirectory(page, "/a/b")

                        page.locator("#new-session-submit").click()

                        val error = page.locator("#new-session-error")
                        assertThat(error).hasText("Pick an agent to start a session.")
                        assertThat(error).hasAttribute("role", "alert")
                        assertThat(page.locator("#session-agent-claude")).isFocused()
                        assertThat(page.locator("#new-session-dialog")).isVisible()
                        assertEquals(0, starts.get(), "nothing was posted for a choice that was not made")

                        agentCard(page, "claude").click()
                        assertThat(page.locator("#new-session-error")).hasCount(0)

                        page.locator("#new-session-submit").click()
                        assertThat(page.locator("#new-session-error")).containsText("Could not start session")
                        assertEquals(
                            1,
                            starts.get(),
                            "an answered form posts exactly once, and the interceptor sees it — which is " +
                                "what makes the zero above a silence rather than a glob that never fired",
                        )
                    }
                }
            }
        }
    }


    @Test
    fun theWorkingDirectoryCompletesFromTheDaemonAndCommitsWithTheKeyboard() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-cwd-completion") {
                        val page = signIn(context, harness)
                        openNewSession(page)

                        val cwd = page.locator("#session-cwd")
                        val options = page.locator("#session-cwd-options li")
                        cwd.fill("/a/")

                        assertThat(page.locator("#session-cwd-options")).isVisible()
                        assertThat(options).hasCount(2)
                        for (path in listOf("/a/b", "/a/c")) {
                            assertThat(options.filter(Locator.FilterOptions().setHasText(path))).hasCount(1)
                        }

                        cwd.fill("/a/.")
                        assertThat(options).hasCount(1)
                        assertThat(options.first()).containsText("/a/.hidden")

                        cwd.fill("/projects/")
                        assertThat(options).hasCount(2)
                        cwd.fill("/projects/kotgent-")
                        assertThat(options).hasCount(1)
                        assertThat(options.first()).containsText("/projects/kotgent-web")

                        cwd.fill("/a/")
                        assertThat(options).hasCount(2)
                        assertThat(cwd).hasAttribute("aria-expanded", "true")

                        page.keyboard().press("ArrowDown")
                        assertThat(cwd).hasAttribute("aria-activedescendant", "session-cwd-option-0")
                        val active = page.locator("#session-cwd-options li.active").textContent().trim()

                        page.keyboard().press("Enter")
                        assertThat(cwd).hasValue(active)
                        assertThat(page.locator("#session-cwd-options")).hasCount(0)
                        assertThat(cwd).isFocused()
                        assertThat(page.locator("#new-session-dialog")).isVisible()
                    }
                }
            }
        }
    }


    @Test
    fun aMissingAgentBinaryIsShownAsTheDaemonsOwnAdvice() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-missing-binary") {
                        val page = signIn(context, harness)
                        page.route("**$API/sessions") { route ->
                            if (route.request().method() != "POST") {
                                route.resume()
                                return@route
                            }
                            route.fulfill(
                                Route.FulfillOptions()
                                    .setStatus(400)
                                    .setContentType("text/plain; charset=utf-8")
                                    .setBody(MISSING_BINARY_BODY),
                            )
                        }

                        openNewSession(page)
                        agentCard(page, "claude").click()
                        fillWorkingDirectory(page, "/a/b")
                        page.locator("#new-session-submit").click()

                        val error = page.locator("#new-session-error")
                        assertThat(error).hasText("Could not start session: $MISSING_BINARY_BODY")
                        assertThat(error).hasAttribute("role", "alert")

                        assertThat(page.locator("#new-session-dialog")).isVisible()
                        assertThat(page.locator("#session-cwd")).hasValue("/a/b")
                        assertThat(page.locator("#new-session-submit")).isEnabled()
                        assertThat(page.locator("#new-session-submit")).hasText("Start session")
                    }
                }
            }
        }
    }

    @Test
    fun aBusyNewSessionDismissalSaysCloseAndItsLateFailureIsAnnounced() {
        val held = AtomicReference<Route?>(null)
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.route("**$API/sessions") { route ->
                        if (route.request().method() == "POST" && held.compareAndSet(null, route)) {
                            return@route
                        }
                        route.resume()
                    }
                    context.traced("new-session-busy-dismissal-label") {
                        val page = signIn(context, harness)
                        openNewSession(page)
                        agentCard(page, "claude").click()
                        fillWorkingDirectory(page, "/a/b")
                        page.locator("#new-session-submit").click()
                        page.waitForCondition { held.get() != null }
                        val dismissalLabel = page.locator("#new-session-cancel").textContent().trim()

                        page.keyboard().press("Escape")
                        assertThat(page.locator("#new-session-dialog")).hasCount(0)
                        held.get()!!.fulfill(
                            Route.FulfillOptions()
                                .setStatus(500)
                                .setContentType("text/plain")
                                .setBody("late start refusal"),
                        )

                        assertThat(page.locator("#status-line"))
                            .containsText("Could not start session: late start refusal")
                        assertEquals(
                            "Close",
                            dismissalLabel,
                            "closing a busy new-session form does not cancel its start",
                        )
                    }
                }
            }
        }
    }


    @Test
    fun importModeAdoptsASessionAndOffersNoShell() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-import") {
                        val page = signIn(context, harness)
                        val body = AtomicReference<String?>(null)
                        val resumes = AtomicInteger(0)
                        val row = sessionDto(
                            id = IMPORTED_ID,
                            name = IMPORTED_NAME,
                            agent = "codex",
                            cwd = "/a/c",
                            state = "resumable",
                            providerSessionId = IMPORTED_PROVIDER_ID,
                        )
                        page.route("**$API/sessions/import") { route ->
                            body.set(route.request().postData())
                            route.fulfill(
                                Route.FulfillOptions()
                                    .setStatus(201)
                                    .setContentType("application/json")
                                    .setBody(row),
                            )
                        }
                        page.route("**$API/sessions/$IMPORTED_ID") { route ->
                            route.fulfill(
                                Route.FulfillOptions().setContentType("application/json").setBody(row),
                            )
                        }
                        page.route("**$API/sessions/*/resume") { route ->
                            resumes.incrementAndGet()
                            route.fulfill(Route.FulfillOptions().setStatus(500).setBody("not expected"))
                        }

                        openNewSession(page)
                        page.locator("#new-session-mode-import").click()
                        assertThat(page.locator("#new-session-mode-import"))
                            .hasAttribute("aria-pressed", "true")

                        assertThat(page.locator("#session-agent-shell")).hasCount(0)
                        for (kind in listOf("claude", "codex", "junie", "cursor")) {
                            assertThat(page.locator("#session-agent-$kind")).hasCount(1)
                        }
                        assertThat(page.locator("#session-agent-cursor")).isDisabled()

                        assertThat(page.locator("#session-provider-id")).isVisible()
                        assertEquals(
                            false,
                            page.locator("#session-cwd").evaluate("el => el.required"),
                            "the daemon finds the cwd from the transcript, so the field is optional here",
                        )

                        agentCard(page, "codex").click()
                        page.locator("#session-provider-id").fill(IMPORTED_PROVIDER_ID)
                        page.locator("#session-register-only").check()
                        page.locator("#new-session-submit").click()

                        assertThat(page.locator("#new-session-dialog")).hasCount(0)
                        val adopted = page.locator("#session-list .session-row")
                        assertThat(adopted).hasCount(1)
                        assertThat(adopted).hasAttribute("aria-label", "Open $IMPORTED_NAME, resumable")
                        assertThat(page).hasURL(harness.baseUrl + "/s/" + IMPORTED_ID)
                        assertThat(page.locator("#status-line")).containsText("Imported $IMPORTED_NAME")

                        val sent = body.get() ?: error("the import request carried no body")
                        assertEquals(
                            true,
                            sent.contains("\"providerSessionId\":\"$IMPORTED_PROVIDER_ID\""),
                            "the import body names the provider session id, was: $sent",
                        )
                        assertEquals(
                            true,
                            sent.contains("\"cwd\":null"),
                            "an empty working directory is sent as null, not as an override, was: $sent",
                        )
                        assertEquals(
                            0,
                            resumes.get(),
                            "register only stops at registration — the session is left resumable",
                        )

                        runFromPalette(page, "Resume this session")
                        assertThat(page.locator("#status-line")).containsText("Resume failed")
                        assertEquals(
                            1,
                            resumes.get(),
                            "the app's own resume must reach this interceptor, or the zero above is the " +
                                "glob's silence rather than the register-only rule",
                        )
                    }
                }
            }
        }
    }


    @Test
    fun aSecondLifecycleActionIsRefusedOutLoudRatherThanDroppedSilently() {
        val stalled = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        try {
            Harness(SESSIONS_SCENARIO).use { harness ->
                onChromium { browser ->
                    browser.newContext().use { context ->
                        context.traced("second-lifecycle-action") {
                            val page = signIn(context, harness)
                            page.route("**/interrupt") { route ->
                                route.resume(
                                    Route.ResumeOptions()
                                        .setUrl("http://127.0.0.1:${stalled.localPort}/interrupt"),
                                )
                            }

                            page.locator("#session-list .session-row[aria-label\$=', running']").click()

                            runFromPalette(page, "Interrupt current session")
                            assertThat(page.locator("#status-line")).containsText("Interrupt in progress")

                            openPaletteSearch(page, "current")
                            for (title in listOf(
                                "Interrupt current session",
                                "Stop current session",
                                "Done current session",
                            )) {
                                val option = page.locator(
                                    "#command-palette-results li",
                                    Page.LocatorOptions().setHasText(title),
                                )
                                assertThat(option).hasCount(1)
                                assertThat(option).hasAttribute("aria-disabled", "true")
                                assertThat(option.locator(".command-palette-disabled-reason"))
                                    .hasText(PENDING_REASON)
                            }
                            assertThat(
                                page.locator(
                                    "#command-palette-results .command-palette-disabled-reason",
                                    Page.LocatorOptions().setHasText(PENDING_REASON),
                                ),
                            ).hasCount(3)

                            page.locator("#command-palette-close").click()
                            assertThat(page.locator("#command-palette")).hasCount(0)

                            runFromPalette(page, "Resume a conversation started outside kotgent")
                            assertThat(page.locator("#new-session-mode-import"))
                                .hasAttribute("aria-pressed", "true")
                            agentCard(page, "codex").click()
                            page.locator("#session-provider-id").fill(IMPORTED_PROVIDER_ID)
                            page.locator("#new-session-submit").click()

                            assertThat(page.locator("#new-session-error")).hasText(APP_PENDING_REASON)
                            assertThat(page.locator("#new-session-dialog")).isVisible()
                        }
                    }
                }
            }
        } finally {
            stalled.close()
        }
    }


    @Test
    fun preferencesOpensAndPreviewsTheGroupingItWouldSave() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("preferences-dialog") {
                        val page = signIn(context, harness)
                        val saves = AtomicInteger(0)
                        page.route("**$API/preferences") { route ->
                            if (route.request().method() == "PUT") saves.incrementAndGet()
                            route.resume()
                        }

                        runFromPalette(page, "Preferences")
                        assertThat(page.locator("#prefs-dialog")).isVisible()
                        assertThat(page.locator("#prefs-form"))
                            .containsText("shared by every browser connected to this daemon")

                        val basePath = page.locator("#prefs-base-path")
                        assertThat(basePath).isVisible()
                        assertThat(basePath).hasValue("")
                        val preview = page.locator("#prefs-grouping-preview")
                        assertThat(preview).containsText("No base path")

                        basePath.fill("/a")
                        page.locator("#prefs-grouping-level").selectOption("1")
                        assertThat(preview).containsText("/a/")
                        assertThat(preview).not().containsText("deeper folders")

                        page.locator("#prefs-grouping-level").selectOption("0")
                        assertThat(preview).containsText("deeper folders stay here")

                        assertThat(page.locator("#prefs-terminal-font-size")).isVisible()
                        assertThat(page.locator("#prefs-terminal-unicode")).isVisible()
                        assertThat(page.locator("#prefs-terminal-unicode-hint")).isVisible()
                        assertEquals(
                            3,
                            page.locator("#prefs-terminal-font-size option").count(),
                            "the three offered terminal sizes",
                        )

                        page.locator("#prefs-cancel").click()
                        assertThat(page.locator("#prefs-dialog")).hasCount(0)
                        assertEquals(0, saves.get(), "leaving the screen commits nothing")

                        runFromPalette(page, "Preferences")
                        assertThat(page.locator("#prefs-dialog")).isVisible()
                        page.locator("#prefs-submit").click()
                        assertThat(page.locator("#prefs-dialog")).hasCount(0)
                        assertEquals(1, saves.get(), "the same form's submit is one PUT through that glob")
                    }
                }
            }
        }
    }

    @Test
    fun helpOpensAndDocumentsEveryStateAndControl() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("help-dialog") {
                        val page = signIn(context, harness)

                        val row = page.locator("#session-list .session-row[data-id='$BADGE_SESSION']")
                        assertThat(row).hasCount(1)
                        val badge = row.locator(".badge")
                        assertThat(badge).hasCount(1)
                        var previous: String? = null
                        val painted = SESSION_STATES.map { state ->
                            harness.send("emit $BADGE_SESSION $state")
                            previous?.let { page.waitForFunction(BADGE_CHANGED, listOf(BADGE_SESSION, it)) }
                            val label = badge.textContent().trim()
                            previous = label
                            label to badge.getAttribute("class").orEmpty()
                        }

                        runFromPalette(page, "Help")
                        assertThat(page.locator("#help-dialog")).isVisible()
                        assertThat(page.locator("#help-body")).isVisible()

                        val documented = page.locator("#help-body .badge")
                        assertThat(documented).hasCount(painted.size)
                        assertEquals(
                            painted,
                            documented.all().map {
                                it.textContent().trim() to it.getAttribute("class").orEmpty()
                            },
                            "Help's state list must be the vocabulary the sidebar actually paints, in the " +
                                "order the reducer declares — label AND badge class",
                        )

                        val controls = page.locator(
                            "#help-body .help-section",
                            Page.LocatorOptions().setHasText("Controls"),
                        )
                        assertThat(controls).hasCount(1)
                        for (control in listOf(
                            "New session", "Import", "Attach", "Interrupt", "Resume", "Stop", "Detach",
                        )) {
                            assertThat(
                                controls.locator("dt", Locator.LocatorOptions().setHasText(control)),
                            ).hasCount(1)
                        }

                        val tmux = page.locator("#help-tmux")
                        assertThat(tmux).isVisible()
                        assertThat(tmux).containsText("Ctrl")
                        assertThat(tmux).containsText("copy mode")
                        assertThat(tmux.locator("kbd", Locator.LocatorOptions().setHasText("Option")))
                            .hasCount(1)

                        val cli = page.locator("#help-body .help-code")
                        assertThat(cli).hasCount(1)
                        assertThat(cli).containsText("kotgent import <agent> <id>")

                        page.locator("#help-done").click()
                        assertThat(page.locator("#help-dialog")).hasCount(0)
                    }
                }
            }
        }
    }

    @Test
    fun phoneAccessDrawsAQrThatCannotSpendTheDisplayedCode() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("phone-dialog") {
                        val page = signIn(context, harness)
                        page.route("**/auth/ticket") { route ->
                            route.fulfill(
                                Route.FulfillOptions()
                                    .setStatus(200)
                                    .setContentType("application/json")
                                    .setBody(
                                        """
                                        {"ticket":"$PHONE_CODE",
                                         "localUrl":"${harness.baseUrl}/auth#ticket=$PHONE_CODE",
                                         "publicUrl":"$PUBLIC_AUTH_URL#ticket=$PHONE_CODE",
                                         "expiresAt":1700000300000}
                                        """.trimIndent(),
                                    ),
                            )
                        }

                        runFromPalette(page, "Sign in from your phone")
                        assertThat(page.locator("#phone-dialog")).isVisible()

                        assertThat(page.locator("#phone-qr svg")).hasCount(1)
                        assertThat(page.locator(".phone-url code")).hasText(PUBLIC_AUTH_URL)
                        assertThat(page.locator("#phone-code")).hasText("AB2C 3D4E")
                        page.locator("#phone-close").click()
                        assertThat(page.locator("#phone-dialog")).hasCount(0)

                        page.route("**/auth/ticket") { route ->
                            route.fulfill(
                                Route.FulfillOptions()
                                    .setStatus(200)
                                    .setContentType("application/json")
                                    .setBody(
                                        """
                                        {"ticket":"$PHONE_CODE",
                                         "localUrl":"${harness.baseUrl}/auth#ticket=$PHONE_CODE",
                                         "publicUrl":null,
                                         "expiresAt":1700000300000}
                                        """.trimIndent(),
                                    ),
                            )
                        }
                        runFromPalette(page, "Sign in from your phone")
                        assertThat(page.locator("#phone-setup")).isVisible()
                        assertThat(page.locator("#phone-dialog"))
                            .containsText("http://127.0.0.1:${harness.port}")
                        assertThat(page.locator("#phone-qr")).hasCount(0)
                    }
                }
            }
        }
    }


    private fun signIn(context: BrowserContext, harness: Harness): Page {
        context.loginWithTicket(harness.ticket, harness.baseUrl)
        val page = context.newPage()
        page.navigate(harness.baseUrl + "/")
        assertThat(page.locator("#sidebar")).isVisible()
        return page
    }

    private fun openNewSession(page: Page) {
        page.locator("#empty-new-session-button").click()
        assertThat(page.locator("#new-session-dialog")).isVisible()
    }

    private fun agentCard(page: Page, value: String): Locator =
        page.locator("label.agent-option:has(#session-agent-$value)")

    private fun fillWorkingDirectory(page: Page, path: String) {
        page.locator("#session-cwd").fill(path)
        page.keyboard().press("Tab")
        assertThat(page.locator("#session-cwd-options")).hasCount(0)
    }

    private fun runFromPalette(page: Page, title: String) {
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator("#command-palette")).isVisible()
        page.locator(".command-palette-leader-command")
            .filter(Locator.FilterOptions().setHasText(title))
            .first()
            .click()
        assertThat(page.locator("#command-palette")).hasCount(0)
    }

    private fun openPaletteSearch(page: Page, query: String) {
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator("#command-palette")).isVisible()
        page.locator("#command-palette-search-mode").click()
        page.locator("#command-palette-query").fill(query)
    }

    private fun countStartRequests(page: Page): AtomicInteger {
        val starts = AtomicInteger(0)
        page.route("**$API/sessions") { route ->
            if (route.request().method() != "POST") {
                route.resume()
                return@route
            }
            starts.incrementAndGet()
            route.fulfill(
                Route.FulfillOptions().setStatus(500).setBody("no start was expected in this test"),
            )
        }
        return starts
    }

    private fun sessionDto(
        id: String,
        name: String,
        agent: String,
        cwd: String,
        state: String,
        providerSessionId: String,
    ): String = """
        {"id":"$id","name":"$name","tags":[],"agent":"$agent","model":null,"cliVersion":null,
         "cliPath":null,"providerSessionId":"$providerSessionId","state":"$state",
         "needsAttention":false,"alive":false,"cwd":"$cwd","tmuxSession":"kt-$id","paneId":null,
         "lastSeq":1,"readCursor":1,"unread":0,"createdAt":1700000000000,"updatedAt":1700000000000,
         "archived":false,"rev":1,"taskRef":null,"projectId":null}
    """.trimIndent()

    private companion object {
        const val API = "/api/v1"

        const val AGENT_RADIOS = "#new-session-form input[name='session-agent']"

        const val BADGE_SESSION = "s-alpha"

        val SESSION_STATES = listOf(
            "running", "ready", "needs_approval", "needs_answer", "stopped", "crashed", "resumable",
        )

        val BADGE_CHANGED = """
            ([id, previous]) => {
              const el = document.querySelector(
                '#session-list .session-row[data-id="' + id + '"] .badge',
              );
              return !!el && el.textContent.trim() !== previous;
            }
        """.trimIndent()

        val RADIO_PAINT = """
            el => {
              const r = el.getBoundingClientRect();
              const s = getComputedStyle(el);
              if (r.width !== 0 || r.height !== 0) return "painted " + r.width + "x" + r.height;
              if (s.display === "none") return "display:none";
              if (s.visibility === "hidden") return "visibility:hidden";
              return "zero-sized and still laid out";
            }
        """.trimIndent()

        const val MISSING_BINARY_BODY =
            "cannot start session: agent 'claude' not found on the daemon's PATH — run `kotgent install` " +
                "from a shell where `claude` is on your PATH (install `claude` first if needed), then " +
                "create the session again"

        const val IMPORTED_ID = "imp-1"
        const val IMPORTED_NAME = "adopted-codex"
        const val IMPORTED_PROVIDER_ID = "5f2b1d64-2c8a-4d21-9f0e-7a63c1d4b8e2"

        const val PENDING_REASON = "another action is still in progress"
        const val APP_PENDING_REASON = "Another action is still in progress — try again in a moment."

        const val PHONE_CODE = "AB2C3D4E"
        const val PUBLIC_AUTH_URL = "https://kotgent.example.com/auth"
    }
}
