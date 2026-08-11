package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The modal screens and the actions they take: New session (both modes), Preferences, Help and Phone
 * access, plus the one-action-at-a-time rule that governs every lifecycle verb.
 *
 * This file is the browser-tier replacement for nine `WebUiServingTest` greps that could only read the
 * SPA's source text. Their subjects survive here as behaviour, which is the whole point of the move:
 * "the radios stay focusable" was a grep for the ABSENCE of `display: none` in a stylesheet, and is now
 * a keyboard walking the arrow-key group; "the refusal carries a reason" was a grep for a string
 * literal, and is now the sentence an operator actually reads after submitting.
 *
 * ## Where a response is shaped rather than produced
 * Three cases need a daemon answer the harness does not (and should not) seed — a missing agent binary,
 * a registered import, a minted phone ticket. Those are `page.route`d, and every such assertion is about
 * **the UI's handling of that answer**, never about the daemon's behaviour: the Kotlin tier already owns
 * the daemon's side (`AgentBinaryNotFoundException` → 400 + hint, the import route's 400/409 vocabulary,
 * `POST /auth/ticket`'s body). What is untested anywhere else, and is tested here, is whether the
 * operator ever SEES it.
 *
 * ## Why a desktop context and not [touchContext]
 * Nothing here is a gesture: `DialogDismissTest` owns the pointer contract of the `Dialog` wrapper. A
 * phone-shaped viewport would additionally hide the sidebar behind its drawer, which is furniture for
 * these tests, not their subject.
 */
class SessionDialogsTest {

    // --- New session: the agent picker ---------------------------------------------------------------

    /**
     * There is deliberately no default agent, so the picker — not the prefilled path — is the first
     * answer this dialog needs, and choosing one is a single click on a card.
     *
     * Focus is the load-bearing half: it lands on the first agent that can actually be STARTED
     * (`FIRST_AVAILABLE_AGENT`), not merely on the first card, because a planned agent's radio is
     * disabled and could not take it. And the group announces the missing answer while it is missing —
     * `aria-describedby` points at the hint only while no agent is chosen, so a screen reader stops
     * repeating a requirement that has been met.
     */
    @Test
    fun theAgentPickerTakesTheFirstFocusAndOneClickAnswersIt() {
        Harness(EMPTY).use { harness ->
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

                        // One click on the card, not a menu to open first.
                        agentCard(page, "codex").click()
                        val chosen = page.locator(AGENT_RADIOS + ":checked")
                        assertThat(chosen).hasCount(1)
                        assertThat(chosen).hasAttribute("id", "session-agent-codex")

                        // Answered: the hint goes, and with it the description that pointed at it.
                        assertThat(page.locator("#new-session-agent-hint")).hasCount(0)
                        assertNull(
                            page.locator(".agent-picker").getAttribute("aria-describedby"),
                            "an answered choice is not still described as missing",
                        )

                        // The choice is also VISIBLE, and that is asserted as two states of the same
                        // component rather than as a colour literal: the chosen card and an unchosen one
                        // must not paint the same. Polled, because the card transitions into its
                        // selected background over 120ms.
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

    /**
     * The radios paint nothing and are still the keyboard's own control.
     *
     * `.agent-option input` zeroes the box the inherited `.field input` rule would otherwise give it —
     * width, height, border — and hides it with `opacity`. Removing it instead (`display: none`,
     * `visibility: hidden`) would also remove it from the tab order, the arrow-key group and the
     * `:focus-visible` outline, and nothing on screen would look any different. So the invariant is
     * exactly this: a zero-sized box that Space still answers and the arrow keys still walk — skipping
     * the planned agent, because its radio is `disabled`.
     */
    @Test
    fun theHiddenRadiosStayInTheKeyboardsArrowGroup() {
        Harness(EMPTY).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-radio-keyboard") {
                        val page = signIn(context, harness)
                        openNewSession(page)

                        assertEquals(
                            true,
                            page.locator("#session-agent-claude").evaluate(
                                "el => { const r = el.getBoundingClientRect();" +
                                    " return r.width === 0 && r.height === 0; }",
                            ),
                            "the radio paints nothing at all — the card behind it is the visible control",
                        )

                        // The dialog put the focus here; Space answers on the spot, without moving.
                        assertThat(page.locator("#session-agent-claude")).isFocused()
                        page.keyboard().press("Space")
                        assertThat(page.locator(AGENT_RADIOS + ":checked"))
                            .hasAttribute("id", "session-agent-claude")

                        // …and the arrows walk the group, wrapping over the four that can be started.
                        // Cursor is never among them: a disabled radio leaves the group entirely.
                        for (expected in listOf("codex", "junie", "shell", "claude")) {
                            page.keyboard().press("ArrowDown")
                            val focused = page.locator(AGENT_RADIOS + ":checked")
                            assertThat(focused).hasAttribute("id", "session-agent-$expected")
                            assertThat(page.locator("#session-agent-$expected")).isFocused()
                        }
                        assertThat(page.locator("#session-agent-cursor")).not().isChecked()
                    }
                }
            }
        }
    }

    /**
     * A planned agent is announced and refuses to be chosen.
     *
     * The card is on screen with its "Soon" note — that is the announcement — while its radio is
     * `disabled`, which is what keeps it out of the tab order and the arrow-key group above. Without
     * the flag the card would take a selection the daemon's `agentFactoryOf` then rejects with a 400,
     * i.e. an offer that can only end in a failed start.
     *
     * The tap is forced on purpose. Playwright resolves a `<label>`'s enabled-ness through the control it
     * labels, so an ordinary `click()` here waits out its whole timeout for the very `disabled` this test
     * exists to assert — the wait would be Playwright agreeing with the test and then failing it. Forcing
     * skips only that agreement: the click is really dispatched, at the card's centre, and what follows
     * measures what the BROWSER did with it. The disabled-ness itself is asserted directly, above, rather
     * than inferred from an actionability timeout.
     */
    @Test
    fun aPlannedAgentIsAnnouncedWithoutBecomingChoosable() {
        Harness(EMPTY).use { harness ->
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

                        // A click on the card selects nothing at all — not the planned agent, and not
                        // some neighbouring card either.
                        card.click(Locator.ClickOptions().setForce(true))
                        assertThat(page.locator("#session-agent-cursor")).not().isChecked()
                        assertThat(page.locator(AGENT_RADIOS + ":checked")).hasCount(0)
                        assertThat(page.locator("#new-session-agent-hint")).isVisible()
                    }
                }
            }
        }
    }

    /**
     * Submitting with no agent chosen says so, out loud, and hands the choice back.
     *
     * One mechanism owns that requirement, because the two obvious ones cannot work here: a `disabled`
     * submit swallows both the click and Enter with no feedback at all, and native `required` would
     * anchor its validation bubble on a radio the stylesheet renders at `opacity: 0` — a message
     * pointing at nothing. So the dialog reports it itself, through a `role="alert"` line, and returns
     * focus to the picker. The request must not leave the browser: that is what "silently refusing to
     * start" would look like from the daemon's side.
     */
    @Test
    fun submittingWithNoAgentIsReportedInsteadOfQuietlyDoingNothing() {
        Harness(EMPTY).use { harness ->
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

                        // Answering the question clears its complaint.
                        agentCard(page, "claude").click()
                        assertThat(page.locator("#new-session-error")).hasCount(0)
                    }
                }
            }
        }
    }

    // --- New session: the working directory -----------------------------------------------------------

    /**
     * The working-directory field completes against the DAEMON's filesystem, not the browser's — a phone
     * has no `/a/b` of its own, which is the reason this endpoint exists at all.
     *
     * The keyboard contract is the one worth pinning: the list is a `role="listbox"` the input owns
     * through `aria-activedescendant` (focus never leaves the combobox), the arrows move that
     * descendant, and Enter commits it. The chosen path is read back off the active option rather than
     * hard-coded, so the assertion is "Enter commits what the arrow selected" and stays true whatever
     * the harness's fixed tree offers first.
     */
    @Test
    fun theWorkingDirectoryCompletesFromTheDaemonAndCommitsWithTheKeyboard() {
        Harness(EMPTY).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("new-session-cwd-completion") {
                        val page = signIn(context, harness)
                        openNewSession(page)

                        val cwd = page.locator("#session-cwd")
                        cwd.fill("/a/")

                        assertThat(page.locator("#session-cwd-options")).isVisible()
                        for (path in listOf("/a/b", "/a/c")) {
                            assertThat(
                                page.locator(
                                    "#session-cwd-options li",
                                    Page.LocatorOptions().setHasText(path),
                                ),
                            ).hasCount(1)
                        }
                        assertThat(cwd).hasAttribute("aria-expanded", "true")

                        page.keyboard().press("ArrowDown")
                        assertThat(cwd).hasAttribute("aria-activedescendant", "session-cwd-option-0")
                        val active = page.locator("#session-cwd-options li.active").textContent().trim()

                        page.keyboard().press("Enter")
                        assertThat(cwd).hasValue(active)
                        // Committing closes the list: selecting is not another typing event.
                        assertThat(page.locator("#session-cwd-options")).hasCount(0)
                        assertThat(cwd).isFocused()
                        // …and Enter on the list did not also submit the form around it.
                        assertThat(page.locator("#new-session-dialog")).isVisible()
                    }
                }
            }
        }
    }

    // --- New session: a start the daemon refuses --------------------------------------------------------

    /**
     * A missing agent binary reaches the operator as the daemon's own advice.
     *
     * The daemon fails this fast, before any tmux side effect, and answers 400 with the sentence that
     * names the fix (`kotgent install` from a shell where the agent is on the PATH) — that half is the
     * Kotlin tier's. What this test is about is the browser end of it: the sentence is shown in the
     * form's own error line, the dialog stays open with the draft intact, and the submit button comes
     * back — a refusal the operator can act on, rather than a click that appeared to do nothing.
     *
     * The response is shaped with `page.route` because the harness runs on fakes and would answer
     * something else entirely; the text is the real `AgentBinaryNotFoundException` message under the
     * start route's own prefix.
     */
    @Test
    fun aMissingAgentBinaryIsShownAsTheDaemonsOwnAdvice() {
        Harness(EMPTY).use { harness ->
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
                        assertThat(error).containsText("not found on the daemon's PATH")
                        assertThat(error).containsText("kotgent install")
                        assertThat(error).hasAttribute("role", "alert")

                        // The draft survives the refusal, and the form is usable again.
                        assertThat(page.locator("#new-session-dialog")).isVisible()
                        assertThat(page.locator("#session-cwd")).hasValue("/a/b")
                        assertThat(page.locator("#new-session-submit")).isEnabled()
                        assertThat(page.locator("#new-session-submit")).hasText("Start session")
                    }
                }
            }
        }
    }

    // --- New session: import mode ------------------------------------------------------------------------

    /**
     * Import mode adopts a conversation started outside kotgent, and never offers Shell.
     *
     * Shell is the one kind with nothing to adopt — a shell session has no provider transcript and no
     * provider id — so its card is filtered out of the picker in this mode rather than shown and then
     * refused. The working directory becomes optional here for the opposite reason: the daemon
     * discovers it from the provider's own records, and a non-empty value is an explicit OVERRIDE of
     * that discovery.
     *
     * Registration is not a launch: with "register only" ticked the flow stops after
     * `POST /sessions/import`, so the assertion includes an endpoint that must NOT be called. The two
     * daemon answers are shaped because the harness cannot mint a real provider transcript; the Kotlin
     * tier owns what the import route does, and this owns what the browser does with its answer.
     */
    @Test
    fun importModeAdoptsASessionAndOffersNoShell() {
        Harness(EMPTY).use { harness ->
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

                        // There is no outside conversation to adopt for a shell, so the card is absent
                        // — while every kind that HAS a transcript is still offered, planned ones
                        // included (announced, disabled).
                        assertThat(page.locator("#session-agent-shell")).hasCount(0)
                        for (kind in listOf("claude", "codex", "junie", "cursor")) {
                            assertThat(page.locator("#session-agent-$kind")).hasCount(1)
                        }
                        assertThat(page.locator("#session-agent-cursor")).isDisabled()

                        // The id is what an import is addressed to; the directory is discovery's job.
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

                        // The dialog is gone, the adopted row is in the sidebar, and the app selected it.
                        assertThat(page.locator("#new-session-dialog")).hasCount(0)
                        val adopted = page.locator("#session-list .session-row")
                        assertThat(adopted).hasCount(1)
                        assertThat(adopted).hasAttribute("aria-label", "Open $IMPORTED_NAME, resumable")
                        assertThat(page).hasURL(harness.baseUrl + "/s/" + IMPORTED_ID)
                        assertThat(page.locator("#status-line")).containsText("Imported $IMPORTED_NAME")

                        // What actually went out: the provider id under the route's own field name, and
                        // no directory at all — an empty cwd must not be sent as an override.
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
                    }
                }
            }
        }
    }

    // --- one action at a time ----------------------------------------------------------------------------

    /**
     * A second lifecycle action is refused out loud rather than dropped silently.
     *
     * `app.js` serialises every lifecycle verb through one `pendingAction` and refuses a second one. The
     * palette closes before a command even runs and owns no live region that outlives it, so a silently
     * dropped action read exactly like a dead chord — hence both halves pinned here:
     *
     *  - the registry turns the in-flight action into a first-class DISABLED REASON, printed on the row
     *    the operator is looking at;
     *  - the app-owned entry points state the refusal where the operator is: the import flow raises it
     *    into the New session dialog's own error line.
     *
     * The reason comes in two strengths, and the difference is the test's other half. The four commands
     * that reach `controlSession` are refused by ANY action in flight. Attach and Detach are local state
     * writes nothing can drop, so they are refused only by an action that will itself rewrite the
     * attachment — blocking a Detach during a pending Interrupt was a real over-block that stranded the
     * operator on a terminal they had asked to leave.
     *
     * Holding an action in flight is done at the network level, not with an unresolved route handler: the
     * interrupt is redirected to a socket that accepts the connection and then says nothing — the same
     * shape as the orphaned daemon socket `ApiClient`'s timeouts exist for. The request therefore stays
     * pending for as long as the test needs, with no dependence on how the driver treats a handler that
     * never answers.
     */
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

                            // The one running session in this scenario; named by its state so the
                            // assertion does not depend on how the fixture labels its rows.
                            page.locator("#session-list .session-row[aria-label\$=', running']").click()

                            runFromPalette(page, "Interrupt current session")
                            assertThat(page.locator("#status-line")).containsText("Interrupt in progress")

                            // Every command that reaches controlSession now says why it cannot run,
                            // on the row the operator is looking at.
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
                            // …and ONLY those three. This query also lists Attach, Detach and Upload,
                            // and none of them may carry the reason: they are local state writes that
                            // nothing can drop, refused only by an action that will itself rewrite the
                            // attachment. A pending Interrupt never does — over-blocking here once left
                            // the operator unable to leave a terminal they had asked to leave.
                            assertThat(
                                page.locator(
                                    "#command-palette-results .command-palette-disabled-reason",
                                    Page.LocatorOptions().setHasText(PENDING_REASON),
                                ),
                            ).hasCount(3)

                            // The × rather than Esc: the search field is a `type="search"` input, and
                            // the platform spends the first Esc on clearing it.
                            page.locator("#command-palette-close").click()
                            assertThat(page.locator("#command-palette")).hasCount(0)

                            // The app-owned half: the import flow takes the same slot, and refuses into
                            // the dialog the operator is standing in rather than returning in silence.
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

    // --- the three device/daemon screens -------------------------------------------------------------------

    /**
     * Preferences opens from the palette and describes what the grouping it is about to save would do.
     *
     * The preview is the screen's whole reason to exist: base path and tree depth are daemon-wide, so a
     * mistake here re-groups every browser connected to this daemon. It is computed against a REAL
     * session's working directory when one matches, which is why this runs on the seeded scenario rather
     * than the empty one — a preview built from placeholders would not prove it reads the list at all.
     *
     * Cancel is the other invariant: the two per-device selects (terminal font, unicode width) live in
     * the same form as the daemon-wide pair, and leaving must commit neither.
     */
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

                        // A base path the seeded sessions live under: the preview names a real one
                        // rather than the placeholder tree it draws when nothing matches.
                        basePath.fill("/a")
                        page.locator("#prefs-grouping-level").selectOption("1")
                        assertThat(preview).containsText("/a/")
                        assertThat(preview).not().containsText("deeper folders")

                        // Depth is the second half of the same answer, so it must move the preview too:
                        // at level 0 the sample's own folder no longer has a row of its own, and the
                        // preview says where it ends up instead.
                        page.locator("#prefs-grouping-level").selectOption("0")
                        assertThat(preview).containsText("deeper folders stay here")

                        // The per-device pair is on the same form.
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
                    }
                }
            }
        }
    }

    /**
     * Help opens and documents every state and control it claims to.
     *
     * These lists are the operator's only explanation of a vocabulary that is otherwise implicit in
     * badges and buttons, and they are written out by hand in `dialogs.js` — so a state renamed in the
     * reducer, or a control renamed in the palette, must fail here rather than quietly ship a wrong
     * explanation. The badges are read in order, which also pins that the alive states lead.
     */
    @Test
    fun helpOpensAndDocumentsEveryStateAndControl() {
        Harness(EMPTY).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("help-dialog") {
                        val page = signIn(context, harness)
                        runFromPalette(page, "Help")

                        assertThat(page.locator("#help-dialog")).isVisible()
                        assertThat(page.locator("#help-body")).isVisible()

                        assertThat(page.locator("#help-body .badge")).hasText(
                            arrayOf(
                                "running", "ready", "needs approval", "needs answer",
                                "stopped", "crashed", "resumable",
                            ),
                        )
                        // Scoped to the Controls section: `hasText` matches case-insensitive substrings,
                        // and the states above it would answer "Stop" with their own "stopped" badge.
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

                        // tmux's own vocabulary, which kotgent's isolated server does not let the
                        // operator rebind: the prefix and the two copy gestures.
                        val tmux = page.locator("#help-tmux")
                        assertThat(tmux).isVisible()
                        assertThat(tmux).containsText("Ctrl")
                        assertThat(tmux).containsText("copy mode")
                        assertThat(tmux.locator("kbd", Locator.LocatorOptions().setHasText("Option")))
                            .hasCount(1)

                        // The same operations from a terminal.
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

    /**
     * Phone access draws a QR for a page that cannot spend the code it displays.
     *
     * An installed iOS PWA has its own cookie jar, so the code the QR would spend in Safari is exactly
     * the one the installed app still needs. The QR therefore points at the credential-free public
     * `/auth` page — the fragment is stripped — and the code is shown separately, grouped in halves the
     * way a human reads eight symbols off a screen.
     *
     * Both branches are exercised, because the second is what a fresh install actually meets: with no
     * public origin configured the daemon answers `publicUrl: null`, and the dialog explains the tunnel
     * setup — naming THIS daemon's port — instead of drawing a QR nothing could reach. The ticket
     * response is shaped so both branches are reachable from one harness; minting itself is the Kotlin
     * tier's subject.
     */
    @Test
    fun phoneAccessDrawsAQrThatCannotSpendTheDisplayedCode() {
        Harness(EMPTY).use { harness ->
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

                        // Drawn, not described: the generator is vendored ESM this suite cannot run
                        // anywhere else.
                        assertThat(page.locator("#phone-qr svg")).hasCount(1)
                        // The scanned address stops before the fragment — that is the whole rule.
                        assertThat(page.locator(".phone-url code")).hasText(PUBLIC_AUTH_URL)
                        assertThat(page.locator("#phone-code")).hasText("AB2C 3D4E")
                        page.locator("#phone-close").click()
                        assertThat(page.locator("#phone-dialog")).hasCount(0)

                        // No tunnel configured: the later handler wins, and the dialog explains the
                        // one-time setup against this daemon's own port rather than drawing a dead QR.
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

    // --- fixtures ------------------------------------------------------------------------------------------

    private fun onChromium(block: (Browser) -> Unit) {
        Playwright.create().use { pw ->
            touchChromium(pw).use { browser -> block(browser) }
        }
    }

    /** Sign this context in and open the app; every test starts from a rendered shell. */
    private fun signIn(context: BrowserContext, harness: Harness): Page {
        context.loginWithTicket(harness.ticket, harness.baseUrl)
        val page = context.newPage()
        page.navigate(harness.baseUrl + "/")
        assertThat(page.locator("#sidebar")).isVisible()
        return page
    }

    /**
     * Open the New session dialog from the first-run panel — the one entry point that needs no palette,
     * which keeps these tests independent of the chord contract `CommandPaletteTest` owns.
     */
    private fun openNewSession(page: Page) {
        page.locator("#empty-new-session-button").click()
        assertThat(page.locator("#new-session-dialog")).isVisible()
    }

    /** The visible card for an agent: the radio itself is `pointer-events: none` and behind it. */
    private fun agentCard(page: Page, value: String): Locator =
        page.locator("label.agent-option:has(#session-agent-$value)")

    /**
     * Type a working directory and leave the field.
     *
     * The blur matters: the field is a combobox, so typing opens a suggestion list positioned over the
     * fields below it. Leaving the field closes the list, which is what a person does before pressing a
     * button anyway.
     */
    private fun fillWorkingDirectory(page: Page, path: String) {
        page.locator("#session-cwd").fill(path)
        page.keyboard().press("Tab")
        assertThat(page.locator("#session-cwd-options")).hasCount(0)
    }

    /** Open the palette's leader grid and run one command by its title. */
    private fun runFromPalette(page: Page, title: String) {
        page.keyboard().press("Meta+k")
        assertThat(page.locator("#command-palette")).isVisible()
        page.locator(".command-palette-leader-command")
            .filter(Locator.FilterOptions().setHasText(title))
            .first()
            .click()
        assertThat(page.locator("#command-palette")).hasCount(0)
    }

    /** Open the palette's search view and type [query] into it. */
    private fun openPaletteSearch(page: Page, query: String) {
        page.keyboard().press("Meta+k")
        assertThat(page.locator("#command-palette")).isVisible()
        page.locator("#command-palette-search-mode").click()
        page.locator("#command-palette-query").fill(query)
    }

    /**
     * Count the launches that leave the browser, answering any that do with a refusal.
     *
     * A test that expects NO start has to prove it against the wire, not against a screenshot: the
     * silent failure this guards is a form that posts and then reports nothing.
     */
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

    /** A committed session row, in the shape `SessionDto` serialises. */
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
        /** The scenario with no sessions: its first-run panel is this file's New-session opener. */
        const val EMPTY = "empty"

        /** Where every client-facing daemon route lives (`API_PREFIX` in `Server.kt`). */
        const val API = "/api/v1"

        const val AGENT_RADIOS = "#new-session-form input[name='session-agent']"

        /** `AgentBinaryNotFoundException` under the start route's prefix, verbatim. */
        const val MISSING_BINARY_BODY =
            "cannot start session: agent 'claude' not found on the daemon's PATH — run `kotgent install` " +
                "from a shell where `claude` is on your PATH (install `claude` first if needed), then " +
                "create the session again"

        const val IMPORTED_ID = "imp-1"
        const val IMPORTED_NAME = "adopted-codex"
        const val IMPORTED_PROVIDER_ID = "5f2b1d64-2c8a-4d21-9f0e-7a63c1d4b8e2"

        /** The registry's reason, printed on the row; and the app's own, raised into a dialog. */
        const val PENDING_REASON = "another action is still in progress"
        const val APP_PENDING_REASON = "Another action is still in progress — try again in a moment."

        const val PHONE_CODE = "AB2C3D4E"
        const val PUBLIC_AUTH_URL = "https://kotgent.example.com/auth"
    }
}
