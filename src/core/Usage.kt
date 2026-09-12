package io.kotgent.core

const val USAGE_HEARTBEAT_MILLIS: Long = 60_000
const val USAGE_RETENTION_MILLIS: Long = 90 * 24 * 60 * 60_000L

/** Render/record provenance; capturedAt is epoch millis, not a provider quota-fetch timestamp. */
data class UsageSource(
    val id: String,
    val revision: Long,
    val capturedAt: Long,
) {
    init {
        require(id.isNotBlank()) { "Usage source id must not be blank" }
        require(revision >= 0) { "Usage source revision must be non-negative" }
        require(capturedAt >= 0) { "Usage source capturedAt must be non-negative" }
    }
}

/** Timestamps are epoch millis; provider seconds are converted at ingress. */
data class UsageObservation(
    val provider: String,
    val windowKey: String,
    val usedPercent: Double,
    val resetsAt: Long?,
    val windowSeconds: Long?,
    /** Store-stamped monotonic revision per provider/window; callers leave it at zero. */
    val observedAt: Long = 0,
    val source: UsageSource? = null,
) {
    init {
        require(provider.isNotBlank()) { "Usage provider must not be blank" }
        require(windowKey.isNotBlank()) { "Usage window key must not be blank" }
        require(usedPercent.isFinite() && usedPercent in 0.0..100.0) {
            "Usage percent must be finite and between 0 and 100"
        }
        require(resetsAt == null || resetsAt >= 0) { "Usage resetsAt must be non-negative" }
        require(windowSeconds == null || windowSeconds > 0) { "Usage window duration must be positive" }
        require(observedAt >= 0) { "Usage observedAt must be non-negative" }
    }
}

data class UsageWindowState(
    val current: UsageObservation,
    val changedAt: Long,
    /** Actual daemon receipt time; unlike current.observedAt this may move backwards with the clock. */
    val receivedAt: Long,
)

data class UsageReset(
    val provider: String,
    val windowKey: String,
    val expectedAt: Long?,
    val observedAt: Long,
    val usedBefore: Double,
    val usedBeforeSeenAt: Long,
    val early: Boolean,
    val resetsAtMoved: Boolean,
    val windowSeconds: Long? = null,
    /** Zero until the store assigns the durable reset row id. */
    val id: Long = 0,
)

fun isWeeklyUsageWindow(provider: String, windowKey: String, windowSeconds: Long?): Boolean =
    when (provider) {
        "claude" -> windowKey == "seven_day"
        "codex" -> windowSeconds == 7 * 24 * 60 * 60L
        else -> false
    }

val UsageReset.isNotificationEligible: Boolean
    get() = early && isWeeklyUsageWindow(provider, windowKey, windowSeconds)
