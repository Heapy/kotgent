package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LayoutTest {

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

    @Test
    fun theMobileDrawerOpensAndClosesFromEachOfItsThreeControls() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onPhone(harness, "layout-drawer") { page ->
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

                page.locator("#drawer-close").click()
                waitForDrawer(page, open = false)
                assertThat(page.locator("#sidebar")).isHidden()
                assertThat(page.locator(".drawer-scrim")).hasCount(0)

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

    @Test
    fun theSidebarPinsItsHeaderAboveTheSessionsAndTasksScrollersOnDesktop() {
        Harness(TASK_LINKED_SESSION_SCENARIO).use { harness ->
            onDesktop(harness, "layout-sidebar-scroll-desktop") { page ->
                exercisePinnedSidebar(page, mobile = false)
            }
        }
    }

    @Test
    fun theSidebarPinsItsHeaderInsideTheMobileDrawer() {
        Harness(TASK_LINKED_SESSION_SCENARIO).use { harness ->
            onPhone(harness, "layout-sidebar-scroll-phone") { page ->
                exercisePinnedSidebar(page, mobile = true)
            }
        }
    }

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

                page.locator("#sidebar-toggle").click()
                page.waitForFunction(SIDEBAR_EXPANDED)
                assertThat(page.locator("#sidebar")).isVisible()
                assertTrue(
                    abs(measureShell(page).num("sidebarWidth") - expanded.num("sidebarWidth")) <= EDGE_EPS,
                    "the restored column is the width it was",
                )

                page.locator("#sidebar-toggle").click()
                page.waitForFunction(SIDEBAR_COLLAPSED)
                page.setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)

                assertThat(page.locator("#sidebar-toggle")).isHidden()
                assertThat(page.locator("#drawer-toggle")).isVisible()
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

                assertFitInvariant(measureTerminal(page), "the phone viewport")
            }
        }
    }

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
                    "16px",
                    helperTextareaFontSize(page),
                    "xterm's helper textarea stays at 16px however large the terminal font is, or iOS " +
                        "Safari zooms on focus and corrupts the viewport geometry above",
                )

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
                assertFitInvariant(after, "the enlarged terminal font")
            }
        }
    }

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

    @Test
    fun theArchiveToggleJoinsTheHeaderRowOnAPhoneWithoutWrappingIt() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onPhone(harness, "layout-archive-toggle") { page ->
                // The row wraps by design on a phone, so an added button is exactly what could break it.
                harness.send("done $ARCHIVED_SESSION_ID")
                page.locator("#drawer-toggle").click()
                assertThat(page.locator("#show-done-toggle")).isVisible()
                // Measuring mid-slide would read positions the drawer never comes to rest at.
                page.waitForFunction(DRAWER_AT_REST)

                val row = page.values(MEASURE_BRAND_ACTIONS)
                assertEquals(
                    1,
                    row.int("rows"),
                    "the header keeps its buttons on one line: ${row.int("buttons")} buttons landed on " +
                        "${row.int("rows")} rows (${row.num("buttonsWidth")}px of buttons in a " +
                        "${row.num("rowWidth")}px row)",
                )
                assertTrue(
                    row.num("doneWidth") >= MIN_TOGGLE_BOX && row.num("doneHeight") >= MIN_TOGGLE_BOX,
                    "the archive toggle keeps a thumb-sized hit box (${row.num("doneWidth")}x" +
                        "${row.num("doneHeight")})",
                )
                assertTrue(
                    row.num("markWidth") > 0.0 && row.num("markHeight") > 0.0,
                    "and the drawn mark inside it has a box of its own",
                )
            }
        }
    }
}


private const val TERMINAL_SESSION_ID = "s-term"

private const val TERMINAL_BANNER = "KOTGENT-TERMINAL-READY"

private const val DESKTOP_WIDTH = 1280
private const val DESKTOP_HEIGHT = 900
private const val PHONE_WIDTH = 390
private const val PHONE_HEIGHT = 844

private const val SIDEBAR_SCROLL_VIEWPORT_HEIGHT = 200

private const val DEFAULT_TERMINAL_FONT_SIZE = 13
private const val LARGEST_TERMINAL_FONT_SIZE = 16

private const val EPS = 2.0

private const val EDGE_EPS = 1.0

private const val SCROLLBAR_RESERVATION = 14

private const val MIN_DRAWER_WIDTH = 200.0

private const val MIN_TOGGLE_BOX = 24.0

private const val ARCHIVED_SESSION_ID = "s-delta"

private const val SETTLE_QUIET_MILLIS = 220

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

private val DRAWER_AT_REST = """
    () => {
      const sidebar = document.querySelector("#sidebar");
      return !!sidebar && sidebar.getBoundingClientRect().left >= 0;
    }
""".trimIndent()

private val MEASURE_BRAND_ACTIONS = """
    () => {
      const row = document.querySelector(".brand-actions");
      if (!row) return { buttons: 0, rows: 0, doneWidth: 0, doneHeight: 0, markWidth: 0, markHeight: 0 };
      const buttons = Array.from(row.querySelectorAll("button"));
      // Buttons of different heights centre at different tops, so a row break is a vertical gap.
      const boxes = buttons.map((b) => b.getBoundingClientRect()).sort((a, b) => a.top - b.top);
      let rows = boxes.length > 0 ? 1 : 0;
      let rowBottom = boxes.length > 0 ? boxes[0].bottom : 0;
      for (const box of boxes.slice(1)) {
        if (box.top >= rowBottom) {
          rows++;
          rowBottom = box.bottom;
        } else {
          rowBottom = Math.max(rowBottom, box.bottom);
        }
      }
      const done = row.querySelector("#show-done-toggle");
      const box = done ? done.getBoundingClientRect() : { width: 0, height: 0 };
      const mark = done ? done.querySelector("svg") : null;
      const markBox = mark ? mark.getBoundingClientRect() : { width: 0, height: 0 };
      return {
        buttons: buttons.length,
        rows: rows,
        doneWidth: box.width,
        doneHeight: box.height,
        markWidth: markBox.width,
        markHeight: markBox.height,
        rowWidth: row.getBoundingClientRect().width,
        buttonsWidth: buttons.reduce((sum, b) => sum + b.getBoundingClientRect().width, 0),
      };
    }
""".trimIndent()

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

private fun exercisePinnedSidebar(page: Page, mobile: Boolean) {
    assertThat(page.locator("#session-list .session-row")).hasCount(3)
    page.setViewportSize(if (mobile) PHONE_WIDTH else DESKTOP_WIDTH, SIDEBAR_SCROLL_VIEWPORT_HEIGHT)
    if (mobile) {
        page.locator("#drawer-toggle").click()
        waitForDrawer(page, open = true)
    }

    assertThat(page.locator("#sidebar-head #attention-count")).hasCount(0)
    assertThat(page.locator("#sidebar-scroll > #attention-count")).hasCount(1)
    assertPinnedSidebarScroll(page, "#attention-count", if (mobile) "the sessions drawer" else "the sessions sidebar")

    val tasks = page.locator(".nav-switch a:text-is('Tasks')")
    assertThat(tasks).isVisible()
    tasks.click()
    page.awaitBoard()
    assertThat(page.locator("#project-list .project-row")).hasCount(1)
    assertPinnedSidebarScroll(page, "#projects-section", if (mobile) "the tasks drawer" else "the tasks sidebar")

    val sessions = page.locator(".nav-switch a:text-is('Sessions')")
    assertThat(sessions).isVisible()
    sessions.click()
    page.awaitSessionView()
    assertThat(page.locator("#session-list .session-row")).hasCount(3)
}

private fun assertPinnedSidebarScroll(page: Page, movingSelector: String, where: String) {
    val sidebar = page.locator("#sidebar")
    val header = page.locator("#sidebar-head")
    val scroll = page.locator("#sidebar-scroll")
    val moving = page.locator(movingSelector)
    val footer = page.locator("#sidebar-footer")

    scroll.evaluate("el => { el.scrollTop = 0; }")
    val clientHeight = scroll.number("el => el.clientHeight")
    val scrollHeight = scroll.number("el => el.scrollHeight")
    assertTrue(
        scrollHeight > clientHeight + EDGE_EPS,
        "$where has no real overflow: its content is ${scrollHeight}px high in a ${clientHeight}px port",
    )
    assertEquals(0.0, sidebar.number("el => el.scrollTop"), "$where starts with no outer-sidebar scroll")
    assertTrue(
        header.number("el => el.scrollHeight") <= header.number("el => el.clientHeight") + EDGE_EPS,
        "$where squeezed the pinned header until its brand or screen switch overflowed",
    )

    val headerBefore = header.boundingBox() ?: fail("$where rendered no header box")
    val scrollBefore = scroll.boundingBox() ?: fail("$where rendered no scroll-port box")
    val movingBefore = moving.boundingBox() ?: fail("$where rendered no $movingSelector box")
    val footerBefore = footer.boundingBox() ?: fail("$where rendered no footer box")
    assertTrue(
        footerBefore.y + footerBefore.height > scrollBefore.y + scrollBefore.height + EDGE_EPS,
        "$where showed the whole footer before scrolling, so reaching it proves nothing",
    )

    val scrolled = scroll.number("el => { el.scrollTop = el.scrollHeight; return el.scrollTop; }")
    assertTrue(scrolled > EDGE_EPS, "$where did not scroll its dedicated region")

    val headerAfter = header.boundingBox() ?: fail("$where lost its header while scrolling")
    val movingAfter = moving.boundingBox() ?: fail("$where lost $movingSelector while scrolling")
    val footerAfter = footer.boundingBox() ?: fail("$where lost its footer while scrolling")
    assertTrue(
        abs(headerAfter.y - headerBefore.y) <= EDGE_EPS &&
            abs(headerAfter.height - headerBefore.height) <= EDGE_EPS &&
            abs(headerAfter.width - headerBefore.width) <= EDGE_EPS,
        "$where moved or resized its pinned header: " +
            "${headerBefore.x},${headerBefore.y} ${headerBefore.width}x${headerBefore.height} -> " +
            "${headerAfter.x},${headerAfter.y} ${headerAfter.width}x${headerAfter.height}",
    )
    assertTrue(
        movingAfter.y < movingBefore.y - EDGE_EPS,
        "$where did not move $movingSelector with the scrollable content",
    )
    assertTrue(
        footerAfter.y >= scrollBefore.y - EDGE_EPS &&
            footerAfter.y + footerAfter.height <= scrollBefore.y + scrollBefore.height + EDGE_EPS,
        "$where did not bring its footer fully into the scroll port",
    )
    assertEquals(0.0, sidebar.number("el => el.scrollTop"), "$where scrolled the outer sidebar")
}

private fun Locator.number(expression: String): Double = (evaluate(expression) as Number).toDouble()

private fun fontSizeApplied(size: Int): String = """
    () => {
      const term = (window.__kotgentTerminals || []).slice(-1)[0];
      return !!term && term.options.fontSize === $size;
    }
""".trimIndent()

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

private fun attachTerminal(page: Page) {
    page.locator("#session-list .session-row[data-id='$TERMINAL_SESSION_ID']").click()
    assertThat(page.locator("#terminal-host .xterm")).isVisible()
    assertThat(page.locator("#terminal-host")).containsText(TERMINAL_BANNER)
    settleTerminal(page)
}

private fun helperTextareaFontSize(page: Page): String = page.evaluate(
    """
    () => {
      const el = document.querySelector("#terminal-host .xterm-helper-textarea");
      if (!el) throw new Error("xterm rendered no helper textarea");
      return getComputedStyle(el).fontSize;
    }
    """.trimIndent(),
) as String

private fun settleTerminal(page: Page) {
    // The sampler is page-global, so stale geometry must not satisfy the next settle wait.
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
    assertTrue(
        shell.num("hostLeft") >= shell.num("paneLeft") - EDGE_EPS &&
            shell.num("hostRight") <= shell.num("paneRight") + EDGE_EPS,
        "$where: the terminal host lives inside its card",
    )
}

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


private fun onDesktop(harness: Harness, trace: String, block: (Page) -> Unit) =
    onPage(harness, trace, DESKTOP_WIDTH, DESKTOP_HEIGHT, deviceScaleFactor = 1.0, mobile = false, block)

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
    onChromium { browser ->
        browser.touchContext(width, height, deviceScaleFactor, mobile).use { context ->
            context.traced(trace) {
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                val page = context.newPage()
                page.addInitScript(TERMINAL_HOOK)
                page.navigate("${harness.baseUrl}/")
                assertThat(page.locator("#terminal-pane")).isVisible()
                block(page)
            }
        }
    }
}

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
