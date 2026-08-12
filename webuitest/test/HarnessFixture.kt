package io.kotgent.webuitest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.test.fail

// The JVM browser tests spawn a native harness because KT-78062 prevents linking its cinterop here.
// Each login needs a fresh context: cookies are host-scoped, not port-scoped, but harness tokens differ.
const val EMPTY_SCENARIO: String = "empty"
const val SESSIONS_SCENARIO: String = "sessions"
const val ATTENTION_SCENARIO: String = "attention"
const val RESTART_SCENARIO: String = "restart"
const val TERMINAL_SCENARIO: String = "terminal"
const val TERMINAL_X10_SCENARIO: String = "terminal-x10"
const val BOARD_SCENARIO: String = "board"
const val BOARD_EMPTY_SCENARIO: String = "board-empty"
const val BOARD_PROJECTS_SCENARIO: String = "board-projects"
const val TASK_DETAIL_SCENARIO: String = "task-detail"
const val TASK_LINKED_SESSION_SCENARIO: String = "task-linked-session"
const val DEEP_LINK_SCENARIO: String = "deep-link"

const val PALETTE_OPENER: String = "Meta+KeyK" // The app matches macOS physical event codes.

const val AUTH_PAGE_PATH: String = "/auth"

const val HEADED_ENV: String = "KOTGENT_WEBUITEST_HEADED"

class Harness(scenario: String) : AutoCloseable {
    private val process: Process
    private val stdin: BufferedWriter
    private val stdoutLines = LinkedBlockingQueue<String>()
    private val stdoutClosed = AtomicBoolean(false)
    private val stderrBuffer = StringBuilder()
    private val readers: List<Thread>

    val port: Int

    val ticket: String

    val baseUrl: String

    init {
        configurePlaywrightDefaults()
        // A cold Playwright download can outlive the harness watchdog, so install before spawning it.
        installBrowserBundleOnce()
        val binary = harnessBinary()
        val webUiDir = repoRoot.resolve(WEB_UI_RELATIVE).toAbsolutePath().normalize()
        if (!Files.isDirectory(webUiDir)) {
            fail("the Web UI directory is missing: $webUiDir")
        }
        process = ProcessBuilder(
            binary.toString(),
            "--scenario=$scenario",
            "--webui-dir=$webUiDir",
            "--exit-after-ms=$WATCHDOG_MILLIS",
        )
            .directory(repoRoot.toFile())
            // Stdout is the handshake protocol; stderr must remain separate.
            .redirectErrorStream(false)
            .start()
        stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        // Drain both pipes continuously; a full child pipe blocks the native harness.
        val stdoutReader = drain("webuicheck-stdout", process.inputStream) { stdoutLines.put(it) }
        val stderrReader = drain("webuicheck-stderr", process.errorStream) { line ->
            synchronized(stderrBuffer) {
                stderrBuffer.append(line).append('\n')
                val overflow = stderrBuffer.length - MAX_STDERR_CHARS
                if (overflow > 0) stderrBuffer.delete(0, overflow)
            }
        }
        readers = listOf(stdoutReader, stderrReader)
        latchStdoutEof(stdoutReader)

        val handshake = try {
            readHandshake()
        } catch (t: Throwable) {
            // A constructor failure has no `use` scope available to close the process.
            runCatching { process.destroyForcibly() }
            throw t
        }
        port = handshake.first
        ticket = handshake.second
        baseUrl = "http://127.0.0.1:$port"
    }

    private fun readHandshake(): Pair<Int, String> {
        val portLine = nextStdoutLine("the PORT handshake line")
        val ticketLine = nextStdoutLine("the TICKET handshake line")
        val readyLine = nextStdoutLine("the READY handshake line")
        if (!portLine.startsWith(PORT_PREFIX)) {
            harnessFailure("expected a '$PORT_PREFIX<n>' handshake line, got '$portLine'")
        }
        if (!ticketLine.startsWith(TICKET_PREFIX)) {
            harnessFailure("expected a '$TICKET_PREFIX<code>' handshake line, got '$ticketLine'")
        }
        if (readyLine != READY_LINE) {
            harnessFailure("expected a '$READY_LINE' handshake line, got '$readyLine'")
        }
        val parsedPort = portLine.removePrefix(PORT_PREFIX).trim().toIntOrNull()
            ?: harnessFailure("the handshake port is not a number: '$portLine'")
        val parsedTicket = ticketLine.removePrefix(TICKET_PREFIX).trim()
        if (parsedTicket.isEmpty()) harnessFailure("the handshake ticket is empty")
        return parsedPort to parsedTicket
    }

    fun send(line: String) {
        require('\n' !in line && '\r' !in line) { "a harness command is exactly one line, got: $line" }
        if (!process.isAlive) {
            harnessFailure("cannot send '$line': the webuicheck harness has already exited")
        }
        try {
            stdin.write(line)
            stdin.write("\n")
            stdin.flush()
        } catch (e: IOException) {
            harnessFailure("writing '$line' to the webuicheck harness failed: ${e.message}")
        }
        if (line.trim() == RESTART_COMMAND) {
            val ready = nextStdoutLine("the second READY after `restart`", RESTART_TIMEOUT_MILLIS)
            if (ready != READY_LINE) {
                harnessFailure("expected a second '$READY_LINE' after `restart`, got '$ready'")
            }
        }
        // A command whose effect reaches the page only through a request the PAGE makes has no frame to
        // wait on, so the harness acknowledges it and this is the barrier. Without it the very next
        // assertion can read a dialog that asked before the write landed, and no retry recovers that.
        val verb = line.trim().substringBefore(' ')
        if (verb in ACKNOWLEDGED_COMMANDS) {
            val ack = nextStdoutLine("the '$COMMAND_ACK_PREFIX$verb' acknowledgement")
            if (ack != COMMAND_ACK_PREFIX + verb) {
                harnessFailure("expected '$COMMAND_ACK_PREFIX$verb' after `$line`, got '$ack'")
            }
        }
    }

    override fun close() {
        runCatching { stdin.close() }
        var killed = false
        if (!process.waitFor(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            killed = true
            process.destroy()
            if (!process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
        readers.forEach { it.join(READER_JOIN_MILLIS) }
        if (killed) {
            harnessFailure(
                "the webuicheck harness did not exit within ${SHUTDOWN_TIMEOUT_MILLIS}ms of its stdin " +
                    "being closed and had to be killed",
            )
        }
        val code = process.exitValue()
        if (code != 0) harnessFailure("the webuicheck harness exited with code $code")
    }

    private fun stderrSoFar(): String = synchronized(stderrBuffer) { stderrBuffer.toString() }

    private fun harnessFailure(message: String): Nothing {
        val status = if (process.isAlive) "still running" else "exited with ${process.exitValue()}"
        val stderr = stderrSoFar().ifBlank { "(nothing on stderr)" }
        fail("$message\n  harness: $status\n  harness stderr:\n$stderr")
    }

    private fun nextStdoutLine(what: String, timeoutMillis: Long = HANDSHAKE_TIMEOUT_MILLIS): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            stdoutLines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)?.let { return it }
            if (stdoutClosed.get() && stdoutLines.isEmpty()) {
                harnessFailure("the webuicheck harness closed its stdout while waiting for $what")
            }
            if (System.nanoTime() >= deadline) {
                harnessFailure("timed out after ${timeoutMillis}ms waiting for $what")
            }
        }
    }

    private fun latchStdoutEof(reader: Thread) {
        Thread {
            reader.join()
            stdoutClosed.set(true)
        }.apply {
            isDaemon = true
            name = "webuicheck-stdout-eof"
            start()
        }
    }

    private fun drain(name: String, stream: InputStream, onLine: (String) -> Unit): Thread =
        Thread {
            runCatching {
                stream.bufferedReader(StandardCharsets.UTF_8).forEachLine(onLine)
            }
        }.apply {
            isDaemon = true
            this.name = name
            start()
        }
}

/** Uses the real form so installed-PWA login and its empty initial cookie jar stay covered. */
fun BrowserContext.loginWithTicket(ticket: String, baseUrl: String) {
    val root = baseUrl.trimEnd('/')
    val page = newPage()
    try {
        page.navigate(root + AUTH_PAGE_PATH)
        assertThat(page.locator("#code-form")).isVisible()
        page.locator("#code").fill(ticket)
        page.locator("#code-submit").click()
        page.waitForURL("$root/")
        assertThat(page.locator("#app")).hasCount(1)
    } finally {
        page.close()
    }
}

/*
 * Driving the command palette is fixture work, not a subject: these four gestures each encode something
 * the platform taught this tier — that a dialog must be seen to unmount before the opener is pressed
 * again, that Chromium spends the first Escape of a non-empty search input on its own clear, and that a
 * wait must be on a state that measurably changes. Reach a command through here so a fifth lesson lands
 * in one place; a few older classes still press their own keys privately and predate this.
 */
fun Page.openPalette(): Locator {
    // Wait for the previous dialog node to unmount before toggling its state again.
    assertThat(locator("#command-palette")).hasCount(0)
    keyboard().press(PALETTE_OPENER)
    val shell = locator(".command-palette-shell.leader")
    assertThat(shell).isVisible()
    assertThat(shell).isFocused()
    return shell
}

fun Page.closePalette() {
    val query = searchQuery()
    // Chromium consumes the first Escape in a non-empty search input to clear the field.
    if (query.count() > 0) query.fill("")
    keyboard().press("Escape")
    assertThat(locator("#command-palette")).hasCount(0)
}

fun Page.pressMnemonic(code: String) {
    keyboard().press(code)
}

fun Page.searchQuery(): Locator = locator("#command-palette-query")

fun Page.searchMode(): Locator {
    pressMnemonic("KeyK")
    val query = searchQuery()
    assertThat(query).isVisible()
    assertThat(query).isFocused()
    return query
}

fun Page.searchFor(query: String): Locator {
    val field = searchMode()
    field.fill(query)
    return field
}

fun Page.paletteOptions(): Locator = locator(".command-palette-option")

fun Page.paletteOption(title: String): Locator =
    paletteOptions().filter(Locator.FilterOptions().setHasText(title))

fun Page.runFirstMatch(query: String, expected: String) {
    val field = searchFor(query)
    val options = paletteOptions()
    assertThat(options).hasCount(1)
    assertThat(options.first()).containsText(expected)
    assertThat(options.first()).hasClass(ACTIVE_OPTION)
    field.press("Enter")
}

fun Page.awaitSessionView() {
    assertThat(locator("#terminal-pane")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
    assertThat(locator("main.board")).hasCount(0)
}

fun Page.awaitBoard() {
    assertThat(locator("main.board")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
    assertThat(locator("#terminal-pane")).hasCount(0)
}

fun visibleWithin(millis: Double): LocatorAssertions.IsVisibleOptions =
    LocatorAssertions.IsVisibleOptions().setTimeout(millis)

/** The palette's highlight is a class among others, so the match is on the word. */
val ACTIVE_OPTION: Pattern = Pattern.compile("\\bactive\\b")

/** A cold page has to boot the app, the events socket and its first snapshot. */
const val BOOT_TIMEOUT_MS: Double = 15_000.0

// Chromium preserves the active pointer id across emulated touch events; synthetic DOM events do not.
fun touchChromium(pw: Playwright): Browser =
    pw.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(System.getenv(HEADED_ENV) == null)
            .setArgs(listOf("--touch-events=enabled")),
    )

fun onChromium(block: (Browser) -> Unit) {
    Playwright.create().use { pw ->
        touchChromium(pw).use { browser -> block(browser) }
    }
}

fun Browser.touchContext(
    width: Int = 390,
    height: Int = 844,
    deviceScaleFactor: Double = 3.0,
    mobile: Boolean = true,
): BrowserContext = newContext(
    Browser.NewContextOptions()
        .setViewportSize(width, height)
        .setDeviceScaleFactor(deviceScaleFactor)
        .setIsMobile(mobile)
        .setHasTouch(true),
)

fun Browser.fineContext(
    width: Int = 1280,
    height: Int = 900,
): BrowserContext = newContext(
    Browser.NewContextOptions()
        .setViewportSize(width, height)
        .setDeviceScaleFactor(1.0)
        .setIsMobile(false)
        .setHasTouch(false),
)

fun testResultsDir(): Path = repoRoot.resolve(TEST_RESULTS_RELATIVE).also { Files.createDirectories(it) }

fun BrowserContext.traced(name: String, block: () -> Unit) {
    val slug = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '-' }
        .joinToString("")
    tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
    var failed = false
    try {
        block()
    } catch (t: Throwable) {
        failed = true
        runCatching {
            pages().lastOrNull()?.screenshot(
                Page.ScreenshotOptions()
                    .setPath(testResultsDir().resolve("$slug.png"))
                    .setFullPage(true),
            )
        }
        throw t
    } finally {
        runCatching {
            if (failed) {
                tracing().stop(Tracing.StopOptions().setPath(testResultsDir().resolve("$slug.zip")))
            } else {
                tracing().stop()
            }
        }
    }
}

/** Playwright evaluates this in JavaScript, where Java's `Pattern.quote` (`\Q…\E`) is not quoting. */
fun regexLiteral(text: String): String = buildString {
    for (ch in text) {
        if (ch in REGEX_METACHARACTERS) append('\\')
        append(ch)
    }
}

private const val REGEX_METACHARACTERS = "\\^$.|?*+()[]{}"

// Install before page load: the listener must precede the app's socket listener to be a reliable barrier.
val FRAME_RECORDER: String = """
    (() => {
      const frames = [];
      window.__kotgentFrames = frames;
      const Native = window.WebSocket;
      const Recording = function (url, protocols) {
        const socket = protocols === undefined ? new Native(url) : new Native(url, protocols);
        if (String(url).indexOf("/api/v1/events") >= 0) {
          socket.addEventListener("message", (event) => {
            if (typeof event.data === "string") frames.push(event.data);
          });
        }
        return socket;
      };
      Recording.prototype = Native.prototype;
      Recording.CONNECTING = Native.CONNECTING;
      Recording.OPEN = Native.OPEN;
      Recording.CLOSING = Native.CLOSING;
      Recording.CLOSED = Native.CLOSED;
      window.WebSocket = Recording;
    })();
""".trimIndent()

/** Null coordinates deliberately send no contact points; Chromium requires that shape for `touchEnd`. */
fun dispatchTouch(cdp: CDPSession, type: String, x: Double?, y: Double?) {
    val points = JsonArray()
    if (x != null && y != null) {
        val point = JsonObject()
        point.addProperty("x", x)
        point.addProperty("y", y)
        point.addProperty("id", 0)
        points.add(point)
    }
    val params = JsonObject()
    params.addProperty("type", type)
    params.add("touchPoints", points)
    cdp.send("Input.dispatchTouchEvent", params)
}

// Duplicated in native WebUiCheckTest because constants cannot cross the native/JVM module boundary.
private val HARNESS_BINARIES = listOf(
    "build/tasks/_webuicheck_linkMacosArm64Debug/webuicheck.kexe",
    "build/tasks/_webuicheck_linkMacosArm64Release/webuicheck.kexe",
)

private const val WEB_UI_RELATIVE = "resources/webui"
private const val TEST_RESULTS_RELATIVE = "webuitest/test-results"
private const val PORT_PREFIX = "PORT="
private const val TICKET_PREFIX = "TICKET="
private const val READY_LINE = "READY"
private const val RESTART_COMMAND = "restart"

// Duplicated from webuicheck's COMMAND_ACK_PREFIX: constants cannot cross the native/JVM boundary.
private const val COMMAND_ACK_PREFIX = "OK "
private val ACKNOWLEDGED_COMMANDS = setOf("project-del", "project-restore")

private const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
private const val RESTART_TIMEOUT_MILLIS = 30_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 15_000L
private const val KILL_GRACE_MILLIS = 5_000L
private const val READER_JOIN_MILLIS = 2_000L
private const val POLL_MILLIS = 100L
private const val MAX_STDERR_CHARS = 64_000
private const val WATCHDOG_MILLIS = 300_000L

private const val ASSERTION_TIMEOUT_MILLIS = 15_000.0

private val playwrightConfigured = AtomicBoolean(false)

private fun configurePlaywrightDefaults() {
    if (playwrightConfigured.compareAndSet(false, true)) {
        PlaywrightAssertions.setDefaultAssertionTimeout(ASSERTION_TIMEOUT_MILLIS)
    }
}

private val browserBundleInstalled = AtomicBoolean(false)

private fun installBrowserBundleOnce() {
    if (!browserBundleInstalled.compareAndSet(false, true)) return
    Playwright.create().close()
}

// JVM test working directories are not fixed by the toolchain.
private val repoRoot: Path by lazy { locateRepoRoot() }

private fun locateRepoRoot(): Path {
    val starts = buildList {
        System.getProperty("user.dir")?.let { add(Path.of(it)) }
        codeSourceDirectory()?.let { add(it) }
    }.map { it.toAbsolutePath().normalize() }
    for (start in starts) {
        var dir: Path? = start
        while (dir != null) {
            val hasManifest = Files.isRegularFile(dir.resolve("project.yaml"))
            val hasWebUi = Files.isRegularFile(dir.resolve(WEB_UI_RELATIVE).resolve("index.html"))
            if (hasManifest && hasWebUi) return dir
            dir = dir.parent
        }
    }
    fail(
        "could not locate the kotgent checkout (looked for a directory holding both project.yaml and " +
            "$WEB_UI_RELATIVE/index.html, walking up from ${starts.joinToString()})",
    )
}

private fun codeSourceDirectory(): Path? = runCatching {
    val location = Harness::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
    val path = Path.of(location.toURI())
    if (Files.isDirectory(path)) path else path.parent
}.getOrNull()

private fun harnessBinary(): Path {
    val candidates = HARNESS_BINARIES.map { repoRoot.resolve(it) }
    return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: fail(
            "the webuicheck binary is missing (looked for ${candidates.joinToString()}). " +
                "It is built by `./kotlin build`, which `./kotlin test` does not do on its own — " +
                "run `./kotlin build` first.",
        )
}
