package io.kotgent.cli

import io.kotgent.pty.NativeTty
import io.kotgent.transport.API_PREFIX
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

data class WinSize(val cols: Int, val rows: Int)

/**
 * A controlling terminal whose prior settings are saved by [enterRaw] and restored by [restore].
 */
interface LocalTty {
    /** Fails rather than continuing in canonical, echoing mode. */
    fun enterRaw()

    /** Best-effort so teardown cannot mask the original failure. */
    fun restore()

    fun windowSize(): WinSize
}

/** Restores the tty even when [body] fails. */
inline fun <T> LocalTty.withRawMode(body: () -> T): T {
    enterRaw()
    try {
        return body()
    } finally {
        restore()
    }
}

class PosixTty(private val fd: Int = STDIN_FILENO) : LocalTty {
    override fun enterRaw() {
        if (!NativeTty.enterRaw(fd)) error("failed to enter raw terminal mode on fd $fd")
    }

    override fun restore() {
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

/**
 * Builds the prefixed terminal WebSocket URL. Authentication stays in the handshake header; valid [size]
 * is sent in the query so the upstream tmux client starts at the right geometry without an initial reflow.
 */
fun terminalWsUrl(baseUrl: String, sessionId: String, size: WinSize? = null): String {
    val origin = baseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/')
    val query = size?.takeIf { it.cols > 0 && it.rows > 0 }?.let { "?cols=${it.cols}&rows=${it.rows}" } ?: ""
    return "$origin$API_PREFIX/sessions/$sessionId/terminal$query"
}

/**
 * Resets private terminal modes that termios restoration cannot see. Mouse trackers must be disabled
 * before SGR encoding; reversing the order makes active trackers emit legacy X10 bytes into the shell.
 * Application keypad mode is reset, while DECCKM remains because shells accept both cursor encodings.
 */
const val TERMINAL_MODE_RESET: String =
    "\u001b[?1003l\u001b[?1002l\u001b[?1000l\u001b[?1006l" +
        "\u001b[?2004l\u001b[?2031l\u001b[?1049l\u001b[?25h\u001b>"

fun resizeFrame(cols: Int, rows: Int): String = """{"type":"resize","cols":$cols,"rows":$rows}"""

/**
 * Raw terminal bridge to a session WebSocket. SIGWINCH only flips a flag because signal handlers cannot
 * perform suspending I/O; the coroutine polls it and sends the resize.
 */
class AttachClient(
    private val baseUrl: String,
    private val sessionId: String,
    private val token: String,
    private val tty: LocalTty = PosixTty(),
    private val clientFactory: () -> HttpClient = { HttpClient(CIO) { install(WebSockets) } },
) {
    val wsUrl: String get() = terminalWsUrl(baseUrl, sessionId, tty.windowSize())

    @OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    suspend fun run() {
        val client = clientFactory()
        val stdinCtx = newSingleThreadContext("kotgent-attach-stdin")
        tty.enterRaw()
        try {
            // Limit only the handshake: an orphan can hold the listening socket without answering, while
            // an HttpTimeout request limit would also terminate a healthy long-lived WebSocket.
            val session = try {
                withTimeout(HANDSHAKE_TIMEOUT_MS) {
                    client.webSocketSession(wsUrl) { header(HttpHeaders.Authorization, "Bearer $token") }
                }
            } catch (e: TimeoutCancellationException) {
                throw AttachTimeoutException(baseUrl, HANDSHAKE_TIMEOUT_MS, e)
            }
            try {
                with(session) {
                    sendResize(tty.windowSize())
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
                    val stdinPump = launch(stdinCtx) { pumpStdinToWs() }
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
            // Reset remote modes while stdout still belongs to this raw-terminal session.
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
                if (n <= 0) break
                send(Frame.Binary(fin = true, data = buf.readBytes(n.toInt())))
            }
        }
    }

    companion object {
        private const val SIGWINCH_POLL_MS: Long = 150

        const val HANDSHAKE_TIMEOUT_MS: Long = 5_000
    }
}

class AttachTimeoutException(baseUrl: String, timeoutMs: Long, cause: Throwable?) : RuntimeException(
    "no answer from the kotgent daemon at $baseUrl within ${timeoutMs}ms — " +
        "the port may be held by a stale process (check: lsof -nP -iTCP -sTCP:LISTEN)",
    cause,
)

/** The non-capturing SIGWINCH handler can only perform the async-signal-safe flag update. */
@Volatile
private var winchPending: Boolean = false

@OptIn(ExperimentalForeignApi::class)
private fun installSigwinch() {
    signal(SIGWINCH, staticCFunction<Int, Unit> { winchPending = true })
}

/** Loops over partial writes so terminal output is not silently truncated. */
@OptIn(ExperimentalForeignApi::class)
private fun writeStdout(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val n = write(STDOUT_FILENO, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
            if (n <= 0) break
            offset += n
        }
    }
}
