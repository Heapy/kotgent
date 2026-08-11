package io.kotgent.daemon

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface CaptureResult {
    data class Bound(val providerSessionId: ProviderSessionId) : CaptureResult
    data object Pending : CaptureResult
}

class ProviderIdCapture(
    private val store: EventStore,
    private val scope: CoroutineScope,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
    private val source: EventSource = EventSource.system,
) {

    // Hooks may race preallocation, so binding is intentionally idempotent.
    suspend fun bind(sessionId: SessionId, providerId: ProviderSessionId): Boolean {
        if (store.projectionOf(sessionId).providerSessionId != null) return false
        store.append(sessionId, AgentEvent.SessionBound(providerId), source)
        return true
    }

    suspend fun captureWithFallback(
        sessionId: SessionId,
        discover: suspend () -> ProviderSessionId?,
    ): CaptureResult {
        // Exhaustion remains explicit: an unbound session must not silently become resumable.
        repeat(maxAttempts) { attempt ->
            val discovered = discover()
            if (discovered != null) {
                bind(sessionId, discovered)
                return CaptureResult.Bound(discovered)
            }
            if (attempt < maxAttempts - 1) delay(retryDelayMillis)
        }
        return CaptureResult.Pending
    }

    fun captureInBackground(
        sessionId: SessionId,
        discover: suspend () -> ProviderSessionId?,
    ): Job = scope.launch { captureWithFallback(sessionId, discover) }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 20
        const val DEFAULT_RETRY_DELAY_MS: Long = 250
    }
}
