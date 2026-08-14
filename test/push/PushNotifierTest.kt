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

class PushNotifierTest {

    private fun meta(id: String, state: SessionState, archived: Boolean = false) = SessionMeta(
        id = SessionId(id), name = id, agent = "claude", cwd = "/w",
        tmuxSession = "kt-$id", state = state, createdAt = 1L, updatedAt = 1L, archived = archived,
    )

    private fun update(id: String, state: SessionState, archived: Boolean = false) = SessionUpdate(
        sessionId = SessionId(id), state = state, lastSeq = Seq(1), unread = 0L, updatedAt = 1L,
        archived = archived,
    )


    @Test
    fun aTransitionIntoAttentionSendsExactlyOnceForThatSession() = runBlocking {
        withTimeout(20_000) {
            val env = Env(sessions = listOf(meta("s1", SessionState.running))).start(this)
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))

            assertEquals(SessionId("s1"), env.sent.receive(), "the edge notifies about the session that moved")
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

            env.store.emit(update("s1", SessionState.running))
            env.store.emit(update("s1", SessionState.ready))
            env.store.emit(update("s1", SessionState.stopped))
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

            val burstSize = 512
            repeat(burstSize) { i ->
                env.store.emit(update("burst-$i", SessionState.needs_approval))
            }
            env.store.emit(update("processed-barrier", SessionState.running))

            releaseFirstDelivery.complete(Unit)
            assertEquals(SessionId("first"), env.sent.receive())
            assertEquals(
                SessionId("burst-${burstSize - 1}"),
                env.sent.receive(),
                "only the latest pending wake survives the blocked delivery",
            )

            env.store.emit(update("sentinel", SessionState.needs_approval))
            assertEquals(SessionId("sentinel"), env.sent.receive(), "a later edge still wakes delivery normally")
            env.stop()
            assertTrue(env.sent.tryReceive().isFailure, "no stale burst remains queued")
        }
    }


    @Test
    fun aThrowingSenderDoesNotStopTheCollector() = runBlocking {
        withTimeout(20_000) {
            val env = Env(sessions = emptyList(), failSendFor = setOf(SessionId("s1"))).start(this)
            env.awaitSeeded()

            env.store.emit(update("s1", SessionState.needs_approval))
            assertEquals(SessionId("s1"), env.sent.receive(), "the failing send was still attempted")

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


    private class FakeUpdatesStore(
        private val sessions: List<SessionMeta>,
        private val beforeListSessions: suspend () -> Unit,
        private val failListSessions: Boolean,
    ) : EventStore {

        // Mirrors the lossy UI signal; the notifier must not consume this one.
        private val updates = MutableSharedFlow<SessionUpdate>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val sessionUpdates: SharedFlow<SessionUpdate> get() = updates

        // Mirrors the unbuffered reliable signal and its producer backpressure.
        private val reliableUpdates = MutableSharedFlow<SessionUpdate>()
        override val reliableSessionUpdates: SharedFlow<SessionUpdate> get() = reliableUpdates

        var listCalls = 0
            private set

        suspend fun emit(update: SessionUpdate) {
            updates.tryEmit(update)
            reliableUpdates.emit(update)
        }

        suspend fun emitUiOnly(update: SessionUpdate) = updates.emit(update)
        suspend fun emitReliableOnly(update: SessionUpdate) = reliableUpdates.emit(update)

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
        override suspend fun setModel(sessionId: SessionId, model: String?) {}
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: io.kotgent.core.ProviderSessionId,
            model: String,
        ): Boolean = false
        override suspend fun markRead(sessionId: SessionId, seq: Seq) {}
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = Seq(0L)
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
    }

    private class Env(
        sessions: List<SessionMeta> = emptyList(),
        beforeListSessions: suspend () -> Unit = {},
        failListSessions: Boolean = false,
        failSendFor: Set<SessionId> = emptySet(),
        beforeSend: suspend (SessionId) -> Unit = {},
    ) {
        val store = FakeUpdatesStore(sessions, beforeListSessions, failListSessions)

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

        suspend fun awaitSeeded() {
            store.awaitSubscriber()
            assertEquals(1, store.listCalls, "readiness includes exactly one completed baseline")
        }

        fun stop() {
            job.cancel()
        }
    }
}
