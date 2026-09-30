package io.kotgent.daemon

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.AgentAdapter
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.db.KotgentDatabase
import io.kotgent.mutex.MutexKey
import io.kotgent.store.MutexAcquireResult
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.SqliteMutexStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SessionEndListenersTest {
    private val provider = ProviderSessionId("abababab-abab-4bab-8bab-abababababab")
    private val keepsTranscript = VendorStoreProbe { _, _, _ -> true }
    private val losesTranscript = VendorStoreProbe { _, _, _ -> false }

    private fun meta(
        id: String,
        state: SessionState,
        paneId: PaneId? = null,
        source: EventSource = EventSource.system,
    ) = SessionMeta(
        id = SessionId(id),
        name = "kt-$id",
        agent = CLAUDE_AGENT_KIND,
        providerSessionId = provider,
        cwd = "/tmp",
        tmuxSession = "kt-$id",
        paneId = paneId,
        state = state,
        stateSource = source,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private class Recorder {
        val ended = mutableListOf<SessionId>()
        val listeners = SessionEndListeners().also { it.add { id -> ended += id } }
    }

    private fun CoroutineScope.manager(
        store: SqliteEventStore,
        tmux: FakeTmux,
        probe: VendorStoreProbe,
        recorder: Recorder,
    ) = SessionManager(
        tmux, store, PaneRegistry(),
        agentFactoryOf(emptyMap<String, (String) -> AgentAdapter>()),
        ProviderIdCapture(store, this),
        probe, VendorSessionLocator { _, _ -> null }, emptySet(),
        now = { 2_000L },
        onSessionEnded = recorder.listeners::fire,
    )

    @Test
    fun aClosedPaneEndsTheSessionEvenWhenItsHookAlreadyWroteTheFinalState() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            store.upsertSession(meta("hooked", SessionState.stopped, PaneId("%1"), EventSource.hook))
            val recorder = Recorder()
            val mgr = manager(store, FakeTmux(), keepsTranscript, recorder)

            mgr.onTmuxSessionClosed(SessionId("hooked"))

            assertEquals(SessionState.stopped, store.getSession(SessionId("hooked"))?.state)
            assertEquals(listOf(SessionId("hooked")), recorder.ended)
        }
    }

    @Test
    fun aHookForAPaneThatIsStillAliveDoesNotEndTheSession() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val pane = tmux.seedPane("alive")
            store.upsertSession(meta("alive", SessionState.running, pane))
            val recorder = Recorder()

            manager(store, tmux, keepsTranscript, recorder).onTmuxSessionClosed(SessionId("alive"))

            assertTrue(recorder.ended.isEmpty())
        }
    }

    @Test
    fun terminatingASessionEndsIt() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val pane = tmux.seedPane("doomed")
            store.upsertSession(meta("doomed", SessionState.running, pane))
            val recorder = Recorder()

            manager(store, tmux, keepsTranscript, recorder).stop(SessionId("doomed"))

            assertEquals(listOf("doomed"), tmux.killed)
            assertEquals(listOf(SessionId("doomed")), recorder.ended)
        }
    }

    @Test
    fun reconciliationEndsEverySessionWithoutAPane() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val pane = tmux.seedPane("alive")
            store.upsertSession(meta("alive", SessionState.running, pane))
            store.upsertSession(meta("gone", SessionState.running, PaneId("%9")))
            val recorder = Recorder()

            val _ = Reconciler(
                tmux, store, losesTranscript, PaneRegistry(),
                onSessionEnded = recorder.listeners::fire,
            ).reconcile()

            assertEquals(SessionState.lost, store.getSession(SessionId("gone"))?.state)
            assertEquals(listOf(SessionId("gone")), recorder.ended)
        }
    }

    @Test
    fun aListenerFailureNeitherReachesTheCallerNorStopsTheOthers() {
        val failures = mutableListOf<Throwable>()
        val reached = mutableListOf<SessionId>()
        val listeners = SessionEndListeners(onError = { failures += it })
        listeners.add { error("broken listener") }
        listeners.add { reached += it }

        listeners.fire(SessionId("s1"))

        assertEquals(listOf(SessionId("s1")), reached)
        assertEquals("broken listener", failures.single().message)
    }

    @Test
    fun aRestartReleasesTheHoldingOfASessionThatDiedWhileTheDaemonWasDown() = runBlocking {
        withTimeout(20.seconds) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val workers = Job()
            try {
                val scope = CoroutineScope(coroutineContext + workers)
                val key = MutexKey("kotlin-build")
                val before = SqliteMutexStore(driver, scope)
                assertIs<MutexAcquireResult.Acquired>(before.acquire(key, SessionId("dead"), null, Duration.ZERO))

                val store = SqliteEventStore.inMemory(now = { 1L })
                store.upsertSession(meta("dead", SessionState.running, PaneId("%3")))
                val restarted = SqliteMutexStore(driver, scope)
                assertEquals(1, restarted.listing.value.entries.size)
                val listeners = SessionEndListeners().also { it.add(SessionEndListener(restarted::sessionEnded)) }

                val _ = Reconciler(
                    FakeTmux(), store, keepsTranscript, PaneRegistry(),
                    onSessionEnded = listeners::fire,
                ).reconcile()

                val _ = restarted.listing.first { it.entries.isEmpty() }
            } finally {
                workers.cancel()
                driver.close()
            }
        }
    }
}
