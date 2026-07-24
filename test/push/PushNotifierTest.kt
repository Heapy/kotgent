package io.kotgent.push

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PushNotifier] is the plumbing between [AttentionTracker] (which decides *whether* to notify, and is
 * tested on its own) and [PushSender] (which decides *what goes on the wire*, likewise). So these tests
 * assert only what this class adds: that the baseline is taken **after** subscribing, that exactly the
 * edges reach the sender, and that nothing — a throwing sender, an unreadable store — can end the
 * collection or escape into the daemon's background scope.
 *
 * The sender is a lambda writing into a [Channel] rather than a real [PushSender]: a real one swallows
 * every failure by design, so "a throwing sender does not stop the collector" would be untestable through
 * it, and re-asserting the header format here would only duplicate `PushSenderTest`.
 *
 * Negative assertions ("this update sends nothing") are written as ORDERING assertions — emit the silent
 * update, then a loud one, and require the loud one to be what arrives first — because a bare "the channel
 * is empty" would also pass if the collector had simply not run yet. Every body is bounded by
 * [withTimeout] (anti-hang, matching the rest of the suite).
 */
class PushNotifierTest {

    private fun meta(id: String, state: SessionState, archived: Boolean = false) = SessionMeta(
        id = SessionId(id), name = id, agent = "claude", cwd = "/w",
        tmuxSession = "kt-$id", state = state, createdAt = 1L, updatedAt = 1L, archived = archived,
    )

    private fun update(id: String, state: SessionState, archived: Boolean = false) = SessionUpdate(
        sessionId = SessionId(id), state = state, lastSeq = Seq(1), unread = 0L, archived = archived,
    )

    // --- what reaches the sender --------------------------------------------------------------------

    @Test
    fun aTransitionIntoAttentionSendsExactlyOnceForThatSession() = runBlocking {
        withTimeout(20_000) {
            val env = Env(this, sessions = listOf(meta("s1", SessionState.running)))
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))

            assertEquals(SessionId("s1"), env.sent.receive(), "the edge notifies about the session that moved")
            // A level re-emit (the /events resync writes one every few seconds) must not ring again; the
            // next arrival has to be the OTHER session, which proves the repeat was seen and dropped.
            env.store.emit(update("s1", SessionState.needs_approval))
            env.store.emit(update("s2", SessionState.needs_approval))
            assertEquals(SessionId("s2"), env.sent.receive(), "only the second session's own edge follows")
            assertTrue(env.errors.isEmpty(), "a clean run reports nothing: ${env.errors}")
            env.stop()
        }
    }

    @Test
    fun anUpdateThatIsNotATransitionSendsNothing() = runBlocking {
        withTimeout(20_000) {
            val env = Env(this, sessions = listOf(meta("s1", SessionState.running)))
            env.awaitSeeded()

            // Neither of these is an edge into "waiting on the human".
            env.store.emit(update("s1", SessionState.running))
            env.store.emit(update("s1", SessionState.ready))
            env.store.emit(update("s1", SessionState.stopped))
            // Archived is not "waiting" either — the service worker filters those out of /sessions, so a
            // push about one would wake the phone only to show the generic filler banner.
            env.store.emit(update("s1", SessionState.needs_approval, archived = true))
            env.store.emit(update("s2", SessionState.needs_approval))

            assertEquals(SessionId("s2"), env.sent.receive(), "the first notification is the first real edge")
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "and nothing else was queued behind it")
        }
    }

    @Test
    fun aSessionAlreadyWaitingAtSeedTimeSendsNothing() = runBlocking {
        withTimeout(20_000) {
            // The daemon-restart case: `s1` was blocked on an approval before the restart, and the
            // reconciler's startup write re-emits that same level. Without the seed it would ring.
            val env = Env(
                this,
                sessions = listOf(meta("s1", SessionState.needs_approval), meta("s2", SessionState.running)),
            )
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))
            env.store.emit(update("s2", SessionState.needs_approval))

            assertEquals(SessionId("s2"), env.sent.receive(), "only the session that actually changed notifies")
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "the already-waiting session never notified")
        }
    }

    // --- the onSubscription idiom -------------------------------------------------------------------

    @Test
    fun theBaselineIsTakenAfterSubscribingSoNoUpdateIsLost() = runBlocking {
        withTimeout(20_000) {
            // `sessionUpdates` has replay = 0. This holds the seed open, emits while it is still running,
            // and requires both updates to be delivered afterwards: with the seed OUTSIDE onSubscription
            // there is no subscriber yet at that moment and both emissions would vanish.
            val gate = CompletableDeferred<Unit>()
            val env = Env(
                this,
                sessions = listOf(meta("s1", SessionState.needs_approval)),
                beforeListSessions = { gate.await() },
            )
            env.store.awaitSubscriber()

            env.store.emit(update("s1", SessionState.needs_approval))
            env.store.emit(update("s2", SessionState.needs_approval))
            gate.complete(Unit)

            // s1 is silent (the baseline says it was already waiting) and s2 fires — which together prove
            // the buffered updates were delivered AND evaluated against the seed, not against an empty map.
            assertEquals(SessionId("s2"), env.sent.receive(), "an update emitted during the seed is not lost")
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "and the seeded session stayed silent")
        }
    }

    @Test
    fun theBaselineIsTakenExactlyOnce() = runBlocking {
        withTimeout(20_000) {
            val env = Env(this, sessions = listOf(meta("s1", SessionState.running)))
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))
            assertEquals(SessionId("s1"), env.sent.receive())
            env.store.emit(update("s2", SessionState.needs_approval))
            assertEquals(SessionId("s2"), env.sent.receive())

            // Re-seeding mid-stream would wipe the tracker's memory and make the next level re-emit look
            // like a fresh edge — the exact re-notify loop the seed exists to prevent.
            assertEquals(1, env.store.listCalls, "the snapshot is a baseline, not a per-update refresh")
            env.stop()
        }
    }

    // --- nothing may end the collector --------------------------------------------------------------

    @Test
    fun aThrowingSenderDoesNotStopTheCollector() = runBlocking {
        withTimeout(20_000) {
            val env = Env(this, sessions = emptyList(), failSendFor = setOf(SessionId("s1")))
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))
            env.store.emit(update("s2", SessionState.needs_approval))

            assertEquals(SessionId("s1"), env.sent.receive(), "the failing send was still attempted")
            assertEquals(SessionId("s2"), env.sent.receive(), "and the next update is still delivered")
            env.stop()
            assertTrue(
                env.errors.any { it.contains("s1") },
                "the swallowed failure names the session it was about: ${env.errors}",
            )
        }
    }

    @Test
    fun anUnreadableSessionListDoesNotStopTheCollector() = runBlocking {
        withTimeout(20_000) {
            // A failing baseline is reported and collection continues with an empty one: at worst one
            // spurious notification per already-waiting session, which beats no notifications at all.
            val env = Env(this, failListSessions = true)
            env.store.awaitSubscriber()

            env.store.emit(update("s1", SessionState.needs_approval))

            assertEquals(SessionId("s1"), env.sent.receive(), "updates are still collected without a baseline")
            env.stop()
            assertTrue(
                env.errors.any { it.contains("database is locked") },
                "the failure is reported with its cause: ${env.errors}",
            )
        }
    }

    @Test
    fun cancellingTheScopeEndsTheCollector() = runBlocking {
        withTimeout(20_000) {
            val env = Env(this, sessions = emptyList())
            env.awaitSeeded()

            env.stop()

            assertTrue(env.job.isCancelled, "the daemon cancels its background scope on shutdown")
            assertTrue(env.errors.isEmpty(), "an ordinary cancellation is not a failure: ${env.errors}")
        }
    }

    // --- harness ------------------------------------------------------------------------------------

    /**
     * The minimum [EventStore] a notifier touches: the non-replaying [sessionUpdates] signal and the
     * [listSessions] baseline. Everything else is unreachable from this class and returns an empty value.
     */
    private class FakeUpdatesStore(
        private val sessions: List<SessionMeta>,
        private val beforeListSessions: suspend () -> Unit,
        private val failListSessions: Boolean,
    ) : EventStore {

        /** Matches the real store's shape: hot, `replay = 0`, buffered so an emit does not block. */
        private val updates = MutableSharedFlow<SessionUpdate>(replay = 0, extraBufferCapacity = 64)
        override val sessionUpdates: SharedFlow<SessionUpdate> get() = updates

        /** How many times the baseline was taken — one, for the lifetime of a collector. */
        var listCalls = 0
            private set

        suspend fun emit(update: SessionUpdate) = updates.emit(update)

        /** Suspend until the notifier has subscribed (i.e. until `onSubscription` is about to run). */
        suspend fun awaitSubscriber() {
            updates.subscriptionCount.first { it > 0 }
        }

        override suspend fun listSessions(): List<SessionMeta> {
            listCalls++
            beforeListSessions()
            if (failListSessions) throw IllegalStateException("cannot list sessions: database is locked")
            return sessions
        }

        override suspend fun upsertSession(meta: SessionMeta) {}
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ) {}
        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {}
        override suspend fun setModel(sessionId: SessionId, model: String, updatedAt: Long) {}
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = Seq(0L)
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
    }

    /** A running [PushNotifier] over a fake store, with its sends and its diagnostics captured. */
    private class Env(
        scope: CoroutineScope,
        sessions: List<SessionMeta> = emptyList(),
        beforeListSessions: suspend () -> Unit = {},
        failListSessions: Boolean = false,
        failSendFor: Set<SessionId> = emptySet(),
    ) {
        val store = FakeUpdatesStore(sessions, beforeListSessions, failListSessions)

        /** Every session the notifier asked to notify about, in order. Unlimited: a send never blocks. */
        val sent = Channel<SessionId>(Channel.UNLIMITED)

        val errors = mutableListOf<String>()

        val job: Job = PushNotifier(
            store = store,
            send = { id ->
                sent.send(id)
                if (id in failSendFor) throw IllegalStateException("push service unreachable")
            },
            onError = { errors += it },
        ).start(scope)

        /** Suspend until the baseline has been taken, so a test can emit into a fully-started collector. */
        suspend fun awaitSeeded() {
            store.awaitSubscriber()
            // The seed runs inside onSubscription, i.e. immediately after the subscription is visible;
            // yielding until listSessions has been counted removes the last bit of start-up ordering.
            while (store.listCalls == 0) yield()
        }

        fun stop() {
            job.cancel()
        }
    }
}
