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

/**
 * The Ktor wiring of the pure rule (plan Task 7): [authenticated] really does decide through [authorize],
 * and [loopbackOnly] really does keep the local-only surface off the tunnel.
 *
 * [AuthorizationTest] already pins the rule itself as a table; what can only be checked HERE is that the
 * facts reach it intact through a real socket — that the `Host` a request arrived under is the one compared,
 * that the session cookie is read off the wire by Ktor's own cookie API, that a `Sec-WebSocket-Key` marks a
 * handshake before any upgrade happens, and that a refusal is written in the `Plugins` phase so the wrapped
 * handler never runs.
 *
 * Deliberately a bare two-route server rather than the full [KotgentServer]: a failure then points at the
 * gate itself instead of at any of the daemon's fakes.
 */
class AuthorizeWiringTest {

    private val token = "wiring-master-token-0123456789abcdef"
    private val publicUrl = "https://kotgent.example.com"

    /** A cookie minted from [token] — what the Task-8 exchange route will hand a browser. */
    private fun cookie(masterToken: String = token): String = issueSessionCookie(masterToken, issuedAt = 1_700_000_000_000)

    // --- the existing surface keeps working ----------------------------------------------------------

    @Test
    fun aBearerIsServedOnAnEphemeralPortAndOnEveryMethod() = withGatedServer { port, client ->
        // `port = 0` is why the loopback rule ignores the port: the real one only exists after start().
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

    // --- the cookie path ------------------------------------------------------------------------------

    @Test
    fun aGetWithAValidCookieAndNoOriginIsServed() = withGatedServer { port, client ->
        // THE case that would have broken the whole UI if Origin were required on reads: browsers do not
        // send Origin on a same-origin GET, so every page load and every poll arrives exactly like this.
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
        // A handshake is a GET, so it is the `Sec-WebSocket-Key` — never the path — that makes it strict.
        // The route under test is a plain GET: if the gate missed the handshake it would answer 200, which
        // is exactly the regression this pins.
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

            holder.rotate()

            // "Log every device out" with no session table: the HMAC key changed, so every cookie ever
            // issued stops verifying at once — on the very next request, without a restart.
            assertEquals(HttpStatusCode.Unauthorized, client.probe(port, cookie = issued).status)
        }
    }

    // --- the Host allowlist ---------------------------------------------------------------------------

    @Test
    fun aForeignHostIs403EvenWithAValidBearer() = withGatedServer { port, client ->
        // Refused before the secret is examined at all: a request that arrived under a hostname we do not
        // serve is not a credential question, and answering 403 leaks nothing about the token.
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
            // The reason Origin is checked at all: SameSite reasons about the SITE (eTLD+1), so a page on a
            // sibling subdomain is same-site and its fetch would carry this cookie.
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

    // --- the loopback-only surface --------------------------------------------------------------------

    @Test
    fun aLoopbackOnlyRouteIsServedLocallyAndRefusedFromTheTunnel() =
        withGatedServer(publicUrl = publicUrl) { port, client ->
            assertEquals(HttpStatusCode.OK, client.probe(port, path = "/local").status, "local callers are served")
            // Even though this host IS in the allowlist for the browser surface, the local-only routes (the
            // hook ingresses, and in Task 8 ticket issuance and rotation) are never published through it.
            assertEquals(
                HttpStatusCode.Forbidden,
                client.probe(port, path = "/local", host = "kotgent.example.com").status,
            )
        }

    // --- the Secure-cookie decision (Task 8 sets the cookie; the rule is decided here) -----------------

    @Test
    fun secureIsDecidedByTheHostThatAnsweredNotByAForwardedHeader() {
        assertTrue(requiresSecureCookie("kotgent.example.com", publicUrl), "the public host is https")
        assertTrue(requiresSecureCookie("KOTGENT.EXAMPLE.COM:443", publicUrl), "case and port do not matter")
        // A Secure cookie handed to a browser on plain http://127.0.0.1 is silently DISCARDED — that is a
        // lockout, so the local surface must never get one.
        assertFalse(requiresSecureCookie("127.0.0.1:27508", publicUrl), "loopback is plain http")
        assertFalse(requiresSecureCookie("127.0.0.1:27508", null), "no public URL at all")
        assertFalse(requiresSecureCookie("127.0.0.1", "http://127.0.0.1:27508"), "an http public URL is not secure")
        assertFalse(requiresSecureCookie(null, publicUrl), "a request without a Host gets nothing")
        assertFalse(requiresSecureCookie("qa.example.com", publicUrl), "another host is not the public one")
    }

    // --- harness --------------------------------------------------------------------------------------

    /**
     * A two-route server: `/ping` (GET + POST) behind [authenticated], and `/local` behind [loopbackOnly].
     * Both handlers answer `200` with a body, so "the gate let it through" and "the gate refused" are
     * distinguishable by status alone.
     */
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

    /**
     * One request with exactly the facts the rule looks at. An explicit [host] overrides the one the client
     * would compute from the URL — that is how a request "arriving through the tunnel" is simulated without
     * a tunnel, and it is precisely the header cloudflared forwards.
     */
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
