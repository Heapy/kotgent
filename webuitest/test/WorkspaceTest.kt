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
    fun sessionDetailsRenameTheSelectedSessionAndOpenItsLinkedTask() =
        onTheWorkspace("workspace-session-details") { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            page.locator("#session-details-toggle").click()
            val details = page.locator("#session-details")
            assertThat(details.locator("#terminal-session-id")).hasText(SESSION_WORK)
            assertThat(details.locator("#terminal-tmux-session")).hasText("kt-$SESSION_WORK")
            val created = details.locator("#terminal-created-at")
            val updated = details.locator("#terminal-updated-at")
            val initialTime = "2023-11-14T22:13:20.001Z"
            assertThat(created).hasAttribute("datetime", initialTime)
            assertThat(updated).hasAttribute("datetime", initialTime)
            details.locator("summary").click()
            assertThat(details.locator("dt:has-text('Project ID') + dd"))
                .hasText("99999999-9999-4999-8999-999999999999")
            assertThat(details.locator("dt:has-text('Provider ID')")).hasCount(0)
            assertThat(details.locator("#terminal-state")).hasText("running")
            details.locator("button:has-text('Rename')").click()
            assertThat(details).isHidden()
            assertThat(page.locator("#rename-session-dialog")).isVisible()
            page.locator("#rename-session-name").fill("Review workspace")
            page.locator("#rename-session-submit").click()
            assertThat(page.locator("#rename-session-dialog")).hasCount(0)
            assertThat(page.locator("#terminal-title")).hasText("Review workspace")
            assertEquals(1, terminal.sockets(), "renaming never replaces the terminal attachment")
            page.locator("#session-details-toggle").click()
            assertThat(details.locator(".session-details-name")).hasText("Review workspace")
            assertThat(updated).hasAttribute("datetime", initialTime)
            harness.send("emit $SESSION_WORK ready")
            assertThat(details.locator("#terminal-state")).hasText("ready")
            assertThat(created).hasAttribute("datetime", initialTime)
            assertThat(updated).not().hasAttribute("datetime", initialTime)
            details.locator("button:has-text('Open task')").click()
            assertThat(page).hasURL(harness.baseUrl + "/tasks/local%3A1")
            assertThat(page.locator("#task-detail-title-input")).hasValue(TASK_TITLE)
        }

    @Test
    fun sessionActionsReuseTheWorkspaceAndReturnFocusAfterCommands() =
        onTheWorkspace("workspace-session-actions") { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            page.locator("#session-actions-toggle").click()
            page.locator("#session-actions button:has-text('Review beside terminal')").click()
            assertThat(page.locator(".workspace-column[data-type='plan']")).isVisible()
            assertThat(page.locator(".workspace-column[data-type='terminal']")).isVisible()
            assertEquals(1, terminal.sockets(), "opening review keeps the same terminal")
            page.openHeaderCommands()
            assertThat(page.locator("#command-palette")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.locator("#session-actions-toggle")).isFocused()
            assertThat(page.locator("#session-actions")).isHidden()

            page.openSession(harness, SESSION_FREE)
            page.locator("#session-actions-toggle").click()
            assertThat(page.locator("#session-actions button:has-text('Open linked task')")).isDisabled()
            assertThat(page.locator("#session-actions button:has-text('Review beside terminal')")).isDisabled()
            page.keyboard().press("Escape")
            page.locator("#session-details-toggle").click()
            assertThat(page.locator("#session-details button:has-text('Open task')")).isDisabled()
            assertThat(page.locator("#session-details button:has-text('Rename')")).isEnabled()
        }

    @Test
    fun aTabWithoutTheTerminalParksItOnTheSameSocketAndReportsNoSizeUntilItShowsAgain() =
        onTheWorkspace("workspace-tab-switch") { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            page.evaluate("() => { document.querySelector('#terminal-host .xterm').__kotgentKept = true; }")

            page.locator("#workspace-add-tab").click()
            assertThat(page.locator(".workspace-tab")).hasCount(2)
            page.locator("#workspace-layout-toggle").click()
            page.locator("#workspace-layout .column-type").selectOption("task")
            page.keyboard().press("Escape")
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

            page.addWorkspaceColumn()
            val divider = page.locator(".workspace-divider")
            assertThat(divider).hasAttribute("aria-valuenow", "50")
            assertThat(divider).hasAttribute("role", "separator")
            assertThat(page.locator(".workspace-column > .column-header")).hasCount(0)
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
    fun aPhoneShowsOneColumnWithASwitcherAndAnAccessibleTabPicker() =
        onTheWorkspace("workspace-phone", phone = true) { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            assertThat(page.locator(".key-bar")).isVisible()

            page.addWorkspaceColumn()
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

            repeat(EXTRA_TABS) {
                page.locator("#workspace-tab-picker-toggle").click()
                page.locator("#workspace-add-tab").click()
            }
            assertThat(page.locator(".workspace-tab")).hasCount(EXTRA_TABS + 1)
            assertThat(page.locator("#workspace-tab-picker")).isHidden()
            assertEquals(48.0, page.locator("#terminal-head").boundingBox().height)
            assertThat(page.locator(".workspace-column > .column-header")).hasCount(0)
            page.locator("#workspace-tab-picker-toggle").click()
            assertThat(page.locator(".workspace-tab.active")).isVisible()
            page.locator("#workspace-tab-t1").click()
            assertThat(page.locator("#workspace-tab-picker-toggle")).containsText("Terminal · Task")
            page.locator("#workspace-tab-picker-toggle").click()
            page.locator(".workspace-tab.active .workspace-tab-close").click()
            assertThat(page.locator(".workspace-tab")).hasCount(EXTRA_TABS)
            page.keyboard().press("Escape")
            assertThat(page.locator("#workspace-tab-picker-toggle")).isFocused()
            assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"))
        }

    @Test
    fun theKeyboardShrinksTheWholeWorkspaceAndKeepsEveryPrimaryKeyReachable() =
        onTheWorkspace("workspace-keyboard", phone = true) { harness, page, terminal ->
            page.openSession(harness, SESSION_WORK)
            page.settle()
            page.evaluate("""() => {
              const app = document.querySelector('#app');
              app.style.setProperty('--device-safe-area-top', '20px');
              app.style.setProperty('--device-safe-area-bottom', '34px');
            }""")
            page.settle()
            val fullBar = page.locator(".key-bar").boundingBox()
            assertEquals(87.0, fullBar.height, "the key bar reserves the bottom inset once")
            assertEquals(844.0, fullBar.y + fullBar.height)
            val fullKey = page.locator(".key-bar > button").first().boundingBox()
            assertEquals(806.0, fullKey.y + fullKey.height, "keys stay above the 34px inset and 4px padding")
            val fullHeight = page.locator("#terminal-pane").boundingBox().height
            val fullRows = page.renderedRows()
            page.evaluate("""() => {
              window.__keyboardViewport = { height: 420, offsetTop: 0 };
              for (const key of ['height', 'offsetTop']) Object.defineProperty(visualViewport, key, {
                configurable: true, get: () => window.__keyboardViewport[key]
              });
              visualViewport.dispatchEvent(new Event('resize'));
            }""")
            page.settle()
            fun assertKeysFit(bottom: Double) {
                val bar = page.locator(".key-bar").boundingBox()
                assertTrue(bar.y + bar.height <= bottom + 1, "the key bar stays above the keyboard: $bar")
                val host = page.locator("#terminal-host").boundingBox()
                assertTrue(host.y + host.height <= bar.y + 1, "the workspace reserves room for keys")
                val keys = page.locator(".key-bar > button")
                assertThat(keys).hasCount(7)
                for (i in 0 until keys.count()) {
                    val key = keys.nth(i).boundingBox()
                    assertTrue(key.width >= 44 && key.height >= 44, "every key remains a 44px target: $key")
                    assertTrue(key.x >= 0 && key.x + key.width <= page.viewportSize().width + 1, "key stays on screen: $key")
                }
            }
            assertKeysFit(420.0)
            assertTrue(page.renderedRows() < fullRows, "the terminal actually shrinks with the workspace")
            assertEquals(page.renderedRows(), terminal.resizes().last().second)
            page.locator(".key-bar button[aria-label='More terminal keys']").tap()
            assertThat(page.locator("#key-bar-extra")).isVisible()
            val extras = page.locator("#key-bar-extra").boundingBox()
            assertTrue(extras.y >= 48 && extras.y + extras.height <= 420, "extra keys open above the bar")
            page.locator(".key-bar button[aria-label='More terminal keys']").tap()
            page.evaluate("""() => {
              window.__keyboardViewport.offsetTop = 24;
              visualViewport.dispatchEvent(new Event('scroll'));
            }""")
            page.settle()
            assertKeysFit(444.0)
            page.evaluate("""() => {
              window.__keyboardViewport.height = 0;
              visualViewport.dispatchEvent(new Event('resize'));
            }""")
            page.settle()
            assertKeysFit(444.0)
            page.evaluate("""() => {
              delete visualViewport.height; delete visualViewport.offsetTop;
              visualViewport.dispatchEvent(new Event('resize'));
            }""")
            page.settle()
            assertEquals(fullHeight, page.locator("#terminal-pane").boundingBox().height, "keyboard dismissal restores height")
            assertEquals(fullRows, page.renderedRows())
            page.setViewportSize(320, 480)
            page.settle()
            assertKeysFit(480.0)
            assertEquals(1, terminal.sockets(), "keyboard geometry never reattaches")
        }

    @Test
    fun theTaskColumnShowsTheLinkedTaskBesideTheTerminal() =
        onTheWorkspace("workspace-task-column") { harness, page, _ ->
            page.openSession(harness, SESSION_WORK)
            page.addWorkspaceColumn()

            val column = page.locator(".workspace-column[data-type='task']")
            assertThat(column.locator("#task-detail-title")).hasText("local:1")
            assertThat(column.locator("#task-detail-title-input")).hasValue(TASK_TITLE)
            assertThat(column.locator(".task-sessions")).containsText("splitter")
            assertThat(page.locator("#task-detail-close")).hasCount(0)
            assertEquals("static", column.locator(".task-detail").evaluate("el => getComputedStyle(el).position"))
            assertThat(page.locator(".workspace-column[data-type='terminal'] #terminal-host")).hasCount(1)
            assertThat(page).hasURL(Regex(".*/s/$SESSION_WORK$").toPattern())

            page.locator("#workspace-layout-toggle").click()
            page.locator("#workspace-layout .column-type").nth(1).selectOption("terminal")
            page.keyboard().press("Escape")
            val columns = page.locator(".workspace-column")
            assertThat(columns.first()).hasAttribute("data-type", "task")
            assertThat(columns.last()).hasAttribute("data-type", "terminal")
            assertThat(columns).hasCount(2)

            page.openSession(harness, SESSION_FREE)
            page.addWorkspaceColumn()
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

internal fun Page.addWorkspaceColumn() {
    locator("#workspace-layout-toggle").click()
    locator("#workspace-layout .column-add").first().click()
}
