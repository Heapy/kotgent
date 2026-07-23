package io.kotgent.transport

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The authorization table (plan Task 3), exercised as the table it is.
 *
 * The verifiers are the REAL ones — [constantTimeEquals] for the bearer and [verifySessionCookie] for the
 * cookie — so a cookie in these tests is a genuinely signed `v1.<issuedAt>.<hmac>` value rather than a
 * stand-in string. That keeps the Task-2 format and the Task-3 rule honest about each other.
 *
 * The two cases worth naming up front, because everything else is a variation on them:
 *  - **GET + valid cookie + NO Origin → allow.** Browsers do not send `Origin` on a same-origin GET;
 *    requiring it on reads would refuse every page load of our own UI.
 *  - **any-method + cookie + a same-SITE but foreign Origin → 403.** `SameSite=Strict` reasons about the
 *    site (eTLD+1), so `qa.heapyhop.com` is same-site with `kotgent.heapyhop.com` and its fetch would carry
 *    our cookie. The `Origin` check is the only thing that separates them.
 */
class AuthorizationTest {

    private val publicUrl = "https://kotgent.heapyhop.com"
    private val token = "t".repeat(64)
    private val cookie = issueSessionCookie(token, 1_753_280_000_000L)
    private val bearer = "Bearer $token"

    /** How many times the bearer verifier ran — the host/origin gates must decide without consulting it. */
    private var tokenChecks = 0

    private fun decide(
        facts: RequestFacts,
        publicUrl: String? = this.publicUrl,
        loopbackOnly: Boolean = false,
    ): AuthDecision = authorize(
        facts = facts,
        publicUrl = publicUrl,
        loopbackOnly = loopbackOnly,
        verifyToken = { presented -> tokenChecks++; constantTimeEquals(presented, token) },
        verifyCookie = { value -> verifySessionCookie(token, value) },
    )

    // --- the Host allowlist -------------------------------------------------------------------------

    @Test
    fun aRequestUnderAForeignHostIsRefusedBeforeAnySecretIsExamined() {
        val decision = decide(RequestFacts(host = "evil.example.com", authHeader = bearer))
        assertDenied(HttpStatusCode.Forbidden, decision, "a foreign Host is refused")
        assertEquals(0, tokenChecks, "the host gate decides without consulting the token verifier")
        assertTrue("evil.example.com" in (decision as AuthDecision.Deny).reason, "the reason names the host")
    }

    @Test
    fun theConfiguredPublicHostIsAllowedAndOnlyWhenItIsConfigured() {
        val facts = RequestFacts(host = "kotgent.heapyhop.com", authHeader = bearer)
        assertAllowed(decide(facts), "the tunnel's hostname is in the allowlist")
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(facts, publicUrl = null),
            "with no public URL configured the daemon is loopback-only, so the same request is refused",
        )
    }

    @Test
    fun everyLoopbackSpellingIsAllowedOnAnyPortAndNothingElseIs() {
        val loopback = listOf(
            "127.0.0.1", "127.0.0.1:27508", "127.0.0.1:0", "localhost", "LocalHost:65535", "[::1]:27508", "::1",
        )
        for (host in loopback) {
            assertTrue(isLoopbackHost(host), "$host is this machine")
            assertTrue(isAllowedHost(host, null), "$host is allowed even with no public URL")
        }
        for (host in listOf("127.0.0.2", "localhost.evil.com", "evil-localhost", "127.0.0.1.evil.com", "", "   ")) {
            assertFalse(isLoopbackHost(host), "$host is not loopback")
            assertFalse(isAllowedHost(host, publicUrl), "$host is not in the allowlist either")
        }
    }

    @Test
    fun aMissingHostIsRefusedRatherThanDefaulted() {
        assertFalse(isAllowedHost(null, publicUrl), "HTTP/1.1 requires Host; we do not invent one")
        assertDenied(HttpStatusCode.Forbidden, decide(RequestFacts(host = null, authHeader = bearer)), "no Host")
    }

    // --- loopback-only routes -----------------------------------------------------------------------

    @Test
    fun aLoopbackOnlyRouteRefusesTheOtherwiseAllowedPublicHost() {
        val facts = RequestFacts(host = "kotgent.heapyhop.com", authHeader = bearer, method = HttpMethod.Post)
        assertAllowed(decide(facts), "the public host reaches the ordinary surface")
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(facts, loopbackOnly = true),
            "…but not the hook ingress / ticket issuance, which are never published through the tunnel",
        )
        assertAllowed(
            decide(facts.copy(host = "127.0.0.1:27508"), loopbackOnly = true),
            "the same route from loopback is served",
        )
    }

    // --- the Origin rule ----------------------------------------------------------------------------

    @Test
    fun aGetWithAValidCookieAndNoOriginIsAllowed() {
        assertAllowed(
            decide(RequestFacts(host = "kotgent.heapyhop.com", cookie = cookie)),
            "browsers send no Origin on a same-origin GET — requiring one would refuse every UI page load",
        )
    }

    @Test
    fun aGetWithAValidCookieAndASameSiteButForeignOriginIsRefused() {
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(RequestFacts(host = "kotgent.heapyhop.com", origin = "https://qa.heapyhop.com", cookie = cookie)),
            "SameSite is site-scoped, so a sibling subdomain would otherwise ride our cookie",
        )
    }

    @Test
    fun aPostAuthenticatedByCookieNeedsAnOriginAndItMustMatch() {
        val post = RequestFacts(host = "kotgent.heapyhop.com", cookie = cookie, method = HttpMethod.Post)
        assertDenied(HttpStatusCode.Forbidden, decide(post), "no Origin on a cookie-authenticated POST")
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(post.copy(origin = "https://qa.heapyhop.com")),
            "a foreign Origin on a POST",
        )
        assertAllowed(decide(post.copy(origin = publicUrl)), "our own origin on a POST is served")
        assertAllowed(
            decide(post.copy(host = "127.0.0.1:27508", origin = "http://127.0.0.1:27508")),
            "and so is the loopback pair the local browser sends",
        )
    }

    @Test
    fun aWebSocketHandshakeIsHeldToThePostRuleEvenThoughItIsAGet() {
        val handshake = RequestFacts(host = "kotgent.heapyhop.com", cookie = cookie, isWebSocket = true)
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(handshake),
            "a handshake is the one browser request that skips CORS, so its Origin is mandatory",
        )
        assertAllowed(decide(handshake.copy(origin = publicUrl)), "with a matching Origin it upgrades")
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(handshake.copy(origin = "https://qa.heapyhop.com")),
            "a foreign Origin cannot open a socket either",
        )
    }

    @Test
    fun theLoopbackOriginMatchesOnAnyPortAndBothSchemes() {
        val loopback = listOf("http://127.0.0.1:27508", "http://127.0.0.1:0", "http://localhost:5173", "https://[::1]")
        for (origin in loopback) {
            assertTrue(isAllowedOrigin(origin, publicUrl), "$origin is a loopback origin")
        }
        assertTrue(
            isAllowedOrigin("http://127.0.0.1:53412", null),
            "the harnesses bind port 0, so the real port is unknowable when the rule is written",
        )
    }

    @Test
    fun anUnparseableOriginIsNeverTrusted() {
        val malformed = listOf(
            "null", // what a sandboxed iframe / file:// page sends
            "",
            "kotgent.heapyhop.com", // no scheme
            "file://",
            "https://",
            "ftp://kotgent.heapyhop.com",
            "https://kotgent.heapyhop.com/auth", // an origin has no path
            "https://kotgent.heapyhop.com?x=1",
            "https://user@kotgent.heapyhop.com",
        )
        for (origin in malformed) {
            assertFalse(isAllowedOrigin(origin, publicUrl), "'$origin' is not a well-formed allowed origin")
        }
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(RequestFacts(host = "kotgent.heapyhop.com", origin = "null", cookie = cookie)),
            "the literal 'null' a sandboxed iframe sends is refused, not treated as absent",
        )
    }

    @Test
    fun theAllowlistIsLoopbackPlusTheConfiguredOrigin() {
        val allowed = allowedOrigins(publicUrl)
        assertTrue(publicUrl in allowed, "the configured origin is in it")
        assertTrue("http://127.0.0.1" in allowed && "http://localhost" in allowed && "http://[::1]" in allowed)
        assertEquals(
            allowed,
            allowedOrigins("$publicUrl/"),
            "a trailing slash in the configured URL is normalised away rather than silently failing to match",
        )
        assertEquals(
            LOOPBACK_HOSTNAMES.size * 2,
            allowedOrigins(null).size,
            "with no public URL only the loopback forms (http + https) remain",
        )
    }

    // --- the credentials themselves -----------------------------------------------------------------

    @Test
    fun aValidBearerNeedsNoOriginOnAnyMethod() {
        for (method in listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete, HttpMethod.Put)) {
            assertAllowed(
                decide(RequestFacts(host = "127.0.0.1:27508", authHeader = bearer, method = method)),
                "a Bearer is not a browser: ${method.value} without an Origin is served",
            )
        }
        assertAllowed(
            decide(RequestFacts(host = "127.0.0.1:27508", authHeader = bearer, isWebSocket = true)),
            "including the CLI's terminal handshake (kotgent attach)",
        )
        assertAllowed(
            decide(RequestFacts(host = "127.0.0.1:27508", authHeader = "bearer $token")),
            "the scheme match is case-insensitive",
        )
    }

    @Test
    fun aBearerThatDoesNotVerifyCannotRescueACookieMissingItsOrigin() {
        assertDenied(
            HttpStatusCode.Forbidden,
            decide(
                RequestFacts(
                    host = "kotgent.heapyhop.com",
                    authHeader = "Bearer ${"x".repeat(64)}",
                    cookie = cookie,
                    method = HttpMethod.Post,
                ),
            ),
            "the credential that would actually authenticate is the cookie, so the Origin rule still applies",
        )
    }

    @Test
    fun nothingPresentedIsUnauthorizedNotForbidden() {
        assertDenied(
            HttpStatusCode.Unauthorized,
            decide(RequestFacts(host = "127.0.0.1:27508")),
            "401 means 'authenticate'; 403 is reserved for 'not from here'",
        )
    }

    @Test
    fun aWrongSecretIsUnauthorized() {
        assertDenied(
            HttpStatusCode.Unauthorized,
            decide(RequestFacts(host = "127.0.0.1:27508", authHeader = "Bearer ${"x".repeat(64)}")),
            "a wrong bearer",
        )
        assertDenied(
            HttpStatusCode.Unauthorized,
            decide(RequestFacts(host = "127.0.0.1:27508", cookie = issueSessionCookie("another-token", 1L))),
            "a cookie signed with a different master token — i.e. one that survived a rotation",
        )
        assertDenied(
            HttpStatusCode.Unauthorized,
            decide(RequestFacts(host = "127.0.0.1:27508", authHeader = "Basic $token", cookie = "garbage")),
            "a non-Bearer scheme and a malformed cookie are simply no credential",
        )
    }

    @Test
    fun anEmptyBearerOrCookieCountsAsAbsent() {
        assertEquals(null, bearerToken("Bearer   "), "an empty token is not a token")
        assertEquals(null, bearerToken("Bearer"), "the prefix alone is not a credential")
        assertEquals(null, bearerToken(null))
        assertEquals(token, bearerToken(" Bearer $token".trim()), "the value is trimmed")
        val blankCookie =
            RequestFacts(host = "kotgent.heapyhop.com", authHeader = bearer, cookie = "", method = HttpMethod.Post)
        assertAllowed(
            decide(blankCookie),
            "a blank cookie header is absent, so the Bearer path applies and needs no Origin",
        )
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun assertAllowed(decision: AuthDecision, message: String) {
        if (decision is AuthDecision.Deny) {
            fail("$message — expected Allow, got ${decision.status}: ${decision.reason}")
        }
    }

    private fun assertDenied(status: HttpStatusCode, decision: AuthDecision, message: String) {
        val deny = decision as? AuthDecision.Deny ?: fail("$message — expected $status, got Allow")
        assertEquals(status, deny.status, "$message (reason: ${deny.reason})")
        assertTrue(deny.reason.isNotBlank(), "$message — a denial carries a reason for the daemon's log")
    }
}
