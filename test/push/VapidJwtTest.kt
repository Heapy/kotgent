package io.kotgent.push

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VapidJwtTest {

    private val headerSegment = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9"

    private val fixedNow = 1_753_329_600_000L

    private val expectedExp = 1_753_372_800L

    private val appleClaimsSegment =
        "eyJhdWQiOiJodHRwczovL2FwaS5wdXNoLmFwcGxlLmNvbSIsImV4cCI6MTc1MzM3MjgwMCwic3ViIjoi" +
            "aHR0cHM6Ly9rb3RnZW50LmV4YW1wbGUuY29tIn0"

    private val appleClaimsSegmentFallbackSub =
        "eyJhdWQiOiJodHRwczovL2FwaS5wdXNoLmFwcGxlLmNvbSIsImV4cCI6MTc1MzM3MjgwMCwic3ViIjoi" +
            "bWFpbHRvOmtvdGdlbnRAbG9jYWxob3N0In0"

    private val googleClaimsSegment =
        "eyJhdWQiOiJodHRwczovL2ZjbS5nb29nbGVhcGlzLmNvbSIsImV4cCI6MTc1MzM3MjgwMCwic3ViIjoi" +
            "bWFpbHRvOmtvdGdlbnRAbG9jYWxob3N0In0"

    private val fakeSignatureSegment = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_QA"

    private fun fakeSignature(): ByteArray = ByteArray(P256_RAW_SIGNATURE_LENGTH) { (it + 1).toByte() }

    private val appleEndpoint = "https://api.push.apple.com/3/device/abc123def456"
    private val googleEndpoint = "https://fcm.googleapis.com/fcm/send/cV9x:APA91bH-token"


    @Test
    fun appleAndGoogleEndpointsProduceDifferentAudiences() {
        val apple = pushServiceOrigin(appleEndpoint)
        val google = pushServiceOrigin(googleEndpoint)

        assertEquals("https://api.push.apple.com", apple)
        assertEquals("https://fcm.googleapis.com", google)
        assertNotEquals(apple, google)
    }

    @Test
    fun theSubscriptionPathIsStrippedFromTheAudience() {
        assertEquals(
            "https://updates.push.services.mozilla.com",
            pushServiceOrigin("https://updates.push.services.mozilla.com/wpush/v2/gAAAAA?x=1#f"),
        )
    }

    @Test
    fun theOriginIsLowerCasedAndKeepsANonDefaultPort() {
        assertEquals("https://push.example.com", pushServiceOrigin("HTTPS://Push.Example.COM/send"))
        assertEquals("https://push.example.com:8443", pushServiceOrigin("https://push.example.com:8443/send"))
    }

    @Test
    fun aDefaultPortIsDroppedSoBothSpellingsShareOneToken() {
        assertEquals("https://push.example.com", pushServiceOrigin("https://push.example.com:443/send"))
        assertEquals("https://push.example.com:8443", pushServiceOrigin("https://push.example.com:8443"))
    }

    @Test
    fun aBracketedIpv6HostIsValidatedAndKeepsItsBrackets() {
        assertEquals("https://[2001:db8::1]", pushServiceOrigin("https://[2001:DB8::1]:443/send"))
        assertEquals("https://[::ffff:192.0.2.1]:8443", pushServiceOrigin("https://[::FFFF:192.0.2.1]:8443/x"))
    }

    @Test
    fun aMalformedEndpointThrowsRatherThanProducingAnOddAudience() {
        assertFailsWith<VapidJwtException>("a relative path is not an endpoint") {
            pushServiceOrigin("/fcm/send/abc")
        }
        assertFailsWith<VapidJwtException>("a bare host is not an endpoint") {
            pushServiceOrigin("api.push.apple.com/3/device/x")
        }
        assertFailsWith<VapidJwtException>("a non-http scheme is refused") {
            pushServiceOrigin("ftp://push.example.com/x")
        }
        assertFailsWith<VapidJwtException>("plain HTTP is refused") {
            pushServiceOrigin("http://push.example.com/x")
        }
        assertFailsWith<VapidJwtException>("an empty authority is refused") {
            pushServiceOrigin("https:///3/device/x")
        }
        assertFailsWith<VapidJwtException>("an empty host with a port is refused") {
            pushServiceOrigin("https://:443/x")
        }
        assertFailsWith<VapidJwtException>("userinfo is refused, not stripped") {
            pushServiceOrigin("https://user:pw@push.example.com/x")
        }
        assertFailsWith<VapidJwtException>("a header-splitting host is refused") {
            pushServiceOrigin("https://push.example.com\r\nX-Evil: 1/x")
        }
        assertFailsWith<VapidJwtException>("an empty string is refused") { pushServiceOrigin("") }
    }

    @Test
    fun invalidPortsAreRefusedInsteadOfBecomingUndeliverableRows() {
        for (endpoint in listOf(
            "https://push.example.com:/x",
            "https://push.example.com:abc/x",
            "https://push.example.com:+443/x",
            "https://push.example.com:-1/x",
            "https://push.example.com:0/x",
            "https://push.example.com:65536/x",
            "https://push.example.com:443:444/x",
        )) {
            assertFailsWith<VapidJwtException>(endpoint) { pushServiceOrigin(endpoint) }
        }
    }

    @Test
    fun malformedIpv6AuthoritiesAreRefused() {
        for (endpoint in listOf(
            "https://[]/x",
            "https://[2001:db8::1/x",
            "https://2001:db8::1/x",
            "https://[2001:db8::1]garbage/x",
            "https://[2001:db8::gg]/x",
            "https://[1:2:3:4:5:6:7:8:9]/x",
            "https://[1::2::3]/x",
            "https://[::ffff:999.1.1.1]/x",
            "https://[::ffff:192.168.001.1]/x",
        )) {
            assertFailsWith<VapidJwtException>(endpoint) { pushServiceOrigin(endpoint) }
        }
    }


    @Test
    fun theSubjectIsThePublicOriginWhenTheDaemonIsPublished() {
        assertEquals("https://kotgent.example.com", vapidSubject("https://kotgent.example.com"))
        assertEquals("https://kotgent.example.com", vapidSubject("https://kotgent.example.com/"))
        assertEquals("https://kotgent.example.com", vapidSubject("  https://Kotgent.Example.com  "))
    }

    @Test
    fun theSubjectFallsBackToTheMailtoWhenThereIsNoUsablePublicUrl() {
        assertEquals(VAPID_FALLBACK_SUBJECT, vapidSubject(null), "loopback-only daemon")
        assertEquals(VAPID_FALLBACK_SUBJECT, vapidSubject("   "), "a blank config value")
        assertEquals(VAPID_FALLBACK_SUBJECT, vapidSubject("http://127.0.0.1:8765"))
        assertEquals(VAPID_FALLBACK_SUBJECT, vapidSubject("not a url"))
        assertEquals("mailto:kotgent@localhost", VAPID_FALLBACK_SUBJECT)
    }


    @Test
    fun theSigningInputIsDeterministicAndCarriesTheExpectedClaims() {
        val input = vapidSigningInput(
            aud = "https://api.push.apple.com",
            exp = expectedExp,
            sub = "https://kotgent.example.com",
        )

        assertEquals("$headerSegment.$appleClaimsSegment", input, "header.claims, both base64url unpadded")
        assertEquals(input, vapidSigningInput("https://api.push.apple.com", expectedExp, "https://kotgent.example.com"))
    }

    @Test
    fun theSigningInputHasExactlyTwoUnpaddedBase64UrlSegments() {
        val input = vapidSigningInput("https://fcm.googleapis.com", expectedExp, VAPID_FALLBACK_SUBJECT)
        val segments = input.split(".")

        assertEquals(2, segments.size, "signing input is header.claims — the signature is appended later")
        assertEquals("$headerSegment.$googleClaimsSegment", input)
        for (segment in segments) {
            assertTrue(segment.none { it == '+' || it == '/' || it == '=' }, "base64url, unpadded: $segment")
        }
    }

    @Test
    fun aClaimValueThatWouldNeedJsonEscapingIsRefused() {
        assertFailsWith<VapidJwtException> {
            vapidSigningInput("""https://evil"," "x":"y""", expectedExp, VAPID_FALLBACK_SUBJECT)
        }
        assertFailsWith<VapidJwtException> {
            vapidSigningInput("https://api.push.apple.com", expectedExp, "mailto:a\\b@c")
        }
        assertFailsWith<VapidJwtException> {
            vapidSigningInput("https://api.push.apple.com", expectedExp, "")
        }
    }


    @Test
    fun theAuthorizationHeaderMatchesTheVapidShapeExactly() {
        val header = vapidAuthorizationHeader("aaa.bbb.ccc", "BKxPublicPoint")

        assertEquals("vapid t=aaa.bbb.ccc, k=BKxPublicPoint", header)
        assertTrue(header.startsWith("vapid t="), "scheme then the t parameter")
        assertTrue(header.contains(", k="), "one comma-space before k, as in RFC 8292 §3")
    }


    @Test
    fun aMintedTokenIsTheSigningInputPlusTheBase64UrlSignature() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            var signed: String? = null
            val cache = VapidTokenCache(
                subject = "https://kotgent.example.com",
                sign = { input -> signed = input; fakeSignature() },
                now = { fixedNow },
            )

            val jwt = cache.tokenFor(appleEndpoint)

            assertEquals("$headerSegment.$appleClaimsSegment", signed, "openssl signs header.claims, nothing else")
            assertEquals("$headerSegment.$appleClaimsSegment.$fakeSignatureSegment", jwt)
        }
    }

    @Test
    fun theSameOriginIsSignedOnceAndThenServedFromTheCache() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            var signCount = 0
            var clock = fixedNow
            val cache = VapidTokenCache(
                subject = VAPID_FALLBACK_SUBJECT,
                sign = { signCount++; fakeSignature() },
                now = { clock },
            )

            val first = cache.tokenFor(appleEndpoint)
            clock += 10L * 60 * 60 * 1000
            val second = cache.tokenFor("https://api.push.apple.com/3/device/a-different-device")

            assertEquals(first, second, "a second device on the same service reuses the token")
            assertEquals(1, signCount, "openssl ran once")
            assertEquals(1, cache.cachedOriginCount())
        }
    }

    @Test
    fun aTokenInsideTheRefreshWindowIsReSignedExactlyOnce() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            var signCount = 0
            var clock = fixedNow
            val cache = VapidTokenCache(
                subject = VAPID_FALLBACK_SUBJECT,
                sign = { signCount++; fakeSignature() },
                now = { clock },
            )

            val first = cache.tokenFor(googleEndpoint)
            clock += 11L * 60 * 60 * 1000 + 60_000
            val refreshed = cache.tokenFor(googleEndpoint)
            val reused = cache.tokenFor(googleEndpoint)

            assertNotEquals(first, refreshed, "a new exp means a new token")
            assertEquals(refreshed, reused, "and the fresh one is cached in turn")
            assertEquals(2, signCount, "exactly one re-sign, not one per call")
            assertEquals(1, cache.cachedOriginCount(), "the origin's entry was replaced, not duplicated")
        }
    }

    @Test
    fun eachPushServiceOriginGetsItsOwnToken() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val audiences = mutableListOf<String>()
            val cache = VapidTokenCache(
                subject = VAPID_FALLBACK_SUBJECT,
                sign = { input -> audiences += input; fakeSignature() },
                now = { fixedNow },
            )

            val apple = cache.tokenFor(appleEndpoint)
            val google = cache.tokenFor(googleEndpoint)

            assertNotEquals(apple, google, "Apple's token is worthless at Google")
            assertEquals("$headerSegment.$appleClaimsSegmentFallbackSub", audiences[0], "aud = Apple's origin")
            assertEquals("$headerSegment.$googleClaimsSegment", audiences[1], "aud = Google's origin")
            assertEquals(2, cache.cachedOriginCount())
        }
    }

    @Test
    fun aFailingSignerPropagatesAndCachesNothing() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            var fail = true
            val cache = VapidTokenCache(
                subject = VAPID_FALLBACK_SUBJECT,
                sign = { if (fail) throw VapidKeyException("no openssl") else fakeSignature() },
                now = { fixedNow },
            )

            assertFailsWith<VapidKeyException> { cache.tokenFor(appleEndpoint) }
            assertEquals(0, cache.cachedOriginCount(), "a half-built token is never stored")

            fail = false
            assertEquals("$headerSegment.$googleClaimsSegment.$fakeSignatureSegment", cache.tokenFor(googleEndpoint))
            assertEquals(1, cache.cachedOriginCount())
        }
    }

    @Test
    fun aMalformedEndpointFailsBeforeAnythingIsSigned() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            var signCount = 0
            val cache = VapidTokenCache(
                subject = VAPID_FALLBACK_SUBJECT,
                sign = { signCount++; fakeSignature() },
                now = { fixedNow },
            )

            assertFailsWith<VapidJwtException> { cache.tokenFor("not-an-endpoint") }
            assertEquals(0, signCount, "no openssl run for a row that cannot be pushed to anyway")
        }
    }

    @Test
    fun aRefreshWindowThatSwallowsTheTtlIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            VapidTokenCache(VAPID_FALLBACK_SUBJECT, { fakeSignature() }, { fixedNow }, ttlMillis = 1000, refreshBeforeMillis = 1000)
        }
        assertFailsWith<IllegalArgumentException> {
            VapidTokenCache(VAPID_FALLBACK_SUBJECT, { fakeSignature() }, { fixedNow }, ttlMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            VapidTokenCache(VAPID_FALLBACK_SUBJECT, { fakeSignature() }, { fixedNow }, refreshBeforeMillis = -1)
        }
    }

    @Test
    fun theDefaultTtlAndRefreshWindowAreTheDocumentedOnes() {
        assertEquals(12L * 60 * 60 * 1000, VAPID_TOKEN_TTL_MILLIS, "12h — well under RFC 8292's 24h cap")
        assertEquals(60L * 60 * 1000, VAPID_TOKEN_REFRESH_BEFORE_MILLIS)
    }
}

private const val TEST_TIMEOUT_MILLIS: Long = 20_000
