package io.kotgent.webuitest

import com.google.gson.JsonParser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.WebSocket
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.URI
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceTest {

    @Test
    fun aTabWithoutTheTerminalParksItOnTheSameSocketAndReportsNoSizeUntilItShowsAgain() =
        onTheWorkspace("workspace-tab-switch") { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            page.evaluate("() => { document.querySelector('#terminal-host .xterm').__kotgentKept = true; }")

            page.locator("#workspace-add-tab").click()
            assertThat(page.locator(".workspace-tab")).hasCount(2)
            page.locator(".workspace-column[data-type='terminal'] .column-type").selectOption("task")
            assertThat(page.locator(".terminal-parking #terminal-host")).hasCount(1)
            assertThat(page.locator(".workspace-column[data-type='task']")).isVisible()
            page.settle()
            terminal.clear()

            page.setViewportSize(1400, 760)
            page.settle()
            assertEquals(emptyList(), terminal.resizes(), "a parked terminal reports no geometry to tmux")

            page.locator("#workspace-tab-t1").click()
            assertThat(page.locator(".workspace-column .terminal-slot > #terminal-host")).hasCount(1)
            page.settle()

            val reported = terminal.resizes()
            assertEquals(1, reported.size, "showing the terminal fits it and reports exactly once: $reported")
            assertEquals(page.renderedRows(), reported.single().second, "the report is the grid now on screen")
            assertEquals(1, terminal.sockets(), "the tab switch kept the one upstream attach")
            assertEquals(
                true,
                page.evaluate("() => document.querySelector('#terminal-host .xterm').__kotgentKept === true"),
                "the same xterm moved between the slots rather than a new one being opened",
            )
            assertThat(page.locator("#terminal-host .xterm-rows").getByText(WORKSPACE_BANNER)).isVisible()
        }

    @Test
    fun theDividerMovesByKeyboardAndPointerAndTheLayoutOutlivesAReload() =
        onTheWorkspace("workspace-divider") { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            val colsAlone = page.settledCols(terminal)

            page.locator(".workspace-column[data-type='terminal'] .column-add").click()
            val divider = page.locator(".workspace-divider")
            assertThat(divider).hasAttribute("aria-valuenow", "50")
            assertThat(divider).hasAttribute("role", "separator")
            val colsHalf = page.settledCols(terminal)
            assertTrue(colsHalf < colsAlone, "sharing the row narrowed the terminal: $colsAlone -> $colsHalf")
            val halfWidth = page.columnWidth("terminal")

            divider.focus()
            page.keyboard().press("ArrowRight")
            assertThat(divider).hasAttribute("aria-valuenow", "55")
            page.keyboard().press("ArrowLeft")
            page.keyboard().press("ArrowLeft")
            assertThat(divider).hasAttribute("aria-valuenow", "45")
            assertTrue(page.columnWidth("terminal") < halfWidth - 20, "the terminal column narrowed on screen")
            assertTrue(page.settledCols(terminal) < colsHalf, "and the narrower grid was reported")

            val box = divider.boundingBox()
            val x = box.x + box.width / 2
            val y = box.y + box.height / 2
            page.mouse().move(x, y)
            page.mouse().down()
            page.mouse().move(x + 200, y, Mouse.MoveOptions().setSteps(8))
            page.mouse().up()
            val dragged = divider.getAttribute("aria-valuenow")!!.toInt()
            assertTrue(dragged > 55, "a mouse drag moved the divider right, to $dragged")

            page.reload()
            assertThat(page.locator("#terminal-host .xterm-rows").getByText(WORKSPACE_BANNER)).isVisible()
            assertThat(page.locator(".workspace-column")).hasCount(2)
            assertThat(page.locator(".workspace-divider")).hasAttribute("aria-valuenow", dragged.toString())
        }

    @Test
    fun aPhoneShowsOneColumnWithASwitcherAndAScrollableTabStrip() =
        onTheWorkspace("workspace-phone", phone = true) { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            assertThat(page.locator(".key-bar")).isVisible()

            page.locator(".workspace-column[data-type='terminal'] .column-add").click()
            assertThat(page.locator(".workspace-column")).hasCount(1)
            assertThat(page.locator(".workspace-column[data-type='task'] #task-detail-title")).hasText("local:1")
            assertThat(page.locator(".terminal-parking #terminal-host")).hasCount(1)
            assertThat(page.locator(".key-bar")).hasCount(0)
            val switches = page.locator(".workspace-switch")
            assertThat(switches).hasCount(2)
            assertThat(page.locator(".workspace-switch[data-type='task']")).hasAttribute("aria-pressed", "true")
            page.settle()
            terminal.clear()

            page.locator(".workspace-switch[data-type='terminal']").click()
            assertThat(page.locator(".workspace-column[data-type='terminal'] #terminal-host")).hasCount(1)
            assertThat(page.locator(".workspace-column")).hasCount(1)
            assertThat(page.locator(".key-bar")).isVisible()
            page.settle()
            assertEquals(1, terminal.resizes().size, "switching back reports the grid once")
            assertEquals(1, terminal.sockets())

            repeat(EXTRA_TABS) { page.locator("#workspace-add-tab").click() }
            assertThat(page.locator(".workspace-tab")).hasCount(EXTRA_TABS + 1)
            val strip = page.evaluate(TAB_STRIP_JS) as Map<*, *>
            val number = { key: String -> (strip[key] as Number).toDouble() }
            assertTrue(number("scrollWidth") > number("clientWidth"), "the tab strip overflows: $strip")
            assertEquals("auto", strip["overflowX"], "and scrolls sideways")
            assertEquals(1.0, number("activeInView"), "the new active tab is scrolled into view: $strip")
            assertEquals(1.0, number("addInView"), "and + stays at the strip's end: $strip")
            assertTrue(
                number("pageScrollWidth") <= number("viewportWidth"),
                "the page itself never scrolls sideways: $strip",
            )
        }

    @Test
    fun theTaskColumnShowsTheLinkedTaskBesideTheTerminal() =
        onTheWorkspace("workspace-task-column") { harness, page, _ ->
            page.openSession(harness, SESSION_WORK)
            page.locator(".workspace-column[data-type='terminal'] .column-add").click()

            val column = page.locator(".workspace-column[data-type='task']")
            assertThat(column.locator("#task-detail-title")).hasText("local:1")
            assertThat(column.locator("#task-detail-title-input")).hasValue(TASK_TITLE)
            assertThat(column.locator(".task-sessions")).containsText("splitter")
            assertThat(page.locator("#task-detail-close")).hasCount(0)
            assertEquals("static", column.locator(".task-detail").evaluate("el => getComputedStyle(el).position"))
            assertThat(page.locator(".workspace-column[data-type='terminal'] #terminal-host")).hasCount(1)
            assertThat(page).hasURL(Regex(".*/s/$SESSION_WORK$").toPattern())

            page.locator(".workspace-column[data-type='task'] .column-type").selectOption("terminal")
            val columns = page.locator(".workspace-column")
            assertThat(columns.first()).hasAttribute("data-type", "task")
            assertThat(columns.last()).hasAttribute("data-type", "terminal")
            assertThat(columns).hasCount(2)

            page.openSession(harness, SESSION_FREE)
            page.locator(".workspace-column[data-type='terminal'] .column-add").click()
            assertThat(page.locator("#workspace-task-empty")).isVisible()
            assertThat(page.locator("#workspace-link-task")).isVisible()
        }
}


private const val SESSION_WORK = "s-work"
private const val SESSION_FREE = "s-free"
private const val WORKSPACE_BANNER = "KOTGENT-WORKSPACE-READY"
private const val TASK_TITLE = "Split the session screen"
private const val EXTRA_TABS = 5
private const val DESKTOP_WIDTH = 1600
private const val DESKTOP_HEIGHT = 900

// Longer than the terminal's 120 ms refit debounce plus a ResizeObserver delivery.
private const val SETTLE_MILLIS = 450.0

private class TerminalSockets(page: Page) {
    private val opened = Collections.synchronizedList(mutableListOf<WebSocket>())
    private val sent = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

    init {
        page.onWebSocket { socket ->
            if (!URI.create(socket.url()).path.endsWith("/terminal")) return@onWebSocket
            opened += socket
            socket.onFrameSent { frame ->
                val text = frame.text() ?: return@onFrameSent
                val message = JsonParser.parseString(text).asJsonObject
                if (message.get("type")?.asString == "resize") {
                    sent += message.get("cols").asInt to message.get("rows").asInt
                }
            }
        }
    }

    fun sockets(): Int = opened.size

    fun resizes(): List<Pair<Int, Int>> = synchronized(sent) { sent.toList() }

    fun clear() = sent.clear()
}

private fun onTheWorkspace(trace: String, phone: Boolean = false, body: (Harness, Page, TerminalSockets) -> Unit) {
    Harness(WORKSPACE_SCENARIO).use { harness ->
        onChromium { browser ->
            val context: BrowserContext =
                if (phone) browser.touchContext() else browser.fineContext(DESKTOP_WIDTH, DESKTOP_HEIGHT)
            context.use {
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                context.traced(trace) {
                    val page = context.newPage()
                    body(harness, page, TerminalSockets(page))
                }
            }
        }
    }
}

private fun Page.openSession(harness: Harness, id: String) {
    navigate(harness.baseUrl + "/s/" + id)
    awaitSessionView()
    assertThat(locator("#terminal-host .xterm-rows").getByText(WORKSPACE_BANNER))
        .isVisible(visibleWithin(BOOT_TIMEOUT_MS))
}

private fun Page.settle() {
    waitForTimeout(SETTLE_MILLIS)
}

private fun Page.settledCols(terminal: TerminalSockets): Int {
    settle()
    return terminal.resizes().lastOrNull()?.first ?: error("the terminal reported no geometry")
}

private fun Page.renderedRows(): Int =
    (evaluate("() => document.querySelectorAll('#terminal-host .xterm-rows > div').length") as Number).toInt()

private fun Page.columnWidth(type: String): Double =
    locator(".workspace-column[data-type='$type']").boundingBox().width

private val TAB_STRIP_JS = """
    () => {
      const strip = document.querySelector(".workspace-tabs");
      const active = strip.querySelector(".workspace-tab.active");
      const s = strip.getBoundingClientRect();
      const a = active.getBoundingClientRect();
      const plus = document.querySelector("#workspace-add-tab").getBoundingClientRect();
      return {
        scrollWidth: strip.scrollWidth,
        clientWidth: strip.clientWidth,
        overflowX: getComputedStyle(strip).overflowX,
        activeInView: a.left >= s.left - 1 && a.right <= plus.left + 1 ? 1 : 0,
        addInView: plus.left >= s.left - 1 && plus.right <= s.right + 1 ? 1 : 0,
        pageScrollWidth: document.documentElement.scrollWidth,
        viewportWidth: window.innerWidth
      };
    }
""".trimIndent()
