package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol

private val CloseRouteTestConnections = createClientPlugin("CloseRouteTestConnections") {
    onRequest { request, _ ->
        val protocol = request.url.protocol
        if ((protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS) &&
            !request.headers.contains(HttpHeaders.Connection)
        ) {
            request.headers.append(HttpHeaders.Connection, "close")
        }
    }
}

/**
 * Ordinary route tests finish each HTTP connection with its response to avoid the native CIO
 * premature HTTP disconnect. Explicit Connection headers are preserved;
 * WebSocket handshakes stay on their upgrade path.
 */
internal fun routeTestClient(config: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}): HttpClient =
    HttpClient(CIO) {
        install(CloseRouteTestConnections)
        config()
    }
