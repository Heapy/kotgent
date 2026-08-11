package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

class SessionCookieTest {

    private val token = "a".repeat(64)
    private val issuedAt = 1_753_280_000_000L

    @Test
    fun aFreshlyIssuedCookieVerifiesAgainstTheSameToken() {
        val value = issueSessionCookie(token, issuedAt)
        assertTrue(verifySessionCookie(token, value), "round-trip verifies")
    }

    @Test
    fun theValueIsVersionIssuedAtAndAHexMac() {
        val fields = issueSessionCookie(token, issuedAt).split('.')
        assertEquals(3, fields.size, "value is exactly three dot-separated fields")
        assertEquals("v1", fields[0])
        assertEquals(issuedAt.toString(), fields[1], "the issue time is readable — it is not a secret")
        assertEquals(64, fields[2].length, "the MAC is a 32-byte SHA-256 digest, hex-encoded")
        assertTrue(fields[2].all { it in '0'..'9' || it in 'a'..'f' }, "lowercase hex MAC")
    }

    @Test
    fun twoDevicesLoggingInAtDifferentMomentsGetDifferentValues() {
        assertNotEquals(
            issueSessionCookie(token, issuedAt),
            issueSessionCookie(token, issuedAt + 1),
            "issuedAt is stamped into the signed message, so the values differ",
        )
        assertEquals(
            issueSessionCookie(token, issuedAt),
            issueSessionCookie(token, issuedAt),
            "…and issuing is deterministic — verification is a recomputation, not a lookup",
        )
    }

    @Test
    fun rotatingTheMasterTokenKillsEveryPreviouslyIssuedCookie() {
        val value = issueSessionCookie(token, issuedAt)
        val rotated = "b".repeat(64)
        assertTrue(verifySessionCookie(token, value), "valid before the rotation")
        assertFalse(
            verifySessionCookie(rotated, value),
            "and dead after it — this IS 'log out all devices', which is why there is no session table",
        )
        assertTrue(verifySessionCookie(rotated, issueSessionCookie(rotated, issuedAt)), "the new key still issues")
    }

    @Test
    fun aTamperedMacIsRejected() {
        val value = issueSessionCookie(token, issuedAt)
        val flipped = value.dropLast(1) + if (value.last() == 'a') 'b' else 'a'
        assertFalse(verifySessionCookie(token, flipped), "one flipped hex digit is enough")
        assertFalse(
            verifySessionCookie(token, "v1.${issuedAt + 1}.${value.substringAfterLast('.')}"),
            "the MAC covers issuedAt, so restamping the timestamp invalidates it",
        )
        assertFalse(
            verifySessionCookie(token, value.uppercase()),
            "MACs are compared as the lowercase hex they are issued as",
        )
    }

    @Test
    fun malformedValuesAreRejected() {
        val mac = issueSessionCookie(token, issuedAt).substringAfterLast('.')
        val bad = listOf(
            null,
            "",
            "v1",
            "v1.$issuedAt",
            "$issuedAt.$mac",
            "v1.$issuedAt.$mac.extra",
            "v2.$issuedAt.$mac",
            "V1.$issuedAt.$mac",
            "v1..$mac",
            "v1.notanumber.$mac",
            "v1.+$issuedAt.$mac",
            "v1.-1.$mac",
            "v1.$issuedAt.",
            "   ",
        )
        for (value in bad) {
            assertFalse(verifySessionCookie(token, value), "must reject ${value ?: "null"}")
        }
    }

    @Test
    fun anEmptyMasterTokenNeverVerifies() {
        assertFalse(verifySessionCookie("", issueSessionCookie("", issuedAt)), "an empty key is refused")
    }


    @Test
    fun theCookieRoundTripsThroughKtorsOwnCookieApi() = withCookieServer { port, client ->
        val issued = client.get("http://127.0.0.1:$port/set")
        val setCookie = issued.headers[HttpHeaders.SetCookie]
        assertTrue(setCookie != null, "the server emitted a Set-Cookie header")

        val cookie = parseServerSetCookieHeader(setCookie)
        assertEquals(SESSION_COOKIE_NAME, cookie.name)
        assertEquals("/", cookie.path, "one cookie covers the API, both WebSockets and the SPA")
        assertTrue(cookie.httpOnly, "page script must not be able to read the session key")
        assertEquals("Strict", cookie.extensions["SameSite"], "another site cannot navigate into an authed request")
        assertEquals(
            SESSION_COOKIE_MAX_AGE_SECONDS,
            cookie.maxAge?.toLong(),
            "an explicit Max-Age — a session cookie would not survive a mobile Safari restart",
        )
        assertFalse(cookie.secure, "not Secure on loopback http, or the browser would silently drop it")
        assertTrue(verifySessionCookie(token, cookie.value), "the transmitted value is the one we signed")

        val echoed = client.get("http://127.0.0.1:$port/check") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=${cookie.value}")
        }
        assertEquals("valid", echoed.bodyAsText(), "Ktor parsed the Cookie header for us")

        val none = client.get("http://127.0.0.1:$port/check")
        assertEquals("absent", none.bodyAsText(), "no cookie is 'absent', not 'valid'")

        val tampered = client.get("http://127.0.0.1:$port/check") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=v1.$issuedAt.${"0".repeat(64)}")
        }
        assertEquals("invalid", tampered.bodyAsText(), "a forged MAC does not survive the trip either")
    }

    @Test
    fun secureIsSetOnlyWhenTheCallerAsksForIt() = withCookieServer { port, client ->
        val secured = client.get("http://127.0.0.1:$port/set?secure=1")
        val cookie = parseServerSetCookieHeader(secured.headers[HttpHeaders.SetCookie]!!)
        assertTrue(cookie.secure, "the public-host path marks the cookie Secure")
        assertTrue(cookie.httpOnly, "and keeps every other attribute")
        assertNull(cookie.domain, "no Domain: the cookie stays on the exact host that set it")
    }

    private fun withCookieServer(block: suspend (port: Int, client: HttpClient) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    get("/set") {
                        val secure = call.request.queryParameters["secure"] == "1"
                        call.setSessionCookie(issueSessionCookie(token, issuedAt), secure = secure)
                        call.respondText("ok")
                    }
                    get("/check") {
                        val value = call.sessionCookie()
                        call.respondText(
                            when {
                                value == null -> "absent"
                                verifySessionCookie(token, value) -> "valid"
                                else -> "invalid"
                            },
                        )
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(port, client)
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    }
}
