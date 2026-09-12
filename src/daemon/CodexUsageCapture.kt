package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.UsageObservation
import io.kotgent.store.EventStore
import io.kotgent.store.UsageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class CodexUsageCapture(
    private val scope: CoroutineScope,
    private val events: EventStore,
    private val usage: UsageStore,
    private val rateLimitsOf: suspend (ProviderSessionId) -> List<UsageObservation>,
    private val awaitRetry: suspend () -> Unit = { delay(250) },
    private val timeoutMillis: Long = 5_000,
    private val onError: (Throwable) -> Unit = {},
) {
    init {
        require(timeoutMillis > 0) { "Codex usage capture timeout must be positive" }
    }

    /** The Stop hook must return before any rollout scan or retry completes. */
    suspend fun onTurnCompleted(sessionId: SessionId) {
        val _ = scope.launch {
            try {
                withTimeout(timeoutMillis) {
                    if (!captureOnce(sessionId)) return@withTimeout
                    // A readable tail may still end in the previous turn's record.
                    awaitRetry()
                    val _ = captureOnce(sessionId)
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                onError(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onError(failure)
            }
        }
    }

    private suspend fun captureOnce(sessionId: SessionId): Boolean {
        try {
            currentCoroutineContext().ensureActive()
            val session = events.getSession(sessionId) ?: return true
            if (session.agent != "codex") return false
            val providerId = session.providerSessionId ?: return true
            val observations = rateLimitsOf(providerId)
            currentCoroutineContext().ensureActive()
            for (observation in observations) usage.observe(observation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onError(failure)
        }
        return true
    }
}
