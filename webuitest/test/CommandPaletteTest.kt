package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command palette, driven the way an operator drives it: ⌘K, then a letter or a typed substring.
 *
 * `lib/commands.js` is the Web UI's ONE command and mnemonic registry — search mode renders its filtered
 * descriptors and leader mode renders the chord-bearing subset — and `app.js` installs exactly one
 * capture-phase document `keydown` listener for the opener. Everything below asserts what those produce
 * on screen and what running a command actually DOES; nothing here reads a source file. That is the whole
 * point of moving these contracts into a browser: the four grep tests this file supersedes could prove
 * that `event.code === "Key" + command.chord.toUpperCase()` was present in the served text, but not that
 * pressing the physical key ran the command — and a mnemonic that is dropped on the floor (see the focus
 * bug recorded in `CommandPalette.js`) leaves that text completely intact.
 *
 * ## What the tests protect, and why the app is written this way
 *
 * **Physical keys, not layout-dependent characters.** Both the opener and the leader mnemonics match
 * `event.code`, so the chords survive a non-QWERTY layout. The tests therefore press keys (`KeyN`,
 * `Meta+KeyK`) and assert the EFFECT — a dialog that opened, a route that changed — rather than
 * inspecting a binding table that nobody's fingers can reach.
 *
 * **`k` is reserved and no command may claim it.** `leaderKeyDown` answers K (and Backspace) with
 * "go to search" BEFORE it consults the registry, so a `k` chord would render a visible grid row that its
 * own letter can never reach. Its neighbours are the same hazard from the other side: the lookup is
 * first-match-wins, so a DUPLICATE chord silently makes one visible row unreachable, and a chord that is
 * not a single ASCII letter composes a `"Key" + …` code no keyboard produces. All three used to be
 * asserted over the registry's source text; here they are read off the grid the operator actually sees.
 *
 * **The modifier guard, and what it costs.** Mnemonics are bare `code` matches with no modifier test, so
 * once Attach took "a" and Detach took "e", every ⌘A (Select All) and ⌘E ran a lifecycle command and
 * swallowed the browser's own default. The guard precedes `preventDefault()` and the run — the lookup
 * itself is a side-effect-free `find` — and its accepted cost is that a mnemonic pressed with the
 * opener's modifier STILL held is refused too. That is the gesture operators already use (⌘K, release,
 * then the letter), which is what makes the trade free.
 *
 * **A refusal is stated, never swallowed.** A disabled command is still drawn: the grid announces its
 * reason through the palette's own `role="status"` line instead of behaving like a dead chord. The
 * reserved `general.notifications` row is the extreme case — visible, keyed, and permanently unavailable
 * until its stage is designed.
 *
 * **The palette yields the keyboard to a dialog that owns it.** The opener returns early while `app.js`
 * holds an open dialog, so ⌘K cannot stack a second modal over a form with a draft in it. One residual is
 * recorded rather than tested: the board's own New task / New project forms live in `Board.js` and never
 * reach `app.js`'s `dialogRef`, so ⌘K does open over those.
 *
 * **The registry is screen-aware.** `/tasks` REPLACES the session view, so while the board is up the
 * palette must not offer commands aimed at a session nobody can see; the one board mnemonic turns around
 * and leads back out instead. Session ROWS stay on both screens, because selecting one is navigation.
 *
 * Every test here runs on the `sessions` scenario with NOTHING selected, which is deliberate: an empty
 * selection is what makes the session commands disabled, and a disabled command is how the availability
 * rules become observable at all. It also keeps the tests off the terminal — the one session a test does
 * select is the `resumable` shell at `/d`, which attaches no pty.
 */
class CommandPaletteTest {

    /**
     * Search mode: what a typed substring reaches, in what order, and where the arrows may land.
     *
     * With no query at all the list is "the sessions, then the commands that can run right now" — the
     * disabled ones disappear rather than pad the list. A typed query reaches them again, because a
     * command an operator went looking for owes them an explanation instead of an absence; those matches
     * are appended AFTER the available ones, and the arrow keys skip them entirely. The wrap is checked
     * from the top precisely because that is where skipping the disabled tail is visible: ArrowUp from the
     * first row must land on the last AVAILABLE row, not on the last row.
     */
    @Test
    fun theSearchViewNarrowsTheOneRegistryAndItsArrowsWalkOnlyTheAvailableRows() =
        onThePaletteScreen("palette-search") { _, page ->
            awaitSessionRows(page)
            val input = openSearchMode(page)
            val options = page.locator("#command-palette-results > li")

            // The accessible shape the search view is built on: focus stays in the combobox and
            // `aria-activedescendant` points into the listbox, so a screen reader follows the arrows
            // without the focus ever leaving the field being typed into.
            assertThat(page.getByRole(AriaRole.LISTBOX)).hasCount(1)
            assertThat(input).isFocused()

            // No query: session rows and the available commands.
            assertThat(options.withText("/d")).hasCount(1)
            assertThat(options.withText(SHOW_DONE_COMMAND)).hasCount(1)
            // …and nothing that could not run. With no session selected, Interrupt is exactly that.
            assertThat(options.withText(INTERRUPT_COMMAND)).hasCount(0)

            // Subtitles are searched too, not only titles: "grouping" appears nowhere but in the
            // Preferences descriptor's subtitle, so this is a one-hit query by construction.
            input.fill("grouping")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("Preferences")

            // Typed, it comes back — carrying the reason, which is the point of showing it at all.
            input.fill("interrupt")
            assertThat(options).hasCount(1)
            assertThat(options.first()).hasAttribute("aria-disabled", "true")
            assertThat(options.first().locator(".command-palette-disabled-reason"))
                .containsText("no session is selected")
            // A query whose every row is unavailable points the combobox at NOTHING, so the attribute is
            // removed entirely. That absence is load-bearing twice over: it is the accessible truth (there
            // is no row Enter could run), and it is this test's BARRIER. The palette resets `activeIndex`
            // in a `useEffect([query])`, i.e. after paint, and an option's id is its INDEX — so "the
            // highlight is on row 0" is indistinguishable between the query before and the query after,
            // and waiting for it proves nothing. Waiting for the attribute to VANISH here and REAPPEAR
            // below is a real transition, which is what makes the pending reset provably spent before the
            // first ArrowDown. Without it that reset lands after the key and silently walks the highlight
            // back to row 0 — observed, as a one-in-several-runs failure of the assertion below.
            assertThat(input).not().hasAttribute(ACTIVE_DESCENDANT, Pattern.compile("."))

            // A query that spans both halves of the list. Wait for a row only this query produces before
            // reading the DOM in bulk: `all()` does not retry, so it must not race the re-render.
            input.fill("session")
            assertThat(options.withText(INTERRUPT_COMMAND)).hasCount(1)
            val rows = options.all()
            val unavailable = rows.map { it.getAttribute("aria-disabled") != null }
            assertTrue(
                unavailable.any { it } && unavailable.any { !it },
                "the query must produce BOTH available and unavailable rows, or neither the ordering " +
                    "nor the arrow-skip below proves anything (got $unavailable)",
            )
            assertEquals(
                unavailable.sorted(),
                unavailable,
                "every available row precedes the unavailable tail (got $unavailable)",
            )

            val ids = rows.map {
                requireNotNull(it.getAttribute("id")) {
                    "every option carries the id `aria-activedescendant` has to be able to point at"
                }
            }
            val lastAvailable = unavailable.indexOfLast { !it }
            assertTrue(lastAvailable >= 1, "the query must offer at least two available rows to walk")

            // The reappearance the barrier above set up: the reset for THIS query has run, so nothing is
            // left pending that could overtake the arrows.
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[0])
            assertThat(options.nth(0)).hasAttribute("aria-selected", "true")

            page.keyboard().press("ArrowDown")
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[1])
            assertThat(options.nth(1)).hasAttribute("aria-selected", "true")
            assertThat(options.nth(0)).hasAttribute("aria-selected", "false")
            // The arrows move the pointer, never the focus — that is what makes this a combobox.
            assertThat(input).isFocused()

            page.keyboard().press("ArrowUp")
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[0])
            // Wrapping from the top skips the whole unavailable tail and lands on the last row that can
            // actually run: pressing Enter is never a no-op.
            page.keyboard().press("ArrowUp")
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[lastAvailable])

            // The opener toggles the two views rather than re-opening the root, so a second ⌘K from
            // search is the way back to the grid.
            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertThat(page.locator("#command-palette-query")).hasCount(0)
        }

    /**
     * Enter runs the highlighted row, and the palette is already gone by the time it does.
     *
     * The closing is synchronous and deliberate: it preserves the key's user gesture for the clipboard
     * command, and it is what keeps a command that opens a dialog from stacking one modal on another.
     * Two different kinds of row are run here, because the registry holds both — a COMMAND (Preferences,
     * observable as the dialog it opens) and a SESSION row (observable as the route it navigates to,
     * `showSession` naming the selection in the URL so a reload lands where the operator left off).
     */
    @Test
    fun enterRunsTheHighlightedRowAndThePaletteIsAlreadyClosedWhenItDoes() =
        onThePaletteScreen("palette-enter") { harness, page ->
            awaitSessionRows(page)

            val options = page.locator("#command-palette-results > li")
            openSearchMode(page).fill("grouping")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("Preferences")
            // "Highlighted" is this test's whole subject and it is chosen by an effect keyed on the query,
            // so it is waited for rather than assumed: Enter runs whatever that effect last settled on.
            assertThat(options.first()).hasAttribute("aria-selected", "true")

            page.keyboard().press("Enter")
            assertThat(page.locator("#prefs-dialog")).isVisible()
            assertThat(page.locator(PALETTE)).hasCount(0)

            page.keyboard().press("Escape")
            assertThat(page.locator("#prefs-dialog")).hasCount(0)

            // A session row is a command too. `/d` is the `sessions` scenario's shell session and the
            // only descriptor whose text carries that directory, so the query is a one-hit by
            // construction; it is also `resumable`, so selecting it attaches no terminal.
            openSearchMode(page).fill("/d")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("shell")
            assertThat(options.first()).hasAttribute("aria-selected", "true")

            page.keyboard().press("Enter")
            assertThat(page.locator(PALETTE)).hasCount(0)
            assertThat(page).hasURL(Pattern.compile(regexLiteral(harness.baseUrl) + "/s/[^/]+$"))
            assertThat(page.locator("#session-list .session-row[title='/d']"))
                .hasAttribute("aria-current", "true")
        }

    /**
     * Leader mode: the grid the operator memorises, and the keyboard it owns while it is up.
     *
     * Four rules are checked against the grid as drawn, because all four fail by making a VISIBLE row
     * unreachable — the failure mode a source-text assertion cannot see. Then the keyboard itself: both
     * ways back to search (K and Backspace, plus the Ctrl-K that only works because that branch sits
     * above the modifier guard); Space, swallowed on the shell that leader mode focuses so mnemonics
     * bubble to its handler, but not on the shell's own buttons; a letter carrying a modifier, left to
     * the browser; the same letter typed bare, answered — refusal included; and an available letter that
     * runs.
     */
    @Test
    fun theLeaderGridOwnsTheKeyboardAndAnswersOnlyBareMnemonics() =
        onThePaletteScreen("palette-leader") { _, page ->
            // Records, for every keystroke that reaches the document, whether anything called
            // `preventDefault()` on it. That flag IS the guard under test twice below: the Space swallow
            // and the modifier refusal both do exactly one observable thing, and it is this.
            installKeyRecorder(page)
            openLeaderMode(page)
            val footer = page.locator(".command-palette-footer")
            assertThat(footer).containsText(LEADER_HINT)

            // Read PER ROW rather than as one document-wide list: a row with two keys beside a row with
            // none satisfies any assertion made about the totals.
            val rows = page.locator(LEADER_COMMAND).all()
            val keys = rows.map { row ->
                val drawn = row.locator(".command-palette-leader-key").allTextContents().map { it.trim() }
                assertEquals(1, drawn.size, "a grid row draws ${drawn.size} keys ($drawn), not one")
                drawn.first()
            }
            // Named rather than counted. A floor ("at least ten") lets eight rows disappear in silence,
            // and an exact total would be a second copy of the registry's length that every new command
            // has to edit. The letters this FILE presses are the ones its other assertions depend on, so
            // those are what it insists the grid still draws.
            assertTrue(
                keys.map { it.lowercase() }.containsAll(EXERCISED_MNEMONICS),
                "the grid no longer draws every mnemonic this file presses ($EXERCISED_MNEMONICS): $keys",
            )
            assertTrue(
                keys.all { it.length == 1 && (it[0] in 'a'..'z' || it[0] in 'A'..'Z') },
                "every drawn key is ONE ASCII letter, or its \"Key\" + chord code is unreachable: $keys",
            )
            assertEquals(
                keys.size,
                keys.map { it.lowercase() }.toSet().size,
                "every mnemonic is claimed once — first-match-wins would hide a duplicate's row: $keys",
            )
            assertTrue(
                keys.none { it.equals("k", ignoreCase = true) },
                "'k' stays the grid's own way back to search, so no command may claim it: $keys",
            )

            // Backspace is the grid's other way back to search — the one a keyboard finds without
            // knowing that the opener's own letter is also a mnemonic here.
            page.keyboard().press("Backspace")
            assertThat(page.locator("#command-palette-query")).isVisible()
            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            // Every mode flip moves the focus by an EFFECT that lands after the paint, and the next
            // keystroke here is one leader mode must answer — see `assertLeaderOwnsTheKeyboard`.
            assertLeaderOwnsTheKeyboard(page)

            // And a Ctrl-K reaches search too, which is the whole reason that branch sits ABOVE the
            // modifier guard rather than below it: the non-mac opener is Ctrl+SHIFT+K, so an operator who
            // releases Shift first and keeps Ctrl produces a keystroke `app.js` no longer matches. From
            // under the guard nothing would answer it and the way back to search would simply be gone.
            page.keyboard().press("Control+KeyK")
            assertThat(page.locator("#command-palette-query")).isVisible()
            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertLeaderOwnsTheKeyboard(page)

            // Space on the shell is SWALLOWED, and swallowing is all it does — the keystroke activates
            // nothing here either way, so "the grid is still up" is true with the guard deleted. What the
            // guard actually performs is `preventDefault()`, which is what suppresses the scroll the
            // browser would otherwise give a focused container; that is read directly.
            page.keyboard().press("Space")
            assertEquals(
                "Space:prevented",
                lastKeyEvent(page, "Space"),
                "Space on the leader shell must be cancelled, or the browser scrolls the grid out from " +
                    "under a hand that meant to type a mnemonic",
            )
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertThat(footer).containsText(LEADER_HINT)

            // …but the guard is scoped to the shell ITSELF, so the controls inside it still answer Space.
            // The first tab stop is the row that opens search; before the target test it did nothing at
            // all, which read as a dead button rather than as an over-broad key handler. Both halves are
            // visible here — the keystroke is NOT cancelled, and the mode really flips.
            page.keyboard().press("Tab")
            // Named rather than assumed: if the tab order ever changes, the failure says so instead of
            // reading as a Space that was swallowed after all.
            assertThat(page.locator("#command-palette-search-mode")).isFocused()
            page.keyboard().press("Space")
            assertEquals(
                "Space:default",
                lastKeyEvent(page, "Space"),
                "the shell's Space guard reached one of its own buttons and cancelled the activation",
            )
            assertThat(page.locator("#command-palette-query")).isVisible()

            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertLeaderOwnsTheKeyboard(page)
            assertThat(footer).containsText(LEADER_HINT)

            // A letter carrying a modifier is refused BEFORE the lookup, so the browser keeps its own
            // binding. Ctrl rather than ⌘ because macOS reserves several ⌘-letters at the window-server
            // level, and this guard tests `metaKey || ctrlKey` either way. The observable is the keystroke
            // itself rather than a settling window over the footer: the guard returns above
            // `preventDefault()`, so a modified letter must leave the page having cancelled nothing.
            page.keyboard().press("Control+KeyI")
            assertEquals(
                "KeyI:default",
                lastKeyEvent(page, "KeyI"),
                "a modified letter was answered by the grid, so the browser lost its own binding for it",
            )
            assertThat(footer).containsText(LEADER_HINT)

            // The same letter, bare, IS answered — cancelled, and a command that cannot run says why
            // rather than behaving like a dead chord. This is the contrast that makes the assertion above
            // mean something: only the modifier separates the two keystrokes.
            page.keyboard().press("KeyI")
            assertEquals("KeyI:prevented", lastKeyEvent(page, "KeyI"), "a bare mnemonic is the grid's")
            assertThat(footer).containsText("$INTERRUPT_COMMAND: no session is selected")
            assertThat(page.locator(PALETTE)).isVisible()

            // And an available mnemonic runs, taking the palette with it.
            page.keyboard().press("KeyN")
            assertThat(page.locator("#new-session-dialog")).isVisible()
            assertThat(page.locator(PALETTE)).hasCount(0)
        }

    /**
     * A chord reserved for a stage that has not been designed yet stays visible and inert.
     *
     * `general.notifications` is drawn with its letter like every other row and refuses both ways in — the
     * keystroke and the tap. Keeping it visible is the deliberate choice: the grid is what an operator
     * memorises, so a command that is coming is better shown as unavailable than silently missing, and the
     * status line says which one and why. Nothing about it may close the palette, because nothing ran.
     */
    @Test
    fun aReservedChordStaysVisibleAndAnnouncesWhyItCannotRun() =
        onThePaletteScreen("palette-reserved") { _, page ->
            openLeaderMode(page)
            val footer = page.locator(".command-palette-footer")
            val reserved = page.locator(".command-palette-leader-command").withText(NOTIFICATIONS_COMMAND)

            assertThat(reserved).hasCount(1)
            assertThat(reserved).isVisible()
            assertThat(reserved).hasAttribute("aria-disabled", "true")
            assertThat(reserved.locator("kbd")).hasText("b")

            page.keyboard().press("KeyB")
            assertThat(footer).containsText("$NOTIFICATIONS_COMMAND: not implemented yet")
            assertThat(page.locator(PALETTE)).isVisible()

            // The phone's way in is a tap, and it is refused identically: the row is a real button, so
            // without the disabled check it would run the empty command body and close the palette.
            //
            // Forced, and that is the point rather than a concession: Playwright's actionability check
            // reads `aria-disabled="true"` as disabled and would wait for an enable that is never coming
            // — which is precisely the state under test. `force` skips the WAIT, not the click: CDP still
            // delivers a real press at the element's centre, so what the row's handler receives is what a
            // thumb produces. Only the app's own refusal can keep the palette open afterwards.
            reserved.click(Locator.ClickOptions().setForce(true))
            assertThat(footer).containsText("$NOTIFICATIONS_COMMAND: not implemented yet")
            assertThat(page.locator(PALETTE)).isVisible()
        }

    /**
     * The opener yields the keyboard while an app dialog owns it, and takes it back afterwards.
     *
     * `app.js` installs ONE capture-phase document listener, ahead of xterm.js and any focused field, and
     * it returns early while a dialog is open. Without that, ⌘K would stack the palette over a form
     * holding an unsaved draft — and the palette's own Esc, backdrop press and swipe would then be
     * dismissing the wrong screen. The recovery half matters just as much: the binding is yielded for the
     * dialog's lifetime, not lost, so it must answer again the moment the dialog closes.
     */
    @Test
    fun thePaletteYieldsTheKeyboardWhileAnotherDialogOwnsIt() =
        onThePaletteScreen("palette-yields") { _, page ->
            openLeaderMode(page)
            page.keyboard().press("KeyP")
            assertThat(page.locator("#prefs-dialog")).isVisible()
            assertThat(page.locator(PALETTE)).hasCount(0)

            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(PALETTE)).hasCount(0)
            assertThat(page.locator("#prefs-dialog")).isVisible()

            // The barrier that makes the absence above an answer rather than a moment too early: Esc goes
            // to whichever dialog holds the top layer, so a palette that HAD opened would eat it and leave
            // Preferences standing. Preferences closing is therefore proof the opener yielded.
            page.keyboard().press("Escape")
            assertThat(page.locator("#prefs-dialog")).hasCount(0)
            assertThat(page.locator(PALETTE)).hasCount(0)

            openLeaderMode(page)
        }

    /**
     * The entry points the desktop chrome no longer draws are all still reachable — through the palette.
     *
     * The sidebar brand row was reduced to the daily notification toggle plus the structural mobile drawer
     * close, and the terminal header's second icon row went away entirely; the palette's leader grid is
     * the one lifecycle surface now. That is only a simplification if every removed button still has a
     * home, so the three dialogs that left the header are opened here through their mnemonics.
     *
     * The pointer path is checked as carefully as the keyboard one, because a phone has no ⌘K and no Esc:
     * `#palette-button` renders in the terminal header even with NO session selected (nothing in that
     * header may be wrapped in a `session &&` guard, or the phone loses its only way in), and the palette
     * — the one dialog that draws no head — carries its × on the top row as the only visible way out.
     */
    @Test
    fun theEntryPointsTheChromeNoLongerDrawsStayReachableFromThePalette() =
        onThePaletteScreen("palette-entry-points") { _, page ->
            // The two headers this test says nothing is left in have to be ON SCREEN before their
            // emptiness means anything: a `hasCount(0)` over a component that has not mounted is the
            // strongest possible pass for the weakest possible reason.
            assertThat(page.locator("#terminal-title")).containsText("No session selected")
            assertThat(page.locator("#palette-button")).isVisible()
            assertThat(page.locator(".brand-actions button")).hasCount(2)
            assertThat(page.locator("#notify-toggle")).isVisible()

            for (id in REMOVED_CHROME_BUTTONS) {
                assertThat(page.locator("#$id")).hasCount(0)
            }

            page.locator("#palette-button").click()
            assertThat(page.locator(LEADER_GRID)).isVisible()

            page.locator("#command-palette-close").click()
            assertThat(page.locator(PALETTE)).hasCount(0)

            page.locator("#palette-button").click()
            assertThat(page.locator(LEADER_GRID)).isVisible()
            // A pointer opened this one, so the keyboard has to be handed over before a letter is typed
            // at it — the same post-paint focus effect the keyboard path waits for.
            assertLeaderOwnsTheKeyboard(page)
            page.keyboard().press("KeyH")
            assertThat(page.locator("#help-dialog")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.locator("#help-dialog")).hasCount(0)

            openLeaderMode(page)
            page.keyboard().press("KeyM")
            assertThat(page.locator("#phone-dialog")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.locator("#phone-dialog")).hasCount(0)

            // Preferences is reached the other way — by search, in `enterRunsTheHighlightedRow…` — so the
            // fourth removed button is covered without opening the same dialog twice here.
            openLeaderMode(page)
            page.keyboard().press("KeyN")
            assertThat(page.locator("#new-session-dialog")).isVisible()
        }

    /**
     * The palette answers for the screen it is on.
     *
     * `/tasks` REPLACES the session view rather than covering it, so while the board is up every command
     * aimed at a session is aimed at something nobody can see: `⌘K a` wrote `attachedId` with no
     * `TerminalPane` mounted and did visibly nothing, `⌘K e` announced a detach from a terminal that was
     * not on screen, and Interrupt/Stop/Done reached whatever row happened to be selected before the
     * operator left for the backlog. Those descriptors are BUILT AWAY rather than disabled — a disabled
     * row is for a command that could apply here and does not right now, and none of these applies at all
     * — and the sidebar-only "show done" toggle goes with them, the sidebar being exactly what the board
     * unmounts.
     *
     * The board mnemonic is ONE descriptor that turns around, not two: `o` leads to the board from the
     * session view and back out of it from the board, where "Open the task board" was a dead letter and
     * the board's own "Sessions" link was the only way out — on a phone, a text link instead of the
     * surface every other action lives in. Two descriptors claiming `o` would make one of them a grid row
     * its own key can never reach.
     *
     * Session ROWS stay on both screens, because selecting one is navigation: on the board the search view
     * is also the way back to a particular session.
     */
    @Test
    fun thePaletteAnswersForTheScreenItIsOn() =
        onThePaletteScreen("palette-screen-aware") { harness, page ->
            awaitSessionRows(page)

            // The session view offers the session group, and the board mnemonic points at the board.
            openLeaderMode(page)
            assertThat(page.locator(LEADER_COMMAND).withText(INTERRUPT_COMMAND)).hasCount(1)
            assertThat(page.locator(LEADER_COMMAND).withText("Open the task board")).hasCount(1)
            page.keyboard().press("KeyO")
            assertThat(page).hasURL("${harness.baseUrl}/tasks")
            assertThat(page.locator("#board-status")).hasCount(1)
            assertThat(page.locator("#terminal-pane")).hasCount(0)

            // On the board none of the session commands is offered at all, and the one mnemonic reads —
            // and does — the opposite.
            openLeaderMode(page)
            for (title in SESSION_COMMANDS) {
                assertThat(page.locator(LEADER_COMMAND).withText(title)).hasCount(0)
            }
            assertThat(page.locator(LEADER_COMMAND).withText("Open the task board")).hasCount(0)
            assertThat(page.locator(LEADER_COMMAND).withText("Back to sessions")).hasCount(1)
            page.keyboard().press("KeyO")
            assertThat(page).hasURL("${harness.baseUrl}/")
            assertThat(page.locator("#terminal-pane")).hasCount(1)

            // Back to the board for the search half: the sidebar-only toggle is gone there (it is present
            // with an empty query on the session view — see the search test), while a session row is not,
            // and running one is how the operator gets back to a particular terminal.
            openLeaderMode(page)
            page.keyboard().press("KeyO")
            assertThat(page.locator("#board-status")).hasCount(1)

            val input = openSearchMode(page)
            val options = page.locator("#command-palette-results > li")
            // The rows a board search still offers. Asserted before the absence below, so that a zero
            // count there can only mean "filtered away" and never "the list never rendered".
            assertThat(options.withText("/d")).hasCount(1)

            input.fill(SHOW_DONE_COMMAND)
            assertThat(options).hasCount(0)

            input.fill("/d")
            assertThat(options).hasCount(1)
            // Enter runs whatever the query EFFECT last settled on, and an effect lands after the paint.
            // The query before this one matched nothing, so the pointer is parked at -1 and an Enter typed
            // into that window is a silent no-op — which is exactly how this line used to fail, sitting on
            // `/tasks` while the row it meant to run was on screen. The highlight is the observable that
            // says the effect has run.
            assertThat(options.first()).hasAttribute("aria-selected", "true")
            page.keyboard().press("Enter")
            assertThat(page).hasURL(Pattern.compile(regexLiteral(harness.baseUrl) + "/s/[^/]+$"))
            assertThat(page.locator("#terminal-pane")).hasCount(1)
            assertThat(page.locator("#board-status")).hasCount(0)
        }

    // --- fixture ---------------------------------------------------------------------------------

    /**
     * One harness, one Chromium, one signed-in page at `/`, with nothing selected.
     *
     * A fresh browser context per test is the module's rule: the session cookie is not scoped by port, so
     * a reused context is sent to the NEXT harness on the same loopback address, fails its HMAC, and the
     * SPA's first-load `401` reads as a flaky login rather than as a reused credential. [trace] names the
     * artifact a failing run leaves behind.
     */
    private fun onThePaletteScreen(trace: String, block: (Harness, Page) -> Unit) {
        Harness(SESSIONS_SCENARIO).use { harness ->
            Playwright.create().use { pw ->
                touchChromium(pw).use { browser ->
                    browser.newContext().use { context ->
                        context.traced(trace) {
                            context.loginWithTicket(harness.ticket, harness.baseUrl)
                            val page = context.newPage()
                            page.navigate("${harness.baseUrl}/")
                            assertThat(page.locator("#sidebar")).isVisible()
                            block(harness, page)
                        }
                    }
                }
            }
        }
    }

    /**
     * Wait until the `sessions_snapshot` has landed, so the registry's session rows exist.
     *
     * The palette builds its rows from app state, and that state arrives over the live `/api/v1/events`
     * socket — a palette opened before the snapshot legitimately holds commands only. The `/d` row is the
     * scenario's shell session and the last thing every session-row assertion here leans on.
     */
    private fun awaitSessionRows(page: Page) {
        assertThat(page.locator("#session-list .session-row[title='/d']")).isVisible()
    }

    /**
     * ⌘K from anywhere the app has focus, landing on the leader grid — the palette's root view.
     *
     * It waits for the palette to be GONE before pressing, because the opener TOGGLES an open palette's
     * two views rather than re-opening the root — and a palette that a command has just closed is not
     * gone yet. `closeThenRun` calls `dialog.close()`, whose `close` event is QUEUED rather than
     * dispatched, so for a beat after a mnemonic ran the element is still mounted with `open === false`
     * while `app.js` still holds its state (measured). An opener pressed inside that window flips the
     * stale state to search on an invisible dialog and the palette never comes back — which is exactly
     * how this helper used to fail, on a `<dialog>` that resolved and then vanished.
     */
    private fun openLeaderMode(page: Page) {
        assertThat(page.locator(PALETTE)).hasCount(0)
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator(PALETTE)).isVisible()
        assertThat(page.locator(LEADER_GRID)).isVisible()
        assertLeaderOwnsTheKeyboard(page)
    }

    /**
     * Leader mode really has the keyboard, which is the precondition of every mnemonic below.
     *
     * `leaderKeyDown` sits on the palette SHELL and only ever runs for a keystroke that BUBBLES out of
     * that subtree, and the focus that puts it there is an EFFECT: measured, the grid is on screen and
     * painted while `document.activeElement` is still `<body>`. A mnemonic pressed in that window is
     * delivered to the `<dialog>` — the shell's parent — and dropped in silence, which is the very bug
     * recorded in `CommandPalette.js`. Waiting for the focus is therefore not a settle: it is the state
     * the next keystroke is addressed to.
     */
    private fun assertLeaderOwnsTheKeyboard(page: Page) {
        assertThat(page.locator(LEADER_SHELL)).isFocused()
    }

    /**
     * ⌘K then a bare K — the way to search that the grid itself offers, and the reason no command may
     * register a `k` chord. Answers the combobox, focused and ready to be typed into.
     */
    private fun openSearchMode(page: Page): Locator {
        openLeaderMode(page)
        page.keyboard().press("KeyK")
        val input = page.locator("#command-palette-query")
        assertThat(input).isVisible()
        // The other half of the same effect: search mode moves the focus into the field, and until it
        // has, a keystroke meant for the query goes to the dialog instead.
        assertThat(input).isFocused()
        return input
    }

    /**
     * Record every keystroke that reaches the document, and whether anything cancelled it.
     *
     * Installed on the DOCUMENT in the bubble phase, which is downstream of everything the palette does:
     * `leaderKeyDown` sits on the shell, so by the time a keystroke arrives here the guard has either
     * called `preventDefault()` or returned without doing so — and that single bit is the whole observable
     * behaviour of two guards this file tests. It survives every remount of the palette, unlike a listener
     * on the dialog, and it is deliberately not `capture`: a capture listener would run BEFORE the shell's
     * handler and always report `default`.
     */
    private fun installKeyRecorder(page: Page) {
        page.evaluate(
            """
            () => {
              window.__kotgentKeys = [];
              document.addEventListener("keydown", (event) => {
                window.__kotgentKeys.push(
                  event.code + ":" + (event.defaultPrevented ? "prevented" : "default"),
                );
              });
            }
            """.trimIndent(),
        )
    }

    /** The newest recorded entry for [code], or a message naming what WAS recorded. */
    private fun lastKeyEvent(page: Page, code: String): String {
        val raw = page.evaluate("() => window.__kotgentKeys || []") as List<*>
        val entries = raw.map { it.toString() }
        return entries.lastOrNull { it.startsWith("$code:") }
            ?: "no $code reached the document at all (saw: $entries)"
    }

    /** Rows of this list whose text contains [text] — case-insensitive, like Playwright's own matcher. */
    private fun Locator.withText(text: String): Locator =
        filter(Locator.FilterOptions().setHasText(text))

    private companion object {
        /**
         * The opener as macOS spells it. `webuicheck` is a `macos/app` binary, so this suite only ever
         * runs where ⌘ is the modifier; the app's second binding (Ctrl+Shift+K) exists for the platforms
         * this harness cannot be built on. `KeyK` and not `k`: the app matches the physical key.
         */
        const val PALETTE_OPENER = "Meta+KeyK"

        const val PALETTE = "#command-palette"
        const val LEADER_GRID = ".command-palette-leader-grid"
        const val LEADER_COMMAND = ".command-palette-leader-command"
        const val ACTIVE_DESCENDANT = "aria-activedescendant"

        /** The element `leaderKeyDown` is installed on: a mnemonic reaches it or it reaches nobody. */
        const val LEADER_SHELL = ".command-palette-shell.leader"

        /** The palette's resting status line, and therefore the proof that nothing was announced. */
        const val LEADER_HINT = "Press a letter"

        /**
         * Every mnemonic this file presses. The grid must still draw all of them, or an assertion below
         * is silently about a chord that no longer exists — which a floor on the row count cannot say.
         */
        val EXERCISED_MNEMONICS = listOf("b", "h", "i", "m", "n", "o", "p")

        const val INTERRUPT_COMMAND = "Interrupt current session"
        const val NOTIFICATIONS_COMMAND = "Toggle notifications"
        const val SHOW_DONE_COMMAND = "Show or hide done sessions"

        /**
         * The session-group titles that must not be offered while the board is on screen. Detach and
         * Attach are the two that used to fail most quietly there — one announced a detach from a
         * terminal that was not mounted, the other wrote an attachment nothing rendered.
         */
        val SESSION_COMMANDS = listOf(
            INTERRUPT_COMMAND,
            "Resume this session",
            "Attach current terminal",
            "Detach current terminal",
            "Stop current session",
            "Done current session",
            "Copy tmux command",
            "Upload files to current folder",
            "Open this session's task",
        )

        /**
         * What the sidebar header and the terminal header no longer draw: the four general-action
         * buttons that duplicated the palette, plus `session-actions` — the mobile-only icon ROW that
         * repeated the lifecycle commands and their disabled rules, which the leader grid now states
         * itself.
         */
        val REMOVED_CHROME_BUTTONS = listOf(
            "new-session-button",
            "phone-button",
            "help-button",
            "prefs-button",
            "session-actions",
        )

    }
}

/**
 * [text] as one literal inside a regular expression — and deliberately NOT `Pattern.quote`.
 *
 * A `java.util.regex.Pattern` handed to a Playwright assertion is never evaluated in Java: the driver
 * ships its SOURCE TEXT to Node and matches it there with a JavaScript `RegExp`, which has no `\Q…\E`
 * quoting. `\Q` is just an escaped `Q`, so a quoted URL compiles on the far side into a pattern that must
 * begin with a literal `Q` and can never match — while the failure prints the Java spelling beside a URL
 * that plainly satisfies it. `RouterTest` carries the same helper for the same reason; each browser test
 * file in this module stays self-contained.
 */
private fun regexLiteral(text: String): String = buildString {
    for (ch in text) {
        if (ch in REGEX_METACHARACTERS) append('\\')
        append(ch)
    }
}

private const val REGEX_METACHARACTERS = "\\^$.|?*+()[]{}"
