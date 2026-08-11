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

class CommandPaletteTest {

    @Test
    fun theSearchViewNarrowsTheOneRegistryAndItsArrowsWalkOnlyTheAvailableRows() =
        onThePaletteScreen("palette-search") { _, page ->
            awaitSessionRows(page)
            val input = openSearchMode(page)
            val options = page.locator("#command-palette-results > li")

            assertThat(page.getByRole(AriaRole.LISTBOX)).hasCount(1)
            assertThat(input).isFocused()

            assertThat(options.withText("/d")).hasCount(1)
            assertThat(options.withText(SHOW_DONE_COMMAND)).hasCount(1)
            assertThat(options.withText(INTERRUPT_COMMAND)).hasCount(0)

            input.fill("grouping")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("Preferences")

            input.fill("interrupt")
            assertThat(options).hasCount(1)
            assertThat(options.first()).hasAttribute("aria-disabled", "true")
            assertThat(options.first().locator(".command-palette-disabled-reason"))
                .containsText("no session is selected")
            assertThat(input).not().hasAttribute(ACTIVE_DESCENDANT, Pattern.compile("."))

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

            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[0])
            assertThat(options.nth(0)).hasAttribute("aria-selected", "true")

            page.keyboard().press("ArrowDown")
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[1])
            assertThat(options.nth(1)).hasAttribute("aria-selected", "true")
            assertThat(options.nth(0)).hasAttribute("aria-selected", "false")
            assertThat(input).isFocused()

            page.keyboard().press("ArrowUp")
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[0])
            page.keyboard().press("ArrowUp")
            assertThat(input).hasAttribute(ACTIVE_DESCENDANT, ids[lastAvailable])

            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertThat(page.locator("#command-palette-query")).hasCount(0)
        }

    @Test
    fun enterRunsTheHighlightedRowAndThePaletteIsAlreadyClosedWhenItDoes() =
        onThePaletteScreen("palette-enter") { harness, page ->
            awaitSessionRows(page)

            val options = page.locator("#command-palette-results > li")
            openSearchMode(page).fill("grouping")
            assertThat(options).hasCount(1)
            assertThat(options.first()).containsText("Preferences")
            assertThat(options.first()).hasAttribute("aria-selected", "true")

            page.keyboard().press("Enter")
            assertThat(page.locator("#prefs-dialog")).isVisible()
            assertThat(page.locator(PALETTE)).hasCount(0)

            page.keyboard().press("Escape")
            assertThat(page.locator("#prefs-dialog")).hasCount(0)

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

    @Test
    fun theLeaderGridOwnsTheKeyboardAndAnswersOnlyBareMnemonics() =
        onThePaletteScreen("palette-leader") { _, page ->
            installKeyRecorder(page)
            openLeaderMode(page)
            val footer = page.locator(".command-palette-footer")
            assertThat(footer).containsText(LEADER_HINT)

            val rows = page.locator(LEADER_COMMAND).all()
            val keys = rows.map { row ->
                val drawn = row.locator(".command-palette-leader-key").allTextContents().map { it.trim() }
                assertEquals(1, drawn.size, "a grid row draws ${drawn.size} keys ($drawn), not one")
                drawn.first()
            }
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

            page.keyboard().press("Backspace")
            assertThat(page.locator("#command-palette-query")).isVisible()
            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertLeaderOwnsTheKeyboard(page)

            page.keyboard().press("Control+KeyK")
            assertThat(page.locator("#command-palette-query")).isVisible()
            page.keyboard().press(PALETTE_OPENER)
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertLeaderOwnsTheKeyboard(page)

            page.keyboard().press("Space")
            assertEquals(
                "Space:prevented",
                lastKeyEvent(page, "Space"),
                "Space on the leader shell must be cancelled, or the browser scrolls the grid out from " +
                    "under a hand that meant to type a mnemonic",
            )
            assertThat(page.locator(LEADER_GRID)).isVisible()
            assertThat(footer).containsText(LEADER_HINT)

            page.keyboard().press("Tab")
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

            // Use Ctrl because macOS may reserve Command letters before the page; the guard treats both alike.
            page.keyboard().press("Control+KeyI")
            assertEquals(
                "KeyI:default",
                lastKeyEvent(page, "KeyI"),
                "a modified letter was answered by the grid, so the browser lost its own binding for it",
            )
            assertThat(footer).containsText(LEADER_HINT)

            page.keyboard().press("KeyI")
            assertEquals("KeyI:prevented", lastKeyEvent(page, "KeyI"), "a bare mnemonic is the grid's")
            assertThat(footer).containsText("$INTERRUPT_COMMAND: no session is selected")
            assertThat(page.locator(PALETTE)).isVisible()

            page.keyboard().press("KeyN")
            assertThat(page.locator("#new-session-dialog")).isVisible()
            assertThat(page.locator(PALETTE)).hasCount(0)
        }

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

            // Playwright otherwise waits for the deliberately disabled control to enable.
            reserved.click(Locator.ClickOptions().setForce(true))
            assertThat(footer).containsText("$NOTIFICATIONS_COMMAND: not implemented yet")
            assertThat(page.locator(PALETTE)).isVisible()
        }

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

            page.keyboard().press("Escape")
            assertThat(page.locator("#prefs-dialog")).hasCount(0)
            assertThat(page.locator(PALETTE)).hasCount(0)

            openLeaderMode(page)
        }

    @Test
    fun theEntryPointsTheChromeNoLongerDrawsStayReachableFromThePalette() =
        onThePaletteScreen("palette-entry-points") { _, page ->
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

            openLeaderMode(page)
            page.keyboard().press("KeyN")
            assertThat(page.locator("#new-session-dialog")).isVisible()
        }


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

    private fun awaitSessionRows(page: Page) {
        assertThat(page.locator("#session-list .session-row[title='/d']")).isVisible()
    }

    private fun openLeaderMode(page: Page) {
        assertThat(page.locator(PALETTE)).hasCount(0)
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator(PALETTE)).isVisible()
        assertThat(page.locator(LEADER_GRID)).isVisible()
        assertLeaderOwnsTheKeyboard(page)
    }

    private fun assertLeaderOwnsTheKeyboard(page: Page) {
        assertThat(page.locator(LEADER_SHELL)).isFocused()
    }

    private fun openSearchMode(page: Page): Locator {
        openLeaderMode(page)
        page.keyboard().press("KeyK")
        val input = page.locator("#command-palette-query")
        assertThat(input).isVisible()
        assertThat(input).isFocused()
        return input
    }

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

    private fun lastKeyEvent(page: Page, code: String): String {
        val raw = page.evaluate("() => window.__kotgentKeys || []") as List<*>
        val entries = raw.map { it.toString() }
        return entries.lastOrNull { it.startsWith("$code:") }
            ?: "no $code reached the document at all (saw: $entries)"
    }

    private fun Locator.withText(text: String): Locator =
        filter(Locator.FilterOptions().setHasText(text))

    private companion object {
        const val PALETTE = "#command-palette"
        const val LEADER_GRID = ".command-palette-leader-grid"
        const val LEADER_COMMAND = ".command-palette-leader-command"
        const val ACTIVE_DESCENDANT = "aria-activedescendant"

        const val LEADER_SHELL = ".command-palette-shell.leader"

        const val LEADER_HINT = "Press a letter"

        val EXERCISED_MNEMONICS = listOf("b", "h", "i", "m", "n", "o", "p")

        const val INTERRUPT_COMMAND = "Interrupt current session"
        const val NOTIFICATIONS_COMMAND = "Toggle notifications"
        const val SHOW_DONE_COMMAND = "Show or hide done sessions"

        val REMOVED_CHROME_BUTTONS = listOf(
            "new-session-button",
            "phone-button",
            "help-button",
            "prefs-button",
            "session-actions",
        )
    }
}
