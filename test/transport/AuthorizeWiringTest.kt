package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizeWiringTest {

    private val token = "wiring-master-token-0123456789abcdef"
    private val publicUrl = "https://kotgent.example.com"

    private fun cookie(masterToken: String = token): String = issueSessionCookie(masterToken, issuedAt = 1_700_000_000_000)


    @Test
    fun aBearerIsServedOnAnEphemeralPortAndOnEveryMethod() = withGatedServer { port, client ->
        assertEquals(HttpStatusCode.OK, client.probe(port, bearer = token).status, "GET with a Bearer")
        assertEquals(
            HttpStatusCode.OK,
            client.probe(port, method = HttpMethod.Post, bearer = token).status,
            "a Bearer needs no Origin even on a POST — it is not a browser",
        )
    }

    @Test
    fun noCredentialIs401AndAWrongOneIs401Too() = withGatedServer { port, client ->
        assertEquals(HttpStatusCode.Unauthorized, client.probe(port).status, "nothing presented")
        assertEquals(HttpStatusCode.Unauthorized, client.probe(port, bearer = "not-the-token").status, "wrong Bearer")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.probe(port, cookie = cookie("some-other-master-token")).status,
            "a cookie signed under another master token",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.probe(port, cookie = "v1.1700000000000.deadbeef").status,
            "a forged MAC",
        )
    }


    @Test
    fun aGetWithAValidCookieAndNoOriginIsServed() = withGatedServer { port, client ->
        assertEquals(HttpStatusCode.OK, client.probe(port, cookie = cookie()).status)
    }

    @Test
    fun aGetWithAValidCookieAndAForeignOriginIs403() = withGatedServer { port, client ->
        assertEquals(
            HttpStatusCode.Forbidden,
            client.probe(port, cookie = cookie(), origin = "https://evil.example.com").status,
            "an Origin that is present must match, on reads as well",
        )
    }

    @Test
    fun aPostWithACookieAndNoOriginIs403ButWithALoopbackOriginItIsServed() = withGatedServer { port, client ->
        assertEquals(
            HttpStatusCode.Forbidden,
            client.probe(port, method = HttpMethod.Post, cookie = cookie()).status,
            "a state-changing request authenticated by cookie must carry an Origin",
        )
        assertEquals(
            HttpStatusCode.OK,
            client.probe(port, method = HttpMethod.Post, cookie = cookie(), origin = "http://127.0.0.1:$port").status,
            "the UI's own origin is allowed on any port",
        )
    }

    @Test
    fun aWebSocketHandshakeWithACookieAndNoOriginIs403() = withGatedServer { port, client ->
        assertEquals(
            HttpStatusCode.Forbidden,
            client.probe(port, cookie = cookie(), webSocketKey = "dGhlIHNhbXBsZSBub25jZQ==").status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.probe(
                port,
                cookie = cookie(),
                origin = "http://127.0.0.1:$port",
                webSocketKey = "dGhlIHNhbXBsZSBub25jZQ==",
            ).status,
            "the same handshake with the UI's own Origin is fine",
        )
    }

    @Test
    fun rotatingTheMasterTokenInvalidatesAnAlreadyIssuedCookie() {
        val holder = TokenHolder(token)
        withGatedServer(tokenProvider = holder::current) { port, client ->
            val issued = cookie()
            assertEquals(HttpStatusCode.OK, client.probe(port, cookie = issued).status, "the cookie works")

            holder.rotate(token)

            assertEquals(HttpStatusCode.Unauthorized, client.probe(port, cookie = issued).status)
        }
    }


    @Test
    fun aForeignHostIs403EvenWithAValidBearer() = withGatedServer { port, client ->
        assertEquals(
            HttpStatusCode.Forbidden,
            client.probe(port, bearer = token, host = "kotgent.example.com").status,
            "no public URL is configured, so nothing but loopback is served",
        )
    }

    @Test
    fun aConfiguredPublicHostIsServedAndItsSiblingsAreNot() =
        withGatedServer(publicUrl = publicUrl) { port, client ->
            assertEquals(
                HttpStatusCode.OK,
                client.probe(port, cookie = cookie(), host = "kotgent.example.com").status,
                "the configured public host is in the allowlist",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.probe(
                    port,
                    method = HttpMethod.Post,
                    cookie = cookie(),
                    host = "kotgent.example.com",
                    origin = publicUrl,
                ).status,
                "and so is its own origin on a POST",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.probe(
                    port,
                    method = HttpMethod.Post,
                    cookie = cookie(),
                    host = "kotgent.example.com",
                    origin = "https://qa.example.com",
                ).status,
                "a same-SITE sibling origin is still a foreign ORIGIN",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.probe(port, cookie = cookie(), host = "qa.example.com").status,
                "a host that is neither loopback nor the configured public one is refused",
            )
        }


    @Test
    fun aLoopbackOnlyRouteIsServedLocallyAndRefusedFromTheTunnel() =
        withGatedServer(publicUrl = publicUrl) { port, client ->
            assertEquals(HttpStatusCode.OK, client.probe(port, path = "/local").status, "local callers are served")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.probe(port, path = "/local", host = "kotgent.example.com").status,
            )
        }


    @Test
    fun secureIsDecidedByTheHostThatAnsweredNotByAForwardedHeader() {
        assertTrue(requiresSecureCookie("kotgent.example.com", publicUrl), "the public host is https")
        assertTrue(requiresSecureCookie("KOTGENT.EXAMPLE.COM:443", publicUrl), "case and port do not matter")
        assertFalse(requiresSecureCookie("127.0.0.1:27508", publicUrl), "loopback is plain http")
        assertFalse(requiresSecureCookie("127.0.0.1:27508", null), "no public URL at all")
        assertFalse(requiresSecureCookie("127.0.0.1", "http://127.0.0.1:27508"), "an http public URL is not secure")
        assertFalse(requiresSecureCookie(null, publicUrl), "a request without a Host gets nothing")
        assertFalse(requiresSecureCookie("qa.example.com", publicUrl), "another host is not the public one")
    }


    private fun withGatedServer(
        publicUrl: String? = null,
        tokenProvider: () -> String = { token },
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                authenticated(tokenProvider, publicUrl) {
                    get("/ping") { call.respondText("pong") }
                    post("/ping") { call.respondText("posted") }
                }
                loopbackOnly {
                    get("/local") { call.respondText("local") }
                }
            }
        }
        try {
            withTimeout(20_000) {
                server.start(wait = false)
                val port = server.engine.resolvedConnectors().first().port
                val client = HttpClient(CIO)
                try {
                    block(port, client)
                } finally {
                    client.close()
                }
            }
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    private suspend fun HttpClient.probe(
        port: Int,
        path: String = "/ping",
        method: HttpMethod = HttpMethod.Get,
        bearer: String? = null,
        cookie: String? = null,
        origin: String? = null,
        host: String? = null,
        webSocketKey: String? = null,
    ): HttpResponse = request("http://127.0.0.1:$port$path") {
        this.method = method
        applyIfPresent(HttpHeaders.Authorization, bearer?.let { "Bearer $it" })
        applyIfPresent(HttpHeaders.Cookie, cookie?.let { "$SESSION_COOKIE_NAME=$it" })
        applyIfPresent(HttpHeaders.Origin, origin)
        applyIfPresent(HttpHeaders.Host, host)
        applyIfPresent(HttpHeaders.SecWebSocketKey, webSocketKey)
    }

    private fun HttpRequestBuilder.applyIfPresent(name: String, value: String?) {
        if (value != null) header(name, value)
    }
}
