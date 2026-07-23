package io.kotgent.transport

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The ONE authorization rule of the daemon, as a pure function over the few request facts it is allowed
 * to look at ([RequestFacts] → [AuthDecision]). The Ktor interceptor that calls it (Task 7) does nothing
 * but collect the facts and turn a [AuthDecision.Deny] into a response — every decision lives here, where
 * it is testable as a table.
 *
 * ## Why a `Host` allowlist and an `Origin` check appear together with the cookie
 * The browser's key is now a cookie ([SESSION_COOKIE_NAME]), and a cookie is attached by the BROWSER, not
 * by the page — so "who may talk to the daemon" stops being "who knows the secret" and becomes "which page
 * can make the browser send it". `SameSite=Strict` is not enough on its own: SameSite reasons about the
 * *site* (eTLD+1), so with the daemon published at `kotgent.heapyhop.com`, a page on `qa.heapyhop.com` or
 * `sql.heapyhop.com` is same-site and its `fetch` would carry the session cookie. The `Origin` check is
 * what tells those apart; the `Host` allowlist is what keeps a request that arrived under some other
 * hostname (DNS rebinding, a stray tunnel rule) from being served at all.
 *
 * ## Why `Origin` is required only on non-GET and on WebSocket handshakes
 * Browsers do NOT send `Origin` on a same-origin GET, so requiring it on reads would refuse every page load
 * of our own UI. Nothing is lost: a cross-site `fetch` is by definition a CORS request and therefore ALWAYS
 * carries `Origin`; every state change in kotgent is a POST; and the one browser channel that bypasses CORS
 * — the WebSocket handshake — carries `Origin` unconditionally. So "`Origin` must be present on non-GET and
 * on WS, and must match whenever it is present at all" covers exactly the requests a foreign page can make.
 *
 * A `Bearer` never needs an `Origin`: it is not a browser (hooks, the CLI, `kotgent attach`), it cannot be
 * attached ambiently by a third-party page the way a cookie can, and demanding a header a curl user has no
 * reason to send would break the whole non-browser surface.
 *
 * ## Loopback-only routes
 * The hook ingress and ticket issuance are additionally restricted to a loopback `Host` ([authorize]'s
 * `loopbackOnly`): only the browser-facing surface is published through the tunnel. Note this is a `Host`
 * check, not a peer-address check — the daemon binds `127.0.0.1` and cloudflared connects to it from
 * localhost, so the peer address cannot distinguish "the tunnel" from "a local client"; the `Host` header
 * the tunnel forwards can.
 */

/**
 * Everything [authorize] is allowed to see. Deliberately a plain data class of already-extracted strings
 * rather than an `ApplicationCall`: the rule stays host-free and table-testable, and the Ktor edge that
 * fills it in (Task 7) stays a handful of accessor calls.
 *
 * @param host the request's `Host` header (may carry a port — every comparison here ignores it).
 * @param origin the `Origin` header, or `null` when the browser did not send one (same-origin GET) or the
 *   client is not a browser at all.
 * @param authHeader the raw `Authorization` header; the `Bearer ` prefix is parsed by [bearerToken].
 * @param cookie the value of the session cookie ([SESSION_COOKIE_NAME]), or `null`.
 * @param isWebSocket whether this is a WebSocket handshake — detected from `Sec-WebSocket-Key`, never by
 *   matching a path, so a future socket route cannot silently miss the stricter `Origin` rule.
 */
data class RequestFacts(
    val host: String?,
    val origin: String? = null,
    val authHeader: String? = null,
    val cookie: String? = null,
    val method: HttpMethod = HttpMethod.Get,
    val isWebSocket: Boolean = false,
)

/**
 * The outcome of [authorize]: serve the request, or refuse it with [AuthDecision.Deny.status].
 *
 * [AuthDecision.Deny.reason] is for the daemon's own logs and for tests — it names the host or origin that
 * failed. The interceptor answers the client with a generic body instead: telling a prober which of the
 * three gates it tripped, and with what value, is free reconnaissance.
 */
sealed interface AuthDecision {
    data object Allow : AuthDecision

    data class Deny(val status: HttpStatusCode, val reason: String) : AuthDecision
}

/** Host names treated as "this machine". Compared against the `Host` header with the port stripped. */
val LOOPBACK_HOSTNAMES: Set<String> = setOf("127.0.0.1", "localhost", "[::1]")

/**
 * Is [host] (a `Host` header or an origin's authority, with or without a port) this machine?
 *
 * The port is IGNORED on purpose. Both test harnesses bind `port = 0`, so the real port only exists after
 * `start()` — a rule that had to name the port could not be written before the server is up, and would then
 * differ between tests and production. Nothing is weakened by ignoring it: the daemon listens on exactly
 * one port, so a `Host` naming another port never reaches a different service through us.
 *
 * Only the three canonical spellings count ([LOOPBACK_HOSTNAMES], plus an unbracketed `::1`). The rest of
 * `127.0.0.0/8` is deliberately out: the daemon binds `127.0.0.1`, so no request can legitimately arrive
 * under `127.0.0.2`, and a narrower allowlist is one less shape to reason about.
 */
fun isLoopbackHost(host: String): Boolean = canonicalHostname(host) in LOOPBACK_HOSTNAMES

/**
 * The origins a browser may make an authenticated request from: every loopback form (any port — see
 * [isLoopbackHost]) plus the configured [publicUrl]'s own origin, if one is configured.
 *
 * Loopback entries are stored WITHOUT a port and candidate origins are canonicalised the same way
 * ([canonicalOrigin]), which is how "any port" survives a plain set membership test. Both `http` and
 * `https` loopback forms are listed: a page served from this machine is already inside the trust boundary
 * (it runs as a user who can read `~/.kotgent/token` outright), so the scheme it used is not the control.
 */
fun allowedOrigins(publicUrl: String?): Set<String> = buildSet {
    for (name in LOOPBACK_HOSTNAMES) {
        add("http://$name")
        add("https://$name")
    }
    publicUrl?.let(::canonicalOrigin)?.let(::add)
}

/**
 * May a browser at [origin] make an authenticated request when the daemon is published at [publicUrl]?
 *
 * Anything that is not a well-formed `scheme://host[:port]` — most notably the literal `null` that browsers
 * send from sandboxed iframes and `file://` pages — is refused: an unparseable origin is not evidence of a
 * trusted one.
 */
fun isAllowedOrigin(origin: String, publicUrl: String?): Boolean {
    val canonical = canonicalOrigin(origin) ?: return false
    return canonical in allowedOrigins(publicUrl)
}

/**
 * May a request that arrived under this `Host` be served at all? Loopback always; the configured
 * [publicUrl]'s hostname when one is configured (its port is ignored, exactly as for loopback).
 *
 * A missing or blank `Host` is refused rather than defaulted — HTTP/1.1 requires the header, and inventing
 * a host for a request that omitted it would be inventing the answer to the question being asked.
 */
fun isAllowedHost(host: String?, publicUrl: String?): Boolean {
    val candidate = host?.trim().orEmpty()
    if (candidate.isEmpty()) return false
    if (isLoopbackHost(candidate)) return true
    val publicHost = publicUrl?.let(::originAuthority)?.let(::canonicalHostname) ?: return false
    return canonicalHostname(candidate) == publicHost
}

/** The token from an `Authorization: Bearer <token>` header (scheme match is case-insensitive), or `null`. */
fun bearerToken(authHeader: String?): String? {
    if (authHeader == null) return null
    if (!authHeader.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
    return authHeader.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
}

/**
 * Decide whether [facts] may be served, in exactly the order the plan's table states:
 *
 * ```
 * Host not in the allowlist                                     → 403
 * route is loopback-only and Host is not loopback               → 403
 * Origin present and not in the allowlist                       → 403
 * (non-GET/HEAD or WS handshake) and no Origin, credential=cookie → 403
 * Bearer valid                                                  → allow
 * cookie valid                                                  → allow
 * otherwise                                                     → 401
 * ```
 *
 * The order is the point. The `Host` and `Origin` gates run BEFORE any secret is examined, so a request
 * from a hostname or a page we do not serve is refused without the verifiers ever being consulted — and a
 * `403` there says "not from here", never "wrong password", so it leaks nothing about the secret.
 *
 * "credential = cookie" means the request did not authenticate as a `Bearer`, i.e. the cookie is what would
 * carry it. That is the ambient credential a foreign page can make the browser attach, and the only one the
 * `Origin` requirement needs to cover.
 *
 * @param publicUrl the configured public origin (`https://kotgent.heapyhop.com`), or `null` when the daemon
 *   is loopback-only — in which case nothing but loopback is an allowed host or origin.
 * @param loopbackOnly `true` for the routes that are never published through the tunnel (hook ingress,
 *   ticket issuance, token rotation).
 * @param verifyToken constant-time comparison against the current master token (a provider, not a captured
 *   string — see Task 5, so a rotation takes effect immediately).
 * @param verifyCookie [verifySessionCookie] against that same current token.
 */
fun authorize(
    facts: RequestFacts,
    publicUrl: String?,
    loopbackOnly: Boolean = false,
    verifyToken: (String) -> Boolean,
    verifyCookie: (String) -> Boolean,
): AuthDecision {
    val host = facts.host?.trim().orEmpty()
    if (!isAllowedHost(host, publicUrl)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "host '$host' is not in the allowlist")
    }
    if (loopbackOnly && !isLoopbackHost(host)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "host '$host' is not loopback; this route is local-only")
    }

    val origin = facts.origin?.trim()?.ifEmpty { null }
    if (origin != null && !isAllowedOrigin(origin, publicUrl)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "origin '$origin' is not in the allowlist")
    }

    val cookie = facts.cookie?.trim()?.ifEmpty { null }
    val bearer = bearerToken(facts.authHeader)
    val bearerOk = bearer != null && verifyToken(bearer)

    if (!bearerOk && cookie != null && origin == null && requiresOrigin(facts)) {
        return AuthDecision.Deny(
            HttpStatusCode.Forbidden,
            "a ${if (facts.isWebSocket) "WebSocket handshake" else facts.method.value} authenticated by cookie " +
                "must carry an Origin header",
        )
    }

    if (bearerOk) return AuthDecision.Allow
    if (cookie != null && verifyCookie(cookie)) return AuthDecision.Allow
    return AuthDecision.Deny(HttpStatusCode.Unauthorized, "no valid credential presented")
}

/** `Authorization` scheme prefix, including its separating space. */
private const val BEARER_PREFIX: String = "Bearer "

/**
 * Must this request carry an `Origin` for a cookie to be accepted? Everything that is not a plain read, plus
 * every WebSocket handshake — a handshake is a `GET`, but it is also the one browser request that reaches us
 * without a CORS preflight, so it is treated as state-changing here.
 */
private fun requiresOrigin(facts: RequestFacts): Boolean =
    facts.isWebSocket || (facts.method != HttpMethod.Get && facts.method != HttpMethod.Head)

/**
 * A host name with the port removed and lower-cased, with a bare `::1` normalised to its bracketed form.
 * Handles the three authority shapes that occur: `host`, `host:port`, and `[v6]:port` (the brackets are
 * kept, since that is how an IPv6 host appears in both a `Host` header and an origin).
 */
private fun canonicalHostname(host: String): String {
    val trimmed = host.trim()
    val name = when {
        trimmed.startsWith("[") -> trimmed.indexOf(']').let { if (it < 0) trimmed else trimmed.substring(0, it + 1) }
        // An unbracketed authority holding more than one colon cannot be `host:port` — it is a bare IPv6.
        trimmed.count { it == ':' } > 1 -> trimmed
        else -> trimmed.substringBefore(':')
    }
    val lower = name.lowercase()
    return if (lower == "::1") "[::1]" else lower
}

/**
 * `scheme://authority` of [value], lower-cased, or `null` if it is not one. An origin is a scheme, a host
 * and an optional port and NOTHING else, so a value carrying a path, a query, a fragment or userinfo is
 * rejected — only a trailing `/` is tolerated, because that is how a `publicUrl` tends to be typed.
 */
private fun canonicalOrigin(value: String): String? {
    val authority = originAuthority(value) ?: return null
    val scheme = value.substringBefore("://").lowercase()
    val hostname = canonicalHostname(authority)
    // Loopback is stored port-less (see [isLoopbackHost]); a public origin keeps its port, where an
    // explicit port really is part of the origin's identity.
    return if (hostname in LOOPBACK_HOSTNAMES) "$scheme://$hostname" else "$scheme://$authority"
}

/** The lower-cased authority (`host[:port]`) of an `http(s)://…` origin, or `null` if [value] is not one. */
private fun originAuthority(value: String): String? {
    val separator = value.indexOf("://")
    if (separator <= 0) return null
    val scheme = value.substring(0, separator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = value.substring(separator + 3).trimEnd('/').lowercase()
    if (authority.isEmpty()) return null
    if (authority.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) return null
    return authority
}
