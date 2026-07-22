package io.kotgent.transport

import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid

/**
 * Task 3 spike: proves the Ktor CIO **server** engine + **WebSockets** plugin run on the
 * macosArm64 native target, exercised end-to-end by [WsSpikeTest] with a Ktor CIO client.
 *
 * It stands up an [embeddedServer] with:
 *  - `GET /hello`   — a plain HTTP round-trip.
 *  - `WS  /echo`    — echoes TEXT and BINARY frames (binary is needed by the future
 *                     terminal fan-out channel).
 *  - `GET /static`  — serves a real on-disk file (written at [start]), read back via posix
 *                     file I/O since Ktor's JVM `staticFiles`/`staticResources` helpers are
 *                     not available on native. This mirrors the eventual Web-UI static serving.
 *
 * The server binds to an ephemeral port (`port = 0`); [port] reports the actual bound port
 * once the connector is resolved. This is the transport foundation for the real Server in
 * Task 14 — kept intentionally minimal and dependency-light.
 */
class SpikeServer(private val host: String = "127.0.0.1") {

    /** Contents written to (and served from) the static file — asserted by the test. */
    val staticFileContent: String = "kotgent-static-spike\nline-two\n"

    // Per-process unique path so parallel/leftover runs don't collide. Overwritten on start().
    @OptIn(ExperimentalForeignApi::class)
    private val staticFilePath: String =
        "${(getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')}/kotgent-ws-spike-static-${getpid()}.txt"

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0, host = host) {
        install(WebSockets)
        routing {
            get("/hello") {
                call.respondText("hello-kotgent")
            }
            webSocket("/echo") {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> send(Frame.Text(frame.readText()))
                        is Frame.Binary -> send(Frame.Binary(fin = true, data = frame.data))
                        else -> { /* ignore ping/pong/close */ }
                    }
                }
            }
            get("/static") {
                call.respondBytes(readFileBytes(staticFilePath), ContentType.Text.Plain)
            }
        }
    }

    /** Writes the static file and starts the engine without blocking. */
    fun start(): SpikeServer {
        writeFileBytes(staticFilePath, staticFileContent.encodeToByteArray())
        server.start(wait = false)
        return this
    }

    /** The actual OS-assigned port (resolves the ephemeral `port = 0` binding). */
    suspend fun port(): Int = server.engine.resolvedConnectors().first().port

    fun stop() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFileBytes(path: String, bytes: ByteArray) {
    val fp = fopen(path, "wb") ?: error("SpikeServer: cannot open $path for write (errno=$errno)")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), fp)
            }
        }
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytes(path: String): ByteArray {
    val fp = fopen(path, "rb") ?: error("SpikeServer: cannot open $path for read (errno=$errno)")
    try {
        fseek(fp, 0, SEEK_END)
        val size = ftell(fp)
        fseek(fp, 0, SEEK_SET)
        if (size <= 0L) return ByteArray(0)
        val buffer = ByteArray(size.toInt())
        buffer.usePinned { pinned ->
            fread(pinned.addressOf(0), 1.convert(), size.convert(), fp)
        }
        return buffer
    } finally {
        fclose(fp)
    }
}
