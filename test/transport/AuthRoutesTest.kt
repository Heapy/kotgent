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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * A code that CANNOT have been minted: `U` is outside [TICKET_CODE_ALPHABET] and, unlike `I`/`L`/`O`,
     * has no Crockford substitution, so [normalizeTicketCode] leaves it alone and the lookup always misses.
     * Using a random-looking value instead would be a 1-in-2^40 flake.
     */
    private val wrongCode = "U".repeat(TICKET_CODE_LENGTH)

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
        assertTrue(
            body.ticket.length == TICKET_CODE_LENGTH && body.ticket.all { it in TICKET_CODE_ALPHABET },
            "the ticket is a typable Crockford base32 code: ${body.ticket}",
        )
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

    @Test
    fun aSessionCookieCanMintATicketThatExchangesIntoAWorkingCookie() = withAuthServer { env ->
        // The PhoneDialog path: an already-logged-in browser (a session cookie, NO Bearer) mints a sign-in
        // link for a second device. The handler's single-token-snapshot re-check accepts the cookie against
        // the current token and binds the new ticket to that same snapshot. Absent any rotation, the ticket
        // then exchanges into a cookie that reaches the control plane — proving the cookie path of the
        // gate-and-bind re-check still works end to end.
        val loginCookie = parseServerSetCookieHeader(
            env.exchange(env.port, env.issueTicket()).headers[HttpHeaders.SetCookie]!!,
        ).value

        val ticketResp = env.client.req(
            env.port, AUTH_TICKET_PATH, HttpMethod.Post,
            cookie = loginCookie, origin = "http://127.0.0.1:${env.port}",
        )
        assertEquals(HttpStatusCode.OK, ticketResp.status, "a valid session cookie mints a ticket (PhoneDialog)")
        val ticket = TRANSPORT_JSON.decodeFromString(TicketResponse.serializer(), ticketResp.bodyAsText()).ticket

        val exchanged = env.exchange(env.port, ticket)
        assertEquals(HttpStatusCode.OK, exchanged.status, "the cookie-minted ticket redeems")
        val newCookie = parseServerSetCookieHeader(exchanged.headers[HttpHeaders.SetCookie]!!).value
        assertTrue(
            verifySessionCookie(token, newCookie),
            "the exchanged cookie verifies under the token that was current at issue",
        )
        assertEquals(
            HttpStatusCode.OK,
            env.client.req(env.port, "/sessions", cookie = newCookie).status,
            "and it authenticates the control plane",
        )
    }

    @Test
    fun aTicketRequestWithANonLiveBearerIs401AndMintsNothing() = withAuthServer { env ->
        // The single-snapshot re-check binds the ticket to the credential the caller actually PROVED: when a
        // Bearer is present it MUST match the snapshot (no fall-through to the cookie), so a stray, non-live
        // Bearer cannot mint a ticket even riding alongside a valid session cookie that would satisfy the gate.
        val cookie = parseServerSetCookieHeader(
            env.exchange(env.port, env.issueTicket()).headers[HttpHeaders.SetCookie]!!,
        ).value
        val resp = env.client.req(
            env.port, AUTH_TICKET_PATH, HttpMethod.Post,
            bearer = "not-the-live-master-token", cookie = cookie, origin = "http://127.0.0.1:${env.port}",
        )
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "a non-live Bearer does not mint a ticket")
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

    @Test
    fun getAuthRendersTheTypedCodeFormForAnAppThatHasNoLink() = withAuthServer { env ->
        // The gap this closes: an installed home-screen PWA launches at start_url with its OWN cookie jar and
        // no way to be handed a `#ticket=` fragment, so the ONLY way in is typing the code here. If this form
        // is missing (or posts somewhere else) that launch dead-ends — and nothing else in the suite looks.
        val page = env.client.req(env.port, AUTH_PAGE_PATH).bodyAsText()
        assertTrue(page.contains("""id="code-form""""), "the page carries the code form")
        assertTrue(page.contains("""id="code""""), "with an input to type the code into")
        assertTrue(page.contains(AUTH_EXCHANGE_PATH), "posting to the same exchange the link path uses")
        assertTrue(
            page.contains("""rel="manifest" href="/manifest.webmanifest"""") &&
                page.contains("""rel="apple-touch-icon"""") &&
                page.contains("""name="apple-mobile-web-app-capable""""),
            "the credential-free QR landing page remains installable before the code is spent",
        )
        assertTrue(page.contains("$TICKET_CODE_LENGTH characters"), "and states the code's length")
        assertTrue(
            page.contains("${TICKET_TTL_MILLIS / 60_000} minutes"),
            "and its life, derived from the constant so the copy cannot drift from the TTL",
        )
        assertTrue(page.contains("429"), "the throttle is told apart from a wrong code — waiting is the remedy")
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
    fun aCodeTypedTheWayAHumanTypesItExchanges() = withAuthServer { env ->
        // The route trims and then hands the raw string to [TicketStore.redeem], which normalises. Asserted
        // through the real route rather than only at the store, because the trim/empty guard in between is
        // exactly the kind of thing that could swallow a grouped, lower-cased code before it ever gets there.
        val ticket = env.issueTicket()
        val typed = " " + ticket.take(4).lowercase() + "-" + ticket.drop(4).lowercase() + " "
        assertEquals(HttpStatusCode.OK, env.exchange(env.port, typed).status, "as typed: '$typed'")
    }

    @Test
    fun aCodeTypedIntoTheFormSignsThatBrowserIn() = withAuthServer { env ->
        // The whole PWA story in one test: the code the CLI/QR dialog shows is minted by the ticket route,
        // typed into the /auth form by a browser that holds NOTHING, and comes back as a cookie that reaches
        // the control plane. Distinct from the link path — no fragment is involved anywhere here.
        val code = env.issueTicket()
        val resp = env.exchange(env.port, groupedAndLowercased(code))
        assertEquals(HttpStatusCode.OK, resp.status, "the typed code exchanges")

        val setCookie = resp.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie, "and the exchange sets the session cookie")
        val cookie = parseServerSetCookieHeader(setCookie).value
        assertTrue(verifySessionCookie(token, cookie), "which verifies against the master token")
        assertEquals(
            HttpStatusCode.OK,
            env.client.req(env.port, "/sessions", cookie = cookie).status,
            "so the installed app is signed in with no link and no CLI on that device",
        )
    }

    @Test
    fun aWrongCodeIs400AndPlantsNoCookie() = withAuthServer { env ->
        val resp = env.exchange(env.port, wrongCode)
        assertEquals(HttpStatusCode.BadRequest, resp.status, "a code that was never issued is refused")
        assertNull(
            resp.headers[HttpHeaders.SetCookie],
            "and nothing is planted — a mistyped code must not leave a half-credential in the browser",
        )
    }

    @Test
    fun exchangeBodyIntakeIsByteBoundedAndTimeBounded() = runBlocking {
        withTimeout(20_000) {
            val exact = "x".repeat(32)
            assertEquals(
                AuthExchangeBodyRead.Received(exact),
                readAuthExchangeBody(ByteReadChannel(exact), maxBytes = exact.length, timeoutMillis = 1_000),
                "an exactly-full body is not mistaken for overflow",
            )
            assertEquals(
                AuthExchangeBodyRead.Incomplete,
                readAuthExchangeBody(
                    ByteReadChannel(exact),
                    expectedBytes = exact.length.toLong() + 1,
                    maxBytes = exact.length + 1,
                    timeoutMillis = 1_000,
                ),
                "EOF before the declared Content-Length is not accepted as a complete body",
            )
            assertEquals(
                AuthExchangeBodyRead.TooLarge,
                readAuthExchangeBody(ByteReadChannel("$exact!"), maxBytes = exact.length, timeoutMillis = 1_000),
                "only max+1 bytes are needed to reject an oversized streaming body",
            )
            val openOverflow = ByteChannel(autoFlush = true)
            try {
                openOverflow.writeFully("$exact!".encodeToByteArray())
                assertEquals(
                    AuthExchangeBodyRead.TooLarge,
                    readAuthExchangeBody(openOverflow, maxBytes = exact.length, timeoutMillis = 1_000),
                    "overflow is rejected without waiting for a chunked peer to close the request body",
                )
            } finally {
                openOverflow.cancel(null)
            }

            val stalled = ByteChannel()
            try {
                assertEquals(
                    AuthExchangeBodyRead.TimedOut,
                    readAuthExchangeBody(stalled, maxBytes = exact.length, timeoutMillis = 50),
                    "a peer that never finishes its body cannot hold an admitted connection forever",
                )
            } finally {
                stalled.cancel(null)
            }
        }
    }

    @Test
    fun prematureEofCannotRedeemACompleteJsonPrefix() {
        val limit = ExchangeRateLimit(now = { fixedNow }, max = 1)
        withAuthServer(exchangeLimit = limit) { env ->
            val ticket = env.issueTicket()
            val validPrefix = """{"ticket":"$ticket"}"""
            val response = env.rawExchangeUntilClosed(
                contentLength = validPrefix.encodeToByteArray().size + 1,
                body = validPrefix,
                halfCloseRequest = true,
            )

            assertTrue(response.contains(" 400 "), "a body shorter than its Content-Length is rejected")
            assertFalse(
                response.contains("Set-Cookie:", ignoreCase = true),
                "an incomplete request cannot plant a session cookie",
            )
            assertEquals(0, limit.failuresInWindow(), "an incomplete non-guess spends no failure budget")
            limit.awaitReleasedCapacity()
            assertEquals(
                HttpStatusCode.OK,
                env.exchange(env.port, ticket).status,
                "rejecting the incomplete framing does not redeem its valid ticket prefix",
            )
        }
    }

    @Test
    fun oversizedExchangeBodyIs413AndReleasesItsReservationWithoutCharging() {
        val limit = ExchangeRateLimit(now = { fixedNow }, max = 1)
        withAuthServer(exchangeLimit = limit) { env ->
            val response = env.client.req(
                env.port,
                AUTH_EXCHANGE_PATH,
                HttpMethod.Post,
                origin = "http://127.0.0.1:${env.port}",
                jsonBody = "x".repeat(AUTH_EXCHANGE_MAX_BODY_BYTES + 1),
            )
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(0, limit.failuresInWindow(), "an oversized non-guess spends no failure budget")
            limit.awaitReleasedCapacity()
            assertEquals(
                HttpStatusCode.OK,
                env.exchange(env.port, env.issueTicket()).status,
                "the sole in-flight reservation was released",
            )
        }
    }

    @Test
    fun unconsumedExchangeBodiesCloseTheRawConnectionAndReleaseLimiterCapacity() {
        val limit = ExchangeRateLimit(now = { fixedNow }, max = 1)
        withAuthServer(exchangeLimit = limit, exchangeBodyTimeoutMillis = 50) { env ->
            val oversized = env.rawExchangeUntilClosed(contentLength = AUTH_EXCHANGE_MAX_BODY_BYTES + 1)
            assertTrue(oversized.contains(" 413 "), "a declared oversized body is rejected before any bytes arrive")
            assertTrue(
                oversized.contains("Connection: close", ignoreCase = true),
                "the early response tells a compliant peer the connection cannot be reused",
            )

            val timedOut = env.rawExchangeUntilClosed(contentLength = 1)
            assertTrue(timedOut.contains(" 408 "), "a peer that withholds its one promised byte is timed out")
            assertEquals(0, limit.failuresInWindow(), "unconsumed non-guesses spend no failure budget")
            assertEquals(
                HttpStatusCode.OK,
                env.exchange(env.port, env.issueTicket()).status,
                "both rejected sockets released the sole slot and did not cancel the server root",
            )

            val failed = assertNotNull(limit.begin())
            failed.finish(failed = true)
            val throttled = env.rawExchangeUntilClosed(contentLength = 1)
            assertTrue(
                throttled.contains(" 429 "),
                "a saturated request is refused and closed without waiting for its promised body",
            )
            assertEquals(
                HttpStatusCode.OK,
                env.client.req(env.port, AUTH_PAGE_PATH).status,
                "forcing the saturated connection closed leaves the server root healthy",
            )
        }
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

    // --- the guessing budget ----------------------------------------------------------------------

    @Test
    fun failedExchangesAreCountedAcrossRequestsAndRefusedWith429() = withAuthServer { env ->
        // The control that pays for a 40-bit login code. Driven through the REAL route with the route's own
        // default limiter, because the failure this guards against is a WIRING one: a limiter constructed
        // per call would count to one and start over, so every request would pass and no unit test of
        // [ExchangeRateLimit] would ever notice. Ten wrong codes are answered 400; the eleventh is 429.
        repeat(EXCHANGE_FAILURE_LIMIT) {
            assertEquals(
                HttpStatusCode.BadRequest,
                env.exchange(env.port, wrongCode).status,
                "guess ${it + 1} is a plain wrong code",
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            env.exchange(env.port, wrongCode).status,
            "the failures accumulated ACROSS requests — one limiter per daemon, not per call",
        )
    }

    @Test
    fun concurrentFailedExchangesCannotBurstPastTheGlobalBudget() = withAuthServer { env ->
        // The old split `allow()` / `recordFailure()` route admitted every request in a concurrent burst:
        // they all saw zero failures before any redemption had returned to record one. Start fifty requests
        // at one gate and require the reservation itself — not scheduler luck — to hold the ten-guess cap.
        val start = CompletableDeferred<Unit>()
        val total = EXCHANGE_FAILURE_LIMIT * 5
        val responses = coroutineScope {
            val requests = List(total) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    start.await()
                    env.exchange(env.port, wrongCode)
                }
            }
            start.complete(Unit)
            requests.awaitAll()
        }
        assertEquals(
            EXCHANGE_FAILURE_LIMIT,
            responses.count { it.status == HttpStatusCode.BadRequest },
            "exactly the budget reached ticket redemption and failed",
        )
        assertEquals(
            total - EXCHANGE_FAILURE_LIMIT,
            responses.count { it.status == HttpStatusCode.TooManyRequests },
            "every excess concurrent guess was throttled before lookup",
        )
    }

    @Test
    fun cancellingAnAdmittedExchangeReleasesItsReservation() {
        val limit = ExchangeRateLimit(now = { fixedNow }, max = 1)
        val abortOnce = CompletableDeferred<Unit>()
        withAuthServer(
            exchangeLimit = limit,
            afterExchangeAdmitted = {
                if (abortOnce.complete(Unit)) throw CancellationException("test abort after admission")
            },
        ) { env ->
            // The test seam aborts after begin() but before redemption. Depending on the CIO boundary the
            // client may see an error response or a closed call; either is an aborted exchange.
            runCatching { env.exchange(env.port, wrongCode) }
            assertTrue(abortOnce.isCompleted, "the request reached the admitted section")

            val probe = assertNotNull(limit.begin(), "the cancelled handler returned its sole reservation")
            probe.finish(failed = false)
            assertEquals(0, limit.failuresInWindow(), "an aborted non-guess does not spend failure budget")

            assertEquals(
                HttpStatusCode.OK,
                env.exchange(env.port, env.issueTicket()).status,
                "a legitimate exchange is admitted immediately after the cancellation",
            )
        }
    }

    @Test
    fun aValidCodeStillRedeemsWhileTheLimiterIsWarmAndTheSuccessSpendsNothing() = withAuthServer { env ->
        val ticket = env.issueTicket()
        repeat(EXCHANGE_FAILURE_LIMIT - 1) {
            assertEquals(HttpStatusCode.BadRequest, env.exchange(env.port, wrongCode).status)
        }

        assertEquals(
            HttpStatusCode.OK,
            env.exchange(env.port, ticket).status,
            "an operator whose earlier attempts were mistypes still gets in under the cap",
        )

        // And that success charged nothing: there is still exactly one guess of budget left.
        assertEquals(HttpStatusCode.BadRequest, env.exchange(env.port, wrongCode).status, "the tenth failure")
        assertEquals(HttpStatusCode.TooManyRequests, env.exchange(env.port, wrongCode).status, "the eleventh")
    }

    @Test
    fun aSaturatedLimiterRefusesBeforeTheCodeIsLookedAtSoAValidTicketSurvives() {
        // The refusal happens ahead of the redemption, which has two consequences worth pinning: a throttled
        // request plants no cookie, and — because the code was never looked at — a legitimate ticket caught
        // by someone else's guessing burst is NOT consumed. It still redeems once the window rolls forward.
        var limiterClock = fixedNow
        val limit = ExchangeRateLimit(now = { limiterClock })
        withAuthServer(exchangeLimit = limit) { env ->
            val ticket = env.issueTicket()
            repeat(EXCHANGE_FAILURE_LIMIT) { env.exchange(env.port, wrongCode) }

            val malformed = env.client.req(
                env.port,
                AUTH_EXCHANGE_PATH,
                HttpMethod.Post,
                origin = "http://127.0.0.1:${env.port}",
                jsonBody = "not json",
            )
            assertEquals(
                HttpStatusCode.TooManyRequests,
                malformed.status,
                "a saturated limiter refuses before reading even a malformed body",
            )

            val throttled = env.exchange(env.port, ticket)
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status, "a valid code is throttled too")
            assertNull(throttled.headers[HttpHeaders.SetCookie], "and no session cookie is planted")

            limiterClock = fixedNow + EXCHANGE_WINDOW_MILLIS
            assertEquals(
                HttpStatusCode.OK,
                env.exchange(env.port, ticket).status,
                "the ticket was never spent — the denial is temporary, not a lost credential",
            )
        }
    }

    @Test
    fun anUnparseableBodyIsRefusedWithoutSpendingBudget() = withAuthServer { env ->
        // A body that does not parse never names a candidate code, so it is not a guess. Spending budget on
        // it would hand an attacker a cheaper way to deny sign-in than actually guessing.
        repeat(EXCHANGE_FAILURE_LIMIT * 2) {
            val resp = env.client.req(
                env.port, AUTH_EXCHANGE_PATH, HttpMethod.Post,
                origin = "http://127.0.0.1:${env.port}", jsonBody = "not json at all",
            )
            assertEquals(HttpStatusCode.BadRequest, resp.status, "an unparseable body is a 400")
        }
        assertEquals(
            HttpStatusCode.OK,
            env.exchange(env.port, env.issueTicket()).status,
            "and the budget is untouched — a real code still exchanges",
        )
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
    fun aTicketMintedBeforeRotationExchangesIntoADeadCookie() = withAuthServer { env ->
        // Rotation is "revoke ALL browser credentials". A cookie is revoked because the HMAC key changes, and
        // an outstanding, UNREDEEMED ticket — a QR/link that was shoulder-surfed, exactly why an operator
        // rotates — is bound to the token it was MINTED under. So after a rotation it can still be redeemed,
        // but the cookie the exchange then mints is signed with the OLD token: it is dead the instant it is
        // set, because the gate verifies against the now-current token. No cross-lock ticket clearing needed.
        val ticket = env.issueTicket()

        val rotate = env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token)
        assertEquals(HttpStatusCode.OK, rotate.status, "rotation succeeds with the old Bearer")
        val rotated = TRANSPORT_JSON.decodeFromString(RotateResponse.serializer(), rotate.bodyAsText()).token

        // The exchange itself still 200s (the pre-rotation ticket redeems), but the cookie it plants is bound
        // to the pre-rotation token — so it does NOT verify under the rotated one and cannot reach the gate.
        val resp = env.exchange(env.port, ticket)
        assertEquals(HttpStatusCode.OK, resp.status, "the pre-rotation ticket still redeems")
        val setCookie = resp.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie, "and a Set-Cookie is returned")
        val cookie = parseServerSetCookieHeader(setCookie).value
        assertFalse(
            verifySessionCookie(rotated, cookie),
            "but the cookie is signed with the OLD token, so it does not verify under the rotated one — dead on arrival",
        )
        assertTrue(
            verifySessionCookie(token, cookie),
            "it verifies only under the token that was live when the ticket was minted",
        )
        // The decisive check: it buys no access — the control-plane gate resolves the CURRENT token.
        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(env.port, "/sessions", cookie = cookie).status,
            "a pre-rotation ticket yields no usable session — rotation revoked it by construction",
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

    @Test
    fun aStaleBearerCannotRotateTwice() = withAuthServer { env ->
        // The end-to-end shape of the compare-and-swap: the first rotate with the master Bearer wins; a second
        // rotate replaying the SAME (now-stale) Bearer is refused (4xx) and mints nothing. Without the CAS the
        // old separate pre-check + rotate would let a concurrent replay mint a SECOND token and hand its holder
        // the final live value; with it, only the winner's token is ever in force.
        val first = env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token)
        assertEquals(HttpStatusCode.OK, first.status)
        val rotated = TRANSPORT_JSON.decodeFromString(RotateResponse.serializer(), first.bodyAsText()).token

        val second = env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token)
        assertTrue(second.status.value in 400..499, "a stale Bearer cannot rotate again; got ${second.status}")

        // Nothing further changed: the token in force is still the FIRST rotation's, so its Bearer rotates.
        assertEquals(
            HttpStatusCode.OK,
            env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = rotated).status,
            "the winner's token is still the live one — the stale replay changed nothing",
        )
    }

    @Test
    fun rotateWithANonLiveBearerConflictsAndDoesNotRotate() = withAuthServer { env ->
        // Drive the handler's 409 branch deterministically: a request that passes the gate by ANOTHER means
        // (a valid session cookie) but presents a Bearer that is NOT the live token. The CAS in
        // TokenHolder.rotate only swaps when the presented value is still current, so a non-live Bearer yields
        // null → 409 and no rotation — a stray/stale Bearer (or a concurrent-rotate loser) cannot mint.
        val cookie = parseServerSetCookieHeader(
            env.exchange(env.port, env.issueTicket()).headers[HttpHeaders.SetCookie]!!,
        ).value
        val resp = env.client.req(
            env.port, AUTH_ROTATE_PATH, HttpMethod.Post,
            bearer = "not-the-live-master-token", cookie = cookie, origin = "http://127.0.0.1:${env.port}",
        )
        assertEquals(HttpStatusCode.Conflict, resp.status, "a non-live Bearer cannot rotate (CAS lost) → 409")

        // And nothing rotated: the real master Bearer still rotates, so the failed attempt left it untouched.
        assertEquals(
            HttpStatusCode.OK,
            env.client.req(env.port, AUTH_ROTATE_PATH, HttpMethod.Post, bearer = token).status,
            "the 409 attempt did not change the token — the live Bearer still rotates",
        )
    }

    /**
     * An early response is flushed before CIO's raw connection is cancelled, so the admitted reservation
     * intentionally survives slightly beyond the client receiving 413/408. Probe until that bounded close
     * finishes, releasing the probe immediately so the subsequent assertion sees the original capacity.
     */
    private suspend fun ExchangeRateLimit.awaitReleasedCapacity() {
        withTimeout(1_000) {
            while (true) {
                val probe = begin()
                if (probe != null) {
                    probe.finish(failed = false)
                    return@withTimeout
                }
                delay(5)
            }
        }
    }

    // --- harness ----------------------------------------------------------------------------------

    /**
     * The code exactly as it leaves a human: split in the middle the way `PhoneDialog`/`kotgent web` print
     * it, and lower-cased the way a phone keyboard hands it over. Both are folded away by
     * [normalizeTicketCode]; using the display form here is what proves the printed shape is redeemable.
     */
    private fun groupedAndLowercased(code: String): String =
        (code.substring(0, code.length / 2) + " " + code.substring(code.length / 2)).lowercase()

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

        /**
         * Speak HTTP over a raw socket, deliberately sending fewer than [contentLength] bytes. With
         * [halfCloseRequest], closing only the write channel tells CIO no more request bytes can arrive while
         * preserving this socket's read side for the response. Returning proves the server closed the socket;
         * a route-only channel cancellation would leave this read suspended in CIO's raw body parser until the
         * outer timeout failed the test.
         */
        suspend fun rawExchangeUntilClosed(
            contentLength: Int,
            body: String = "",
            halfCloseRequest: Boolean = false,
        ): String = withTimeout(2_000) {
            val selector = SelectorManager(Dispatchers.Default)
            val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
            try {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                val request = buildString {
                    append("POST $AUTH_EXCHANGE_PATH HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Origin: http://127.0.0.1:$port\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Connection: keep-alive\r\n")
                    append("\r\n")
                }
                output.writeFully(request.encodeToByteArray())
                if (body.isNotEmpty()) output.writeFully(body.encodeToByteArray())
                if (halfCloseRequest) output.flushAndClose()

                val response = StringBuilder()
                val buffer = ByteArray(1_024)
                try {
                    while (true) {
                        val read = input.readAvailable(buffer)
                        if (read < 0) break
                        response.append(buffer.decodeToString(endIndex = read))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A reset is as decisive as EOF: either proves the accepted socket was reclaimed.
                }
                response.toString()
            } finally {
                socket.close()
                selector.close()
            }
        }
    }

    private fun withAuthServer(
        publicUrl: String? = null,
        exchangeLimit: ExchangeRateLimit? = null,
        afterExchangeAdmitted: (suspend () -> Unit)? = null,
        exchangeBodyTimeoutMillis: Long = AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS,
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        withTimeout(30_000) {
            val tokens = TokenHolder(token)
            val tickets = TicketStore(now = { fixedNow })
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    // Two mount shapes on purpose. Without an injected limiter the route's OWN default is
                    // used — which is what pins that the default is evaluated once, HERE at mount time, and
                    // not once per request (a per-call instance would count to one and start over: a
                    // limiter that silently does nothing).
                    if (exchangeLimit != null) {
                        authRoutes(
                            tokens,
                            tickets,
                            publicUrl,
                            TRANSPORT_JSON,
                            { fixedNow },
                            exchangeLimit,
                            afterExchangeAdmitted ?: {},
                            exchangeBodyTimeoutMillis,
                        )
                    } else {
                        authRoutes(
                            tokens,
                            tickets,
                            publicUrl,
                            TRANSPORT_JSON,
                            now = { fixedNow },
                            exchangeBodyTimeoutMillis = exchangeBodyTimeoutMillis,
                        )
                    }
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
