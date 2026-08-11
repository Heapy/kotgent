package io.kotgent.transport

import io.kotgent.crypto.hmacSha256Hex
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall


const val SESSION_COOKIE_NAME: String = "kotgent_session"

private const val SESSION_COOKIE_VERSION: String = "v1"

private const val FIELD_SEPARATOR: Char = '.'

private const val MESSAGE_SEPARATOR: Char = '|'

private const val FIELD_COUNT: Int = 3

// Persistent until master-token rotation; mobile Safari drops session-only cookies on restart.
const val SESSION_COOKIE_MAX_AGE_SECONDS: Long = 10L * 365 * 24 * 60 * 60

fun issueSessionCookie(token: String, issuedAt: Long): String {
    val mac = hmacSha256Hex(token, signedMessage(issuedAt.toString()))
    return "$SESSION_COOKIE_VERSION$FIELD_SEPARATOR$issuedAt$FIELD_SEPARATOR$mac"
}

fun verifySessionCookie(token: String, value: String?): Boolean {
    // An empty string is a valid HMAC key, so an unreadable/blank master token must fail explicitly.
    if (token.isEmpty() || value.isNullOrEmpty()) return false
    val fields = value.split(FIELD_SEPARATOR)
    if (fields.size != FIELD_COUNT) return false
    val (version, issuedAt, mac) = fields
    if (version != SESSION_COOKIE_VERSION) return false
    if (issuedAt.isEmpty() || !issuedAt.all { it in '0'..'9' }) return false
    return constantTimeEquals(mac, hmacSha256Hex(token, signedMessage(issuedAt)))
}

private fun signedMessage(issuedAt: String): String = "$SESSION_COOKIE_VERSION$MESSAGE_SEPARATOR$issuedAt"


fun ApplicationCall.sessionCookie(): String? = request.cookies[SESSION_COOKIE_NAME, CookieEncoding.RAW]

fun ApplicationCall.setSessionCookie(value: String, secure: Boolean) {
    // Browser credentials stay script-inaccessible and ambient only on same-site requests.
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
