package io.kotgent.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

class ExchangeRateLimit(
    private val now: () -> Long = ::rateLimitMonotonicMillis,
    private val max: Int = EXCHANGE_FAILURE_LIMIT,
    private val windowMillis: Long = EXCHANGE_WINDOW_MILLIS,
) {
    // Global by design: tunnelled requests all have the same loopback peer, so per-IP identity is unusable.
    init {
        require(max > 0) { "the failed-exchange budget must be positive, got $max" }
        require(windowMillis > 0) { "the rate-limit window must be positive, got $windowMillis ms" }
    }

    private val mutex = Mutex()

    private val failures = ArrayDeque<Long>()

    private val inFlight = mutableSetOf<Attempt>()

    suspend fun begin(): Attempt? = mutex.withLock {
        // Reservations count immediately so a concurrent burst cannot all observe spare capacity.
        val at = now()
        pruneAged(at)
        if (failures.size + inFlight.size >= max) return@withLock null
        Attempt(this).also { inFlight.add(it) }
    }

    class Attempt internal constructor(private val owner: ExchangeRateLimit) {
        suspend fun finish(failed: Boolean) {
            owner.finish(this, failed)
        }
    }

    private suspend fun finish(attempt: Attempt, failed: Boolean) = mutex.withLock {
        check(inFlight.remove(attempt)) { "exchange attempt has already been finished" }
        val at = now()
        pruneAged(at)
        if (failed) failures.addLast(at)
    }

    suspend fun failuresInWindow(): Int = mutex.withLock {
        pruneAged(now())
        failures.size
    }

    private fun pruneAged(at: Long) {
        failures.removeAll { failureAt -> at - failureAt >= windowMillis }
    }
}

const val EXCHANGE_FAILURE_LIMIT: Int = 10

const val EXCHANGE_WINDOW_MILLIS: Long = 60_000L

// Wall-clock corrections must neither extend nor prematurely clear the rolling window.
private val rateLimitMonotonicOrigin = TimeSource.Monotonic.markNow()

private fun rateLimitMonotonicMillis(): Long = rateLimitMonotonicOrigin.elapsedNow().inWholeMilliseconds
