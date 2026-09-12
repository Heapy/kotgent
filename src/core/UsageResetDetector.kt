package io.kotgent.core

const val EARLY_TOLERANCE_MILLIS: Long = 5 * 60_000

/** The store admits source ordering and same-session evidence before comparing this pair. */
fun detectReset(previous: UsageObservation?, current: UsageObservation): UsageReset? {
    if (previous == null || previous.provider != current.provider || previous.windowKey != current.windowKey) {
        return null
    }
    val dropped = current.usedPercent < previous.usedPercent
    val moved = previous.resetsAt != null && current.resetsAt != null && previous.resetsAt != current.resetsAt
    val reset = when (current.provider) {
        "claude" -> dropped
        "codex" -> moved && dropped
        else -> moved && dropped
    }
    if (!reset) return null

    val beforeEnd = previous.resetsAt != null &&
        current.observedAt < previous.resetsAt - EARLY_TOLERANCE_MILLIS
    return UsageReset(
        provider = current.provider,
        windowKey = current.windowKey,
        expectedAt = previous.resetsAt,
        observedAt = current.observedAt,
        usedBefore = previous.usedPercent,
        usedBeforeSeenAt = previous.observedAt,
        early = beforeEnd,
        resetsAtMoved = moved,
        windowSeconds = previous.windowSeconds,
    )
}
