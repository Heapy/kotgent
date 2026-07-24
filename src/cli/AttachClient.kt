package io.kotgent.cli

import io.kotgent.pty.NativeTty
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeout
import platform.posix.SIGWINCH
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.read
import platform.posix.signal
import platform.posix.write
import kotlin.concurrent.Volatile

/** The local terminal size, `cols` x `rows`. */
data class WinSize(val cols: Int, val rows: Int)

/**
 * The local controlling terminal, behind an interface so [AttachClient]'s raw-mode lifecycle is unit
 * testable with a pure fake (the production [PosixTty] touches the real tty via `sysnative` cinterop,
 * which is not linked into test binaries — KT-78062). Tests inject a fake and assert save/restore.
 */
interface LocalTty {
    /** Switch the tty to raw mode, saving the prior settings for [restore]. THROWS if it cannot — attach
     *  must abort rather than run in canonical (line-buffered/echoing) mode. */
    fun enterRaw()

    /** Restore the tty to the settings saved by [enterRaw]. Must be safe to call on any exit path;
     *  best-effort (warns rather than throws) so it never masks a real error during teardown. */
    fun restore()

    /** The current window size (for the initial + SIGWINCH resize frames). */
    fun windowSize(): WinSize
}

/**
 * Run [body] with the tty in raw mode, ALWAYS restoring it afterwards — even if [body] throws. This is
 * the crux of a well-behaved `attach`: a crash or Ctrl-C mid-session must not leave the user's terminal
 * stuck in raw mode. Pure orchestration over [LocalTty], so it is exercised directly in tests.
 */
inline fun <T> LocalTty.withRawMode(body: () -> T): T {
    enterRaw()
    try {
        return body()
    } finally {
        restore()
    }
}

/** The production [LocalTty]: the real controlling terminal via the `sysnative` [NativeTty] cinterop. */
class PosixTty(private val fd: Int = STDIN_FILENO) : LocalTty {
    override fun enterRaw() {
        // Abort rather than silently run in canonical mode: NativeTty returns false on tcsetattr/tcgetattr
        // failure, and running the passthrough without raw mode (line-buffered, echoing) is broken.
        if (!NativeTty.enterRaw(fd)) error("failed to enter raw terminal mode on fd $fd")
    }

    override fun restore() {
        // Best-effort on exit: warn but never throw (restore runs in a finally on every exit path, so a
        // throw here would mask the real error). A false result means the tty may be left in raw mode.
        if (!NativeTty.restore(fd)) {
            eprintln("warning: failed to restore terminal mode on fd $fd (run `reset` if your terminal misbehaves)")
        }
    }

    override fun windowSize(): WinSize {
        val (cols, rows) = NativeTty.windowSize(fd) ?: (DEFAULT_COLS to DEFAULT_ROWS)
        return WinSize(cols, rows)
    }

    companion object {
        const val DEFAULT_COLS: Int = 80
        const val DEFAULT_ROWS: Int = 24
    }
}

/** Build the terminal WebSocket URL for [sessionId] on [baseUrl] (an `http(s)://…` origin). The token is
 *  no longer carried in the URL — [AttachClient] presents it as an `Authorization: Bearer` handshake header
 *  (a native client, unlike a browser, can set headers on a WebSocket handshake).
 *
 *  [size], when known, rides along as `?cols=&rows=`: the daemon then opens the upstream `tmux attach`
 *  at our geometry, so the first bytes are already the right shape instead of the pty default reflowing
 *  to ours once the initial resize frame lands. A non-positive dimension is omitted. */
fun terminalWsUrl(baseUrl: String, sessionId: String, size: WinSize? = null): String {
    val origin = baseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/')
    val query = size?.takeIf { it.cols > 0 && it.rows > 0 }?.let { "?cols=${it.cols}&rows=${it.rows}" } ?: ""
    return "$origin/sessions/$sessionId/terminal$query"
}

/**
 * The terminal modes `attach` must switch back off on exit, in one write: the three mouse **trackers**
 * (any-motion / button / normal), then the SGR mouse **encoding**, then bracketed paste, theme-change
 * reporting, the alternate screen, cursor visibility, and application-keypad mode.
 *
 * [LocalTty.restore] only restores **termios** — it knows nothing about the private DEC modes the
 * remote side turned on. Those arrive as ordinary bytes in the stream: the upstream `tmux attach`
 * (and any TUI running inside the pane) emits e.g. `\e[?1049h` / `\e[?1006h` and they are broadcast
 * straight to this client's stdout. The matching disable sequences are written into the *upstream*
 * pty AFTER the last subscriber has gone (`TerminalBridge` closes the upstream on 1→0), so they can
 * never reach the operator's terminal. Without this reset the terminal is left on the alternate
 * screen or emitting `\e[<…M` on every click until the user runs `reset`.
 *
 * ## What the remote side actually turns on (measured, tmux 3.7b, real pty attach)
 * A `tmux attach` client emits at startup, unconditionally: `\e[?1049h` (alternate screen),
 * `\e[?2004h` (bracketed paste), `\e[?2031h` (theme-change reporting), `\e[?25h`, and terminfo's
 * `smkx` (`\e[?1h\e=`, application cursor keys + application keypad). Because kotgent forces
 * `mouse on`, it also emits `\e[?1006h\e[?1000h\e[?1002h`. When the pane's own app enables
 * **any-motion** tracking (`\e[?1003h`, exactly what crossterm/ratatui's `EnableMouseCapture` sends,
 * i.e. the codex TUI), tmux propagates it and the client emits `\e[?1003h` as well — measured with
 * the `mouse` option both on and off.
 *
 * ## Order is load-bearing: every tracker off BEFORE the encoding
 * `1000` / `1002` / `1003` are independent flags on terminals that model them separately (alacritty,
 * wezterm, foot). Switching `1006` off first would leave a still-active tracker reporting in the
 * **legacy X10 encoding** — raw bytes above 127 injected into the operator's shell on every mouse
 * move, strictly worse than no reset at all. So all three trackers go off first and the SGR encoding
 * last.
 *
 * The two `smkx` halves are treated deliberately rather than conflated. DECCKM `\e[?1h` (application
 * cursor keys) remains untouched — both `\eOA` and `\e[A` are bound in every shell's readline. Its
 * application-keypad half `\e=` is undone with `\e>`: leaving it set makes numeric keypad keys keep
 * emitting SS3 sequences after kotgent exits. Cursor blink (`\e[?12`, cosmetic) also remains untouched.
 * The pinning test therefore names the modes this constant covers instead of claiming completeness.
 */
const val TERMINAL_MODE_RESET: String =
    "\u001b[?1003l\u001b[?1002l\u001b[?1000l\u001b[?1006l" +
        "\u001b[?2004l\u001b[?2031l\u001b[?1049l\u001b[?25h\u001b>"

/**
 * Encode a terminal resize as the text control frame the server's terminal WS expects (see
 * [io.kotgent.transport.terminalWs]): `{"type":"resize","cols":C,"rows":R}`. Kept as a tiny pure
 * function so the exact wire shape is unit-testable without a live socket.
 */
fun resizeFrame(cols: Int, rows: Int): String = """{"type":"resize","cols":$cols,"rows":$rows}"""

/**
 * Raw-passthrough client for `kotgent attach <id>` (plan Task 15): it bridges the local terminal to a
 * session's terminal fan-out over the binary WebSocket `GET /sessions/{id}/terminal`.
 *
 *  - the local tty is put into **raw mode** on entry and RESTORED on any exit;
 *  - **stdin → WS**: local input is forwarded as binary frames;
 *  - **WS → stdout**: server bytes (the `capture-pane` seed, then live deltas) are written to stdout;
 *  - **SIGWINCH → resize**: a window-size change sets a flag; a poll loop reads the new size and sends
 *    it as a [resizeFrame] text control frame (matching the server's text-resize sub-protocol).
 *
 * This is inherently interactive (a real tty + stdin/stdout), so it has NO automated end-to-end test —
 * only smoke coverage of its pure parts ([terminalWsUrl] / [resizeFrame] / [withRawMode] over a fake
 * tty). Full verification is the Task 18 manual run (noted in the plan's Post-Completion).
 *
 * [decision] SIGWINCH is turned into a resize by a flag-set handler + a 150 ms poll loop rather than
 * doing WS I/O in the signal handler: a K/N signal handler must be a non-capturing static C function
 * and may only do async-signal-safe work, so it just flips an [AtomicInt] the coroutine world polls.
 */
class AttachClient(
    private val baseUrl: String,
    private val sessionId: String,
    private val token: String,
    private val tty: LocalTty = PosixTty(),
    private val clientFactory: () -> HttpClient = { HttpClient(CIO) { install(WebSockets) } },
) {
    /** The WebSocket URL this client connects to (also the unit-tested URL-construction seam). */
    val wsUrl: String get() = terminalWsUrl(baseUrl, sessionId, tty.windowSize())

    /**
     * Connect and pump until the terminal ends (server closes / EOF) or the process is interrupted. The
     * local tty is raw for the duration and restored on return. INTERACTIVE — not run by any test.
     */
    @OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    suspend fun run() {
        val client = clientFactory()
        val stdinCtx = newSingleThreadContext("kotgent-attach-stdin")
        tty.enterRaw()
        try {
            // Handshake under a timeout, the streaming session without one. A dead daemon whose
            // listening socket is still held open by some other process (an orphaned tmux server —
            // see io.kotgent.sys.markOpenFdsCloexec) accepts the TCP connection at the kernel level and
            // then answers nothing, so a plain connect hangs forever instead of failing. The timeout
            // cannot be an HttpTimeout plugin: its requestTimeoutMillis spans the whole WebSocket
            // request, so a finite value would tear down a healthy long-lived attach.
            val session = try {
                withTimeout(HANDSHAKE_TIMEOUT_MS) {
                    client.webSocketSession(wsUrl) { header(HttpHeaders.Authorization, "Bearer $token") }
                }
            } catch (e: TimeoutCancellationException) {
                throw AttachTimeoutException(baseUrl, HANDSHAKE_TIMEOUT_MS, e)
            }
            try {
                with(session) {
                    sendResize(tty.windowSize()) // initial size
                    installSigwinch()
                    val resizeLoop = launch {
                        while (isActive) {
                            delay(SIGWINCH_POLL_MS)
                            if (winchPending) {
                                winchPending = false
                                sendResize(tty.windowSize())
                            }
                        }
                    }
                    // stdin → WS on a dedicated thread doing the blocking read().
                    val stdinPump = launch(stdinCtx) { pumpStdinToWs() }
                    // WS → stdout (this coroutine), until the server closes the stream.
                    for (frame in incoming) {
                        if (frame is Frame.Binary) writeStdout(frame.readBytes())
                    }
                    resizeLoop.cancel()
                    stdinPump.cancel()
                }
            } finally {
                runCatching { session.close() }
            }
        } finally {
            // Order matters: the mode reset is terminal OUTPUT, so it goes out while the tty is still
            // ours, before termios is handed back. See TERMINAL_MODE_RESET for why restore() alone
            // leaves the terminal reporting mouse events / stuck on the alternate screen.
            writeStdout(TERMINAL_MODE_RESET.encodeToByteArray())
            tty.restore()
            client.close()
            stdinCtx.close()
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendResize(size: WinSize) {
        runCatching { send(Frame.Text(resizeFrame(size.cols, size.rows))) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun DefaultClientWebSocketSession.pumpStdinToWs() {
        memScoped {
            val bufSize = 4096
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val n = read(STDIN_FILENO, buf, bufSize.convert())
                if (n <= 0) break // EOF / error on stdin
                send(Frame.Binary(fin = true, data = buf.readBytes(n.toInt())))
            }
        }
    }

    companion object {
        /** How often the resize loop checks the SIGWINCH flag. */
        private const val SIGWINCH_POLL_MS: Long = 150

        /** Budget for the WebSocket handshake (connect + `101`), after which attach fails loudly. */
        const val HANDSHAKE_TIMEOUT_MS: Long = 5_000
    }
}

/** Thrown when the daemon accepts the connection but never completes the terminal WS handshake. */
class AttachTimeoutException(baseUrl: String, timeoutMs: Long, cause: Throwable?) : RuntimeException(
    "no answer from the kotgent daemon at $baseUrl within ${timeoutMs}ms — " +
        "the port may be held by a stale process (check: lsof -nP -iTCP -sTCP:LISTEN)",
    cause,
)

/**
 * SIGWINCH plumbing. The handler must be a non-capturing static C function, so it only flips a global
 * flag; a coroutine in [AttachClient.run] polls the flag and does the actual (suspending) resize send.
 * Setting an atomic int is async-signal-safe.
 */
@Volatile
private var winchPending: Boolean = false

@OptIn(ExperimentalForeignApi::class)
private fun installSigwinch() {
    signal(SIGWINCH, staticCFunction<Int, Unit> { winchPending = true })
}

/**
 * Write [bytes] straight to stdout (raw fd write — no buffering, so terminal output is immediate),
 * looping over partial `write()` counts so a short write does not silently drop terminal output
 * (like [io.kotgent.pty.Pty.write] / the stdin pump).
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeStdout(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val n = write(STDOUT_FILENO, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
            if (n <= 0) break // error / would-block: best-effort, stop rather than spin
            offset += n
        }
    }
}
