package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageSource
import io.kotgent.store.UsageStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

const val CLAUDE_USAGE_MAX_BODY_BYTES: Int = 256 * 1024

/** Null means malformed capture metadata; an empty list is a valid capture without usable quotas. */
fun parseClaudeUsageCapture(text: String): List<UsageObservation>? {
    val envelope = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    val payload = envelope["payload"] as? JsonObject ?: return null
    val sourcePayload = envelope["source"] as? JsonObject ?: return null
    val sessionId = payload.stringValue("session_id") ?: return null
    if (sessionId.length !in 1..256 || !SAFE_SOURCE_PART.matches(sessionId)) return null
    val sourceId = sourcePayload.stringValue("id") ?: return null
    val prefix = "$sessionId:"
    if (sourceId.length > 512 || !sourceId.startsWith(prefix)) return null
    val incarnation = sourceId.substring(prefix.length)
    if (incarnation.isEmpty() || !SAFE_SOURCE_PART.matches(incarnation)) return null
    val revision = sourcePayload.nonNegativeLong("revision") ?: return null
    val capturedAt = sourcePayload.nonNegativeLong("capturedAt") ?: return null
    val source = UsageSource(sourceId, revision, capturedAt)
    val limits = payload["rate_limits"] as? JsonObject ?: return emptyList()
    return listOf("five_hour", "seven_day").mapNotNull { key ->
        val window = limits[key] as? JsonObject ?: return@mapNotNull null
        val percent = window.numberValue("used_percentage")?.doubleOrNull ?: return@mapNotNull null
        if (!percent.isFinite() || percent !in 0.0..100.0) return@mapNotNull null
        val resetSeconds = window.nonNegativeLong("resets_at")
        UsageObservation(
            provider = "claude",
            windowKey = key,
            usedPercent = percent,
            resetsAt = resetSeconds?.takeIf { it <= Long.MAX_VALUE / 1_000L }?.times(1_000L),
            windowSeconds = if (key == "five_hour") 18_000L else 604_800L,
            source = source,
        )
    }
}

fun Route.claudeUsageRoutes(token: () -> String, store: UsageStore): Route = loopbackOnly {
    post(ClaudeHookConfig.USAGE_INGRESS_PATH) {
        val presented = call.request.headers[ClaudeHookConfig.HOOK_TOKEN_HEADER]
        if (presented == null || !constantTimeEquals(presented, token())) {
            call.rejectUsageCapture("unauthorized", HttpStatusCode.Unauthorized)
            return@post
        }
        val channel = call.receiveChannel()
        val contentLength = call.request.contentLength()
        val body = if ((contentLength ?: 0L) > CLAUDE_USAGE_MAX_BODY_BYTES) {
            AuthExchangeBodyRead.TooLarge
        } else {
            readAuthExchangeBody(channel, expectedBytes = contentLength, maxBytes = CLAUDE_USAGE_MAX_BODY_BYTES)
        }
        val text = when (body) {
            is AuthExchangeBodyRead.Received -> body.text
            AuthExchangeBodyRead.Incomplete -> {
                call.rejectUsageCapture("incomplete request body", HttpStatusCode.BadRequest, channel)
                return@post
            }
            AuthExchangeBodyRead.TooLarge -> {
                call.rejectUsageCapture("request body too large", HttpStatusCode.PayloadTooLarge, channel)
                return@post
            }
            AuthExchangeBodyRead.TimedOut -> {
                call.rejectUsageCapture("request body timed out", HttpStatusCode.RequestTimeout, channel)
                return@post
            }
        }
        val observations = parseClaudeUsageCapture(text)
        if (observations == null) {
            call.respondText("invalid usage capture", status = HttpStatusCode.BadRequest)
            return@post
        }
        for (observation in observations) store.observe(observation)
        call.respondText("ok", status = HttpStatusCode.OK)
    }
}

private suspend fun ApplicationCall.rejectUsageCapture(
    text: String,
    status: HttpStatusCode,
    body: ByteReadChannel? = null,
) = respondToUnconsumedBodyAndClose(text, status, body, "closing rejected Claude usage capture")

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.numberValue(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }

private fun JsonObject.nonNegativeLong(key: String): Long? = numberValue(key)?.longOrNull?.takeIf { it >= 0 }

private val SAFE_SOURCE_PART = Regex("[A-Za-z0-9._-]+")
