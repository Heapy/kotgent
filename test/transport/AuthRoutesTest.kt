package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The login-route wiring (plan Task 8): a ticket is minted only for a loopback `Bearer`, the page it points
 * at burns nothing, the exchange spends the ticket exactly once for a session cookie, and the cookie it
 * hands back authenticates the real control plane until the master token is rotated.
 *
 * A bare server mounting [authRoutes] plus a single [authenticated] `/sessions` stand-in, rather than the
 * whole [KotgentServer]: the thing under test is the login flow and how its cookie composes with the gate,
 * so a failure points here and not at any of the daemon's fakes. [AuthorizeWiringTest] already pins the gate
 * itself; [SessionCookieTest] pins the cookie's own round-trip through Ktor.
 */
class AuthRoutesTest {

    private val token = "auth-routes-master-token-0123456789ab"
    private val publicUrl = "https://kotgent.example.com"
    private val fixedNow = 1_753_280_000_000L

    // --- ticket issuance --------------------------------------------------------------------------

    @Test
    fun issuingATicketRequiresABearer() = withAuthServer { env ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(env.port, AUTH_TICKET_PATH, HttpMethod.Post).status,
            "no Bearer → 401",
        )
        val ok = env.client.req(env.port, AUTH_TICKET_PATH, HttpMethod.Post, bearer = token)
        assertEquals(HttpStatusCode.OK, ok.status, "a loopback Bearer mints one")
        val body = TRANSPORT_JSON.decodeFromString(TicketResponse.serializer(), ok.bodyAsText())
        assertTrue(body.ticket.length == 64, "the ticket is 32 bytes of entropy, hex-encoded")
        assertTrue(
            body.localUrl == "http://127.0.0.1:${env.port}$AUTH_PAGE_PATH#ticket=${body.ticket}",
            "the local URL carries the ticket in its fragment on the port it arrived on",
        )
        assertNull(body.publicUrl, "no public URL is configured, so there is none to advertise")
        assertEquals(fixedNow + TICKET_TTL_MILLIS, body.expiresAt, "expiry is now + the TTL")
    }

    @Test
    fun aConfiguredPublicUrlIsReflectedInTheTicket() = withAuthServer(publicUrl = publicUrl) { env ->
        val ok = env.client.req(env.port, AUTH_TICKET_PATH, HttpMethod.Post, bearer = token)
        val body = TRANSPORT_JSON.decodeFromString(TicketResponse.serializer(), ok.bodyAsText())
        assertEquals(
            "$publicUrl$AUTH_PAGE_PATH#ticket=${body.ticket}",
            body.publicUrl,
            "the public URL is the same ticket in the configured origin's fragment",
        )
    }

    @Test
    fun issuingATicketFromTheTunnelIs403() = withAuthServer(publicUrl = publicUrl) { env ->
        // The public host IS in the browser allowlist, but ticket issuance is loopback-only: the surface
        // that mints credentials is never published through the tunnel.
        assertEquals(
            HttpStatusCode.Forbidden,
            env.client.req(env.port, AUTH_TICKET_PATH, HttpMethod.Post, bearer = token, host = "kotgent.example.com").status,
        )
    }

    // --- the page burns nothing -------------------------------------------------------------------

    @Test
    fun getAuthServesThePageAndBurnsNoTicket() = withAuthServer { env ->
        val ticket = env.issueTicket()

        val page = env.client.req(env.port, AUTH_PAGE_PATH)
        assertEquals(HttpStatusCode.OK, page.status, "the page is served without any credential")
        assertTrue(page.contentType()?.match(ContentType.Text.Html) == true, "as HTML")
        assertTrue(page.bodyAsText().contains("/auth/exchange"), "the page posts to the exchange")

        // Load it again — a prefetcher hitting the link twice must not consume the ticket.
        env.client.req(env.port, AUTH_PAGE_PATH)

        // The ticket is still spendable: the fragment never reached the server, so nothing could burn it.
        assertEquals(
            HttpStatusCode.OK,
            env.exchange(env.port, ticket).status,
            "the exchange still succeeds after two page loads",
        )
    }

    // --- the exchange -----------------------------------------------------------------------------

    @Test
    fun exchangeSpendsTheTicketAndSetsAVerifiableCookie() = withAuthServer { env ->
        val ticket = env.issueTicket()
        val resp = env.exchange(env.port, ticket)
        assertEquals(HttpStatusCode.OK, resp.status)

        val setCookie = resp.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie, "the exchange sets the session cookie")
        val cookie = parseServerSetCookieHeader(setCookie)
        assertEquals(SESSION_COOKIE_NAME, cookie.name)
        assertTrue(verifySessionCookie(token, cookie.value), "the cookie verifies against the master token")
        assertEquals(fixedNow.toString(), cookie.value.split('.')[1], "it is stamped with the injected clock")
        assertTrue(cookie.httpOnly && !cookie.secure, "HttpOnly, and not Secure on loopback http")
    }

    @Test
    fun aSecondExchangeOfTheSameTicketIs400() = withAuthServer { env ->
        val ticket = env.issueTicket()
        assertEquals(HttpStatusCode.OK, env.exchange(env.port, ticket).status, "spent once")
        assertEquals(HttpStatusCode.BadRequest, env.exchange(env.port, ticket).status, "and not twice")
    }

    @Test
    fun anUnknownTicketIs400() = withAuthServer { env ->
        assertEquals(
            HttpStatusCode.BadRequest,
            env.exchange(env.port, "0".repeat(64)).status,
            "a value that was never issued reads the same as one already spent",
        )
    }

    @Test
    fun exchangeWithNoOriginIs403() = withAuthServer { env ->
        val ticket = env.issueTicket()
        // A POST with no Origin: a browser always sends one on a POST, so its absence is not a browser.
        val resp = env.client.req(env.port, AUTH_EXCHANGE_PATH, HttpMethod.Post, jsonBody = """{"ticket":"$ticket"}""")
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun exchangeWithAForeignOriginIs403() = withAuthServer { env ->
        val ticket = env.issueTicket()
        val resp = env.client.req(
            env.port, AUTH_EXCHANGE_PATH, HttpMethod.Post,
            origin = "https://evil.example.com", jsonBody = """{"ticket":"$ticket"}""",
        )
        assertEquals(HttpStatusCode.Forbidden, resp.status, "a phished ticket cannot plant a cookie cross-site")
    }

    @Test
    fun exchangeOverTheTunnelHostSetsASecureCookie() = withAuthServer(publicUrl = publicUrl) { env ->
        val ticket = env.issueTicket()
        // The REAL phone path, which every other exchange test skips by driving from loopback: the exchange
        // arrives under the public Host with the public Origin. It must succeed AND the cookie must be Secure,
        // because it will ride back over https through the tunnel (a non-Secure cookie there would leak).
        val resp = env.client.req(
            env.port, AUTH_EXCHANGE_PATH, HttpMethod.Post,
            host = "kotgent.example.com", origin = publicUrl,
            jsonBody = """{"ticket":"$ticket"}""",
        )
        assertEquals(HttpStatusCode.OK, resp.status, "the public host with a matching Origin exchanges")
        val cookie = parseServerSetCookieHeader(resp.headers[HttpHeaders.SetCookie]!!)
        assertTrue(verifySessionCookie(token, cookie.value), "the cookie verifies against the master token")
        assertTrue(cookie.secure, "a cookie set over the https public host carries Secure")
        assertTrue(cookie.httpOnly, "and stays HttpOnly")
    }

    @Test
    fun exchangeUnderAForeignHostIs403() = withAuthServer(publicUrl = publicUrl) { env ->
        val ticket = env.issueTicket()
        // A Host we do not serve (a stray tunnel rule, DNS rebinding) is refused before the ticket is even
        // looked at — the exchange never plants a cookie for a hostname outside the allowlist.
        val resp = env.client.req(
            env.port, AUTH_EXCHANGE_PATH, HttpMethod.Post,
            host = "evil.example.com", origin = "https://evil.example.com",
            jsonBody = """{"ticket":"$ticket"}""",
        )
        assertEquals(HttpStatusCode.Forbidden, resp.status, "a host we do not serve cannot exchange a ticket")
    }

    // --- the cookie composes with the real gate ---------------------------------------------------

    @Test
    fun theCookieFromAnExchangeAuthenticatesTheControlPlane() = withAuthServer { env ->
        val cookie = parseServerSetCookieHeader(env.exchange(env.port, env.issueTicket()).headers[HttpHeaders.SetCookie]!!)
        val resp = env.client.req(env.port, "/sessions", cookie = cookie.value)
        assertEquals(HttpStatusCode.OK, resp.status, "the login cookie reaches a same-origin GET with no Origin")
    }

    @Test
    fun rotatingTheTokenReturnsANewValueAndKillsTheCookie() = withAuthServer { env ->
        val cookie = parseServerSetCookieHeader(env.exchange(env.port, env.issueTicket()).headers[HttpHeaders.SetCookie]!!)
        assertEquals(HttpStatusCode.OK, env.client.req(env.port, "/sessions", cookie = cookie.value).status, "works first")

        val rotate = env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token)
        assertEquals(HttpStatusCode.OK, rotate.status)
        val rotated = TRANSPORT_JSON.decodeFromString(RotateResponse.serializer(), rotate.bodyAsText()).token
        assertNotEquals(token, rotated, "rotation mints a fresh token")

        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(env.port, "/sessions", cookie = cookie.value).status,
            "the HMAC key changed, so every cookie ever issued stops verifying — this IS 'log out all devices'",
        )
    }

    @Test
    fun rotationRejectsASessionCookieAndDoesNotRotate() = withAuthServer { env ->
        // The escalation this guards against: a browser holding a valid session cookie (e.g. an XSS in the
        // SPA) fires a SAME-ORIGIN POST at /auth/rotate. Everything the browser gate checks passes — Host is
        // loopback, Origin is allowed, the cookie verifies — so [authenticated] alone would ADMIT it, and
        // rotation echoes the NEW master token back in its body. That would turn a browser-scoped credential
        // into the machine key. Rotation is Bearer-only, so it must be refused 403.
        val cookie = parseServerSetCookieHeader(
            env.exchange(env.port, env.issueTicket()).headers[HttpHeaders.SetCookie]!!,
        ).value

        val denied = env.client.req(
            env.port, AUTH_ROTATE_PATH, HttpMethod.Post,
            cookie = cookie, origin = "http://127.0.0.1:${env.port}",
        )
        assertEquals(HttpStatusCode.Forbidden, denied.status, "a session cookie cannot rotate the master token")

        // And nothing rotated: the OLD master token still authenticates a real (Bearer) rotate. Had the cookie
        // attempt gone through, the OLD token would no longer be current and this would 401.
        assertEquals(
            HttpStatusCode.OK,
            env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token).status,
            "the refused cookie attempt left the token untouched — the old Bearer still rotates",
        )
    }

    @Test
    fun rotationRequiresALoopbackBearer() = withAuthServer(publicUrl = publicUrl) { env ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post).status,
            "no Bearer → 401",
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token, host = "kotgent.example.com").status,
            "rotation is never reachable through the tunnel",
        )
    }

    // --- harness ----------------------------------------------------------------------------------

    private inner class Env(val port: Int, val client: HttpClient, val tokens: TokenHolder) {
        /** Mint a ticket the loopback way and return its opaque value. */
        suspend fun issueTicket(): String {
            val resp = client.req(port, AUTH_TICKET_PATH, HttpMethod.Post, bearer = token)
            return TRANSPORT_JSON.decodeFromString(TicketResponse.serializer(), resp.bodyAsText()).ticket
        }

        /** Spend [ticket] the way the page does: a POST carrying a same-origin loopback Origin. */
        suspend fun exchange(port: Int, ticket: String): HttpResponse = client.req(
            port, AUTH_EXCHANGE_PATH, HttpMethod.Post,
            origin = "http://127.0.0.1:$port", jsonBody = """{"ticket":"$ticket"}""",
        )
    }

    private fun withAuthServer(publicUrl: String? = null, block: suspend (Env) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val tokens = TokenHolder(token)
            val tickets = TicketStore(now = { fixedNow })
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    authRoutes(tokens, tickets, publicUrl, TRANSPORT_JSON, now = { fixedNow })
                    // The one gated route standing in for the whole control plane, so the login cookie can be
                    // shown to reach it (and to stop reaching it after a rotation).
                    authenticated(tokens::current, publicUrl) {
                        get("/sessions") { call.respondText("[]") }
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(Env(port, client, tokens))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    }

    private suspend fun HttpClient.req(
        port: Int,
        path: String,
        method: HttpMethod = HttpMethod.Get,
        bearer: String? = null,
        cookie: String? = null,
        origin: String? = null,
        host: String? = null,
        jsonBody: String? = null,
    ): HttpResponse = request("http://127.0.0.1:$port$path") {
        this.method = method
        applyIfPresent(HttpHeaders.Authorization, bearer?.let { "Bearer $it" })
        applyIfPresent(HttpHeaders.Cookie, cookie?.let { "$SESSION_COOKIE_NAME=$it" })
        applyIfPresent(HttpHeaders.Origin, origin)
        applyIfPresent(HttpHeaders.Host, host)
        if (jsonBody != null) {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
    }

    private fun HttpRequestBuilder.applyIfPresent(name: String, value: String?) {
        if (value != null) header(name, value)
    }
}
