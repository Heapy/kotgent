package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the Ktor CIO server + WebSockets spike (Task 3). These prove the
 * Ktor CIO **server** engine, the WebSockets plugin, and a Ktor CIO **client** all work on
 * the macosArm64 native target — the transport foundation for the future control REST,
 * events WS and (binary) terminal WS channels.
 *
 * Every network interaction is wrapped in a bounded [withTimeout] so a broken round-trip
 * fails fast instead of hanging the suite (anti-flaky requirement, mirrors PtyTest).
 *
 * Unlike the PTY cinterop tests (Task 2), these are NOT @Ignore'd: Ktor is a third-party
 * dependency klib, and this suite doubles as a probe for KT-78062 — if Ktor's (or a
 * transitive dependency's) internal cinterop failed to link into the TEST binary we would
 * see an IrLinkageError / unresolved-symbol here at link or runtime. Green tests confirm
 * KT-78062 affects only our own raw cinterop, not dependency klibs.
 */
class WsSpikeTest {

    /**
     * Boots a fresh [SpikeServer] on an ephemeral port, hands the caller its bound port and a
     * CIO client (WebSockets installed), and guarantees teardown of both. The whole exchange
     * (start + bind + request) runs under a single [withTimeout] so nothing can hang.
     */
    private fun withSpikeServer(
        block: suspend (port: Int, client: HttpClient, server: SpikeServer) -> Unit,
    ) = runBlocking {
        val server = SpikeServer()
        try {
            withTimeout(20_000) {
                server.start()
                val port = server.port()
                val client = HttpClient(CIO) { install(WebSockets) }
                try {
                    block(port, client, server)
                } finally {
                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }

    /** Receive frames until a data (TEXT/BINARY) frame arrives, skipping any ping/pong/close. */
    private suspend fun DefaultClientWebSocketSession.receiveDataFrame(): Frame {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text || frame is Frame.Binary) return frame
        }
    }

    @Test
    fun httpGetRoundTrip() = withSpikeServer { port, client, _ ->
        val body = client.get("http://127.0.0.1:$port/hello").bodyAsText()
        assertEquals("hello-kotgent", body, "GET /hello should echo the greeting")
    }

    @Test
    fun webSocketEchoesTextFrame() = withSpikeServer { port, client, _ ->
        client.webSocket(host = "127.0.0.1", port = port, path = "/echo") {
            send(Frame.Text("ping-kotgent"))
            val reply = receiveDataFrame()
            assertTrue(reply is Frame.Text, "expected a TEXT frame back, got ${reply.frameType}")
            assertEquals("ping-kotgent", reply.readText(), "WS should echo the text payload")
        }
    }

    @Test
    fun webSocketEchoesBinaryFrame() = withSpikeServer { port, client, _ ->
        // BINARY is required for the future terminal channel (raw pty bytes fan-out), so it
        // gets its own round-trip covering non-ASCII / signed bytes.
        val payload = byteArrayOf(0, 1, 2, 3, 42, 127, -1, -128)
        client.webSocket(host = "127.0.0.1", port = port, path = "/echo") {
            send(Frame.Binary(fin = true, data = payload))
            val reply = receiveDataFrame()
            assertTrue(reply is Frame.Binary, "expected a BINARY frame back, got ${reply.frameType}")
            assertContentEquals(payload, reply.readBytes(), "WS should echo the binary payload byte-for-byte")
        }
    }

    @Test
    fun servesStaticFile() = withSpikeServer { port, client, server ->
        val body = client.get("http://127.0.0.1:$port/static").bodyAsText()
        assertEquals(server.staticFileContent, body, "GET /static should return the on-disk file's contents")
    }
}
