package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The task commands in the Web UI's ONE command registry, driven from a real browser: the board and its
 * tasks are reachable from the palette, a chord actually EXECUTES and its effect lands in the DOM, and
 * the registry's two screen layers do not collide.
 *
 * `resources/webui/lib/commands.js` is the single registry of commands and leader mnemonics. The tests
 * this file replaces asserted that as a property of the module's SOURCE TEXT — the file was read off
 * disk and its descriptors matched with regexes — because there is no JavaScript test harness and a
 * second list anywhere would be the defect they existed to catch. A live browser can do better on every
 * one of those claims except one, and the trade is written out at the bottom of this comment.
 *
 * **The registry is SCREEN-AWARE, and that is the whole shape of this file.** `/tasks` REPLACES the
 * session view, so which commands exist depends on which screen the router has put on. While the board
 * was up the registry still offered nine commands aimed at a session nobody could see: ⌘K a wrote
 * `attachedId` with no `TerminalPane` mounted (visibly nothing at all happened), ⌘K e announced a detach
 * from a terminal that was not on screen, and Interrupt/Stop/Done acted on whatever row was selected
 * before the operator left for the backlog. The group is therefore BUILT only for the session view
 * rather than each descriptor growing a disabled reason — a disabled row is for a command that could
 * apply here and does not right now, and none of these applies at all. `general.show-done` is dropped
 * the same way, because the sidebar it toggles is precisely what the board screen unmounts. Session
 * ROWS stay on both screens: selecting one navigates to `/s/{id}`, so on the board the search view is
 * also the way back to a particular session.
 *
 * `general.task-board` is ONE mnemonic for "the other screen". Leaving `o` pointing at the board while
 * the board is on screen spends the letter on a navigation that has already happened, and the board's
 * own way out would then be the only one — which on a phone means hunting for a link instead of the
 * palette every other action lives in. Reusing the letter rather than adding a second one is not only
 * economy: `leaderKeyDown` resolves a letter FIRST-MATCH-WINS over the whole list, so two descriptors
 * claiming `o` would make one of them a visible grid row its own key can never reach. That is why
 * [everyMnemonicTheGridDrawsIsUniqueAndKIsLeftToTheWayBackToSearch] reads the letters the grid actually
 * DRAWS and refuses a duplicate — and why no command may claim `k`, which the palette answers with
 * "switch to search" before it consults the registry at all.
 *
 * What the browser cannot see, and what replaced it: the old suite also asserted that the registry stays
 * a registry — that no run body reaches `routePath(` / `navigate(` / `history.` / `location.`, which
 * would make `lib/commands.js` a second holder of app state. A running page cannot tell where a closure
 * was written. Its observable counterpart is here instead, and it is the consequence that actually
 * matters: every trip these commands make is a `history.pushState` through app.js's own router, so the
 * page never reloads and Back walks it backwards ([theBoardIsReachableFromThePaletteAndTheSameLetterLeadsBackOut]
 * plants a witness on `window` and finds it alive at the end). The other half — that app.js really
 * supplies the five callbacks — is proven by exercising all five: `openBoard`, `openSessions`, `newTask`,
 * `newProject` and `openSessionTask` each run from the palette here and each is observed by its effect.
 */
class TaskCommandsTest {

    /**
     * The nine commands that act on the session the operator is LOOKING at, by the title the leader grid
     * draws. Matched as case-insensitive substrings, which stays unambiguous: "Attach current terminal"
     * is not a substring of "Detach current terminal", and the chordless "Show or hide done sessions"
     * never reaches the grid at all.
     */
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
        onScenario("board", "board-round-trip") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()
            // The witness for "the app never reloaded". It survives a `pushState` and every `popstate`
            // that follows, and dies on a document load — so finding it at the end is what says the
            // whole round trip went through app.js's router rather than the address bar.
            page.evaluate("() => { window.__kotgentPaletteWitness = 1; }")

            page.openPalette()
            assertThat(page.leaderRow("Open the task board")).hasCount(1)
            assertThat(page.leaderRow("Back to sessions")).hasCount(0)
            page.pressMnemonic("KeyO")

            // The palette closes BEFORE the command runs, synchronously, so the action never fires into
            // a dialog that is still holding the top layer.
            assertThat(page.locator("#command-palette")).hasCount(0)
            assertThat(page).hasURL(Pattern.compile("/tasks$"))
            page.awaitBoard()
            assertThat(page.taskCard("local:1")).isVisible()

            // The SAME letter, turned around. On the board "Open the task board" is a navigation that
            // already happened, so `o` leads out instead — one mnemonic for "the other screen".
            page.openPalette()
            assertThat(page.leaderRow("Back to sessions")).hasCount(1)
            assertThat(page.leaderRow("Open the task board")).hasCount(0)
            page.pressMnemonic("KeyO")

            // This scenario seeds no sessions, so there is nothing to name in the URL and `/` is exactly
            // right; leaving the board with a selection names it instead, which is not this fixture.
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
        onScenario("task-linked-session", "session-group-per-screen") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            for (title in sessionCommandTitles) {
                assertThat(page.leaderRow(title)).hasCount(1)
            }
            // The sidebar-only toggle is chordless, so the search list is the only place it can be seen.
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
            // Not merely hidden from the grid: the group is not BUILT here, so search cannot reach it
            // either — which is the difference between "this command does not apply" and "this command
            // is disabled right now", and the reason the whole group is composed conditionally.
            val field = page.searchFor("interrupt")
            assertThat(page.paletteOptions()).hasCount(0)
            // A live control between the two empty answers: without it each `hasCount(0)` would also be
            // satisfied by a search field that stopped answering after the previous query.
            field.fill("new task")
            assertThat(page.paletteOptions()).hasCount(1)
            field.fill(SHOW_DONE_QUERY)
            assertThat(page.paletteOptions()).hasCount(0)
        }

    @Test
    fun theSessionRowsSurviveOnTheBoardBecauseTheyAreNavigation() =
        onScenario("task-linked-session", "session-rows-on-the-board") { harness, page ->
            // Arriving at the board FROM a selected session is what makes the assertion below sharp: the
            // list has demonstrably reached the browser, so an empty option list would be a missing
            // group rather than a snapshot that has not landed yet.
            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()

            // Re-opened, because the mnemonic above took the palette with it: `closeThenRun` closes
            // before it runs, so `o` navigates AND dismisses. Without this the K below is typed at the
            // board and the search view never appears.
            page.openPalette()
            val query = page.searchMode()
            // With no query the session rows lead the list, ahead of every available command.
            val first = page.paletteOptions().first()
            assertThat(first).hasClass(ACTIVE_OPTION)
            query.press("Enter")

            // Selecting a row is NAVIGATION, not a session control — which is why the group survived a
            // screen that unmounted every command aimed at a session. It is also the way back to one
            // particular session from the board.
            assertThat(page).hasURL(Pattern.compile("/s/s-linked-[123]$"))
            page.awaitSessionView()
        }

    @Test
    fun newTaskOpensTheBoardsCreateFormEveryTimeItIsAskedAndNeverUnasked() =
        onScenario("board", "new-task-command") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()

            // One keystroke, both halves: the navigation and the request for the form. The board owns
            // the form because it owns the project selector — the browser has no session to infer a
            // project from, so a create must name one.
            page.openPalette()
            page.pressMnemonic("KeyW")
            assertThat(page).hasURL(Pattern.compile("/tasks$"))
            assertThat(page.locator("#new-task-dialog")).isVisible()
            assertThat(page.locator("#new-task-title-input")).isVisible()

            page.keyboard().press("Escape")
            assertThat(page.locator("#new-task-dialog")).hasCount(0)
            page.awaitBoard()

            // Asked again while the board is ALREADY open. This is why the request is a one-shot COUNTER
            // and not a boolean: a boolean would need a reset round-trip before it could fire twice, and
            // the second ⌘K w would do nothing at all.
            page.openPalette()
            page.pressMnemonic("KeyW")
            assertThat(page.locator("#new-task-dialog")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.locator("#new-task-dialog")).hasCount(0)

            // Leaving the board RETIRES the request, and that is the whole reason a counter is safe. A
            // counter that only ever grew re-opened the form on every later visit: ⌘K w once, back to a
            // session, then a task badge tapped weeks later pops a New-task modal over the detail,
            // unasked. Both sides go back to 0, which is exactly "never asked".
            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitSessionView()
            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()
            assertThat(page.taskCard("local:1")).isVisible()
            assertThat(page.locator("#new-task-dialog")).hasCount(0)

            // …and asking once more still works, so the silence above is a retired request rather than a
            // mechanism that quietly stopped answering.
            page.openPalette()
            page.pressMnemonic("KeyW")
            assertThat(page.locator("#new-task-dialog")).isVisible()
        }

    @Test
    fun theBoardsNewProjectFormIsReachableFromTheSearchListWithoutAMnemonic() =
        onScenario("board", "new-project-command") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            page.awaitSessionView()

            page.openPalette()
            // Chordless on purpose — the board draws its own "New project" button, and the leader grid
            // is the small set worth memorising — so this one never grows the grid.
            assertThat(page.leaderRow("New project")).hasCount(0)
            assertThat(page.leaderRow("New task")).hasCount(1)

            page.runFirstMatch("new project", "New project")

            assertThat(page).hasURL(Pattern.compile("/tasks$"))
            assertThat(page.locator("#new-project-dialog")).isVisible()
            assertThat(page.locator("#new-project-path")).isVisible()
            // The two forms have a counter EACH: `form` holds one value, so a shared counter could not
            // say which of them the palette asked for, and the wrong dialog opening is exactly what that
            // mistake looks like from here.
            assertThat(page.locator("#new-task-dialog")).hasCount(0)
        }

    @Test
    fun openingThisSessionsTaskIsRefusedAloudForASessionThatCarriesNoTask() =
        onScenario("task-linked-session", "open-session-task") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-2")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            val refused = page.leaderRow("Open this session's task")
            assertThat(refused).hasCount(1)
            assertThat(refused).hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyJ")
            // Refused ALOUD: the grid stays up and says why, rather than offering a chord that does
            // nothing. The condition is exactly the one that makes the command a no-op — the session's
            // own `taskRef`, the field the /events rows already carry.
            assertThat(page.locator(".command-palette-footer")).containsText("not linked to a task")
            assertThat(page).hasURL(Pattern.compile("/s/s-linked-2$"))
            page.closePalette()

            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            val offered = page.leaderRow("Open this session's task")
            assertThat(offered).not().hasAttribute("aria-disabled", "true")
            page.pressMnemonic("KeyJ")
            // `taskPath` carries the ref through `encodeURIComponent`, so the mandatory `:` of
            // `<tracker>:<key>` arrives as `%3A` — the address a badge links to and the router parses
            // back. Liveness is deliberately no part of the condition above: a stopped or archived
            // session still points at the task it was working on, and reading that task is precisely
            // what an operator does after the agent finished.
            assertThat(page).hasURL(Pattern.compile("/tasks/local%3A1$"))
            assertThat(page.locator("#task-detail-title")).hasText("local:1")
        }

    @Test
    fun everyMnemonicTheGridDrawsIsUniqueAndKIsLeftToTheWayBackToSearch() =
        onScenario("task-linked-session", "leader-mnemonics") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-linked-1")
            page.awaitSessionView()
            page.awaitSelectedSession()

            page.openPalette()
            page.assertMnemonicsAreDistinct("the session view")
            // Why `k` must stay free: the palette answers it with "switch to search" BEFORE it consults
            // the registry, so a command lettered `k` would be a visible grid row permanently unreachable
            // by its own key — and the one way back to search would be gone with it.
            page.pressMnemonic("KeyK")
            assertThat(page.searchQuery()).isVisible()
            page.closePalette()

            page.openPalette()
            page.pressMnemonic("KeyO")
            page.awaitBoard()

            // The board draws a DIFFERENT set (no session group, `o` reversed), so the rule has to hold
            // on both screens rather than on one snapshot of the registry.
            page.openPalette()
            page.assertMnemonicsAreDistinct("the board")
            page.pressMnemonic("KeyK")
            assertThat(page.searchQuery()).isVisible()
        }

    // --- harness ---------------------------------------------------------------------------------

    /**
     * One harness, one browser, one FRESH context (the cookie is not bound to a port, so reusing one
     * across tests breaks the login), signed in with the scenario's ticket and traced — the trace and
     * its screenshot are kept only when [block] throws.
     */
    private fun onScenario(scenario: String, trace: String, block: (Harness, Page) -> Unit) {
        Harness(scenario).use { harness ->
            Playwright.create().use { playwright ->
                val browser = touchChromium(playwright)
                val context = browser.touchContext()
                try {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(trace) { block(harness, context.newPage()) }
                } finally {
                    context.close()
                    browser.close()
                }
            }
        }
    }

    /**
     * Open the palette on its leader grid.
     *
     * The focus assertion is not decoration: `leaderKeyDown` sits on the palette SHELL and only ever
     * runs for a keystroke that bubbles out of that subtree, so a mnemonic pressed before the mode
     * effect moved the focus there is typed into nothing at all — which is exactly the bug that made
     * every chord silently do nothing while the grid's click handlers kept working.
     *
     * Re-opening is deliberately not done through the opener: ⌘K on an open palette TOGGLES its two
     * views, so every flow here closes first or lets the command it ran close it.
     */
    private fun Page.openPalette(): Locator {
        // The stated precondition, WAITED for rather than assumed. A command's own close is asynchronous
        // — `dialog.close()` queues its `close` event — so for a beat after a mnemonic ran the element is
        // still mounted with `open === false` while app.js still holds the palette state. An opener
        // pressed inside that window toggles the stale state onto an invisible dialog, and the leader
        // shell below never arrives.
        assertThat(locator("#command-palette")).hasCount(0)
        keyboard().press(PALETTE_OPENER)
        val shell = locator(".command-palette-shell.leader")
        assertThat(shell).isVisible()
        assertThat(shell).isFocused()
        return shell
    }

    /**
     * Esc, and the one platform detail that makes it two keystrokes.
     *
     * The query field is an `<input type="search">`, and Chromium spends the FIRST Esc of a non-empty one
     * on the UA's own clear: the keydown's default action is consumed there, so the `<dialog>` never sees
     * a close request and the palette stays up with an emptied field. That is the browser's behaviour and
     * not the app's — nothing in `CommandPalette.js` reads Escape at all — so the field is emptied first
     * and the one Esc that follows means exactly one thing.
     */
    private fun Page.closePalette() {
        val query = searchQuery()
        if (query.count() > 0) query.fill("")
        keyboard().press("Escape")
        assertThat(locator("#command-palette")).hasCount(0)
    }

    /** Press a bare leader mnemonic. `event.code` is what the palette matches, hence "KeyO" and not "o". */
    private fun Page.pressMnemonic(code: String) {
        keyboard().press(code)
    }

    /** The grid's own way over to the search list (`K`, handled bare inside the palette). */
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

    /**
     * Type [query], check that it selects exactly the one command [expected] names, and run it with
     * Enter.
     *
     * The `active` assertion is what keeps this from racing the palette: the active option is chosen by
     * an effect keyed on the query, and Enter runs whatever that effect last settled on — pressing it
     * one paint too early would run the PREVIOUS selection.
     */
    private fun Page.runFirstMatch(query: String, expected: String) {
        val field = searchFor(query)
        val options = paletteOptions()
        assertThat(options).hasCount(1)
        assertThat(options.first()).containsText(expected)
        assertThat(options.first()).hasClass(ACTIVE_OPTION)
        field.press("Enter")
    }

    private fun Page.paletteOptions(): Locator = locator(".command-palette-option")

    /** A leader-grid row by the title it draws (case-insensitive substring, like Playwright's own). */
    private fun Page.leaderRow(title: String): Locator =
        locator(".command-palette-leader-command").filter(Locator.FilterOptions().setHasText(title))

    private fun Page.taskCard(ref: String): Locator = locator(".task-card[data-ref=\"$ref\"]")

    /**
     * Every letter the grid DRAWS is one ASCII letter, claimed once, and never `k`.
     *
     * Read from the rendered grid rather than from the registry's source: what the operator is taught is
     * the `<kbd>`, and a letter claimed twice makes the second row unreachable by the very key it
     * displays (`leaderKeyDown` is a first-match-wins `find` over the whole list). That the drawn letter
     * really WORKS is the other half, and the `o` / `w` / `j` tests above are where it is proven.
     */
    private fun Page.assertMnemonicsAreDistinct(screen: String) {
        assertThat(locator(".command-palette-leader-grid")).isVisible()
        val rows = locator(".command-palette-leader-command").count()
        val keys = locator(".command-palette-leader-key").allTextContents().map { it.trim() }
        assertTrue(keys.isNotEmpty(), "$screen offers leader mnemonics at all")
        assertEquals(rows, keys.size, "every row of $screen's grid draws exactly one key")
        for (key in keys) {
            assertTrue(
                key.length == 1 && (key[0] in 'a'..'z' || key[0] in 'A'..'Z'),
                "'$key' on $screen is one ASCII letter, or `\"Key\" + chord.toUpperCase()` names no " +
                    "physical code and the row it draws can never be pressed",
            )
            // Case-INSENSITIVE throughout, because the runtime key is `"Key" + chord.toUpperCase()`: an
            // "O" beside an "o" both resolve to KeyO and shadow each other.
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

    /** The session view is on screen (and the board is not: the two are the arms of one branch). */
    private fun Page.awaitSessionView() {
        assertThat(locator("#terminal-pane")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
        assertThat(locator("main.board")).hasCount(0)
    }

    private fun Page.awaitBoard() {
        assertThat(locator("main.board")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
        assertThat(locator("#terminal-pane")).hasCount(0)
    }

    /**
     * The route named a session and the app has selected it.
     *
     * Asserted as the ABSENCE of the empty title rather than as the session's name: `displayName` falls
     * back through `name` and `tmuxSession` to the id, and the fixture promises none of the three — but
     * "No session selected" is exactly what the pane says until the session list arrives and the route's
     * id can be resolved in it.
     *
     * It must follow [awaitSessionView], and that ordering is load-bearing: a NEGATED text assertion is
     * satisfied by an element that does not exist yet, so on its own this would pass before the pane had
     * rendered at all.
     */
    private fun Page.awaitSelectedSession() {
        assertThat(locator("#terminal-title")).not().hasText(
            "No session selected",
            LocatorAssertions.HasTextOptions().setTimeout(BOOT_TIMEOUT_MS),
        )
    }

    private fun visibleWithin(millis: Double): LocatorAssertions.IsVisibleOptions =
        LocatorAssertions.IsVisibleOptions().setTimeout(millis)

    private companion object {
        /**
         * The palette opener, as a physical code: app.js matches `event.code`, so the shortcut survives
         * a non-QWERTY layout. `Control+Shift+KeyK` is the same door for a non-mac keyboard.
         */
        const val PALETTE_OPENER = "Meta+KeyK"

        /** Matches "Show or hide done sessions" and nothing else on either screen. */
        const val SHOW_DONE_QUERY = "hide done"

        val ACTIVE_OPTION: Pattern = Pattern.compile("\\bactive\\b")

        /** The first paint of the SPA plus its `/events` snapshot; every later assertion is instant. */
        const val BOOT_TIMEOUT_MS = 15_000.0
    }
}
