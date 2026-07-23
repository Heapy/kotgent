package io.kotgent.transport

import io.kotgent.crypto.hmacSha256Hex
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall

/**
 * The browser's key: a **stateless** session cookie derived from the master token
 * (`~/.kotgent/token`), not a row in a session table.
 *
 * ## Shape
 * `kotgent_session=v1.<issuedAtMillis>.<hmacHex>` where
 * `hmac = HMAC-SHA256(key = master token as UTF-8, message = "v1|" + issuedAtMillis)`.
 *
 * Verification is a RECOMPUTATION plus [constantTimeEquals] — the daemon stores nothing per browser, so
 * there is no session table, no schema migration, and nothing to lose across a restart. `issuedAt` is not
 * an expiry (the cookie is deliberately long-lived — see [SESSION_COOKIE_MAX_AGE_SECONDS]); it is there so
 * two devices that log in at different moments carry DIFFERENT cookie values, which keeps one device's
 * value from being a usable fingerprint of another's.
 *
 * ## Revocation
 * "Log every device out" is exactly `kotgent token rotate`: the HMAC key changes, so every cookie ever
 * issued stops verifying at once. That is the whole revocation story, and it needs no state.
 *
 * ## Why a separate key at all
 * The master token is the MACHINE's key — the hooks, the CLI, and ticket issuance all present it as a
 * `Bearer`. Handing that same value to a browser (in a URL fragment, as before) leaks it into history,
 * bookmark sync and, behind a tunnel, into somebody else's logs. The cookie is a derived, browser-scoped
 * key: `HttpOnly` (script cannot read it), `SameSite=Strict`, and it never appears in a URL.
 */

/** Cookie name carrying the browser's derived session key. */
const val SESSION_COOKIE_NAME: String = "kotgent_session"

/** Version tag of the cookie format; also the first field of the signed message. */
private const val SESSION_COOKIE_VERSION: String = "v1"

/** Field separator inside the cookie VALUE (`v1.<issuedAt>.<hmac>`). */
private const val FIELD_SEPARATOR: Char = '.'

/** Separator inside the SIGNED message — deliberately not [FIELD_SEPARATOR], see [signedMessage]. */
private const val MESSAGE_SEPARATOR: Char = '|'

/** How many `.`-separated fields a well-formed cookie value has. */
private const val FIELD_COUNT: Int = 3

/**
 * `Max-Age` of the session cookie: ten years, i.e. "until the token is rotated".
 *
 * An explicit `Max-Age` is REQUIRED, not cosmetic. Without one the cookie is a session cookie, and mobile
 * Safari drops those when the browser is restarted — which is precisely the phone-login case this whole
 * flow exists for. Expiry is not the security control here; rotation is.
 */
const val SESSION_COOKIE_MAX_AGE_SECONDS: Long = 10L * 365 * 24 * 60 * 60

/**
 * Mint a cookie value for [token] stamped with [issuedAt] (epoch millis).
 *
 * Anyone can read [issuedAt] out of the value — it is not a secret, and it is not meant to be. The MAC
 * over it is what makes the value unforgeable without the master token.
 */
fun issueSessionCookie(token: String, issuedAt: Long): String {
    val mac = hmacSha256Hex(token, signedMessage(issuedAt.toString()))
    return "$SESSION_COOKIE_VERSION$FIELD_SEPARATOR$issuedAt$FIELD_SEPARATOR$mac"
}

/**
 * Does [value] verify against [token]? Fail-closed on everything: `null`, blank, wrong field count, an
 * unknown version tag, a non-numeric timestamp, a bad MAC — and on an EMPTY [token], because an empty
 * HMAC key is still a valid key, so a blank/unreadable master token would otherwise let anyone mint a
 * cookie that verifies.
 *
 * The MAC comparison goes through [constantTimeEquals] so a forgery attempt cannot learn how many leading
 * hex characters it got right from response timing.
 */
fun verifySessionCookie(token: String, value: String?): Boolean {
    if (token.isEmpty() || value.isNullOrEmpty()) return false
    val fields = value.split(FIELD_SEPARATOR)
    if (fields.size != FIELD_COUNT) return false
    val (version, issuedAt, mac) = fields
    if (version != SESSION_COOKIE_VERSION) return false
    // Reject anything that is not a plain decimal timestamp: it keeps the signed message space canonical,
    // so "the thing we verified" and "the thing a later reader parses" cannot diverge.
    if (issuedAt.isEmpty() || !issuedAt.all { it in '0'..'9' }) return false
    return constantTimeEquals(mac, hmacSha256Hex(token, signedMessage(issuedAt)))
}

/**
 * The bytes actually signed: `"v1|<issuedAt>"`.
 *
 * The separator differs from the one in the cookie value on purpose. The version tag is signed too, so a
 * future `v2` cookie can never be replayed as a `v1` one by re-cutting the fields.
 */
private fun signedMessage(issuedAt: String): String = "$SESSION_COOKIE_VERSION$MESSAGE_SEPARATOR$issuedAt"

// --- Ktor core cookie API ------------------------------------------------------------------------

/**
 * The session cookie the client sent, or `null`. Reads through Ktor's own [io.ktor.server.request.RequestCookies]
 * (present in the `macosArm64` klib — it is core, not a plugin), so kotgent parses no `Cookie:` header
 * itself.
 *
 * [CookieEncoding.RAW]: the value is hex and dots, so there is nothing to escape, and reading the wire
 * bytes verbatim keeps "what was signed" and "what was transmitted" the same string.
 */
fun ApplicationCall.sessionCookie(): String? = request.cookies[SESSION_COOKIE_NAME, CookieEncoding.RAW]

/**
 * Attach [value] as the session cookie on this response, via Ktor's own
 * [io.ktor.server.response.ResponseCookies] — no hand-rolled `Set-Cookie` serializer.
 *
 * `HttpOnly` keeps the value out of reach of page script (an XSS in the UI cannot exfiltrate it),
 * `SameSite=Strict` stops another site from navigating the browser into an authenticated request, and
 * `Path=/` covers the API, both WebSockets and the SPA in one cookie.
 *
 * [secure] is decided by the CALLER from the request's own `Host` (public host ⇒ `true`), never from
 * `X-Forwarded-Proto`: a local client can forge that header, and a `Secure` cookie handed to a browser on
 * `http://127.0.0.1` would be silently discarded — locking the operator out of their own daemon.
 */
fun ApplicationCall.setSessionCookie(value: String, secure: Boolean) {
    response.cookies.append(
        name = SESSION_COOKIE_NAME,
        value = value,
        encoding = CookieEncoding.RAW,
        maxAge = SESSION_COOKIE_MAX_AGE_SECONDS,
        path = "/",
        secure = secure,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )
}
