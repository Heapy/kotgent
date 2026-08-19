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
            for ((index, file) in picked.withIndex()) {
                val (name, bytes) = file
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
        onMobileTerminal("mobile-unicode") { harness, context, page ->
            val fetched = CopyOnWriteArrayList<String>()
            page.onRequest { fetched.add(it.url()) }
            context.addInitScript(CAPTURE_TERMINAL_CONSTRUCTIONS)

            page.navigate(harness.baseUrl + SESSION_ROUTE)
            assertThat(page.locator(TERMINAL_ROWS)).containsText(TERMINAL_BANNER)

            assertEquals(
                BUILT_IN_UNICODE_VERSION,
                activeUnicodeVersion(page),
                "a terminal opens on xterm's built-in Unicode 6 table",
            )
            assertTrue(
                fetched.any { it.contains("/vendor/xterm.js") },
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

            page.waitForFunction(WAIT_FOR_UNICODE_11)
            assertEquals(
                UNICODE_11_MODE,
                activeUnicodeVersion(page),
                "the selected provider is made ACTIVE, not merely registered",
            )

            val addons = fetched.filter { it.contains(UNICODE_ADDON_MARKER) }
            assertEquals(
                1,
                addons.size,
                "exactly the selected mode's module is fetched — the other addon stays undownloaded: " +
                    "$addons",
            )
            assertTrue(
                REVISIONED_UNICODE_11.containsMatchIn(addons[0]),
                "the relative specifier resolved under the content-revision prefix: ${addons[0]}",
            )

            runLeaderCommand(page, "Preferences")
            assertThat(page.locator("#prefs-dialog")).isVisible()
            page.locator("#prefs-terminal-unicode").selectOption(DEFAULT_UNICODE_MODE)
            page.locator("#prefs-submit").click()
            assertThat(page.locator("#prefs-dialog")).hasCount(0)

            page.waitForFunction(WAIT_FOR_BUILT_IN_UNICODE)
            assertEquals(
                BUILT_IN_UNICODE_VERSION,
                activeUnicodeVersion(page),
                "turning the preference off restores the version the install captured",
            )
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

    private fun activeUnicodeVersion(page: Page): String = page.evaluate(ACTIVE_UNICODE_VERSION) as String

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

        const val BUILT_IN_UNICODE_VERSION = "6"
        const val DEFAULT_UNICODE_MODE = "default"
        const val UNICODE_11_MODE = "11"
        const val UNICODE_ADDON_MARKER = "addon-unicode"
        val REVISIONED_UNICODE_11 = Regex("/_v/[0-9a-f]{12}/vendor/addon-unicode11\\.module\\.js")

        // Control-C is last because the tty line discipline may flush earlier queued echo on INTR.
        val SPECIAL_KEYS = listOf(
            SpecialKey("Escape", listOf(0x1b), "^["),
            SpecialKey("Shift Tab", listOf(0x1b, 0x5b, 0x5a), "^[[Z"),
            SpecialKey("Up arrow", listOf(0x1b, 0x5b, 0x41), "^[[A"),
            SpecialKey("Down arrow", listOf(0x1b, 0x5b, 0x42), "^[[B"),
            SpecialKey("Left arrow", listOf(0x1b, 0x5b, 0x44), "^[[D"),
            SpecialKey("Right arrow", listOf(0x1b, 0x5b, 0x43), "^[[C"),
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

        val CAPTURE_TERMINAL_CONSTRUCTIONS = """
            (() => {
              let real = null;
              window.__kotgentTerminals = [];
              Object.defineProperty(window, "Terminal", {
                configurable: true,
                get() {
                  if (!real) return undefined;
                  if (!window.__kotgentTerminalProxy) {
                    window.__kotgentTerminalProxy = new Proxy(real, {
                      construct(target, args) {
                        const term = Reflect.construct(target, args);
                        window.__kotgentTerminals.push(term);
                        return term;
                      },
                    });
                  }
                  return window.__kotgentTerminalProxy;
                },
                set(value) { real = value; },
              });
            })();
        """.trimIndent()

        val ACTIVE_UNICODE_VERSION = """
            () => {
              const terms = window.__kotgentTerminals || [];
              if (terms.length === 0) return "no-terminal-was-constructed";
              return String(terms[terms.length - 1].unicode.activeVersion);
            }
        """.trimIndent()

        val WAIT_FOR_UNICODE_11 = """
            () => {
              const terms = window.__kotgentTerminals || [];
              return terms.length > 0 && terms[terms.length - 1].unicode.activeVersion === "$UNICODE_11_MODE";
            }
        """.trimIndent()

        val WAIT_FOR_BUILT_IN_UNICODE = """
            () => {
              const terms = window.__kotgentTerminals || [];
              return terms.length > 0 &&
                terms[terms.length - 1].unicode.activeVersion === "$BUILT_IN_UNICODE_VERSION";
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
