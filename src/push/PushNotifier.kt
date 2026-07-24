package io.kotgent.push

import io.kotgent.cli.eprintln
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * The push feature's one long-lived job: watch every session's state and fire a Web Push the moment one
 * starts waiting on the human.
 *
 * It is the thin edge between two things that are already tested on their own — [AttentionTracker] (pure
 * `false → true` edge detection) and [PushSender] (the wire format and the store pruning) — so all it owns
 * is the *plumbing*: subscribe, seed, and never let a failure escape.
 *
 * ## Startup is a barrier, not a fire-and-forget launch
 * [EventStore.sessionUpdates] is a hot `SharedFlow` with **`replay = 0`**: nothing emitted before this
 * collector subscribed is ever delivered. Conversely, taking the [EventStore.listSessions] baseline from
 * inside `onSubscription` lets a write land in the shared-flow buffer *before* the snapshot read; that
 * write is then already reflected by the snapshot and its genuine `false → true` edge is suppressed when
 * the buffered level is collected.
 *
 * [start] closes both windows by being a suspending readiness barrier: it seeds first, subscribes second,
 * and returns only once the subscription is installed. The daemon awaits it before binding the HTTP
 * server, so no hook can write in the seed-to-subscribe interval and every hook accepted after the bind
 * has a live collector waiting for it.
 *
 * Without any seed at all the failure is the daemon-restart one: the reconciler's startup writes about a
 * session that was ALREADY blocked before the restart would each look like a new approval.
 *
 * ## Nothing here may take the daemon down
 * This runs on the daemon's background scope. An exception escaping a `launch` on Kotlin/Native reaches
 * the unhandled-exception hook, so a push failure could kill the process — the exact opposite of what an
 * optional convenience feature is allowed to do. Hence three nested guards: a failing send is reported and
 * the collection continues, a failing seed is reported and collection continues with an empty baseline
 * (worth at most one spurious notification per waiting session — far better than no notifications at all),
 * and the whole collection is wrapped so even a store-level failure ends this job quietly instead of the
 * daemon. [CancellationException] is always re-thrown: swallowing it would detach this from the scope that
 * owns it.
 *
 * @param store the source of both the baseline ([EventStore.listSessions]) and the live signal
 *   ([EventStore.sessionUpdates]).
 * @param send delivers the notification for one session — `PushSender::send` in production. A function
 *   seam rather than the [PushSender] type itself, matching how the rest of this package injects its
 *   edges (`VapidTokenCache(sign = signer::sign)`,
 *   `PushSender(vapidToken = cache::tokenFor)`): it keeps the wire format out of these tests, and it is the
 *   only way to exercise "a throwing sender does not stop the collector" — a real [PushSender] swallows
 *   everything by design.
 * @param tracker the edge detector. Injectable but never shared: it is deliberately unsynchronized and is
 *   confined to the single collector coroutine started here.
 * @param onError where a swallowed failure is reported; stderr in production, a collector in tests.
 */
class PushNotifier(
    private val store: EventStore,
    private val send: suspend (SessionId) -> Unit,
    private val tracker: AttentionTracker = AttentionTracker(),
    private val onError: (String) -> Unit = ::eprintln,
) {

    /**
     * Launch the collector on [scope], await its seeded-and-subscribed readiness point, and return its
     * [Job] (the daemon cancels it by cancelling the scope).
     *
     * Start this AFTER the daemon's startup reconciliation has finished writing: those writes are evaluated
     * against the world as it was found, and the baseline this takes must already include them. The server
     * must be bound only AFTER this function returns: that ordering is what makes the seed-before-subscribe
     * handoff lossless for hook updates.
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
            job.cancel()
            throw e
        }
        return job
    }

    /**
     * Seed, then collect until the flow ends or the coroutine is cancelled.
     *
     * [onSubscribed] is the readiness edge used by [start]. A caller invoking this directly owes the same
     * contract: no live producer may write between the baseline and subscription.
     */
    suspend fun run(onSubscribed: () -> Unit = {}) = coroutineScope {
        seed()

        // Edge detection must never wait for an endpoint. One delivery can spend 20 seconds per device;
        // keeping those waits on a separate queue lets the shared-flow collector record every level
        // transition promptly instead of overflowing the store's DROP_OLDEST buffer.
        val deliveries = Channel<SessionId>(Channel.UNLIMITED)
        launch {
            for (sessionId in deliveries) deliver(sessionId)
        }

        try {
            store.sessionUpdates
                .onSubscription { onSubscribed() }
                .collect { update ->
                    if (tracker.isNewAttention(update)) deliveries.send(update.sessionId)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The store's own signal broke. Push stops until the next daemon start; everything else lives on.
            onError("push: the notification watcher stopped, no further notifications will be sent: ${e.describe()}")
        } finally {
            deliveries.close()
        }
    }

    /** The baseline: what was already waiting before this collector existed. See the class KDoc. */
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

    /** One queued edge: deliver it without ever letting a notification failure end the worker. */
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
