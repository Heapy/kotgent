package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
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
import kotlin.test.fail

/**
 * The browser tier's one fixture: how a test gets a live kotgent Web UI, signed in, in a real browser.
 *
 * ```
 *   Harness("sessions")                 spawn webuicheck --scenario=… --webui-dir=…, read PORT/TICKET/READY
 *   touchChromium(pw)                   a Chromium that can be given touch contexts
 *   context.loginWithTicket(t, url)     type the code into the REAL /auth form, land on the SPA
 *   harness.send("restart")             a command down the harness's stdin
 *   harness.close()                     close stdin → graceful stop → exit 0 asserted
 * ```
 *
 * ## Why a spawned harness instead of a server started in-process
 * The harness is a native `macos/app` main binary because that is the only place this project's own
 * cinterop links (KT-78062), so the terminal scenarios sit on a REAL `Pty` under a REAL `TerminalBridge`.
 * A JVM test cannot host that at all. It also means each test gets its own process, its own port and its
 * own fresh scenario state, so no test can observe another's writes.
 *
 * ## Why every test builds a fresh [BrowserContext] and logs in again
 * A kotgent session cookie is `HMAC-SHA256(master-token, "v1|" + issuedAt)` and cookies are **not scoped
 * by port**. A saved storage state from one harness is therefore sent to the NEXT harness on the same
 * `127.0.0.1`, where it fails the HMAC against that process's own token; the SPA's first-load `401` then
 * does `location.replace("/auth")` and the test reads as a flaky login rather than as a reused credential.
 * There is deliberately no storage-state cache here — logging in costs one page load and removes the whole
 * class of failure.
 */

/** The scenario name every smoke assertion uses; the harness's own scenario map is the authority. */
const val SESSIONS_SCENARIO: String = "sessions"

/**
 * The login page's path. Spelled here rather than imported: `AUTH_PAGE_PATH` is a constant of the NATIVE
 * root module, which this JVM module cannot see. If the two ever diverge, every test in this module fails
 * on the sign-in form, which is loud enough.
 */
const val AUTH_PAGE_PATH: String = "/auth"

/** Set this environment variable to any value to watch the browser instead of running it headless. */
const val HEADED_ENV: String = "KOTGENT_WEBUITEST_HEADED"

/**
 * One running `webuicheck` process, its handshake already read.
 *
 * The constructor spawns the binary, reads the three handshake lines with a deadline, and throws — loudly,
 * with the child's stderr attached — if anything about that is wrong. [close] closes the harness's stdin,
 * which is its documented graceful-shutdown signal, waits for the exit, and **asserts it was `0`**: the
 * harness answers malformed input with a non-zero exit precisely so a fixture cannot swallow it.
 *
 * Use it from a `use {}` block. Kotlin's `use` attaches a close failure as a suppressed exception when the
 * body already failed, so asserting the exit code here cannot mask the real assertion failure.
 */
class Harness(scenario: String) : AutoCloseable {
    private val process: Process
    private val stdin: BufferedWriter
    private val stdoutLines = LinkedBlockingQueue<String>()
    private val stdoutClosed = AtomicBoolean(false)
    private val stderrBuffer = StringBuilder()
    private val readers: List<Thread>

    /** The loopback port the harness bound, straight out of its `PORT=` line. */
    val port: Int

    /** The single unspent sign-in code the harness minted, straight out of its `TICKET=` line. */
    val ticket: String

    /** `http://127.0.0.1:<port>` — no trailing slash, so `baseUrl + "/auth"` reads correctly. */
    val baseUrl: String

    init {
        configurePlaywrightDefaults()
        val binary = harnessBinary()
        val webUiDir = repoRoot.resolve(WEB_UI_RELATIVE).toAbsolutePath().normalize()
        if (!Files.isDirectory(webUiDir)) {
            fail("the Web UI directory is missing: $webUiDir")
        }
        // `--webui-dir` is absolute on purpose: this module's working directory is not guaranteed to be
        // the checkout root, and the harness must not have to guess where the SPA lives.
        process = ProcessBuilder(
            binary.toString(),
            "--scenario=$scenario",
            "--webui-dir=$webUiDir",
            // Belt for the case the JVM dies without running `close()`: a harness whose driver vanished
            // still owns a bound port and, in the terminal scenarios, a pty child. The watchdog only ever
            // fires on a hung run, so it costs a healthy test nothing.
            "--exit-after-ms=$WATCHDOG_MILLIS",
        )
            .directory(repoRoot.toFile())
            // Never merge the streams. The handshake contract is "exactly three lines on stdout and
            // nothing else ever"; folding stderr in would make the harness's own logging unparseable.
            .redirectErrorStream(false)
            .start()
        stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        // Both pipes are drained continuously by their own daemon threads. A child that fills a pipe
        // buffer nobody reads BLOCKS in `write(2)`, so an undrained stderr would deadlock the harness
        // somewhere past the handshake and present as an inexplicable timeout.
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

        // A handshake that fails leaves a process nobody will ever close (the constructor throws, so no
        // `use {}` block exists yet), holding a bound port for the whole watchdog. Kill it first.
        val handshake = try {
            readHandshake()
        } catch (t: Throwable) {
            runCatching { process.destroyForcibly() }
            throw t
        }
        port = handshake.first
        ticket = handshake.second
        baseUrl = "http://127.0.0.1:$port"
    }

    /** The three handshake lines, validated: `PORT=<n>`, `TICKET=<code>`, `READY`, in that order. */
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

    /**
     * Write one command line to the harness's stdin.
     *
     * `restart` is the one command with an observable answer — the harness re-listens on the same port and
     * prints a second `READY` — so this call **waits for that line** before returning. Returning earlier
     * would hand the test a window in which the server is down and every assertion is a coin flip.
     */
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
    }

    override fun close() {
        // Closing stdin is the graceful-shutdown signal, not a kill: the harness sees EOF, stops the
        // server, and exits 0. Anything else is a harness defect and this fixture says so.
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

    /** Everything the child has said on stderr so far — the only diagnosis a failing harness offers. */
    private fun stderrSoFar(): String = synchronized(stderrBuffer) { stderrBuffer.toString() }

    /** Fail the test, naming the harness state and quoting its stderr. Never returns. */
    private fun harnessFailure(message: String): Nothing {
        val status = if (process.isAlive) "still running" else "exited with ${process.exitValue()}"
        val stderr = stderrSoFar().ifBlank { "(nothing on stderr)" }
        fail("$message\n  harness: $status\n  harness stderr:\n$stderr")
    }

    /**
     * The next line the harness printed on stdout, or a failure. Polls rather than blocking forever so a
     * harness that died mid-handshake is reported as a death rather than as a timeout.
     */
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

    /** Latch "stdout reached EOF" once its reader thread finishes, so a waiter can stop waiting. */
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

/**
 * Sign this context in by typing [ticket] into the REAL `/auth` form, exactly as an operator does.
 *
 * The form, not the `#ticket=` link: the typed code is the path an installed PWA takes (its cookie jar is
 * empty and it launches at `start_url`, so there is no fragment to hand it), and it is the path that
 * exercises the page's own script. The exchange sets the session cookie ON THIS CONTEXT, so every page
 * opened from it afterwards is signed in; the throwaway page used to submit the form is closed again.
 *
 * The success condition is the SPA shell replacing the login page at `/` — `#app` is `index.html`'s root
 * element, which the login page (a self-contained string constant served from Kotlin) does not have.
 */
fun BrowserContext.loginWithTicket(ticket: String, baseUrl: String) {
    val root = baseUrl.trimEnd('/')
    val page = newPage()
    try {
        page.navigate(root + AUTH_PAGE_PATH)
        assertThat(page.locator("#code-form")).isVisible()
        page.locator("#code").fill(ticket)
        page.locator("#code-submit").click()
        // The page's script does `location.replace("/")` on a successful exchange, and the SPA does not
        // rewrite that path on load (a session route is only pushed by a selection), so this is exact.
        page.waitForURL("$root/")
        assertThat(page.locator("#app")).hasCount(1)
    } finally {
        page.close()
    }
}

/**
 * A Chromium suitable for the touch tests.
 *
 * **Touch is a CONTEXT property in Playwright, not a browser one**, so this launcher cannot supply it on
 * its own: a caller that wants taps, `pointerType === "touch"` or the `@media (any-pointer: coarse)` ink
 * must create its contexts with `hasTouch` set — [touchContext] is that one-liner. What the browser-level
 * flag here adds is the touch-capable device profile underneath the emulation, so the coarse-pointer
 * queries the dialog grabber and the palette's 44px × depend on resolve the way a phone resolves them.
 *
 * Chromium and not WebKit, measured: Chromium with `hasTouch` delivers the full
 * `pointerdown → touchstart → pointerup → touchend → click` chain with **one `pointerId` across down, up
 * and click** — which is exactly the invariant kotgent's light-dismiss gesture is built on. WebKit
 * delivered the element nothing at all for the same `touchscreen().tap()`. Synthetic
 * `dispatchEvent(new PointerEvent(...))` works in both and proves nothing: it tests our listeners against
 * invented events, not `touch-action`, not the compatibility mouse burst, not pointer capture.
 */
fun touchChromium(pw: Playwright): Browser =
    pw.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(System.getenv(HEADED_ENV) == null)
            .setArgs(listOf("--touch-events=enabled")),
    )

/**
 * A phone-shaped context with touch on, for the gesture tests. Defaults describe a modern iPhone-class
 * viewport; pass a wider one for the tablet cases (the grabber's `any-pointer: coarse` ink is scoped by
 * pointer accuracy, not by width, so a tablet must show it too).
 */
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

/**
 * Where a failing browser test must leave its evidence.
 *
 * CI uploads `test-results/` and `webuitest/test-results/` and nothing else, and only on `failure()`. A
 * screenshot or trace written anywhere else is written for nobody: the runner is gone by the time anyone
 * reads the log.
 */
fun testResultsDir(): Path = repoRoot.resolve(TEST_RESULTS_RELATIVE).also { Files.createDirectories(it) }

/**
 * Run [block] with a Playwright trace recording, and keep the trace **only if it fails** — together with a
 * screenshot of the context's last page. A passing run discards its trace, so the artifact directory holds
 * exactly the runs somebody needs to look at.
 */
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

/** Task-output paths of the harness binary, in the order `./kotlin build` is likely to have produced them. */
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

/** Generous enough for a cold macOS runner linking nothing but starting a Ktor server. */
private const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
private const val RESTART_TIMEOUT_MILLIS = 30_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 15_000L
private const val KILL_GRACE_MILLIS = 5_000L
private const val READER_JOIN_MILLIS = 2_000L
private const val POLL_MILLIS = 100L
private const val MAX_STDERR_CHARS = 64_000
private const val WATCHDOG_MILLIS = 300_000L

/** Playwright's own default assertion timeout is 5s; a cold first paint on a loaded CI runner can beat it. */
private const val ASSERTION_TIMEOUT_MILLIS = 15_000.0

private val playwrightConfigured = AtomicBoolean(false)

private fun configurePlaywrightDefaults() {
    if (playwrightConfigured.compareAndSet(false, true)) {
        PlaywrightAssertions.setDefaultAssertionTimeout(ASSERTION_TIMEOUT_MILLIS)
    }
}

/**
 * The checkout root, found by walking up from the JVM's working directory (and, as a fallback, from this
 * class's own code source — the toolchain does not promise which directory a JVM test module runs in).
 * The marker is a pair, not `project.yaml` alone, so a stray manifest cannot be mistaken for the root.
 */
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

/**
 * The harness binary, or a loud failure naming the command that builds it.
 *
 * Never a skip: this suite has zero skips by policy, and a "skipped because the binary was missing" browser
 * tier is a green run that tested nothing at all. `./kotlin test` does not link main binaries, so the
 * message has to say what `./kotlin build` is for — the same trap `PtyTest` names for `ptycheck`.
 */
private fun harnessBinary(): Path {
    val candidates = HARNESS_BINARIES.map { repoRoot.resolve(it) }
    return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: fail(
            "the webuicheck binary is missing (looked for ${candidates.joinToString()}). " +
                "It is built by `./kotlin build`, which `./kotlin test` does not do on its own — " +
                "run `./kotlin build` first.",
        )
}
