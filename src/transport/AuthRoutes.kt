package io.kotgent.transport

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds


const val AUTH_PAGE_PATH: String = "/auth"

const val AUTH_TICKET_PATH: String = "$API_PREFIX/auth/ticket"

const val AUTH_EXCHANGE_PATH: String = "$API_PREFIX/auth/exchange"

const val AUTH_ROTATE_PATH: String = "$API_PREFIX/auth/rotate"

const val LEGACY_AUTH_TICKET_PATH: String = "/auth/ticket"

const val LEGACY_AUTH_EXCHANGE_PATH: String = "/auth/exchange"

const val LEGACY_AUTH_ROTATE_PATH: String = "/auth/rotate"

private val AUTH_TICKET_PATHS: List<String> = listOf(AUTH_TICKET_PATH, LEGACY_AUTH_TICKET_PATH)

private val AUTH_EXCHANGE_PATHS: List<String> = listOf(AUTH_EXCHANGE_PATH, LEGACY_AUTH_EXCHANGE_PATH)

private val AUTH_ROTATE_PATHS: List<String> = listOf(AUTH_ROTATE_PATH, LEGACY_AUTH_ROTATE_PATH)

@Serializable
data class TicketResponse(
    val ticket: String,
    val localUrl: String,
    val publicUrl: String? = null,
    val expiresAt: Long,
)

@Serializable
data class ExchangeRequest(val ticket: String)

@Serializable
data class RotateResponse(val token: String)

sealed interface AuthExchangeBodyRead {
    data class Received(val text: String) : AuthExchangeBodyRead
    data object Incomplete : AuthExchangeBodyRead
    data object TooLarge : AuthExchangeBodyRead
    data object TimedOut : AuthExchangeBodyRead
}

suspend fun readAuthExchangeBody(
    channel: ByteReadChannel,
    expectedBytes: Long? = null,
    maxBytes: Int = AUTH_EXCHANGE_MAX_BODY_BYTES,
    timeoutMillis: Long = AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS,
): AuthExchangeBodyRead {
    // This unauthenticated read buffers at most maxBytes+1 and cannot hold a handler indefinitely.
    require(expectedBytes == null || expectedBytes >= 0) {
        "the expected auth exchange body length must be non-negative, got $expectedBytes"
    }
    require(maxBytes > 0) { "the auth exchange body limit must be positive, got $maxBytes" }
    require(maxBytes < Int.MAX_VALUE) { "the auth exchange body limit is too large to probe for overflow" }
    require(timeoutMillis > 0) { "the auth exchange body timeout must be positive, got $timeoutMillis ms" }

    return withTimeoutOrNull(timeoutMillis.milliseconds) {
        val bytes = ByteArray(maxBytes + 1)
        var size = 0
        while (size < bytes.size) {
            val read = channel.readAvailable(bytes, size, bytes.size - size)
            if (read < 0) break
            size += read
        }
        when {
            size > maxBytes -> AuthExchangeBodyRead.TooLarge
            expectedBytes != null && size.toLong() < expectedBytes -> AuthExchangeBodyRead.Incomplete
            else -> AuthExchangeBodyRead.Received(bytes.decodeToString(endIndex = size))
        }
    } ?: AuthExchangeBodyRead.TimedOut
}

fun Route.authRoutes(
    tokens: TokenHolder,
    tickets: TicketStore,
    publicUrl: String? = null,
    json: Json = TRANSPORT_JSON,
    now: () -> Long = ::authEpochMillis,
    exchangeLimit: ExchangeRateLimit = ExchangeRateLimit(),
    afterExchangeAdmitted: suspend () -> Unit = {},
    exchangeBodyTimeoutMillis: Long = AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS,
) {
    val _ = authenticated(tokens::current, publicUrl) {
        val _ = loopbackOnly {
            for (path in AUTH_TICKET_PATHS) post(path) {
                // Re-check one token snapshot and bind the ticket to that exact value: rotation between
                // the outer gate and issuance must not launder an old credential onto the new token.
                val token = tokens.current()
                val presented = call.presentedToken()
                val proven = if (presented != null) {
                    constantTimeEquals(presented, token)
                } else {
                    verifySessionCookie(token, call.sessionCookie())
                }
                if (!proven) {
                    call.respondText(refusalBody(HttpStatusCode.Unauthorized), status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val ticket = tickets.issue(token)
                val authority = call.request.headers[HttpHeaders.Host].orEmpty()
                val body = TicketResponse(
                    ticket = ticket.value,
                    localUrl = ticketUrl("http://$authority", ticket.value),
                    publicUrl = publicUrl?.let { ticketUrl(it, ticket.value) },
                    expiresAt = ticket.expiresAt,
                )
                call.respondText(
                    json.encodeToString(TicketResponse.serializer(), body),
                    ContentType.Application.Json,
                )
            }

            for (path in AUTH_ROTATE_PATHS) post(path) {
                // Rotation returns the machine key, so a browser cookie must never authorize it.
                val presented = call.presentedToken()
                if (presented == null) {
                    call.respondText(refusalBody(HttpStatusCode.Forbidden), status = HttpStatusCode.Forbidden)
                    return@post
                }
                val rotated = tokens.rotate(expected = presented)
                if (rotated == null) {
                    call.respondText("token changed", status = HttpStatusCode.Conflict)
                    return@post
                }
                call.respondText(
                    json.encodeToString(RotateResponse.serializer(), RotateResponse(rotated)),
                    ContentType.Application.Json,
                )
            }
        }
    }

    for (path in AUTH_EXCHANGE_PATHS) post(path) {
        val facts = call.requestFacts()
        val decision = authorizeTicketExchange(facts, publicUrl)
        if (decision is AuthDecision.Deny) {
            call.respondToUnconsumedExchangeAndClose(refusalBody(decision.status), decision.status)
            return@post
        }
        val attempt = exchangeLimit.begin()
        // Reserve before reading an attacker-controlled body; the limiter also bounds stalled peers.
        if (attempt == null) {
            call.respondToUnconsumedExchangeAndClose(
                "too many failed sign-in attempts",
                HttpStatusCode.TooManyRequests,
            )
            return@post
        }
        var failedExchange = false
        try {
            afterExchangeAdmitted()
            val requestBody = call.receiveChannel()
            val contentLength = call.request.contentLength()
            val body = when {
                (contentLength ?: 0L) > AUTH_EXCHANGE_MAX_BODY_BYTES ->
                    AuthExchangeBodyRead.TooLarge

                else -> readAuthExchangeBody(
                    requestBody,
                    expectedBytes = contentLength,
                    timeoutMillis = exchangeBodyTimeoutMillis,
                )
            }
            val text = when (body) {
                is AuthExchangeBodyRead.Received -> body.text
                AuthExchangeBodyRead.Incomplete -> {
                    call.respondToUnconsumedExchangeAndClose(
                        "incomplete request body",
                        HttpStatusCode.BadRequest,
                        requestBody,
                    )
                    return@post
                }
                AuthExchangeBodyRead.TooLarge -> {
                    call.respondToUnconsumedExchangeAndClose(
                        "request body too large",
                        HttpStatusCode.PayloadTooLarge,
                        requestBody,
                    )
                    return@post
                }
                AuthExchangeBodyRead.TimedOut -> {
                    call.respondToUnconsumedExchangeAndClose(
                        "request body timed out",
                        HttpStatusCode.RequestTimeout,
                        requestBody,
                    )
                    return@post
                }
            }
            val presented = try {
                json.decodeFromString(ExchangeRequest.serializer(), text).ticket.trim()
            } catch (_: SerializationException) {
                call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                return@post
            }
            val boundToken = if (presented.isEmpty()) null else tickets.redeem(presented)
            failedExchange = boundToken == null
            if (boundToken == null) {
                call.respondText("invalid or expired ticket", status = HttpStatusCode.BadRequest)
                return@post
            }
            call.setSessionCookie(
                // Tickets retain their mint-time token, so rotation makes a late exchange's cookie invalid.
                issueSessionCookie(boundToken, now()),
                secure = requiresSecureCookie(facts.host, publicUrl),
            )
            call.respondText("ok")
        } finally {
            // Request cancellation must not leak an in-flight limiter reservation.
            withContext(NonCancellable) { attempt.finish(failedExchange) }
        }
    }
}

private suspend fun ApplicationCall.respondToUnconsumedExchangeAndClose(
    text: String,
    status: HttpStatusCode,
    requestBody: ByteReadChannel? = null,
) = respondToUnconsumedBodyAndClose(
    text, status, requestBody, "closing unconsumed $AUTH_EXCHANGE_PATH request body",
)

fun authorizeTicketExchange(facts: RequestFacts, publicUrl: String?): AuthDecision {
    // The ticket is the credential; Host+Origin are the browser-side CSRF boundary for this POST.
    val host = facts.host?.trim().orEmpty()
    if (!isAllowedHost(host, publicUrl)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "host '$host' is not in the allowlist")
    }
    val origin = facts.origin?.trim()?.ifEmpty { null }
        ?: return AuthDecision.Deny(HttpStatusCode.Forbidden, "a ticket exchange must carry an Origin header")
    if (!isAllowedOrigin(origin, publicUrl)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "origin '$origin' is not in the allowlist")
    }
    return AuthDecision.Allow
}

private fun ticketUrl(origin: String, ticket: String): String =
    "${origin.trimEnd('/')}$AUTH_PAGE_PATH#ticket=$ticket"

private fun authEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

const val AUTH_EXCHANGE_MAX_BODY_BYTES: Int = 1_024

const val AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS: Long = 5_000L
