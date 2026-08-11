package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import com.microsoft.playwright.WebSocketFrame
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FilePayload
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three phone-shaped features, driven in a real touch-capable browser against a real pty.
 *
 * All three used to be pinned by reading the SPA's own source text out of the daemon
 * (`WebUiServingTest`), which is exactly the wrong instrument for every one of them:
 *
 *  - the **key bar** promises that a special key leaves as a *binary* WebSocket frame, that pressing it
 *    does not move focus away from xterm's hidden textarea, and that its Ctrl is a ONE-SHOT modifier that
 *    rewrites exactly the next printable key and then disarms. A grep can see `Uint8Array.from(...)`,
 *    `event.preventDefault()` and the `ctrlBytesFor` switch; it cannot see a frame, a
 *    `document.activeElement` or an `aria-pressed` that stayed true. Here all of them are read directly —
 *    the frames off the live socket, and the echo the pty's line discipline sends back, which is what
 *    proves the bytes were delivered as terminal INPUT rather than parsed as a resize control.
 *  - the **upload** promises that the palette's picker reaches `POST /sessions/{id}/files?name=…` with a
 *    leaf name, that the daemon's uploader stores it under the session's own cwd, and that a partial
 *    batch names every file that failed. A grep can see the URL being built; only a browser can pick
 *    files, run the loop, and read what the operator is told afterwards.
 *  - the **unicode addons** promise that nothing is downloaded until the preference asks for it. The
 *    absence of a `<script>` tag was the closest a grep could get to that. Playwright watches the
 *    network instead: no request, then the preference flips, then exactly one request — and the live
 *    terminal's `unicode.activeVersion` changes with it, which is the half a download alone does not buy
 *    (`Unicode11Addon.activate()` only REGISTERS its provider; without the explicit assignment in
 *    `lib/unicode.js` the fetch happens and absolutely nothing changes — a failure with no symptom).
 *
 * Every test runs the `terminal` scenario: one running session (`s-term`, cwd `/w/terminal`) whose
 * upstream is a real `/bin/sh -c 'printf …; cat'` on a real pty. Two properties of that fixture are
 * load-bearing here. The banner is printed LAST, so waiting for it means the whole payload arrived and
 * the browser is a live subscriber of the bridge. And `cat` holds the pty open while the tty's own line
 * discipline (kernel-default termios: `ICANON|ECHO|ECHOCTL`) echoes what is written into it — control
 * bytes as `^X`, which is what makes an Escape or a Ctrl-C *visible in the DOM* rather than invisible.
 */
class MobileFeaturesTest {

    /**
     * A key bar press is a binary terminal frame, it arrives at the pty, and it does not steal focus.
     *
     * The three assertions are deliberately independent, because the three ways this breaks are
     * independent. A key that became a TEXT frame would be read by the terminal WS as a resize control
     * and silently discarded (no error, the key just stops working) — so the frames are inspected by
     * kind, and the socket's text frames are separately shown to be nothing but resize controls. A key
     * whose bytes were mangled would still be a binary frame — so the pty's echo is read back. And a
     * press that moved focus would close the phone's software keyboard under the operator's thumb — so
     * `document.activeElement` is checked after every press.
     *
     * The keys are pressed one at a time and each echo is awaited before the next press. That is not
     * politeness: `^C` is the tty's INTR character, and the line discipline flushes the output queue
     * before echoing it (`ttyflush(FREAD|FWRITE)`), so anything still queued at that moment would be
     * discarded. Waiting for each echo to be painted proves the queue is already drained. (The Ctrl-C
     * reaches nobody, by the way — a `posix_spawn`ed child has no controlling terminal and the pts has
     * no foreground process group, so `cat` survives it. That is the same fact `Pty.resize` has to send
     * `SIGWINCH` by hand for.)
     */
    @Test
    fun theKeyBarsSpecialKeysArriveAtThePtyAsBinaryFramesWithoutTakingFocusFromXterm() {
        onMobileTerminal("mobile-key-bar") { harness, _, page ->
            // Registered before the navigation that opens the socket: a handler attached afterwards
            // would miss the frames of the very keys under test.
            val sent = CopyOnWriteArrayList<WebSocketFrame>()
            page.onWebSocket { socket ->
                if (socket.url().contains(TERMINAL_WS_PATH)) socket.onFrameSent { sent.add(it) }
            }

            page.navigate(harness.baseUrl + SESSION_ROUTE)
            val rows = page.locator(TERMINAL_ROWS)
            assertThat(rows).containsText(TERMINAL_BANNER)
            // The bar is rendered only for an ATTACHED session and is `display: none` above the mobile
            // breakpoint, so its visibility here is the whole "phone-only, attached-only" contract.
            assertThat(page.locator(KEY_BAR)).isVisible()

            assertEquals(
                true,
                page.evaluate(FOCUS_XTERM_TEXTAREA),
                "the test's own precondition: xterm's hidden textarea can be focused",
            )

            var echoed = ""
            var pressed = 0
            for (key in SPECIAL_KEYS) {
                // A tap, not a click: this bar exists for a thumb, and `preserveTerminalFocus` cancels
                // `pointerdown` precisely so the platform's compatibility mouse burst — the thing that
                // would focus the button — never happens. The command itself stays on `click`, so
                // keyboard and switch-control activation still work.
                page.locator(KEY_BAR + " button[aria-label='" + key.label + "']").tap()
                pressed++
                // Every key waits for its own frame, which is what keeps the queue drained before `^C`
                // (the INTR character) reaches the line discipline and flushes whatever is still in it.
                page.waitForCondition { sent.count { frame -> frame.text() == null } >= pressed }
                if (key.echo == null) {
                    // Tab breaks the running string: the tty echoes it as a real HT, so the cursor jumps
                    // to the next stop and the row's text carries a gap rather than a character. Its
                    // BYTES are still asserted below, and the prefix restarts after it.
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
            // `WebSocketFrameImpl` carries either a text payload or a binary one and nulls the other, so
            // this partition IS the frame kind — not a guess about the payload's shape.
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

            // --- the sticky Ctrl one-shot -------------------------------------------------------------
            //
            // The bar's Ctrl is a MODIFIER, not a key: it arms `ctrlActiveRef` and the NEXT printable
            // character `term.onData` delivers is rewritten by `ctrlBytesFor` and consumes the arm. Two
            // things ship silently if that breaks — a Ctrl left armed forever (every later keystroke
            // becomes a control code) and a wrong byte out of the digit aliases, which are the only part
            // of the mapping that is not `code & 0x1f`.
            val ctrl = page.locator(CTRL_KEY)
            assertThat(ctrl).hasAttribute("aria-pressed", "false")
            val beforeCtrl = sent.count { frame -> frame.text() == null }

            ctrl.tap()
            assertThat(ctrl).hasAttribute("aria-pressed", "true")
            // The tap kept xterm's textarea focused (same `preserveTerminalFocus` as every other key), so
            // this really is typing at the terminal.
            page.keyboard().type("a")
            assertThat(rows).containsText("^A")
            assertThat(ctrl).hasAttribute(
                "aria-pressed",
                "false",
                LocatorAssertions.HasAttributeOptions().setTimeout(KEY_ECHO_TIMEOUT_MS),
            )

            // Disarmed: the very same key is now itself. Without the one-shot this would be a second `^A`.
            page.keyboard().type("a")
            assertThat(rows).containsText("^Aa")

            // A digit alias: Ctrl+3 is ESC, which `code & 0x1f` would never produce (0x33 & 0x1f = 0x13).
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

    /**
     * The palette's `f` command uploads the picked files into the selected session's own folder, and a
     * batch in which some files fail names every one of them.
     *
     * **What the browser can and cannot see of the sink.** The harness replaces the filesystem edge with
     * an in-memory uploader keyed by `<directory>/<name>`; nothing outside that process can read its map.
     * So the evidence is what the daemon says back, and it is enough to pin both halves of the contract:
     *
     *  - the 201 body is written by the route from the uploader's OWN answer — the name it stored, the
     *    number of bytes it counted, and the directory it resolved from the session row (never from the
     *    browser, which submits no path at all);
     *  - a file whose bytes were re-encoded on the way would report a different count, which is why one
     *    payload is deliberately not valid UTF-8: `0x80 0xfe 0xff` would each become a three-byte
     *    replacement character and the stored size would grow;
     *  - and the second batch re-picks those same names, which the uploader answers `AlreadyExists` for —
     *    the in-memory stand-in for the real writer's no-clobber `link(2)`. That 409 is proof the first
     *    batch really landed under exactly those names in exactly that directory, and it is why this test
     *    needs no `page.route` to manufacture a failure: the harness produces a real one.
     */
    @Test
    fun thePaletteUploadsPickedFilesIntoTheSessionsFolderAndNamesEveryFailureInAPartialBatch() {
        onMobileTerminal("mobile-upload") { harness, _, page ->
            val uploads = CopyOnWriteArrayList<Request>()
            val answers = CopyOnWriteArrayList<Response>()
            page.onRequest { if (it.url().contains(UPLOAD_PATH)) uploads.add(it) }
            page.onResponse { if (it.url().contains(UPLOAD_PATH)) answers.add(it) }

            page.navigate(harness.baseUrl + SESSION_ROUTE)
            assertThat(page.locator(TERMINAL_ROWS)).containsText(TERMINAL_BANNER)

            runLeaderCommand(page, "Upload files to current folder")
            assertThat(page.locator("#upload-dialog")).isVisible()
            // Display-only, and that is the point: the request carries a session id and a leaf name, so
            // this is the daemon's cwd being shown to the operator, not a path the browser submits.
            assertThat(page.locator(".upload-destination")).containsText(SESSION_CWD)

            page.locator("#upload-files").setInputFiles(
                arrayOf(
                    FilePayload(NOTES_NAME, "text/plain", NOTES_BYTES),
                    FilePayload(DATA_NAME, "application/octet-stream", DATA_BYTES),
                ),
            )
            page.locator("#upload-submit").click()

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

            // The partial batch: the two names already in the folder, plus one that is not.
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
            // The one file that DID land must not appear in the failure report, and the loop must not
            // have stopped at the first refusal — the count above already says one file was stored.
            assertThat(failures).not().containsText(FRESH_NAME)
        }
    }

    /**
     * The unicode addon is fetched only once the preference selects it, and then it really becomes the
     * table the terminal measures with.
     *
     * The gate is the dynamic `import()` in `lib/unicode.js` and nothing else: both addons are vendored
     * and served, but no markup mentions them, so an operator who never changes the default pays none of
     * their 65 KB. That absence is asserted from the network, which is strictly stronger than the grep it
     * replaces — a `<script>` tag is only one of the ways a well-meaning "just load them with xterm"
     * change could start fetching them. (The vendored-and-served half stays in `WebUiServingTest`: it is
     * a statement about the daemon's static serving, not about a browser.)
     *
     * `activeVersion` is read off the app's own live `Terminal`, captured through an accessor installed
     * on `window.Terminal` before any page script runs. xterm is loaded as a classic script, so its UMD
     * wrapper assigns the class onto the global object — the accessor intercepts that assignment and
     * hands the app a construction-recording proxy of the real class. Nothing in `resources/webui` is
     * altered or re-served; the object read here is the very one the pane is drawing with.
     *
     * The request's URL carries the `/_v/<rev>/` content revision, which is the property the RELATIVE
     * specifier buys: it resolves against `lib/unicode.js`'s own revisioned URL, where a
     * document-relative importmap target could not.
     */
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

            // Waiting on the version rather than on the request is what makes this deterministic: the
            // fetch is only the first half, and the install lands a microtask after the module resolves.
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

            // Back to the default, which is the disposer's whole job and the reason CLAUDE.md calls it
            // load-bearing: `Unicode11Addon.dispose()` is EMPTY and a registered provider can never be
            // unregistered, only shadowed — so `installTerminalUnicode` has to restore the version it
            // captured at install time by hand. Without that line the selector moves and the terminal
            // keeps measuring with Unicode 11 forever, on a preference the operator has turned off.
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

    // --- fixture plumbing ------------------------------------------------------------------------

    /**
     * One harness on the `terminal` scenario, one touch-capable Chromium, one signed-in phone-shaped
     * context, and a page that has NOT navigated yet — every test here installs request, WebSocket or
     * init-script observers first, and an observer attached after the first navigation misses the very
     * traffic it exists to watch.
     */
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

    /**
     * Open the palette on its leader grid (the header's ⋯ button, the one control that is on screen at
     * every width) and run the command whose title contains [title].
     *
     * The grid, not the search list: the leader grid is exactly the chord-bearing descriptors of the one
     * registry in `lib/commands.js`, so a command that disappeared from it would fail here rather than
     * be quietly found by a substring search over everything.
     */
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

    /** The leaf name a `POST …/files?name=<leaf>` carries. */
    private fun uploadedName(request: Request): String = request.url().substringAfter("name=")

    /**
     * One key bar button: what it is labelled, what it sends, and how the tty echoes that back — `null`
     * for a key whose echo moves the cursor instead of drawing a character (Tab).
     */
    private class SpecialKey(val label: String, val bytes: List<Int>, val echo: String?)

    private companion object {
        /** The `terminal` scenario's single session and the banner its upstream prints last. */
        const val SESSION_ROUTE = "/s/s-term"
        const val SESSION_CWD = "/w/terminal"
        const val TERMINAL_BANNER = "KOTGENT-TERMINAL-READY"

        const val TERMINAL_ROWS = "#terminal-host .xterm-rows"
        const val KEY_BAR = ".key-bar"
        const val XTERM_TEXTAREA_CLASS = "xterm-helper-textarea"
        const val TERMINAL_WS_PATH = "/terminal"
        const val UPLOAD_PATH = "/files?name="

        /**
         * `lib/unicode.js`'s own name for what xterm registers in its constructor — and, separately, the
         * PREFERENCE value that selects it. The two are deliberately different words there
         * (`DEFAULT_TERMINAL_UNICODE` is `"default"`, `BUILT_IN_UNICODE_VERSION` is `"6"`), because one
         * names a menu entry and the other names the provider xterm ends up measuring with.
         */
        const val BUILT_IN_UNICODE_VERSION = "6"
        const val DEFAULT_UNICODE_MODE = "default"
        const val UNICODE_11_MODE = "11"
        const val UNICODE_ADDON_MARKER = "addon-unicode"
        val REVISIONED_UNICODE_11 = Regex("/_v/[0-9a-f]{12}/vendor/addon-unicode11\\.module\\.js")

        /**
         * EVERY key the bar renders, its bytes, and the echo the tty answers with.
         *
         * All eight, not a sample: a byte is a byte and nothing else in the app or the tests would notice
         * `←` sending the sequence for `→`. The four arrows differ only in their last character, which is
         * exactly the mistake a partial list cannot catch.
         *
         * `ECHOCTL` renders a control byte as `^` plus the character 0x40 above it, so `0x1b` comes back
         * as the two printable characters `^[` and `0x03` as `^C`; the `[`, `A` and `Z` of a CSI sequence
         * are printable already and echo as themselves. Nothing here is interpreted by xterm as an escape
         * sequence — the terminal is showing the LITERAL text the line discipline produced, which is
         * exactly why it is readable in the DOM. Tab is the exception and carries no echo string: `ECHOCTL`
         * exempts HT, so the tty echoes a real tab and the cursor jumps instead of a character appearing.
         *
         * `^C` goes last: the INTR character makes the line discipline flush the output queue before it
         * echoes, so it is the one key that could discard an earlier key's echo if the two were pressed
         * without waiting in between. Tab goes second-to-last so the running echo prefix stays contiguous
         * for as long as possible.
         */
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

        /** The bar's sticky modifier. */
        const val CTRL_KEY = "#key-bar-ctrl"

        /** A key's echo has to cross a pty and a render; give the one-shot's own read the same room. */
        const val KEY_ECHO_TIMEOUT_MS = 10_000.0

        const val NOTES_NAME = "kotgent-notes.txt"
        val NOTES_BYTES = "upload one\nupload two\n".toByteArray()

        /**
         * Deliberately NOT valid UTF-8. `0x80`, `0xfe` and `0xff` are each a lone invalid byte, so any
         * path that decoded this body as text and re-encoded it would replace them with U+FFFD and the
         * stored size would be six bytes larger than the file's — which is what makes the byte count the
         * daemon reports evidence about the CONTENT and not only about the length.
         */
        const val DATA_NAME = "kotgent-data.bin"
        val DATA_BYTES = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xfe.toByte(), 0xff.toByte())

        const val FRESH_NAME = "kotgent-fresh.txt"
        val FRESH_BYTES = "the one file this batch had not sent before\n".toByteArray()

        /**
         * Capture every `Terminal` the app constructs, without touching a line of `resources/webui`.
         *
         * xterm's UMD wrapper copies its exports onto the global object with a plain assignment
         * (`e[s] = i[s]` over `globalThis`), so an accessor installed at document-start intercepts it.
         * The getter hands out a `Proxy` whose only trap is `construct`, which means the app still builds
         * the real class with its real arguments and every static, prototype and property behaves as it
         * did — the proxy merely remembers the instance so a test can read its public API.
         */
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
