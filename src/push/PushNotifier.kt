package io.kotgent.push

import io.kotgent.cli.eprintln
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * ## Why the seed lives inside `onSubscription`
 * [EventStore.sessionUpdates] is a hot `SharedFlow` with **`replay = 0`**: nothing emitted before this
 * collector subscribed is ever delivered. Taking the [EventStore.listSessions] baseline *before*
 * subscribing would open a window in which an update is both missing from the snapshot and already gone
 * from the flow — the session would then look like it had never been waiting, and its next level re-emit
 * (the `/events` resync writes one every few seconds) would read as a fresh edge and ring the phone about
 * something the operator answered ages ago. `onSubscription` runs *after* the subscription exists, so
 * anything emitted meanwhile is buffered and delivered right after the seed. This is verbatim the idiom
 * `EventsWs.streamGlobalUpdates` uses for the browser's snapshot, and for the same reason.
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
 * @param send delivers the notification for one session — `PushSender::send` in production, wrapped so its
 *   accepted-count return value is discarded. A function seam rather than the [PushSender] type itself,
 *   matching how the rest of this package injects its edges (`VapidTokenCache(sign = signer::sign)`,
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
     * Launch the collector on [scope] and return its [Job] (the daemon cancels it by cancelling the scope).
     *
     * Start this AFTER the daemon's startup reconciliation has finished writing: those writes are evaluated
     * against the world as it was found, and the baseline this takes must already include them.
     */
    fun start(scope: CoroutineScope): Job = scope.launch { run() }

    /**
     * Collect until the flow ends or the coroutine is cancelled. Public so a test can drive it directly on
     * its own scope instead of guessing at [start]'s timing.
     */
    suspend fun run() {
        try {
            store.sessionUpdates
                .onSubscription { seed() }
                .collect { update -> deliver(update) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The store's own signal broke. Push stops until the next daemon start; everything else lives on.
            onError("push: the notification watcher stopped, no further notifications will be sent: ${e.describe()}")
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

    /** One update: notify only on the edge, and never let the notification's failure end the collection. */
    private suspend fun deliver(update: SessionUpdate) {
        if (!tracker.isNewAttention(update)) return
        try {
            send(update.sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: cannot notify about session ${update.sessionId.value}: ${e.describe()}")
        }
    }
}
