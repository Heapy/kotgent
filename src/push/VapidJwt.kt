package io.kotgent.push

import io.kotgent.crypto.base64Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * VAPID tokens are audience-bound to one HTTPS push-service origin. This parser is stricter than the
 * browser-origin policy: push endpoints are third-party HTTPS URLs and `aud` must be their literal origin.
 */
const val VAPID_JWT_HEADER_JSON: String = """{"typ":"JWT","alg":"ES256"}"""

/** Half the RFC 8292 maximum leaves clock-skew margin. */
const val VAPID_TOKEN_TTL_MILLIS: Long = 12L * 60 * 60 * 1000

/** Prevents a cached token from expiring in flight. */
const val VAPID_TOKEN_REFRESH_BEFORE_MILLIS: Long = 60L * 60 * 1000

/** Deliberately unroutable; kotgent must not invent contact data the operator did not publish. */
const val VAPID_FALLBACK_SUBJECT: String = "mailto:kotgent@localhost"

const val VAPID_AUTH_SCHEME: String = "vapid"

class VapidJwtException(message: String) : IllegalArgumentException(message)

/**
 * Canonical HTTPS origin for a push endpoint. Paths contain subscription identity and must not enter
 * `aud`; malformed authorities, userinfo, and invalid ports are rejected.
 */
fun pushServiceOrigin(endpoint: String): String {
    val value = endpoint
    if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        throw VapidJwtException("push endpoint '$value' has whitespace or control characters")
    }
    val separator = value.indexOf("://")
    if (separator <= 0) {
        throw VapidJwtException("push endpoint '$value' is not an absolute URL")
    }
    val scheme = value.substring(0, separator).lowercase()
    if (scheme != "https") {
        throw VapidJwtException("push endpoint scheme must be https, got '$scheme'")
    }
    val authority = value.substring(separator + 3)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isEmpty()) throw VapidJwtException("push endpoint '$value' has no host")
    if (authority.contains('@')) {
        throw VapidJwtException("push endpoint '$value' carries userinfo")
    }

    val (host, port) = parsePushAuthority(authority, value)
    return buildString {
        append("https://")
        append(host)
        if (port != null && port != HTTPS_DEFAULT_PORT) append(':').append(port)
    }
}

/**
 * Uses the public HTTPS origin as contact identity. HTTP or malformed URLs fall back rather than disabling
 * push with an unreachable subject.
 */
fun vapidSubject(publicUrl: String?): String {
    val value = publicUrl?.trim().orEmpty()
    if (value.isEmpty()) return VAPID_FALLBACK_SUBJECT
    val origin = try {
        pushServiceOrigin(value)
    } catch (_: VapidJwtException) {
        return VAPID_FALLBACK_SUBJECT
    }
    return origin
}

/**
 * Claims use a literal to keep the signed byte sequence, including key order and spacing, fixed.
 */
fun vapidSigningInput(aud: String, exp: Long, sub: String): String {
    requireJsonSafe(aud, "aud")
    requireJsonSafe(sub, "sub")
    val header = base64Url(VAPID_JWT_HEADER_JSON.encodeToByteArray())
    val claims = base64Url("""{"aud":"$aud","exp":$exp,"sub":"$sub"}""".encodeToByteArray())
    return "$header.$claims"
}

fun vapidAuthorizationHeader(jwt: String, publicKey: String): String =
    "$VAPID_AUTH_SCHEME t=$jwt, k=$publicKey"

/**
 * Caches one audience-bound token per push-service origin. Signing under the mutex collapses concurrent
 * first use into one OpenSSL invocation; cache size is naturally bounded by the few service origins.
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

    private data class Cached(val jwt: String, val expiresAt: Long)

    private val tokens = mutableMapOf<String, Cached>()

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

    suspend fun cachedOriginCount(): Int = mutex.withLock { tokens.size }
}

private const val MILLIS_PER_SECOND: Long = 1000

/** Claims are embedded literally, so callers bypassing canonicalizers must still fail closed. */
private fun requireJsonSafe(value: String, name: String) {
    if (value.isEmpty()) throw VapidJwtException("the VAPID $name claim must not be empty")
    if (value.any { it == '"' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
        throw VapidJwtException("the VAPID $name claim contains a character that JSON would have to escape")
    }
}

private const val HTTPS_DEFAULT_PORT: Int = 443

private fun parsePushAuthority(authority: String, endpoint: String): Pair<String, Int?> {
    if (authority.startsWith('[')) {
        val closingBracket = authority.indexOf(']')
        if (closingBracket <= 1 ||
            authority.indexOf('[', startIndex = 1) >= 0 ||
            authority.indexOf(']', startIndex = closingBracket + 1) >= 0
        ) {
            throw VapidJwtException("push endpoint '$endpoint' has a malformed IPv6 host")
        }
        val literal = authority.substring(1, closingBracket)
        if (!isValidIpv6Literal(literal)) {
            throw VapidJwtException("push endpoint '$endpoint' has an invalid IPv6 host")
        }
        val port = parsePushPort(authority.substring(closingBracket + 1), endpoint)
        return "[${literal.lowercase()}]" to port
    }

    if (authority.contains('[') || authority.contains(']')) {
        throw VapidJwtException("push endpoint '$endpoint' has a malformed IPv6 host")
    }
    val firstColon = authority.indexOf(':')
    if (firstColon != authority.lastIndexOf(':')) {
        throw VapidJwtException("push endpoint '$endpoint' has an unbracketed IPv6 host")
    }
    val host = if (firstColon < 0) authority else authority.substring(0, firstColon)
    if (!isValidPushHost(host)) {
        throw VapidJwtException("push endpoint '$endpoint' has an invalid host")
    }
    val port = if (firstColon < 0) null else parsePushPort(authority.substring(firstColon), endpoint)
    return host.lowercase() to port
}

private fun parsePushPort(suffix: String, endpoint: String): Int? {
    if (suffix.isEmpty()) return null
    val digits = suffix.removePrefix(":")
    if (!suffix.startsWith(':') || digits.isEmpty() || digits.any { it !in '0'..'9' }) {
        throw VapidJwtException("push endpoint '$endpoint' has an invalid port")
    }
    val port = digits.toIntOrNull()
    if (port == null || port !in 1..65535) {
        throw VapidJwtException("push endpoint '$endpoint' has an invalid port")
    }
    return port
}

private fun isValidPushHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    val labels = host.split('.')
    if (labels.any { label ->
            label.isEmpty() ||
                label.length > 63 ||
                label.first() !in ASCII_ALPHANUMERIC ||
                label.last() !in ASCII_ALPHANUMERIC ||
                label.any { it !in ASCII_ALPHANUMERIC && it != '-' }
        }
    ) {
        return false
    }
    if (host.all { it == '.' || it in '0'..'9' }) return isValidIpv4Address(host)
    return true
}

/** Legacy integer and octal IPv4 spellings are intentionally rejected. */
private fun isValidIpv4Address(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
            (octet.length == 1 || octet.first() != '0') &&
            octet.all { it in '0'..'9' } &&
            octet.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun isValidIpv6Literal(value: String): Boolean {
    if (value.isEmpty() || '%' in value) return false
    val compression = value.indexOf("::")
    if (compression >= 0 && value.indexOf("::", startIndex = compression + 2) >= 0) return false

    if (compression < 0) {
        return ipv6Units(value, allowIpv4Tail = true) == IPV6_UNIT_COUNT
    }

    val left = value.substring(0, compression)
    val right = value.substring(compression + 2)
    val leftUnits = ipv6Units(left, allowIpv4Tail = false) ?: return false
    val rightUnits = ipv6Units(right, allowIpv4Tail = true) ?: return false
    return leftUnits + rightUnits < IPV6_UNIT_COUNT
}

private fun ipv6Units(value: String, allowIpv4Tail: Boolean): Int? {
    if (value.isEmpty()) return 0
    val groups = value.split(':')
    if (groups.any { it.isEmpty() }) return null
    var units = 0
    for ((index, group) in groups.withIndex()) {
        if ('.' in group) {
            if (!allowIpv4Tail || index != groups.lastIndex || !isValidIpv4Address(group)) return null
            units += 2
        } else {
            if (group.length !in 1..4 || group.any { it !in ASCII_HEX }) return null
            units++
        }
    }
    return units
}

private const val IPV6_UNIT_COUNT: Int = 8
private const val ASCII_ALPHANUMERIC: String =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val ASCII_HEX: String = "0123456789abcdefABCDEF"

private fun vapidEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
