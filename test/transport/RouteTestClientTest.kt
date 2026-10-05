package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class RouteTestClientTest {
    @Test
    fun ordinaryHttpConnectionsCloseWithTheirResponse() = withServer { port, client ->
        assertEquals("close", client.get("http://127.0.0.1:$port/connection").bodyAsText())
    }

    @Test
    fun anExplicitKeepAliveRequestStillExercisesTheKeepAlivePath() = withServer { port, client ->
        val response = client.get("http://127.0.0.1:$port/connection") {
            header(HttpHeaders.Connection, "keep-alive")
        }
        assertEquals("keep-alive", response.bodyAsText())
    }

    @Test
    fun aWebSocketUpgradesAndExchangesFramesWithoutTheCloseHeader() = withServer { port, client ->
        client.webSocket("ws://127.0.0.1:$port/socket") {
            val connection = assertIs<Frame.Text>(incoming.receive()).readText()
                .split(',').map { it.trim().lowercase() }
            assertTrue("upgrade" in connection)
            assertFalse("close" in connection)
            send("round trip")
            assertEquals("round trip", assertIs<Frame.Text>(incoming.receive()).readText())
        }
    }

    private fun withServer(block: suspend (Int, HttpClient) -> Unit) = runBlocking {
        withTimeout(30.seconds) {
            val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
                install(ServerWebSockets)
                routing {
                    get("/connection") {
                        call.respondText(call.request.headers[HttpHeaders.Connection].orEmpty())
                    }
                    webSocket("/socket") {
                        send(call.request.headers[HttpHeaders.Connection].orEmpty())
                        for (frame in incoming) send(frame)
                    }
                }
            }.start(wait = false)
            val client = routeTestClient { install(WebSockets) }
            try {
                block(server.engine.resolvedConnectors().first().port, client)
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    }
}
