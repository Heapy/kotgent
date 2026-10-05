import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class CioReproTest {
    @Test
    fun defaultConnections() = exercise(close = false)

    @Test
    fun explicitClose() = exercise(close = true)

    @OptIn(ExperimentalNativeApi::class)
    private fun exercise(close: Boolean) = runBlocking {
        println("Kotlin/Native processors: ${Platform.getAvailableProcessors()}")
        withTimeout(120_000) {
            repeat(1_000) { instance ->
                val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
                    routing {
                        get("/") { call.respondText("x".repeat(514)) }
                        post("/") {
                            call.receiveText()
                            call.respondText("x".repeat(514))
                        }
                    }
                }.start(wait = false)
                val port = server.engine.resolvedConnectors().first().port
                val client = HttpClient(ClientCIO) {
                    if (close) defaultRequest { header(HttpHeaders.Connection, "close") }
                }
                try {
                    repeat(6) { request ->
                        val url = "http://127.0.0.1:$port/"
                        val response = if (request == 3 || request == 4) client.get(url) else {
                            client.post(url) { setBody("instance-$instance-request-$request") }
                        }
                        assertEquals("x".repeat(514), response.bodyAsText())
                    }
                } finally {
                    client.close()
                    server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
                }
            }
        }
    }
}
