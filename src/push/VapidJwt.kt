package io.kotgent.push

import io.kotgent.crypto.base64Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The VAPID (RFC 8292) credential the daemon puts on every push request: a short-lived ES256 JWT bound to
 * ONE push-service origin, plus the `Authorization: vapid t=…, k=…` header that carries it.
 *
 * ## What a push service checks
 * A payload-less push (RFC 8030) has nothing in it but headers, so the JWT is the entire proof that the
 * request came from the application server the browser subscribed to. Apple and Google both verify three
 * things: the ES256 signature against the `k=` public key, that `k=` is the key the subscription was
 * created with, and that the `aud` claim is *their* origin — a token minted for Apple is worthless at
 * Google, which is why [VapidTokenCache] is keyed by [pushServiceOrigin] rather than holding one token.
 *
 * ## Why everything here is pure
 * The only impure step in producing a VAPID header is the ES256 signature itself, and on Kotlin/Native
 * that means shelling out to `openssl` (see [VapidKey] on why). So the split is: this file owns the whole
 * *format* — claim shape, base64url segments, the header string, the refresh policy — as pure functions
 * over an injected clock and an injected signing lambda, and `OpensslVapidSigner` owns the one call that
 * spawns a process. The result is that the part that silently breaks a push (a mis-shaped claim set, an
 * `aud` with a stray path, a token that expires while in use) is unit-testable without openssl.
 *
 * ## Why the origin parser is local
 * `src/transport/Authorization.kt` has its own origin canonicaliser, but it is private and its rules are
 * deliberately browser-shaped — loopback origins are stored port-less, and `http://` is a first-class
 * citizen. Neither is right for a push endpoint, where the value is an `https://` URL from a third party
 * and the `aud` must be its literal origin. Duplicating ~15 lines is cheaper than coupling the push
 * package to the transport's browser-allowlist semantics.
 */

/** The fixed JWS header for VAPID: ES256, the only algorithm RFC 8292 allows. */
const val VAPID_JWT_HEADER_JSON: String = """{"typ":"JWT","alg":"ES256"}"""

/**
 * How long a minted token claims to be valid: 12 hours.
 *
 * RFC 8292 §2 caps `exp` at 24 hours from issuance and push services enforce it. Half of that leaves room
 * for a clock skewed against the service's, while still being long enough that openssl runs roughly twice
 * a day rather than once per notification.
 */
const val VAPID_TOKEN_TTL_MILLIS: Long = 12L * 60 * 60 * 1000

/**
 * How much life must remain before a cached token is re-signed: one hour.
 *
 * Without it a token would be handed out at `exp - 1ms` and expire in flight, which a push service answers
 * with an opaque `401`. An hour is far more than any request needs and costs one extra openssl run a day.
 */
const val VAPID_TOKEN_REFRESH_BEFORE_MILLIS: Long = 60L * 60 * 1000

/**
 * The `sub` claim when the daemon has no public URL: a `mailto:` is what RFC 8292 asks for, and this one
 * is deliberately unroutable — a loopback-only daemon never reaches Apple's validator anyway, and inventing
 * a real address the operator did not consent to publishing would be worse.
 */
const val VAPID_FALLBACK_SUBJECT: String = "mailto:kotgent@localhost"

/** The `Authorization` scheme name RFC 8292 §3 defines for a VAPID credential. */
const val VAPID_AUTH_SCHEME: String = "vapid"

/** A malformed push endpoint, or a claim value that cannot be embedded in JSON. */
class VapidJwtException(message: String) : IllegalArgumentException(message)

/**
 * The origin of push endpoint [endpoint] — `scheme://host[:port]`, lower-cased, with a default port
 * dropped. This is the JWT's `aud`, and the key [VapidTokenCache] stores tokens under.
 *
 * Everything after the authority is discarded: the path of a push endpoint IS the device's subscription
 * identity, so leaving it in `aud` would both leak it into every token and guarantee rejection. Userinfo,
 * whitespace and control characters are refused rather than stripped — a push endpoint never has them, and
 * a value that odd is a corrupt row, not something to guess at.
 *
 * @throws VapidJwtException if [endpoint] is not an absolute `http(s)://` URL with a host.
 */
fun pushServiceOrigin(endpoint: String): String {
    val value = endpoint.trim()
    val separator = value.indexOf("://")
    if (separator <= 0) {
        throw VapidJwtException("push endpoint '$value' is not an absolute URL")
    }
    val scheme = value.substring(0, separator).lowercase()
    if (scheme != "https" && scheme != "http") {
        throw VapidJwtException("push endpoint scheme must be http(s), got '$scheme'")
    }
    val authority = value.substring(separator + 3)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .lowercase()
    // `:443` as an authority is empty-host-with-a-port; it survives the emptiness check but would leave
    // `https://` as the aud once the default port is dropped.
    if (authority.isEmpty() || authority.startsWith(":")) {
        throw VapidJwtException("push endpoint '$value' has no host")
    }
    if (authority.contains('@')) {
        throw VapidJwtException("push endpoint '$value' carries userinfo")
    }
    if (authority.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        throw VapidJwtException("push endpoint '$value' has whitespace or control characters in its host")
    }
    return "$scheme://${withoutDefaultPort(scheme, authority)}"
}

/**
 * The `sub` claim: the origin of [publicUrl] when the daemon is published over `https`, else
 * [VAPID_FALLBACK_SUBJECT].
 *
 * `sub` tells the push service who to contact about a misbehaving application server, and Apple's
 * validator is the strict one — it accepts an `https:` URL without argument. An `http://` public URL (only
 * ever a loopback one here, since there is no TLS on native) is NOT used: it would be a subject the service
 * cannot reach, so the honest `mailto:` fallback is better. Anything unparseable falls back the same way
 * rather than throwing — a bad `publicUrl` must not be able to disable push.
 */
fun vapidSubject(publicUrl: String?): String {
    val value = publicUrl?.trim().orEmpty()
    if (value.isEmpty()) return VAPID_FALLBACK_SUBJECT
    val origin = try {
        pushServiceOrigin(value)
    } catch (_: VapidJwtException) {
        return VAPID_FALLBACK_SUBJECT
    }
    return if (origin.startsWith("https://")) origin else VAPID_FALLBACK_SUBJECT
}

/**
 * The JWS signing input for a VAPID token: `base64url(header) + "." + base64url(claims)`, which is both
 * what gets signed and the first two segments of the finished JWT.
 *
 * The claims are built as a literal rather than through kotlinx.serialization so the byte sequence is
 * fixed: a JWT is verified over the exact bytes that were signed, so key order and spacing are part of the
 * contract, not a formatting preference.
 *
 * @param aud the push service origin ([pushServiceOrigin]).
 * @param exp expiry as a JWT NumericDate — epoch **seconds**, not millis.
 * @param sub the contact subject ([vapidSubject]).
 * @throws VapidJwtException if [aud] or [sub] contains a character that would have to be JSON-escaped.
 */
fun vapidSigningInput(aud: String, exp: Long, sub: String): String {
    requireJsonSafe(aud, "aud")
    requireJsonSafe(sub, "sub")
    val header = base64Url(VAPID_JWT_HEADER_JSON.encodeToByteArray())
    val claims = base64Url("""{"aud":"$aud","exp":$exp,"sub":"$sub"}""".encodeToByteArray())
    return "$header.$claims"
}

/**
 * The finished `Authorization` header value: `vapid t=<jwt>, k=<publicKey>`.
 *
 * [publicKey] is the base64url uncompressed P-256 point ([VapidKey.publicKeyBase64Url]) — the push service
 * checks it against the key the subscription was created with, so a mismatch fails every send at once.
 * The single space after the comma is the RFC 8292 §3 example's form and is what every push service is
 * tested against.
 */
fun vapidAuthorizationHeader(jwt: String, publicKey: String): String =
    "$VAPID_AUTH_SCHEME t=$jwt, k=$publicKey"

/**
 * One live VAPID token per push-service origin, re-signed only when it is close to expiring.
 *
 * Signing runs `/usr/bin/openssl` through [io.kotgent.tmux.ProcessRunner] (tens of milliseconds), so it
 * inherits that spawn path's CLOEXEC sweep; a burst of notifications would otherwise pay it once per
 * subscription. Keyed by origin because a token names its audience: Apple and Google each get their own,
 * minted on first use.
 *
 * ## Concurrency
 * [Mutex]-guarded, the shape [VapidKey] and `TicketStore` use. The map is a read-modify-write and
 * [PushSender] may fan out to several subscriptions at once; the lock also collapses a concurrent burst
 * into a single openssl run instead of one per caller. [sign] is called while the lock is held, which is
 * deliberate — it is short, and the alternative (sign outside, then insert) re-signs under contention.
 *
 * ## No eviction
 * The map is bounded by the number of distinct push services in the world that this operator's devices
 * use — in practice one to three entries, each a few hundred bytes. A sweep would be ceremony.
 *
 * @param subject the `sub` claim for every token ([vapidSubject] of the configured public URL).
 * @param sign produces the 64-byte raw `r||s` ES256 signature over the signing input. Task 8's
 *   `OpensslVapidSigner::sign` in production, a lambda in tests — the whole point of this class being pure.
 * @param now epoch millis, injected so expiry is testable without sleeping (`getTimeMillis` is an
 *   ERROR-level deprecation).
 * @param ttlMillis how long a minted token claims to live.
 * @param refreshBeforeMillis how much remaining life makes a cached token too stale to hand out.
 */
class VapidTokenCache(
    private val subject: String,
    private val sign: (String) -> ByteArray,
    private val now: () -> Long = ::vapidEpochMillis,
    private val ttlMillis: Long = VAPID_TOKEN_TTL_MILLIS,
    private val refreshBeforeMillis: Long = VAPID_TOKEN_REFRESH_BEFORE_MILLIS,
) {
    init {
        require(ttlMillis > 0) { "VAPID token TTL must be positive, got $ttlMillis ms" }
        require(refreshBeforeMillis >= 0) {
            "VAPID refresh window must not be negative, got $refreshBeforeMillis ms"
        }
        require(refreshBeforeMillis < ttlMillis) {
            "VAPID refresh window ($refreshBeforeMillis ms) must be shorter than the TTL ($ttlMillis ms), " +
                "or every token would be stale the moment it is minted"
        }
    }

    private val mutex = Mutex()

    /** A minted token and the wall-clock instant (epoch millis) its `exp` claim names. */
    private data class Cached(val jwt: String, val expiresAt: Long)

    /** origin → its current token. */
    private val tokens = mutableMapOf<String, Cached>()

    /**
     * The JWT to send to [endpoint], minting one if there is none for its origin or the cached one has
     * less than [refreshBeforeMillis] left.
     *
     * A failing [sign] propagates and caches nothing, so the next attempt retries rather than serving a
     * half-built token — a missing openssl must degrade to "no push", never to "malformed push".
     */
    suspend fun tokenFor(endpoint: String): String {
        val origin = pushServiceOrigin(endpoint)
        return mutex.withLock {
            val at = now()
            val cached = tokens[origin]
            if (cached != null && at < cached.expiresAt - refreshBeforeMillis) return@withLock cached.jwt
            val expiresAt = at + ttlMillis
            val input = vapidSigningInput(aud = origin, exp = expiresAt / MILLIS_PER_SECOND, sub = subject)
            val jwt = "$input.${base64Url(sign(input))}"
            tokens[origin] = Cached(jwt, expiresAt)
            jwt
        }
    }

    /** How many origins currently hold a token. For tests and diagnostics. */
    suspend fun cachedOriginCount(): Int = mutex.withLock { tokens.size }
}

/** JWT `exp` is a NumericDate — seconds, while every clock in this codebase is millis. */
private const val MILLIS_PER_SECOND: Long = 1000

/**
 * Refuse [value] if embedding it in a JSON string literal would need escaping.
 *
 * Both claim values are constrained by construction ([pushServiceOrigin] rejects the odd characters, and
 * [vapidSubject] returns either such an origin or a fixed constant), so this can only fire if a caller
 * bypasses them — in which case throwing is far better than emitting a JWT whose claims silently do not
 * parse at the push service.
 */
private fun requireJsonSafe(value: String, name: String) {
    if (value.isEmpty()) throw VapidJwtException("the VAPID $name claim must not be empty")
    if (value.any { it == '"' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
        throw VapidJwtException("the VAPID $name claim contains a character that JSON would have to escape")
    }
}

/**
 * [authority] with a trailing default port for [scheme] removed, so `https://host:443` and `https://host`
 * produce the same `aud` (RFC 6454 serializes an origin without its default port, and a push service
 * compares strings).
 */
private fun withoutDefaultPort(scheme: String, authority: String): String {
    val defaultPort = if (scheme == "https") ":443" else ":80"
    // A bracketed IPv6 literal keeps its own colons inside the brackets, so only a suffix match is safe.
    return if (authority.endsWith(defaultPort)) authority.removeSuffix(defaultPort) else authority
}

/** Wall clock in epoch millis — the production [VapidTokenCache.now]. */
private fun vapidEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
