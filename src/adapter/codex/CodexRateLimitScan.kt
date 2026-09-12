package io.kotgent.adapter.codex

import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageSource
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A bounded JSONL tail may start or finish mid-record; only complete token_count objects count. */
fun extractCodexRateLimits(
    text: String,
    sourceId: String? = null,
    baseOffset: Long = 0,
): List<UsageObservation> = extractCodexRateLimits(text.encodeToByteArray(), sourceId, baseOffset)

/** Byte offsets remain stable even when a bounded tail starts inside a UTF-8 code point. */
fun extractCodexRateLimits(
    bytes: ByteArray,
    sourceId: String? = null,
    baseOffset: Long = 0,
): List<UsageObservation> {
    require(baseOffset >= 0) { "Rollout byte offset must be non-negative" }
    var end = bytes.size
    while (end > 0) {
        var precedingNewline = end - 1
        while (precedingNewline >= 0 && bytes[precedingNewline] != '\n'.code.toByte()) precedingNewline--
        val start = precedingNewline + 1
        val line = bytes.decodeToString(start, end)
        end = precedingNewline
        val record = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: continue
        if (record.string("type") != "event_msg") continue
        val payload = record["payload"] as? JsonObject ?: continue
        if (payload.string("type") != "token_count") continue
        val limits = payload["rate_limits"] as? JsonObject ?: continue
        val limitId = limits["limit_id"]
        if (limitId != null && limitId != JsonNull && limits.string("limit_id") != "codex") continue
        val capturedAt = record.string("timestamp")?.let(::recordEpochMillis)
        val source = if (!sourceId.isNullOrBlank() && capturedAt != null) {
            UsageSource(sourceId, baseOffset + start, capturedAt)
        } else null

        // This object is authoritative even if no supported window contains a usable percentage.
        return listOf("primary", "secondary", "credits").mapNotNull { key ->
            val window = limits[key] as? JsonObject ?: return@mapNotNull null
            val percent = window.number("used_percent")?.doubleOrNull ?: return@mapNotNull null
            if (!percent.isFinite() || percent !in 0.0..100.0) return@mapNotNull null
            UsageObservation(
                provider = "codex",
                windowKey = key,
                usedPercent = percent,
                resetsAt = window.scaledLong("resets_at", 1_000, allowZero = true),
                windowSeconds = window.scaledLong("window_minutes", 60, allowZero = false),
                source = source,
            )
        }
    }
    return emptyList()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.number(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }

private fun JsonObject.scaledLong(key: String, multiplier: Long, allowZero: Boolean): Long? {
    val value = number(key)?.longOrNull ?: return null
    if (value < 0 || (!allowZero && value == 0L) || value > Long.MAX_VALUE / multiplier) return null
    return value * multiplier
}

@OptIn(ExperimentalTime::class)
private fun recordEpochMillis(timestamp: String): Long? = try {
    Instant.parse(timestamp).toEpochMilliseconds().takeIf { it >= 0 }
} catch (_: IllegalArgumentException) {
    null
}
