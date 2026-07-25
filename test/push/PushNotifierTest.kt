package io.kotgent.push

import io.kotgent.cli.withStartupCompensation
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PushNotifier] is the plumbing between [AttentionTracker] (which decides *whether* to notify, and is
 * tested on its own) and [PushSender] (which decides *what goes on the wire*, likewise). So these tests
 * assert only what this class adds: startup does not become ready until the baseline and subscription are
 * both installed, edge tracking does not wait for delivery, exactly the edges reach the sender, and
 * nothing — a throwing sender, an unreadable store — can end the collection or escape into the daemon's
 * background scope.
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
            val env = Env(sessions = listOf(meta("s1", SessionState.running))).start(this)
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
            val env = Env(sessions = listOf(meta("s1", SessionState.running))).start(this)
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
                sessions = listOf(meta("s1", SessionState.needs_approval), meta("s2", SessionState.running)),
            ).start(this)
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))
            env.store.emit(update("s2", SessionState.needs_approval))

            assertEquals(SessionId("s2"), env.sent.receive(), "only the session that actually changed notifies")
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "the already-waiting session never notified")
        }
    }

    // --- startup handoff ----------------------------------------------------------------------------

    @Test
    fun startReturnsOnlyAfterTheBaselineAndSubscriptionAreReady() = runBlocking {
        withTimeout(20_000) {
            val seedStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val env = Env(
                sessions = listOf(meta("s1", SessionState.running)),
                beforeListSessions = {
                    seedStarted.complete(Unit)
                    gate.await()
                },
            )
            val scope = this
            val starting = async(start = CoroutineStart.UNDISPATCHED) { env.start(scope) }

            seedStarted.await()
            assertTrue(!starting.isCompleted, "startup cannot report ready while the baseline is still blocked")
            assertEquals(0, env.store.subscriberCount(), "subscription is installed only after the baseline")
            gate.complete(Unit)
            starting.await()

            env.store.awaitSubscriber()
            env.store.emit(update("s1", SessionState.needs_approval))
            assertEquals(
                SessionId("s1"),
                env.sent.receive(),
                "the first update accepted after readiness is evaluated against the completed baseline",
            )
            env.stop()
        }
    }

    @Test
    fun cancellingStartJoinsAnIndependentWatcherBeforeCompensation() = runBlocking {
        withTimeout(20_000) {
            val seedStarted = CompletableDeferred<Unit>()
            val watcherCancelling = CompletableDeferred<Unit>()
            val allowWatcherToFinish = CompletableDeferred<Unit>()
            val watcherFinished = CompletableDeferred<Unit>()
            val compensationObservedFinished = CompletableDeferred<Boolean>()
            val notifierParent = SupervisorJob()
            val notifierScope = CoroutineScope(coroutineContext.minusKey(Job) + notifierParent)
            val env = Env(
                beforeListSessions = {
                    seedStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            watcherCancelling.complete(Unit)
                            allowWatcherToFinish.await()
                            watcherFinished.complete(Unit)
                        }
                    }
                },
            )

            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                withStartupCompensation(
                    compensate = {
                        compensationObservedFinished.complete(watcherFinished.isCompleted)
                    },
                ) {
                    env.start(notifierScope)
                }
            }

            try {
                seedStarted.await()
                caller.cancel()
                watcherCancelling.await()
                val compensationRanBeforeJoin = compensationObservedFinished.isCompleted
                allowWatcherToFinish.complete(Unit)
                caller.join()

                assertTrue(!compensationRanBeforeJoin, "compensation waits for watcher teardown")
                assertTrue(watcherFinished.isCompleted, "the independent watcher completed")
                assertTrue(compensationObservedFinished.await(), "compensation observed the completed watcher")
            } finally {
                allowWatcherToFinish.complete(Unit)
                caller.cancel()
                notifierParent.cancel()
            }
        }
    }

    @Test
    fun theBaselineIsTakenExactlyOnce() = runBlocking {
        withTimeout(20_000) {
            val env = Env(sessions = listOf(meta("s1", SessionState.running))).start(this)
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

    @Test
    fun theNotifierConsumesTheReliableSignalInsteadOfTheLossyUiSignal() = runBlocking {
        withTimeout(20_000) {
            val env = Env(
                sessions = listOf(
                    meta("ui-only", SessionState.running),
                    meta("reliable", SessionState.running),
                ),
            ).start(this)

            // The UI signal is deliberately DROP_OLDEST and may omit intermediate transitions. A notifier
            // wired to it would send ui-only first; the correctness signal carries only the second update.
            env.store.emitUiOnly(update("ui-only", SessionState.needs_approval))
            env.store.emitReliableOnly(update("reliable", SessionState.needs_approval))

            assertEquals(
                SessionId("reliable"),
                env.sent.receive(),
                "attention tracking uses the store's ordered reliable signal",
            )
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "the lossy UI-only update was ignored")
        }
    }

    @Test
    fun blockedDeliveryRetainsOnlyTheLatestWakeAndLaterEdgesStillDeliver() = runBlocking {
        withTimeout(20_000) {
            val firstDeliveryStarted = CompletableDeferred<Unit>()
            val releaseFirstDelivery = CompletableDeferred<Unit>()
            var first = true
            val env = Env(
                beforeSend = { id ->
                    if (first && id == SessionId("first")) {
                        first = false
                        firstDeliveryStarted.complete(Unit)
                        releaseFirstDelivery.await()
                    }
                },
            ).start(this)

            env.store.emit(update("first", SessionState.needs_approval))
            firstDeliveryStarted.await()

            // Every one is a real false -> true edge for a distinct session. The reliable source makes the
            // collector observe all of them, but a payload-less push fetches the complete /sessions list, so
            // retaining hundreds of stale ids while the endpoint is blocked adds no information.
            val burstSize = 512
            repeat(burstSize) { i ->
                env.store.emit(update("burst-$i", SessionState.needs_approval))
            }
            // Taking this following silent value proves the collector finished the previous callback, so
            // the last burst id is already in the conflated slot before delivery is released.
            env.store.emit(update("processed-barrier", SessionState.running))

            releaseFirstDelivery.complete(Unit)
            assertEquals(SessionId("first"), env.sent.receive())
            assertEquals(
                SessionId("burst-${burstSize - 1}"),
                env.sent.receive(),
                "only the latest pending wake survives the blocked delivery",
            )

            // This ordering assertion also proves the conflated slot was drained: an unlimited stale
            // backlog would put another burst id ahead of this later edge.
            env.store.emit(update("sentinel", SessionState.needs_approval))
            assertEquals(SessionId("sentinel"), env.sent.receive(), "a later edge still wakes delivery normally")
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "no stale burst remains queued")
        }
    }

    // --- nothing may end the collector --------------------------------------------------------------

    @Test
    fun aThrowingSenderDoesNotStopTheCollector() = runBlocking {
        withTimeout(20_000) {
            val env = Env(sessions = emptyList(), failSendFor = setOf(SessionId("s1"))).start(this)
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))
            assertEquals(SessionId("s1"), env.sent.receive(), "the failing send was still attempted")

            // Do not let conflation legally replace s1 before the worker takes it: only after observing the
            // throwing attempt do we prove its catch kept the worker alive for a later edge.
            env.store.emit(update("s2", SessionState.needs_approval))
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
            val env = Env(failListSessions = true).start(this)
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
            val parent = Job()
            val env = Env(sessions = emptyList()).start(CoroutineScope(parent))
            env.awaitSeeded()

            parent.cancel()
            env.job.join()

            assertTrue(env.job.isCancelled, "cancelling the supplied parent scope cancels and joins the collector")
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

        /** Matches the real UI signal: hot, replay-free, buffered, and allowed to drop old values. */
        private val updates = MutableSharedFlow<SessionUpdate>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val sessionUpdates: SharedFlow<SessionUpdate> get() = updates

        /** Matches the notifier signal: replay-free and unbuffered, so an active subscriber backpressures. */
        private val reliableUpdates = MutableSharedFlow<SessionUpdate>()
        override val reliableSessionUpdates: SharedFlow<SessionUpdate> get() = reliableUpdates

        /** How many times the baseline was taken — one, for the lifetime of a collector. */
        var listCalls = 0
            private set

        /** One production-shaped cache mutation publishes to both audiences in the same order. */
        suspend fun emit(update: SessionUpdate) {
            updates.tryEmit(update)
            reliableUpdates.emit(update)
        }

        /** Test seams proving [PushNotifier] chooses the reliable stream, not the UI stream. */
        suspend fun emitUiOnly(update: SessionUpdate) = updates.emit(update)
        suspend fun emitReliableOnly(update: SessionUpdate) = reliableUpdates.emit(update)

        /** Suspend until the notifier has subscribed (i.e. until `onSubscription` is about to run). */
        suspend fun awaitSubscriber() {
            reliableUpdates.subscriptionCount.first { it > 0 }
        }

        fun subscriberCount(): Int = reliableUpdates.subscriptionCount.value

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
        override suspend fun markRead(sessionId: SessionId, seq: Seq) {}
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = Seq(0L)
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
    }

    /** A running [PushNotifier] over a fake store, with its sends and its diagnostics captured. */
    private class Env(
        sessions: List<SessionMeta> = emptyList(),
        beforeListSessions: suspend () -> Unit = {},
        failListSessions: Boolean = false,
        failSendFor: Set<SessionId> = emptySet(),
        beforeSend: suspend (SessionId) -> Unit = {},
    ) {
        val store = FakeUpdatesStore(sessions, beforeListSessions, failListSessions)

        /** Every session the notifier asked to notify about, in order. Unlimited: a send never blocks. */
        val sent = Channel<SessionId>(Channel.UNLIMITED)

        val errors = mutableListOf<String>()

        private val notifier = PushNotifier(
            store = store,
            send = { id ->
                beforeSend(id)
                sent.send(id)
                if (id in failSendFor) throw IllegalStateException("push service unreachable")
            },
            onError = { errors += it },
        )

        lateinit var job: Job
            private set

        suspend fun start(scope: CoroutineScope): Env {
            job = notifier.start(scope)
            return this
        }

        /** Suspend until the baseline has been taken, so a test can emit into a fully-started collector. */
        suspend fun awaitSeeded() {
            store.awaitSubscriber()
            assertEquals(1, store.listCalls, "readiness includes exactly one completed baseline")
        }

        fun stop() {
            job.cancel()
        }
    }
}
