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

/**
 * The outcome of guaranteeing a session's provider id (Task 13):
 *  - [Bound]   — a `SessionBound(providerSessionId)` is (or is now) in the log; resume is unblocked.
 *  - [Pending] — the id could not be captured within the retry budget; the session stays "id pending"
 *    (its `provider_session_id` is still null → [SessionManager.resume] refuses it), never silently lost.
 */
sealed interface CaptureResult {
    data class Bound(val providerSessionId: ProviderSessionId) : CaptureResult
    data object Pending : CaptureResult
}

/**
 * Guarantees every started session gets its provider id saved (plan Task 13, "provider-id capture").
 *
 * Two paths, mirroring [io.kotgent.adapter.LaunchSpec.preallocatedSessionId]:
 *  - **Preallocated (primary).** A modern `claude --session-id <uuid>` gives us the id up front, so
 *    [bind] appends [AgentEvent.SessionBound] immediately (source [EventSource.system] by default) —
 *    the id is in the log before the agent even emits a hook.
 *  - **Fallback (older claude without `--session-id`).** No id up front; the `SessionStart` hook is
 *    expected to deliver it later (the ingress appends `SessionBound(source=hook)`). [captureWithFallback]
 *    polls [discover] on a bounded retry, binding when it appears; if the budget runs out the session is
 *    reported [CaptureResult.Pending] — "id pending", resume blocked — rather than quietly dropped.
 *
 * For the v1 slice the preallocated path is primary; the pending/retry logic is present and tested so
 * the fallback is honest (and so `resume` can be blocked while an id is genuinely missing).
 */
class ProviderIdCapture(
    private val store: EventStore,
    /** Scope the background capture ([captureInBackground]) runs on. */
    private val scope: CoroutineScope,
    /** Max polls before giving up and reporting [CaptureResult.Pending]. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Delay between polls. */
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
    /** Provenance stamped on a [bind]-appended `SessionBound` (preallocation is [EventSource.system]). */
    private val source: EventSource = EventSource.system,
) {

    /**
     * Append `SessionBound(providerId)` to [sessionId]'s log, unless the session is already bound.
     * Returns `true` if it appended, `false` if the id was already present (idempotent — safe to call
     * on the preallocated path even if a `SessionStart` hook raced in first). This is the primary path.
     */
    suspend fun bind(sessionId: SessionId, providerId: ProviderSessionId): Boolean {
        if (store.projectionOf(sessionId).providerSessionId != null) return false
        store.append(sessionId, AgentEvent.SessionBound(providerId), source)
        return true
    }

    /**
     * Fallback capture: poll [discover] up to [maxAttempts] times (sleeping [retryDelayMillis] between
     * tries). On the first non-null id, [bind] it (idempotently) and return [CaptureResult.Bound];
     * if every attempt yields null, return [CaptureResult.Pending] (the session stays "id pending",
     * resume blocked). [discover] is injected so the daemon can poll the store for a hook-delivered
     * `SessionBound` (`{ store.projectionOf(id).providerSessionId }`) while tests drive it directly.
     */
    suspend fun captureWithFallback(
        sessionId: SessionId,
        discover: suspend () -> ProviderSessionId?,
    ): CaptureResult {
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

    /** Kick off [captureWithFallback] as a bounded background job (the daemon's fire-and-forget path). */
    fun captureInBackground(
        sessionId: SessionId,
        discover: suspend () -> ProviderSessionId?,
    ): Job = scope.launch { captureWithFallback(sessionId, discover) }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 20
        const val DEFAULT_RETRY_DELAY_MS: Long = 250
    }
}
