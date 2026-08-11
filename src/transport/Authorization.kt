package io.kotgent.transport

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode


data class RequestFacts(
    val host: String?,
    val origin: String? = null,
    val authHeader: String? = null,
    val cookie: String? = null,
    val method: HttpMethod = HttpMethod.Get,
    val isWebSocket: Boolean = false,
)

sealed interface AuthDecision {
    data object Allow : AuthDecision

    data class Deny(val status: HttpStatusCode, val reason: String) : AuthDecision
}

val LOOPBACK_HOSTNAMES: Set<String> = setOf("127.0.0.1", "localhost", "[::1]")

fun isLoopbackHost(host: String): Boolean = canonicalHostname(host) in LOOPBACK_HOSTNAMES

fun allowedOrigins(publicUrl: String?): Set<String> = buildSet {
    for (name in LOOPBACK_HOSTNAMES) {
        add("http://$name")
        add("https://$name")
    }
    publicUrl?.let(::canonicalOrigin)?.let(::add)
}

fun isAllowedOrigin(origin: String, publicUrl: String?): Boolean {
    val canonical = canonicalOrigin(origin) ?: return false
    return canonical in allowedOrigins(publicUrl)
}

fun isAllowedHost(host: String?, publicUrl: String?): Boolean {
    val candidate = host?.trim().orEmpty()
    if (candidate.isEmpty()) return false
    if (isLoopbackHost(candidate)) return true
    val configured = publicHost(publicUrl) ?: return false
    return canonicalHostname(candidate) == configured
}

fun requiresSecureCookie(host: String?, publicUrl: String?): Boolean {
    // Derive this from the allowlisted Host, never forgeable X-Forwarded-Proto; loopback serves plain HTTP.
    val url = publicUrl?.trim()?.lowercase() ?: return false
    if (!url.startsWith("https://")) return false
    val configured = publicHost(url) ?: return false
    val candidate = host?.trim()?.ifEmpty { null } ?: return false
    return canonicalHostname(candidate) == configured
}

private fun publicHost(publicUrl: String?): String? =
    publicUrl?.let(::originAuthority)?.let(::canonicalHostname)

fun bearerToken(authHeader: String?): String? {
    if (authHeader == null) return null
    if (!authHeader.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
    return authHeader.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
}

fun authorize(
    facts: RequestFacts,
    publicUrl: String?,
    loopbackOnly: Boolean = false,
    verifyToken: (String) -> Boolean,
    verifyCookie: (String) -> Boolean,
): AuthDecision {
    // Cookies are ambient and SameSite does not separate sibling subdomains, so Host and Origin gate
    // cookie use. Bearers are explicit non-browser credentials and do not require Origin.
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

private const val BEARER_PREFIX: String = "Bearer "

private fun requiresOrigin(facts: RequestFacts): Boolean =
    // Same-origin reads normally omit Origin; WebSocket GETs are the CORS-bypassing exception.
    facts.isWebSocket || (facts.method != HttpMethod.Get && facts.method != HttpMethod.Head)

private fun canonicalHostname(host: String): String {
    val trimmed = host.trim()
    val name = when {
        trimmed.startsWith("[") -> trimmed.indexOf(']').let { if (it < 0) trimmed else trimmed.substring(0, it + 1) }
        trimmed.count { it == ':' } > 1 -> trimmed
        else -> trimmed.substringBefore(':')
    }
    val lower = name.lowercase()
    return if (lower == "::1") "[::1]" else lower
}

private fun canonicalOrigin(value: String): String? {
    val authority = originAuthority(value) ?: return null
    val scheme = value.substringBefore("://").lowercase()
    val hostname = canonicalHostname(authority)
    // Browsers serialize default ports away; public non-default ports remain part of origin identity.
    return if (hostname in LOOPBACK_HOSTNAMES) "$scheme://$hostname"
    else "$scheme://${authorityWithoutDefaultPort(scheme, authority)}"
}

private fun authorityWithoutDefaultPort(scheme: String, authority: String): String {
    val defaultPort = when (scheme) {
        "https" -> "443"
        "http" -> "80"
        else -> return authority
    }
    return if (authorityPort(authority) == defaultPort) authority.removeSuffix(":$defaultPort") else authority
}

private fun authorityPort(authority: String): String? {
    val trimmed = authority.trim()
    return when {
        trimmed.startsWith("[") ->
            trimmed.indexOf(']').let { if (it < 0) null else trimmed.substring(it + 1).removePrefix(":").ifEmpty { null } }
        trimmed.count { it == ':' } > 1 -> null
        trimmed.contains(':') -> trimmed.substringAfter(':').ifEmpty { null }
        else -> null
    }
}

private fun originAuthority(value: String): String? {
    val separator = value.indexOf("://")
    if (separator <= 0) return null
    val scheme = value.substring(0, separator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = value.substring(separator + 3).trimEnd('/').lowercase()
    if (authority.isEmpty()) return null
    if (authorityHasForbiddenChars(authority)) return null
    return authority
}

internal fun authorityHasForbiddenChars(authority: String): Boolean =
    authority.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }
