package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Geometry and layout, measured in a real browser.
 *
 * ## What this file is for
 * Every assertion here reads **geometry** — `getBoundingClientRect`, the terminal's own `cols`/`rows`,
 * whether an element is visible, and a computed *value* (a colour, a padding length) in two states. None
 * of it compares the TEXT of a CSS rule or of a JavaScript source file. That distinction is the whole
 * point of the tier: the source-level tests this file replaces could prove that `padding: 6px 8px` was
 * written somewhere in `style.css`, but not that the fitted terminal grid actually ends before the bottom
 * of its host, and not that the drawer a thumb opens is on screen afterwards.
 *
 * Superseded, in `test/transport/WebUiServingTest.kt` (deleted by the plan's final sweep, not here):
 * `xtermFitSubtractsThePaddingThatFramesTerminalContent`, `theWebUiShipsTheMobileDrawerAndViewportRules`,
 * `theDesktopSidebarCollapsesWithoutOverloadingTheMobileDrawer`,
 * `theShellFloatsCardsWithoutMovingPaddingOntoTheTerminalHost`,
 * `theWebUiShipsKeyboardAwareTerminalSizingAndFontPreferences` (its font half; the visual-viewport half is
 * a phone-keyboard contract with no headless signal and stays in manual verification), and
 * `theNotificationsToggleIsDrawnInTheShellsOwnAccentRatherThanAVendorEmoji`.
 *
 * ## What is deliberately NOT here
 * **Safe-area.** `env(safe-area-inset-*)` resolves to zero in a headless browser and `navigator.standalone`
 * cannot be produced at all, so an assertion about the notch, the Home indicator or the installed-PWA
 * `100vh` fallback would be an assertion about the constant `0` wearing the name of a real device. The
 * plan sends those to manual verification on purpose; manufacturing them here would recreate exactly the
 * false coverage this tier exists to remove.
 *
 * **The muted bell's innards.** The old notifications test asserted the mask element's `fill="#fff"` and
 * `stroke="#000"`, the direction of the slash path, and the ABSENCE of two hex literals from the
 * stylesheet. A rendered SVG offers no behavioural handle on any of that — a mask that cut the wrong shape
 * still renders, and "this hex string is nowhere in the file" is a statement about a file, not about a
 * page. What survives is what a viewer can actually tell apart: the two states carry different colours,
 * that colour is the shell's own token rather than a vendor's ink, and the muted state draws a
 * structurally different mark (it has a `<mask>`; the enabled one does not) rather than merely turning
 * red. The dropped claims have no replacement and are recorded as dropped.
 *
 * ## How `cols`/`rows` are read
 * `resources/webui/index.html` loads xterm as a CLASSIC script, so the library publishes `Terminal` onto
 * the global object; [TERMINAL_HOOK] is an init script that turns that global into an accessor and keeps
 * every constructed instance in `window.__kotgentTerminals`. The hook returns the real instance untouched
 * (a constructor that returns an object yields that object), so the page under test behaves exactly as it
 * does for an operator — the only thing added is a reference the test can ask `cols` and `rows` for. It
 * doubles as the check that a font change reuses the LIVE terminal instead of building a new one.
 */
class LayoutTest {

    /**
     * FitAddon measures the terminal's PARENT box, then subtracts padding read off the `.xterm` element
     * itself. Padding the parent instead makes the proposed grid one row too tall whenever the
     * unaccounted pixels cross a cell boundary, and `#terminal-host`'s `overflow: hidden` then clips the
     * last row — silently, because nothing errors and the grid is only wrong by one line.
     *
     * "Whenever ... crosses a cell boundary" is why this test SWEEPS the viewport height one pixel at a
     * time instead of measuring once: at a single arbitrary height the broken and the correct arithmetic
     * agree most of the time, so a single measurement would pass against a regression roughly
     * `1 - padding/cellHeight` of the time. The sweep runs until it has seen the row count actually
     * change, which is proof it crossed the boundary where the two answers differ, and asserts the full
     * invariant at every step on both sides of it. The sweep failing to change the row count at all is
     * itself a failure — a vacuous sweep is not a passing one.
     *
     * The width half of the same invariant carries a second contract for free. The addon also reserves a
     * FIXED 14px for a scroll bar unless `scrollback` is exactly 0 — no measurement, no check that a bar
     * could ever appear. kotgent sets `scrollback: 0` (history belongs to the tmux pane, which the forced
     * `mouse on` lets a wheel reach), and the maximality assertion below — one more column would not fit —
     * fails if that reservation ever comes back, because a reserved 14px is nearly two of Menlo's columns.
     */
    @Test
    fun theFittedGridFillsTheHostMinusItsGutterAndClipsNoRow() {
        Harness(TERMINAL_SCENARIO).use { harness ->
            onDesktop(harness, "layout-fit") { page ->
                attachTerminal(page)

                val base = measureTerminal(page)
                assertFitInvariant(base, "the initial desktop viewport")
                val cellHeight = base.num("gridHeight") / base.num("rows")
                assertTrue(
                    cellHeight > 1.0,
                    "a terminal cell must have a real height before this sweep means anything, got $cellHeight",
                )

                // One cell's worth of pixels plus slack is enough to guarantee a boundary crossing
                // whatever phase the initial height happened to land on.
                val maxSteps = ceil(cellHeight).toInt() + 2
                val rowCountsSeen = mutableSetOf(base.int("rows"))
                var step = 1
                while (step <= maxSteps && rowCountsSeen.size < 2) {
                    val height = DESKTOP_HEIGHT - step
                    page.setViewportSize(DESKTOP_WIDTH, height)
                    settleTerminal(page)
                    val measured = measureTerminal(page)
                    assertFitInvariant(measured, "a ${DESKTOP_WIDTH}x$height viewport")
                    rowCountsSeen += measured.int("rows")
                    step += 1
                }
                assertTrue(
                    rowCountsSeen.size >= 2,
                    "sweeping $maxSteps pixels of viewport height never changed the row count " +
                        "(saw $rowCountsSeen), so the sweep never reached the boundary where a grid " +
                        "fitted to the unsubtracted box would differ from one fitted to the padded box — " +
                        "the invariant above was therefore never actually exercised",
                )
            }
        }
    }

    /**
     * The mobile drawer, driven by each of the three controls that exist only below the breakpoint.
     *
     * All three (`#drawer-toggle`, `#drawer-close`, `.drawer-scrim`) are `display: none` above 720px and
     * live in the DOM on every screen, so "the phone has them" is a visibility question, not a markup one.
     * The closed drawer is translated fully off the left edge AND made `visibility: hidden`, because a
     * translated-away drawer is still focusable and a keyboard or VoiceOver user would otherwise walk into
     * invisible controls — so both halves are asserted: off-screen by geometry, hidden by Playwright's own
     * visibility rule.
     *
     * The drawer opener is asserted to be an OVERLAY rather than a column: on a phone the terminal card is
     * full-bleed, and the open drawer sits on top of it instead of pushing it aside. That is the difference
     * between the mobile rule and the desktop layout, and it is only visible in the two boxes' coordinates.
     */
    @Test
    fun theMobileDrawerOpensAndClosesFromEachOfItsThreeControls() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onPhone(harness, "layout-drawer") { page ->
                // The desktop collapse control does not exist for a thumb; the drawer opener does.
                assertThat(page.locator("#sidebar-toggle")).isHidden()
                assertThat(page.locator("#drawer-toggle")).isVisible()
                assertThat(page.locator("#sidebar")).isHidden()

                val closed = measureShell(page)
                assertTrue(
                    closed.num("sidebarRight") <= EDGE_EPS,
                    "a closed drawer is translated fully off the left edge, but its right edge is at " +
                        "${closed.num("sidebarRight")}",
                )
                assertEquals(
                    0,
                    closed.int("scrimPresent"),
                    "there is no scrim to tap while the drawer is closed",
                )

                page.locator("#drawer-toggle").click()
                waitForDrawer(page, open = true)
                assertThat(page.locator("#sidebar")).isVisible()

                val open = measureShell(page)
                assertTrue(
                    abs(open.num("sidebarLeft")) <= EDGE_EPS,
                    "the open drawer starts at the left edge, not at ${open.num("sidebarLeft")}",
                )
                assertTrue(
                    open.num("sidebarWidth") >= MIN_DRAWER_WIDTH &&
                        open.num("sidebarWidth") <= open.num("viewportWidth"),
                    "the open drawer is a real, on-screen panel: ${open.num("sidebarWidth")}px wide in a " +
                        "${open.num("viewportWidth")}px viewport",
                )
                // An overlay, not a flex column: the terminal card keeps the whole width underneath it.
                assertTrue(
                    abs(open.num("paneLeft")) <= EDGE_EPS &&
                        open.num("paneWidth") >= open.num("viewportWidth") - EDGE_EPS,
                    "the phone terminal stays full-bleed under the drawer, but its box is " +
                        "${open.num("paneLeft")}..${open.num("paneRight")}",
                )
                assertTrue(
                    open.num("sidebarRight") > open.num("paneLeft") + EDGE_EPS,
                    "the drawer overlaps the terminal it covers",
                )
                assertThat(page.locator(".drawer-scrim")).isVisible()
                assertTrue(
                    open.num("scrimWidth") >= open.num("viewportWidth") - EDGE_EPS &&
                        open.num("scrimHeight") >= open.num("viewportHeight") - EDGE_EPS,
                    "the scrim covers the whole viewport, so a tap anywhere outside the drawer closes it",
                )

                // Closed from inside: the scrim covers the hamburger that opened it, which is exactly why
                // this button exists.
                page.locator("#drawer-close").click()
                waitForDrawer(page, open = false)
                assertThat(page.locator("#sidebar")).isHidden()
                assertThat(page.locator(".drawer-scrim")).hasCount(0)

                // Closed from outside. The scrim spans the whole viewport, so its CENTRE — the point
                // Playwright clicks by default — is under the drawer, where a session row swallows the
                // tap. "Outside" is the strip the drawer does not cover, so the point is derived from the
                // drawer's own measured right edge rather than guessed, and Playwright's hit-target check
                // then doubles as proof that the scrim really is what a thumb reaches there.
                page.locator("#drawer-toggle").click()
                waitForDrawer(page, open = true)
                val reopened = measureShell(page)
                val outsideX = (reopened.num("sidebarRight") + reopened.num("viewportWidth")) / 2
                assertTrue(
                    outsideX > reopened.num("sidebarRight") + EDGE_EPS,
                    "the drawer leaves a strip of scrim beside it to tap: it is " +
                        "${reopened.num("sidebarWidth")}px wide in a ${reopened.num("viewportWidth")}px " +
                        "viewport",
                )
                page.locator(".drawer-scrim").click(
                    Locator.ClickOptions().setPosition(
                        outsideX - reopened.num("scrimLeft"),
                        reopened.num("viewportHeight") / 2 - reopened.num("scrimTop"),
                    ),
                )
                waitForDrawer(page, open = false)
                assertThat(page.locator("#sidebar")).isHidden()
            }
        }
    }

    /**
     * The desktop collapse, and the proof it does not overload the drawer.
     *
     * `⌘1` is reserved for tab switching in an ordinary browser tab (it is reliable only in the installed
     * PWA), so `#sidebar-toggle` is the guaranteed path and the one this drives. Collapsing is measured
     * where it is meant to be felt: the sidebar's box goes to zero width and the terminal pane takes the
     * space back.
     *
     * The second half is the interesting one. The collapse is a persisted, app-level state, so it survives
     * the window becoming phone-sized — where the sidebar is no longer a flex column at all but a fixed
     * overlay drawer. Nothing clears the state on the way; the mobile rule re-declares the drawer for
     * `#sidebar` and `#sidebar.collapsed` alike, which is what keeps a phone from inheriting a
     * zero-width, zero-padding drawer that opens onto nothing. This test therefore collapses on a desktop
     * viewport, narrows the SAME page to a phone, and asserts the drawer still opens to full width while
     * `#sidebar` demonstrably still carries the `collapsed` class — the class is what makes it a test of
     * the media query rather than of some hidden reset.
     */
    @Test
    fun theDesktopSidebarCollapsesAndANarrowedWindowStillOpensAFullDrawer() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onDesktop(harness, "layout-collapse") { page ->
                assertThat(page.locator("#drawer-toggle")).isHidden()
                assertThat(page.locator("#sidebar-toggle")).isVisible()

                val expanded = measureShell(page)
                assertTrue(
                    expanded.num("sidebarWidth") >= MIN_DRAWER_WIDTH,
                    "the desktop sidebar starts as a real column, got ${expanded.num("sidebarWidth")}px",
                )

                page.locator("#sidebar-toggle").click()
                page.waitForFunction(SIDEBAR_COLLAPSED)
                val collapsed = measureShell(page)
                assertTrue(
                    collapsed.num("sidebarWidth") < 1.0,
                    "a collapsed sidebar occupies no width at all, got ${collapsed.num("sidebarWidth")}px",
                )
                assertThat(page.locator("#sidebar")).isHidden()
                assertTrue(
                    collapsed.num("paneWidth") >= expanded.num("paneWidth") + MIN_DRAWER_WIDTH,
                    "the terminal pane takes the freed column: ${expanded.num("paneWidth")}px -> " +
                        "${collapsed.num("paneWidth")}px",
                )
                assertTrue(
                    collapsed.num("paneLeft") < expanded.num("paneLeft"),
                    "and moves left to claim it",
                )

                // Expanding again restores the column — a one-way collapse would be a trap.
                page.locator("#sidebar-toggle").click()
                page.waitForFunction(SIDEBAR_EXPANDED)
                assertThat(page.locator("#sidebar")).isVisible()
                assertTrue(
                    abs(measureShell(page).num("sidebarWidth") - expanded.num("sidebarWidth")) <= EDGE_EPS,
                    "the restored column is the width it was",
                )

                // Collapse, then narrow the window under the collapsed state.
                page.locator("#sidebar-toggle").click()
                page.waitForFunction(SIDEBAR_COLLAPSED)
                page.setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)

                assertThat(page.locator("#sidebar-toggle")).isHidden()
                assertThat(page.locator("#drawer-toggle")).isVisible()
                // The class is still there: what follows is a test of the media query neutralizing the
                // desktop state, not of some reset quietly clearing it on the way down.
                assertThat(page.locator("#sidebar.collapsed")).hasCount(1)

                page.locator("#drawer-toggle").click()
                waitForDrawer(page, open = true)
                val drawer = measureShell(page)
                assertTrue(
                    drawer.num("sidebarWidth") >= MIN_DRAWER_WIDTH,
                    "the drawer opens to its full width even though the desktop collapse is still set, " +
                        "got ${drawer.num("sidebarWidth")}px",
                )
                assertTrue(
                    abs(drawer.num("sidebarLeft")) <= EDGE_EPS,
                    "and it is on screen, not parked at ${drawer.num("sidebarLeft")}",
                )
            }
        }
    }

    /**
     * The shell floats its cards without moving padding onto the measured terminal parent.
     *
     * Two boxes are involved and the difference between them is the whole rule. `#terminal-host` is what
     * FitAddon MEASURES; `#terminal-host .xterm` is what it subtracts padding FROM. So the card's inset
     * lives on `#terminal-pane` as a margin, the visible gutter around the glyphs lives on `.xterm` as
     * padding, and the host in between must have neither: any padding there is pixels the addon counts as
     * available and the browser then hides.
     *
     * That is asserted twice over — the host's computed padding is zero on all four sides, and the
     * `.xterm` box coincides with the host box edge for edge, which is the same statement made without
     * consulting a stylesheet at all.
     *
     * The phone half is the reverse: the card gives up its margin, radius and shadow to become the whole
     * screen, and the gutter rule must not travel with it. Same two assertions, full-bleed geometry.
     *
     * The one non-geometric reading here is `backdrop-filter`, taken as a computed VALUE in two viewport
     * states (the same shape as the colour reading in the notifications test below). It is kept because
     * the invariant it guards is real and has no geometric signature: a phone's terminal repaints
     * continuously behind the drawer, and a composite blur over it is a frame-rate cost that no
     * measurement of a box would ever reveal.
     */
    @Test
    fun theCardsInsetTheShellWithoutPaddingTheMeasuredTerminalParent() {
        Harness(TERMINAL_SCENARIO).use { harness ->
            onDesktop(harness, "layout-cards") { page ->
                attachTerminal(page)

                val desktop = measureShell(page)
                for ((edge, inset) in listOf(
                    "left" to desktop.num("paneLeft") - desktop.num("appLeft"),
                    "top" to desktop.num("paneTop") - desktop.num("appTop"),
                    "right" to desktop.num("appRight") - desktop.num("paneRight"),
                    "bottom" to desktop.num("appBottom") - desktop.num("paneBottom"),
                )) {
                    assertTrue(inset > 0.0, "the terminal card is inset from the shell's $edge, got $inset")
                }
                assertTrue(
                    desktop.num("sidebarLeft") > desktop.num("appLeft"),
                    "the sidebar card is inset too",
                )
                assertTrue(
                    desktop.num("paneLeft") > desktop.num("sidebarRight"),
                    "and the two cards float apart rather than sharing a rail",
                )
                assertNoGutterOnTheMeasuredParent(desktop, "the desktop card layout")
                assertTrue(
                    desktop.str("sidebarBlur") != "none",
                    "the desktop sidebar is the translucent one: it composites a blur",
                )

                // The same page, narrowed: the card becomes the screen.
                page.setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
                settleTerminal(page)
                val phone = measureShell(page)
                assertTrue(
                    abs(phone.num("paneLeft") - phone.num("appLeft")) <= EDGE_EPS &&
                        abs(phone.num("paneRight") - phone.num("appRight")) <= EDGE_EPS,
                    "the phone terminal is full-bleed, but sits at ${phone.num("paneLeft")}.." +
                        "${phone.num("paneRight")} inside ${phone.num("appLeft")}..${phone.num("appRight")}",
                )
                assertNoGutterOnTheMeasuredParent(phone, "the phone full-bleed layout")
                assertEquals(
                    "none",
                    phone.str("sidebarBlur"),
                    "the phone drawer never enables a composite blur over a repainting terminal",
                )

                // And the grid is still fitted to the padded box after that reflow.
                assertFitInvariant(measureTerminal(page), "the phone viewport")
            }
        }
    }

    /**
     * The terminal font preference is a view preference, not a new attachment.
     *
     * Two things have to be true at once and only one of them is visible on screen. The grid must actually
     * reshape — a bigger cell in the same box is fewer columns and fewer rows — and the LIVE xterm must be
     * the one that changed, because rebuilding it would drop the one upstream `tmux attach` WebSocket and
     * the operator would watch their session blink. The hook counts constructed terminals, which is the
     * only way to tell those two apart from outside: a reconnect renders a terminal that looks identical.
     *
     * The route in is the command palette's leader grid, which is where rare Web UI actions live. The
     * base-path note is the other way to Preferences and is not rendered here, since the fixture leaves
     * `basePath` empty and grouping therefore off.
     */
    @Test
    fun theTerminalFontPreferenceReshapesTheLiveGridWithoutBuildingANewTerminal() {
        Harness(TERMINAL_SCENARIO).use { harness ->
            onDesktop(harness, "layout-font") { page ->
                attachTerminal(page)

                val before = measureTerminal(page)
                assertEquals(
                    DEFAULT_TERMINAL_FONT_SIZE.toDouble(),
                    before.num("fontSize"),
                    "the fixture starts on the default terminal font step",
                )
                assertEquals(1, before.int("terminals"), "exactly one terminal has been constructed")

                page.locator("#palette-button").click()
                assertThat(page.locator("#command-palette")).isVisible()
                page.locator(".command-palette-leader-command:has-text(\"Preferences\")").click()
                assertThat(page.locator("#prefs-dialog")).isVisible()
                page.locator("#prefs-terminal-font-size").selectOption(LARGEST_TERMINAL_FONT_SIZE.toString())
                page.locator("#prefs-submit").click()
                assertThat(page.locator("#prefs-dialog")).hasCount(0)

                page.waitForFunction(fontSizeApplied(LARGEST_TERMINAL_FONT_SIZE))
                settleTerminal(page)
                val after = measureTerminal(page)

                assertEquals(
                    1,
                    after.int("terminals"),
                    "a font change must reuse the live terminal — a second construction means the " +
                        "attachment, and its upstream WebSocket, was torn down and rebuilt",
                )
                assertTrue(
                    after.num("cols") < before.num("cols") && after.num("rows") < before.num("rows"),
                    "a larger cell in the same box is a smaller grid: " +
                        "${before.int("cols")}x${before.int("rows")} -> " +
                        "${after.int("cols")}x${after.int("rows")}",
                )
                // The reshaped grid obeys the same fit rule; a re-fit that forgot the gutter would clip.
                assertFitInvariant(after, "the enlarged terminal font")
            }
        }
    }

    /**
     * The notifications toggle wears the shell's own accent, in both of its states.
     *
     * Colour is read as a computed VALUE and compared against the shell's own token resolved through a
     * throwaway probe element, so the assertion is "this button is painted the colour `--accent` names",
     * not "the stylesheet contains the string `var(--accent)`". Both directions are driven, because a
     * toggle that only lights up is half a toggle.
     *
     * The state is in the mark as well as in its colour: the muted bell is the same bell masked and then
     * struck through, so the off state renders a `<mask>` element the on state does not. A colour swap
     * alone would leave the muted button reading as a bell that merely went red — which is the reason the
     * mark is drawn rather than typed, and why the button's own text content must stay empty (a vendor
     * emoji would bring its own colour and ignore the shell's).
     *
     * The click is a real one and the push handshake behind it cannot complete headless — there is no push
     * service, and the notification permission is never granted. That is the point rather than a
     * limitation: the toggle is the per-device in-tab preference and lands whatever the handshake does, so
     * a failed subscription must not un-press it.
     */
    @Test
    fun theNotificationsToggleWearsTheShellsOwnAccentInBothStates() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onDesktop(harness, "layout-notify") { page ->
                val toggle = page.locator("#notify-toggle")
                assertThat(toggle).isVisible()
                assertThat(toggle).hasAttribute("aria-pressed", "false")

                val off = measureNotifyToggle(page)
                assertEquals(
                    "",
                    off.str("label"),
                    "the toggle types no glyph of its own — the mark is drawn, so it can take the " +
                        "shell's colour instead of a vendor's",
                )
                assertTrue(
                    off.num("buttonWidth") >= MIN_TOGGLE_BOX && off.num("buttonHeight") >= MIN_TOGGLE_BOX,
                    "the toggle keeps a real hit box (${off.num("buttonWidth")}x" +
                        "${off.num("buttonHeight")}) even though nothing paints a button around it",
                )
                assertTrue(
                    off.num("markWidth") > 0.0 && off.num("markHeight") > 0.0,
                    "and the mark inside it has a box of its own",
                )
                assertEquals(
                    off.str("attnColor"),
                    off.str("color"),
                    "off wears the attention colour the shell defines",
                )
                assertEquals(
                    off.str("color"),
                    off.str("markFill"),
                    "the mark takes the button's colour rather than baking one in",
                )
                assertEquals(
                    1,
                    off.int("maskCount"),
                    "the muted mark is the bell masked by the slash it is struck with",
                )

                toggle.click()
                assertThat(toggle).hasAttribute("aria-pressed", "true")
                page.waitForFunction(NOTIFY_TOGGLE_ACTIVE)

                val on = measureNotifyToggle(page)
                assertEquals(
                    on.str("accentColor"),
                    on.str("color"),
                    "on wears the shell's accent",
                )
                assertEquals(
                    on.str("color"),
                    on.str("markFill"),
                    "and the mark follows it in this state too",
                )
                assertTrue(
                    on.str("color") != off.str("color"),
                    "the two states are distinguishable at a glance: both painted ${on.str("color")}",
                )
                assertEquals(
                    0,
                    on.int("maskCount"),
                    "the enabled mark is the bare bell — the difference is a shape, not only a colour",
                )

                // Both directions: a toggle that cannot be turned back off is not a toggle.
                toggle.click()
                assertThat(toggle).hasAttribute("aria-pressed", "false")
                page.waitForFunction(NOTIFY_TOGGLE_INACTIVE)
                assertEquals(
                    off.str("color"),
                    measureNotifyToggle(page).str("color"),
                    "turning it off returns it to the attention colour",
                )
            }
        }
    }
}

// --- fixtures ---------------------------------------------------------------------------------------

/** The `terminal` scenario's single session, per the frozen fixture contract. */
private const val TERMINAL_SCENARIO = "terminal"
private const val TERMINAL_SESSION_ID = "s-term"

/** The last thing that scenario's payload prints, so seeing it means the whole payload has landed. */
private const val TERMINAL_BANNER = "KOTGENT-TERMINAL-READY"

/** Wide enough for the sidebar column plus a terminal with room to lose a row and still be legible. */
private const val DESKTOP_WIDTH = 1280
private const val DESKTOP_HEIGHT = 900
private const val PHONE_WIDTH = 390
private const val PHONE_HEIGHT = 844

/** `lib/prefs.js` ships three steps; these are the two ends this file drives. */
private const val DEFAULT_TERMINAL_FONT_SIZE = 13
private const val LARGEST_TERMINAL_FONT_SIZE = 16

/**
 * Slack for measurements that pass through the addon's `parseInt` and the renderer's device-pixel
 * rounding. Two pixels is far below one terminal cell in either axis, so it can never hide a lost row or
 * a lost column — which is all these assertions are about.
 */
private const val EPS = 2.0

/** Slack for two boxes that are meant to coincide exactly. */
private const val EDGE_EPS = 1.0

/** A drawer or sidebar column narrower than this is not a panel anybody can use. */
private const val MIN_DRAWER_WIDTH = 200.0

/** `.icon-button-small` is a 28px box; anything much smaller is not a thumb target. */
private const val MIN_TOGGLE_BOX = 24.0

/**
 * How long the terminal's geometry has to hold still before it counts as settled. `TerminalPane.js`
 * debounces its re-fit by 120ms, so anything comfortably past that means the debounce fired and the fit
 * behind it finished.
 */
private const val SETTLE_QUIET_MILLIS = 220

/**
 * Capture every xterm `Terminal` the page constructs.
 *
 * `index.html` loads xterm as a classic script whose UMD tail assigns each export onto the global object,
 * so replacing `Terminal` with an accessor is enough to see the constructor without touching a line of
 * application code. The wrapper builds the real instance and returns it, and a constructor returning an
 * object yields that object — so `new Terminal(...)` in `TerminalPane.js` receives exactly what it would
 * have received anyway.
 */
private val TERMINAL_HOOK = """
    (() => {
      const seen = [];
      let real = null;
      const Hooked = function (options) {
        const term = new real(options);
        seen.push(term);
        return term;
      };
      window.__kotgentTerminals = seen;
      Object.defineProperty(window, "Terminal", {
        configurable: true,
        get: () => (real === null ? undefined : Hooked),
        set: (value) => { real = value; },
      });
    })();
""".trimIndent()

/**
 * A terminal that has stopped moving.
 *
 * Waiting a fixed number of milliseconds would be a guess; waiting for the fit invariant itself would
 * turn a real regression into an unhelpful timeout. So this waits for QUIET: the host box, the grid box
 * and the reported `cols`/`rows` unchanged for [SETTLE_QUIET_MILLIS], which is comfortably longer than
 * `TerminalPane.js`'s 120ms re-fit debounce and therefore proves the debounce fired and the fit that
 * followed it completed. The DOM row count is required to agree with the reported row count in the same
 * breath, so a half-rendered grid is never measured. Whatever it settles ON is then asserted in Kotlin,
 * where a failure can say what it saw.
 *
 * [SETTLE_RESET] must run first: the sampler's state is a page global, and a leftover sample from the
 * previous settle would otherwise be old enough to satisfy the quiet period on the very first poll.
 */
private val SETTLE_RESET = "() => { window.__kotgentSettle = null; }"

private val TERMINAL_SETTLED = """
    () => {
      const host = document.querySelector("#terminal-host");
      const screen = host && host.querySelector(".xterm-screen");
      const term = (window.__kotgentTerminals || []).slice(-1)[0];
      if (!host || !screen || !term) return false;
      const h = host.getBoundingClientRect();
      const g = screen.getBoundingClientRect();
      const key = [h.width, h.height, g.width, g.height, term.cols, term.rows].join("x");
      const now = performance.now();
      const previous = window.__kotgentSettle;
      if (!previous || previous.key !== key) {
        window.__kotgentSettle = { key: key, since: now };
        return false;
      }
      if (now - previous.since < $SETTLE_QUIET_MILLIS) return false;
      return term.cols > 2 && host.querySelectorAll(".xterm-rows > div").length === term.rows;
    }
""".trimIndent()

/**
 * The sidebar at the END of its 180ms `width` transition, on the side [widthTest] names.
 *
 * The width alone is not a predicate. `width >= 1` is already true on the transition's FIRST frame, so a
 * measurement taken behind it samples the animation in flight and the restored column reads as some
 * arbitrary intermediate value — the width-was-restored assertion then fails on a column that was on its
 * way to exactly the right place. Chromium keeps a running CSS transition in `Element.getAnimations()` and
 * drops it the moment it finishes, so "no unfinished animation on `#sidebar`" is the arrival signal, and
 * the width test says which end it arrived at. Both halves are needed: before the class change lands there
 * is no animation either, and the width is still the one the click is about to move away from.
 */
private fun sidebarSettled(widthTest: String): String = """
    () => {
      const el = document.querySelector("#sidebar");
      if (!el) return false;
      if (el.getAnimations().some((a) => a.playState !== "finished")) return false;
      const width = el.getBoundingClientRect().width;
      return $widthTest;
    }
""".trimIndent()

private val SIDEBAR_COLLAPSED = sidebarSettled("width < 1")

private val SIDEBAR_EXPANDED = sidebarSettled("width >= 1")

private val NOTIFY_TOGGLE_ACTIVE = """
    () => {
      const el = document.querySelector("#notify-toggle");
      return !!el && el.classList.contains("active") && !!el.querySelector("svg");
    }
""".trimIndent()

private val NOTIFY_TOGGLE_INACTIVE = """
    () => {
      const el = document.querySelector("#notify-toggle");
      return !!el && !el.classList.contains("active") && !!el.querySelector("svg");
    }
""".trimIndent()

/**
 * Everything the fit invariant needs, in one round trip.
 *
 * The grid box is `.xterm-screen`, whose width and height the DOM renderer sets to the canvas dimensions
 * — `cell.width * cols` by `cell.height * rows`. Dividing by the terminal's own `cols`/`rows` therefore
 * recovers the cell size the addon divided by, without reaching into any private field.
 */
private val MEASURE_TERMINAL = """
    () => {
      const host = document.querySelector("#terminal-host");
      const xterm = host && host.querySelector(".xterm");
      const screen = host && host.querySelector(".xterm-screen");
      const rowEls = host ? host.querySelectorAll(".xterm-rows > div") : [];
      const term = (window.__kotgentTerminals || []).slice(-1)[0];
      if (!host || !xterm || !screen || rowEls.length === 0 || !term) return { ready: 0 };
      const xs = getComputedStyle(xterm);
      const hs = getComputedStyle(host);
      const h = host.getBoundingClientRect();
      const x = xterm.getBoundingClientRect();
      const g = screen.getBoundingClientRect();
      const first = rowEls[0].getBoundingClientRect();
      const last = rowEls[rowEls.length - 1].getBoundingClientRect();
      return {
        ready: 1,
        cols: term.cols,
        rows: term.rows,
        fontSize: term.options.fontSize,
        terminals: (window.__kotgentTerminals || []).length,
        rowCount: rowEls.length,
        hostLeft: h.left, hostTop: h.top, hostRight: h.right, hostBottom: h.bottom,
        hostWidth: h.width, hostHeight: h.height,
        xtermLeft: x.left, xtermTop: x.top, xtermRight: x.right, xtermBottom: x.bottom,
        gridWidth: g.width, gridHeight: g.height,
        firstRowHeight: first.height,
        lastRowTop: last.top, lastRowBottom: last.bottom, lastRowHeight: last.height,
        padTop: parseFloat(xs.paddingTop), padBottom: parseFloat(xs.paddingBottom),
        padLeft: parseFloat(xs.paddingLeft), padRight: parseFloat(xs.paddingRight),
        hostPadTop: parseFloat(hs.paddingTop), hostPadBottom: parseFloat(hs.paddingBottom),
        hostPadLeft: parseFloat(hs.paddingLeft), hostPadRight: parseFloat(hs.paddingRight)
      };
    }
""".trimIndent()

/** The shell's boxes, flattened so a Kotlin caller reads plain numbers rather than nested maps. */
private val MEASURE_SHELL = """
    () => {
      const out = {};
      const add = (name, selector) => {
        const el = document.querySelector(selector);
        if (!el) { out[name + "Present"] = 0; return; }
        out[name + "Present"] = 1;
        const r = el.getBoundingClientRect();
        out[name + "Left"] = r.left;
        out[name + "Top"] = r.top;
        out[name + "Right"] = r.right;
        out[name + "Bottom"] = r.bottom;
        out[name + "Width"] = r.width;
        out[name + "Height"] = r.height;
      };
      add("app", "#app");
      add("sidebar", "#sidebar");
      add("pane", "#terminal-pane");
      add("host", "#terminal-host");
      add("xterm", "#terminal-host .xterm");
      add("scrim", ".drawer-scrim");
      const host = document.querySelector("#terminal-host");
      const xterm = host && host.querySelector(".xterm");
      const hs = host ? getComputedStyle(host) : null;
      const xs = xterm ? getComputedStyle(xterm) : null;
      out.hostPadTop = hs ? parseFloat(hs.paddingTop) : -1;
      out.hostPadBottom = hs ? parseFloat(hs.paddingBottom) : -1;
      out.hostPadLeft = hs ? parseFloat(hs.paddingLeft) : -1;
      out.hostPadRight = hs ? parseFloat(hs.paddingRight) : -1;
      out.xtermPadTop = xs ? parseFloat(xs.paddingTop) : -1;
      out.xtermPadBottom = xs ? parseFloat(xs.paddingBottom) : -1;
      out.xtermPadLeft = xs ? parseFloat(xs.paddingLeft) : -1;
      out.xtermPadRight = xs ? parseFloat(xs.paddingRight) : -1;
      const sidebar = document.querySelector("#sidebar");
      out.sidebarBlur = sidebar ? (getComputedStyle(sidebar).backdropFilter || "none") : "absent";
      out.viewportWidth = window.innerWidth;
      out.viewportHeight = window.innerHeight;
      return out;
    }
""".trimIndent()

/**
 * The toggle's two colours, its mark, and the shell's own tokens resolved through a throwaway probe.
 *
 * The probe is how a token becomes a comparable value: `getComputedStyle(:root).getPropertyValue("--attn")`
 * answers with the token's raw text, while a colour read off a painted element is always `rgb(...)`.
 * Painting a scratch element with `var(--attn)` and reading ITS colour back puts both sides of the
 * comparison in the same space, which is what makes this a value assertion rather than a string one.
 */
private val MEASURE_NOTIFY_TOGGLE = """
    () => {
      const button = document.querySelector("#notify-toggle");
      if (!button) return { ready: 0 };
      const mark = button.querySelector("svg");
      if (!mark) return { ready: 0 };
      const probe = (token) => {
        const el = document.createElement("span");
        el.style.color = "var(" + token + ")";
        document.body.appendChild(el);
        const value = getComputedStyle(el).color;
        el.remove();
        return value;
      };
      const b = button.getBoundingClientRect();
      const m = mark.getBoundingClientRect();
      return {
        ready: 1,
        label: button.textContent.trim(),
        active: button.classList.contains("active") ? 1 : 0,
        color: getComputedStyle(button).color,
        markFill: getComputedStyle(mark).fill,
        maskCount: mark.querySelectorAll("mask").length,
        buttonWidth: b.width, buttonHeight: b.height,
        markWidth: m.width, markHeight: m.height,
        attnColor: probe("--attn"),
        accentColor: probe("--accent")
      };
    }
""".trimIndent()

private fun fontSizeApplied(size: Int): String = """
    () => {
      const term = (window.__kotgentTerminals || []).slice(-1)[0];
      return !!term && term.options.fontSize === $size;
    }
""".trimIndent()

/** A drawer whose transform has finished travelling, in whichever direction it was sent. */
private fun waitForDrawer(page: Page, open: Boolean) {
    val predicate = if (open) {
        """
        () => {
          const el = document.querySelector("#sidebar");
          if (!el) return false;
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.left >= -0.5;
        }
        """.trimIndent()
    } else {
        """
        () => {
          const el = document.querySelector("#sidebar");
          if (!el) return false;
          return el.getBoundingClientRect().right <= 0.5;
        }
        """.trimIndent()
    }
    page.waitForFunction(predicate)
}

/** Select the fixture's one live session and wait until its terminal has painted its whole payload. */
private fun attachTerminal(page: Page) {
    page.locator("#session-list .session-row[data-id='$TERMINAL_SESSION_ID']").click()
    assertThat(page.locator("#terminal-host .xterm")).isVisible()
    // The banner is the payload's last line, so its arrival means the pty, the bridge, the socket and the
    // renderer are all in place — and that the grid being measured is a grid with content in it.
    assertThat(page.locator("#terminal-host")).containsText(TERMINAL_BANNER)
    settleTerminal(page)
}

private fun settleTerminal(page: Page) {
    page.evaluate(SETTLE_RESET)
    page.waitForFunction(TERMINAL_SETTLED)
}

private fun measureTerminal(page: Page): PageValues {
    val values = page.values(MEASURE_TERMINAL)
    assertEquals(
        1,
        values.int("ready"),
        "the terminal, its grid box and the hooked Terminal instance must all exist before measuring; " +
            "an empty __kotgentTerminals means the init-script hook never saw xterm publish its global",
    )
    return values
}

private fun measureShell(page: Page): PageValues {
    val values = page.values(MEASURE_SHELL)
    for (name in listOf("app", "sidebar", "pane")) {
        assertEquals(1, values.int(name + "Present"), "the shell renders #$name")
    }
    return values
}

private fun measureNotifyToggle(page: Page): PageValues {
    val values = page.values(MEASURE_NOTIFY_TOGGLE)
    assertEquals(1, values.int("ready"), "the notifications toggle renders a mark of its own")
    return values
}

/**
 * The measured parent carries no gutter of its own — stated once from the computed padding and once from
 * the two boxes' coordinates, so neither reading has to be trusted alone.
 */
private fun assertNoGutterOnTheMeasuredParent(shell: PageValues, where: String) {
    assertEquals(1, shell.int("hostPresent"), "$where: the terminal host is rendered")
    assertEquals(1, shell.int("xtermPresent"), "$where: xterm has opened inside it")
    for (side in listOf("Top", "Bottom", "Left", "Right")) {
        assertEquals(
            0.0,
            shell.num("hostPad$side"),
            "$where: #terminal-host is the box FitAddon measures, so padding on its $side would be " +
                "pixels the addon counts as available and the browser then clips",
        )
        assertTrue(
            shell.num("xtermPad$side") > 0.0,
            "$where: the visible gutter belongs on .xterm, the element the addon subtracts padding from",
        )
    }
    for ((edge, delta) in listOf(
        "left" to shell.num("xtermLeft") - shell.num("hostLeft"),
        "top" to shell.num("xtermTop") - shell.num("hostTop"),
        "right" to shell.num("xtermRight") - shell.num("hostRight"),
        "bottom" to shell.num("xtermBottom") - shell.num("hostBottom"),
    )) {
        assertTrue(
            abs(delta) <= EDGE_EPS,
            "$where: .xterm fills its measured parent exactly; the $edge edges differ by $delta",
        )
    }
    // Stated once more without any stylesheet in the loop: the card's own inset is a margin on the pane,
    // and the pane fully contains the host it measures.
    assertTrue(
        shell.num("hostLeft") >= shell.num("paneLeft") - EDGE_EPS &&
            shell.num("hostRight") <= shell.num("paneRight") + EDGE_EPS,
        "$where: the terminal host lives inside its card",
    )
}

/**
 * The complete fit contract, at one measured moment.
 *
 * The addon computes `rows = floor(available / cellHeight)` and `cols = floor(available / cellWidth)`, so
 * the grid must both FIT inside the padded box and be MAXIMAL within it. The first half catches a grid
 * fitted to a box that was never reduced by the gutter — its last row is then hidden by the host's
 * `overflow: hidden`, which is precisely the bug this whole file exists for. The second half catches a box
 * reduced by MORE than the gutter: a returning scroll-bar reservation, a padding that migrated onto the
 * measured parent, a gutter counted twice.
 */
private fun assertFitInvariant(measured: PageValues, where: String) {
    val cols = measured.num("cols")
    val rows = measured.num("rows")
    assertTrue(cols > 2.0 && rows > 1.0, "$where: the grid is ${cols}x$rows, i.e. the addon's floor")
    assertEquals(
        measured.int("rows"),
        measured.int("rowCount"),
        "$where: the rendered row elements are the rows the terminal reports",
    )

    val cellWidth = measured.num("gridWidth") / cols
    val cellHeight = measured.num("gridHeight") / rows
    assertTrue(
        abs(measured.num("firstRowHeight") - cellHeight) <= EDGE_EPS,
        "$where: a rendered row is one cell tall ($cellHeight vs ${measured.num("firstRowHeight")})",
    )

    val availableHeight = measured.num("hostHeight") - measured.num("padTop") - measured.num("padBottom")
    val availableWidth = measured.num("hostWidth") - measured.num("padLeft") - measured.num("padRight")
    assertTrue(
        measured.num("gridHeight") <= availableHeight + EPS,
        "$where: the ${rows.toInt()}-row grid (${measured.num("gridHeight")}px) must fit the host minus " +
            "its gutter (${availableHeight}px) — it does not, so its last row is clipped",
    )
    assertTrue(
        measured.num("gridHeight") + cellHeight + EPS > availableHeight,
        "$where: one more row would still fit in ${availableHeight}px, so the grid was fitted to " +
            "something smaller than the padded box",
    )
    assertTrue(
        measured.num("gridWidth") <= availableWidth + EPS,
        "$where: the ${cols.toInt()}-column grid (${measured.num("gridWidth")}px) overflows the host " +
            "minus its gutter (${availableWidth}px)",
    )
    assertTrue(
        measured.num("gridWidth") + cellWidth + EPS > availableWidth,
        "$where: one more column would still fit in ${availableWidth}px — the addon reserved width " +
            "nobody asked for (a returning ${SCROLLBAR_RESERVATION}px scroll-bar reservation is the " +
            "known cause; it is suppressed only while `scrollback` is exactly 0)",
    )

    assertTrue(
        measured.num("lastRowHeight") > 0.0 &&
            measured.num("lastRowBottom") <= measured.num("hostBottom") + EPS &&
            measured.num("lastRowTop") >= measured.num("hostTop") - EPS,
        "$where: the last row is drawn inside the host that clips it — its box is " +
            "${measured.num("lastRowTop")}..${measured.num("lastRowBottom")} inside " +
            "${measured.num("hostTop")}..${measured.num("hostBottom")}",
    )
}

/** What FitAddon takes off the width for a scroll bar whenever `scrollback` is not exactly 0. */
private const val SCROLLBAR_RESERVATION = 14

// --- plumbing ---------------------------------------------------------------------------------------

/**
 * A signed-in page in a desktop-shaped context, with the terminal hook installed before anything runs.
 *
 * Not `isMobile`, so the desktop half of every width media query applies; touch stays on because that is
 * what `touchContext` is, and no assertion here depends on pointer accuracy.
 */
private fun onDesktop(harness: Harness, trace: String, block: (Page) -> Unit) =
    onPage(harness, trace, DESKTOP_WIDTH, DESKTOP_HEIGHT, deviceScaleFactor = 1.0, mobile = false, block)

/** A signed-in page in a phone-shaped, touch-capable context. */
private fun onPhone(harness: Harness, trace: String, block: (Page) -> Unit) =
    onPage(harness, trace, PHONE_WIDTH, PHONE_HEIGHT, deviceScaleFactor = 3.0, mobile = true, block)

private fun onPage(
    harness: Harness,
    trace: String,
    width: Int,
    height: Int,
    deviceScaleFactor: Double,
    mobile: Boolean,
    block: (Page) -> Unit,
) {
    Playwright.create().use { pw ->
        touchChromium(pw).use { browser ->
            browser.touchContext(width, height, deviceScaleFactor, mobile).use { context ->
                context.traced(trace) {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    val page = context.newPage()
                    // Before the first navigation, so the accessor is already in place when xterm's
                    // classic script publishes its global.
                    page.addInitScript(TERMINAL_HOOK)
                    page.navigate("${harness.baseUrl}/")
                    assertThat(page.locator("#terminal-pane")).isVisible()
                    block(page)
                }
            }
        }
    }
}

/**
 * A page's answer to one `evaluate`, typed at the point of use rather than at the point of return.
 *
 * Playwright hands a JS object back as a `Map` of boxed values whose numeric type depends on what the
 * number happened to be, so every read goes through [Number] rather than a cast to `Double`. A missing or
 * wrongly typed key names itself AND quotes the script that should have produced it — the alternative is a
 * `ClassCastException` from inside a test whose whole subject is arithmetic on those numbers.
 */
private class PageValues(private val raw: Map<String, Any?>, private val script: String) {
    fun num(key: String): Double {
        val value = raw[key] ?: fail("the page returned nothing for `$key`\n  script: $script")
        return (value as? Number)?.toDouble()
            ?: fail("`$key` came back as $value, which is not a number\n  script: $script")
    }

    fun int(key: String): Int = num(key).toInt()

    fun str(key: String): String {
        val value = raw[key] ?: fail("the page returned nothing for `$key`\n  script: $script")
        return value as? String
            ?: fail("`$key` came back as $value, which is not a string\n  script: $script")
    }
}

private fun Page.values(script: String): PageValues {
    val result = evaluate(script)
        ?: fail("evaluating this script returned nothing at all\n  script: $script")
    if (result !is Map<*, *>) fail("expected an object from this script, got $result\n  script: $script")
    @Suppress("UNCHECKED_CAST")
    return PageValues(result as Map<String, Any?>, script)
}
