package io.kotgent.push

import io.kotgent.cli.eprintln
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the reliable, replay-free session update flow for attention edges. [start] is a readiness
 * barrier: it seeds the restart baseline, subscribes, then returns before the daemon accepts hooks. The
 * lossy UI flow cannot be used because dropping a leave/re-entry pair would suppress later edges.
 * Optional push failures are reported but never escape this background job; cancellation still propagates.
 */
class PushNotifier(
    private val store: EventStore,
    private val send: suspend (SessionId) -> Unit,
    private val tracker: AttentionTracker = AttentionTracker(),
    private val onError: (String) -> Unit = ::eprintln,
) {

    /**
     * Call after reconciliation and before binding the server; those boundaries make the seed-to-subscribe
     * handoff lossless.
     */
    suspend fun start(scope: CoroutineScope): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            run(onSubscribed = { subscribed.complete(Unit) })
        }
        job.invokeOnCompletion { cause ->
            subscribed.completeExceptionally(
                cause ?: IllegalStateException("the push notification watcher ended before subscribing"),
            )
        }
        try {
            subscribed.await()
        } catch (e: Throwable) {
            // The caller and owning scope can cancel independently; do not leave a startup child behind.
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw e
        }
        return job
    }

    private suspend fun run(onSubscribed: () -> Unit) = coroutineScope {
        seed()

        // Payload-less pushes fetch current state, so one conflated follow-up wake preserves information.
        val deliveries = Channel<SessionId>(Channel.CONFLATED)
        launch {
            for (sessionId in deliveries) deliver(sessionId)
        }

        try {
            store.reliableSessionUpdates
                .onSubscription { onSubscribed() }
                .collect { update ->
                    if (tracker.isNewAttention(update)) deliveries.send(update.sessionId)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: the notification watcher stopped, no further notifications will be sent: ${e.describe()}")
        } finally {
            deliveries.close()
        }
    }

    private suspend fun seed() {
        val sessions = try {
            store.listSessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: cannot read the session list to seed the notification baseline: ${e.describe()}")
            return
        }
        tracker.seed(sessions)
    }

    private suspend fun deliver(sessionId: SessionId) {
        try {
            send(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: cannot notify about session ${sessionId.value}: ${e.describe()}")
        }
    }
}
