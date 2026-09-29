package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import com.microsoft.playwright.Route
import com.microsoft.playwright.WebSocketFrame
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FilePayload
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileFeaturesTest {

    @Test
    fun theKeyBarsSpecialKeysArriveAtThePtyAsBinaryFramesWithoutTakingFocusFromXterm() {
        onMobileTerminal("mobile-key-bar") { harness, _, page ->
            val sent = CopyOnWriteArrayList<WebSocketFrame>()
            page.onWebSocket { socket ->
                if (socket.url().contains(TERMINAL_WS_PATH)) socket.onFrameSent { sent.add(it) }
            }

            page.navigate(harness.baseUrl + SESSION_ROUTE)
            val rows = page.locator(TERMINAL_ROWS)
            assertThat(rows).containsText(TERMINAL_BANNER)
            assertThat(page.locator(KEY_BAR)).isVisible()

            assertEquals(
                true,
                page.evaluate(FOCUS_XTERM_TEXTAREA),
                "the test's own precondition: xterm's hidden textarea can be focused",
            )

            var echoed = ""
            var pressed = 0
            for (key in SPECIAL_KEYS) {
                page.locator(KEY_BAR + " button[aria-label='" + key.label + "']").tap()
                pressed++
                page.waitForCondition { sent.count { frame -> frame.text() == null } >= pressed }
                if (key.echo == null) {
                    echoed = ""
                } else {
                    echoed += key.echo
                    assertThat(rows).containsText(echoed)
                }
                val focused = focusedElement(page)
                assertTrue(
                    focused.contains(XTERM_TEXTAREA_CLASS),
                    "pressing ${key.label} left focus on '$focused' instead of xterm's hidden textarea, " +
                        "which on a phone closes the software keyboard",
                )
            }
            val binary = sent.filter { it.text() == null }.map { it.binary().toList() }
            assertEquals(
                SPECIAL_KEYS.map { key -> key.bytes.map(Int::toByte) },
                binary,
                "each key sends exactly its own bytes, in one binary frame, in press order",
            )
            val text = sent.mapNotNull { it.text() }
            assertTrue(
                text.isNotEmpty(),
                "the socket does carry text frames — its resize controls — so the partition above is " +
                    "distinguishing two real kinds rather than finding one kind empty",
            )
            assertTrue(
                text.all { it.contains("\"type\":\"resize\"") },
                "nothing but resize controls travels as text; a key that became one would be parsed as " +
                    "geometry and dropped: $text",
            )

            val ctrl = page.locator(CTRL_KEY)
            assertThat(ctrl).hasAttribute("aria-pressed", "false")
            val beforeCtrl = sent.count { frame -> frame.text() == null }

            ctrl.tap()
            assertThat(ctrl).hasAttribute("aria-pressed", "true")
            page.keyboard().type("a")
            assertThat(rows).containsText("^A")
            assertThat(ctrl).hasAttribute(
                "aria-pressed",
                "false",
                LocatorAssertions.HasAttributeOptions().setTimeout(KEY_ECHO_TIMEOUT_MS),
            )

            page.keyboard().type("a")
            assertThat(rows).containsText("^Aa")

            ctrl.tap()
            assertThat(ctrl).hasAttribute("aria-pressed", "true")
            page.keyboard().type("3")
            assertThat(rows).containsText("^Aa^[")

            page.waitForCondition { sent.count { frame -> frame.text() == null } >= beforeCtrl + 3 }
            assertEquals(
                listOf(listOf(0x01.toByte()), listOf(0x61.toByte()), listOf(0x1b.toByte())),
                sent.filter { it.text() == null }.drop(beforeCtrl).map { it.binary().toList() },
                "armed Ctrl rewrites one printable key and then disarms; the digit alias is its own byte",
            )
        }
    }

    @Test
    fun thePaletteUploadsPickedFilesIntoTheSessionsFolderAndNamesEveryFailureInAPartialBatch() {
        onMobileTerminal("mobile-upload") { harness, _, page ->
            val uploads = CopyOnWriteArrayList<Request>()
            val answers = CopyOnWriteArrayList<Response>()
            val held = AtomicReference<Route?>(null)
            page.onRequest { if (it.url().contains(UPLOAD_PATH)) uploads.add(it) }
            page.onResponse { if (it.url().contains(UPLOAD_PATH)) answers.add(it) }

            page.navigate(harness.baseUrl + SESSION_ROUTE)
            assertThat(page.locator(TERMINAL_ROWS)).containsText(TERMINAL_BANNER)

            runLeaderCommand(page, "Upload files to current folder")
            assertThat(page.locator("#upload-dialog")).isVisible()
            assertThat(page.locator(".upload-destination")).containsText(SESSION_CWD)
            page.route("**/*") { route ->
                val request = route.request()
                if (request.method() == "POST" && request.url().contains(UPLOAD_PATH) &&
                    held.compareAndSet(null, route)
                ) return@route
                route.resume()
            }

            page.locator("#upload-files").setInputFiles(
                arrayOf(
                    FilePayload(NOTES_NAME, "text/plain", NOTES_BYTES),
                    FilePayload(DATA_NAME, "application/octet-stream", DATA_BYTES),
                ),
            )
            page.locator("#upload-submit").click()
            page.waitForCondition { held.get() != null }
            assertThat(page.locator("#upload-cancel")).hasText("Cancel upload")
            held.get()!!.resume()

            assertThat(page.locator(".upload-result")).containsText("Uploaded 2 files to $SESSION_CWD.")
            assertThat(page.locator("#upload-error")).hasCount(0)

            page.waitForCondition { answers.size >= 2 }
            assertEquals(
                listOf(NOTES_NAME, DATA_NAME),
                uploads.map { uploadedName(it) },
                "one request per picked file, sequential, each naming its own leaf in the query",
            )
            assertTrue(uploads.all { it.method() == "POST" }, "every upload is a POST")
            val picked = listOf(NOTES_NAME to NOTES_BYTES, DATA_NAME to DATA_BYTES)
            for ([index, file] in picked.withIndex()) {
                val [name, bytes] = file
                val answer = answers[index]
                assertEquals(201, answer.status(), "$name was stored")
                val body = answer.text()
                assertTrue(
                    body.contains("\"name\":\"$name\"") &&
                        body.contains("\"bytes\":${bytes.size}") &&
                        body.contains("\"directory\":\"$SESSION_CWD\""),
                    "the uploader reports $name at its exact byte count in $SESSION_CWD: $body",
                )
            }

            page.locator("#upload-files").setInputFiles(
                arrayOf(
                    FilePayload(NOTES_NAME, "text/plain", NOTES_BYTES),
                    FilePayload(DATA_NAME, "application/octet-stream", DATA_BYTES),
                    FilePayload(FRESH_NAME, "text/plain", FRESH_BYTES),
                ),
            )
            page.locator("#upload-submit").click()

            assertThat(page.locator(".upload-result")).containsText("Uploaded 1 file to $SESSION_CWD.")
            val failures = page.locator("#upload-error")
            for (name in listOf(NOTES_NAME, DATA_NAME)) {
                assertThat(failures).containsText(
                    "$name: cannot upload '$name': a file with that name already exists in $SESSION_CWD",
                )
            }
            assertThat(failures).not().containsText(FRESH_NAME)
        }
    }

    @Test
    fun theUnicodeAddonIsFetchedOnlyWhenThePreferenceSelectsItAndThenBecomesTheActiveVersion() {
        onMobileTerminal("mobile-unicode") { harness, _, page ->
            val fetched = CopyOnWriteArrayList<String>()
            page.onRequest { fetched.add(it.url()) }

            page.navigate(harness.baseUrl + SESSION_ROUTE)
            assertThat(page.locator(TERMINAL_ROWS)).containsText(TERMINAL_BANNER)

            assertUnicodeWidth(page, "built-in", 2.0)
            assertTrue(
                fetched.any { it.contains("/assets/index-") && it.endsWith(".js") },
                "the request log does see this page's script fetches, so the absence below can fail",
            )
            val beforeTheChoice = fetched.filter { it.contains(UNICODE_ADDON_MARKER) }
            assertTrue(
                beforeTheChoice.isEmpty(),
                "no width table is downloaded for the default mode, yet these were: $beforeTheChoice",
            )

            runLeaderCommand(page, "Preferences")
            assertThat(page.locator("#prefs-dialog")).isVisible()
            page.locator("#prefs-terminal-unicode").selectOption(UNICODE_11_MODE)
            page.locator("#prefs-submit").click()
            assertThat(page.locator("#prefs-dialog")).hasCount(0)

            assertUnicodeWidth(page, "unicode11", 3.0)

            val addons = fetched.filter { it.contains(UNICODE_ADDON_MARKER) }
            assertEquals(
                1,
                addons.size,
                "exactly the selected mode's module is fetched — the other addon stays undownloaded: " +
                    "$addons",
            )
            assertTrue(
                UNICODE_11_CHUNK.containsMatchIn(addons[0]),
                "the selected addon is fetched as a hashed lazy chunk: ${addons[0]}",
            )

            runLeaderCommand(page, "Preferences")
            assertThat(page.locator("#prefs-dialog")).isVisible()
            page.locator("#prefs-terminal-unicode").selectOption(DEFAULT_UNICODE_MODE)
            page.locator("#prefs-submit").click()
            assertThat(page.locator("#prefs-dialog")).hasCount(0)

            assertUnicodeWidth(page, "restored", 2.0)
            assertEquals(
                1,
                fetched.count { it.contains(UNICODE_ADDON_MARKER) },
                "and nothing new is downloaded on the way back: the built-in table needs no module",
            )
        }
    }


    private fun onMobileTerminal(trace: String, block: (Harness, BrowserContext, Page) -> Unit) {
        Harness(TERMINAL_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.touchContext().use { context ->
                    context.traced(trace) {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        block(harness, context, context.newPage())
                    }
                }
            }
        }
    }

    private fun runLeaderCommand(page: Page, title: String) {
        page.locator("#palette-button").click()
        assertThat(page.locator("#command-palette")).isVisible()
        page.locator(
            ".command-palette-leader-command",
            Page.LocatorOptions().setHasText(title),
        ).click()
    }

    private fun focusedElement(page: Page): String = page.evaluate(FOCUSED_ELEMENT) as String

    private fun assertUnicodeWidth(page: Page, label: String, expectedCells: Double) {
        // U+1F9D1 takes one cell with the built-in widths and two with Unicode 11.
        val probe = "$label A🧑B"
        assertEquals(true, page.evaluate(FOCUS_XTERM_TEXTAREA))
        page.keyboard().insertText(probe)
        val row = page.locator("$TERMINAL_ROWS > div")
            .filter(com.microsoft.playwright.Locator.FilterOptions().setHasText(probe))
        page.waitForCondition {
            val cells = row.evaluateAll(
                """
                (rows, probe) => {
                  const row = rows.at(-1);
                  if (!row || getComputedStyle(row).visibility !== "visible") return null;
                  const rect = row.getBoundingClientRect();
                  if (rect.width === 0 || rect.height === 0) return null;
                  return ($MEASURE_UNICODE_WIDTH)(row, probe);
                }
                """.trimIndent(),
                probe,
            ) as? Number
            cells != null && abs(cells.toDouble() - expectedCells) < 0.15
        }
        page.keyboard().press("Enter")
    }

    private fun uploadedName(request: Request): String = request.url().substringAfter("name=")

    private class SpecialKey(val label: String, val bytes: List<Int>, val echo: String?)

    private companion object {
        const val SESSION_ROUTE = "/s/s-term"
        const val SESSION_CWD = "/w/terminal"
        const val TERMINAL_BANNER = "KOTGENT-TERMINAL-READY"

        const val TERMINAL_ROWS = "#terminal-host .xterm-rows"
        const val KEY_BAR = ".key-bar"
        const val XTERM_TEXTAREA_CLASS = "xterm-helper-textarea"
        const val TERMINAL_WS_PATH = "/terminal"
        const val UPLOAD_PATH = "/files?name="

        const val DEFAULT_UNICODE_MODE = "default"
        const val UNICODE_11_MODE = "11"
        const val UNICODE_ADDON_MARKER = "addon-unicode"
        val UNICODE_11_CHUNK = Regex("/assets/addon-unicode11-[A-Za-z0-9_-]+\\.js$")

        // Control-C is last because the tty line discipline may flush earlier queued echo on INTR.
        val SPECIAL_KEYS = listOf(
            SpecialKey("Escape", listOf(0x1b), "^["),
            SpecialKey("Shift Tab", listOf(0x1b, 0x5b, 0x5a), "^[[Z"),
            SpecialKey("Up arrow", listOf(0x1b, 0x5b, 0x41), "^[[A"),
            SpecialKey("Down arrow", listOf(0x1b, 0x5b, 0x42), "^[[B"),
            SpecialKey("Left arrow", listOf(0x1b, 0x5b, 0x44), "^[[D"),
            SpecialKey("Right arrow", listOf(0x1b, 0x5b, 0x43), "^[[C"),
            SpecialKey("Shift Left arrow", listOf(0x1b, 0x5b, 0x31, 0x3b, 0x32, 0x44), "^[[1;2D"),
            SpecialKey("Tab", listOf(0x09), null),
            SpecialKey("Control C", listOf(0x03), "^C"),
        )

        const val CTRL_KEY = "#key-bar-ctrl"

        const val KEY_ECHO_TIMEOUT_MS = 10_000.0

        const val NOTES_NAME = "kotgent-notes.txt"
        val NOTES_BYTES = "upload one\nupload two\n".toByteArray()

        const val DATA_NAME = "kotgent-data.bin"
        val DATA_BYTES = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xfe.toByte(), 0xff.toByte())

        const val FRESH_NAME = "kotgent-fresh.txt"
        val FRESH_BYTES = "the one file this batch had not sent before\n".toByteArray()

        val MEASURE_UNICODE_WIDTH = """
            (row, probe) => {
              const start = row.textContent.indexOf(probe) + probe.indexOf("A🧑B");
              function rectAt(offset) {
                const nodes = document.createTreeWalker(row, NodeFilter.SHOW_TEXT);
                let node;
                while ((node = nodes.nextNode())) {
                  if (offset < node.length) {
                    const range = document.createRange();
                    range.setStart(node, offset);
                    range.setEnd(node, offset + 1);
                    return range.getBoundingClientRect();
                  }
                  offset -= node.length;
                }
                throw new Error("Unicode probe is missing from the terminal row");
              }
              const a = rectAt(start);
              const b = rectAt(start + "A🧑".length);
              return (b.left - a.left) / a.width;
            }
        """.trimIndent()

        val FOCUS_XTERM_TEXTAREA = """
            () => {
              const area = document.querySelector("#terminal-host .xterm-helper-textarea");
              if (!area) return false;
              area.focus();
              return document.activeElement === area;
            }
        """.trimIndent()

        val FOCUSED_ELEMENT = """
            () => {
              const active = document.activeElement;
              if (!active) return "nothing";
              return String(active.className || "") + " " + active.tagName.toLowerCase();
            }
        """.trimIndent()
    }
}
