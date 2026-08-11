package io.kotgent.daemon

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.SqliteEventStore
import io.kotgent.tmux.TmuxPane
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcilerTest {

    private fun meta(
        idV: String,
        state: SessionState,
        providerId: ProviderSessionId? = null,
        paneId: PaneId? = null,
        agent: String = "claude",
    ) = SessionMeta(
        id = SessionId(idV),
        name = "kt-$idV",
        agent = agent,
        providerSessionId = providerId,
        cwd = "/tmp",
        tmuxSession = "kt-$idV",
        paneId = paneId,
        state = state,
        stateSource = EventSource.system,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun uuid(c: Char): ProviderSessionId =
        ProviderSessionId("$c$c$c$c$c$c$c$c-$c$c$c$c-4$c$c$c-8$c$c$c-$c$c$c$c$c$c$c$c$c$c$c$c")


    @Test
    fun classifyIsExhaustiveOverTheTruthTable() {
        assertEquals(SessionState.running, Reconciler.classify(true, SessionState.running, stopIntent = false, transcriptExists = false))
        assertEquals(SessionState.needs_approval, Reconciler.classify(true, SessionState.needs_approval, false, false), "alive keeps a finer live state")
        assertEquals(SessionState.running, Reconciler.classify(true, SessionState.crashed, false, false), "alive corrects a stale dead state up to running")

        assertEquals(SessionState.stopped, Reconciler.classify(false, SessionState.running, stopIntent = true, transcriptExists = true))
        assertEquals(SessionState.stopped, Reconciler.classify(false, SessionState.running, stopIntent = true, transcriptExists = false))

        assertEquals(SessionState.resumable, Reconciler.classify(false, SessionState.running, stopIntent = false, transcriptExists = true))
        assertEquals(SessionState.crashed, Reconciler.classify(false, SessionState.running, stopIntent = false, transcriptExists = false))
    }


    @Test
    fun reconcileClassifiesEachCombinationAndRebuildsRegistryFromLivePanes() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })

            val idA = uuid('a')
            val idB = uuid('b')
            val idC = uuid('c')
            val idD = uuid('d')

            store.upsertSession(meta("alive", SessionState.running, providerId = idA, paneId = null))
            store.upsertSession(meta("stopd", SessionState.stopped, providerId = idB))
            store.upsertSession(meta("resum", SessionState.running, providerId = idC))
            store.upsertSession(meta("crash", SessionState.running, providerId = idD))
            store.upsertSession(meta("noid", SessionState.running, providerId = null))

            val livePane = PaneId("%10")
            val tmux = FakeTmux(
                seedPanes = listOf(
                    TmuxPane(session = "kt-alive", paneId = livePane, pid = 1, dead = false, width = 80, height = 24),
                ),
            )
            val transcripts = setOf(idA, idB, idC)
            val vendorProbe = VendorStoreProbe { _, _, id -> id in transcripts }

            val registry = PaneRegistry()
            registry.register(PaneId("%999"), SessionId("ghost"))

            val reconciler = Reconciler(tmux, store, vendorProbe, registry, now = { 2L })
            val result = reconciler.reconcile()

            assertEquals(SessionState.running, store.getSession(SessionId("alive"))!!.state)
            assertEquals(SessionState.stopped, store.getSession(SessionId("stopd"))!!.state, "clean-stop intent wins over a surviving transcript")
            assertEquals(SessionState.resumable, store.getSession(SessionId("resum"))!!.state)
            assertEquals(SessionState.crashed, store.getSession(SessionId("crash"))!!.state, "dead + no transcript -> crashed")
            assertEquals(SessionState.crashed, store.getSession(SessionId("noid"))!!.state, "dead + no provider id -> crashed")

            assertEquals(livePane, store.getSession(SessionId("alive"))!!.paneId, "reconcile recaptured the live pane_id")

            val expected = mapOf(livePane to SessionId("alive"))
            assertEquals(expected, registry.snapshot(), "registry rebuilt from live panes, stale entries pruned")
            assertEquals(expected, result.livePanes)
            assertEquals(SessionId("alive"), registry.lookup(livePane))
            assertEquals(null, registry.lookup(PaneId("%999")))

            val byId = result.sessions.associateBy { it.sessionId }
            assertEquals(true, byId.getValue(SessionId("alive")).paneAlive)
            assertEquals(false, byId.getValue(SessionId("crash")).paneAlive)
            assertEquals(SessionState.running to SessionState.resumable, byId.getValue(SessionId("resum")).let { it.previousState to it.newState })
        }
    }


    @Test
    fun eachSessionIsProbedWithItsOwnProvidersStore() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val claudeId = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val codexId = ProviderSessionId("bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb")
            store.upsertSession(meta("clsess", SessionState.running, providerId = claudeId, agent = "claude"))
            store.upsertSession(meta("cxsess", SessionState.running, providerId = codexId, agent = "codex"))
            store.upsertSession(meta("unknwn", SessionState.running, providerId = claudeId, agent = "aider"))

            val probe = byAgentVendorStoreProbe(
                mapOf(
                    "claude" to VendorStoreProbe { _, _, id -> id == claudeId },
                    "codex" to VendorStoreProbe { _, _, id -> id == codexId },
                ),
            )

            Reconciler(FakeTmux(), store, probe, PaneRegistry(), now = { 2L }).reconcile()

            assertEquals(SessionState.resumable, store.getSession(SessionId("clsess"))!!.state)
            assertEquals(SessionState.resumable, store.getSession(SessionId("cxsess"))!!.state)
            assertEquals(
                SessionState.crashed,
                store.getSession(SessionId("unknwn"))!!.state,
                "an agent kind with no registered probe answers 'no transcript', never another provider's",
            )
        }
    }


    @Test
    fun reconcileClassifiesALiveSessionFromTheCacheAuthoritativeStateNotTheEventLog() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val id = uuid('a')
            store.upsertSession(meta("intr", SessionState.running, providerId = id, paneId = PaneId("%5")))
            store.append(SessionId("intr"), AgentEvent.ApprovalRequested("perm"), EventSource.hook)
            assertEquals(SessionState.needs_approval, store.projectionOf(SessionId("intr")).state, "log projection is needs_approval")
            store.updateSessionState(SessionId("intr"), SessionState.ready, EventSource.user, PaneId("%5"), 2L)

            val tmux = FakeTmux(
                seedPanes = listOf(
                    TmuxPane(session = "kt-intr", paneId = PaneId("%5"), pid = 1, dead = false, width = 80, height = 24),
                ),
            )
            val reconciler = Reconciler(tmux, store, VendorStoreProbe { _, _, _ -> false }, PaneRegistry(), now = { 3L })
            reconciler.reconcile()

            assertEquals(
                SessionState.ready,
                store.getSession(SessionId("intr"))!!.state,
                "live-session reconciliation honors the cache-authoritative state, not the event-log replay",
            )
        }
    }


    @Test
    fun aStopFromAPreviousIncarnationDoesNotMaskAResumableAfterAResume() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val id = uuid('a')
            store.upsertSession(meta("reinc", SessionState.running, providerId = id, paneId = PaneId("%7")))
            store.append(SessionId("reinc"), AgentEvent.SessionBound(id), EventSource.system)
            store.append(SessionId("reinc"), AgentEvent.Exited(0), EventSource.hook)
            assertEquals(SessionState.stopped, store.projectionOf(SessionId("reinc")).state, "the log ends at stopped")

            store.updateSessionState(SessionId("reinc"), SessionState.ready, EventSource.user, PaneId("%8"), 2L)

            val reconciler = Reconciler(FakeTmux(), store, VendorStoreProbe { _, _, _ -> true }, PaneRegistry(), now = { 3L })
            reconciler.reconcile()

            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("reinc"))!!.state,
                "the historical Exited belongs to a dead incarnation — it must not be read as a fresh stop intent",
            )
        }
    }

    @Test
    fun aCleanlyStoppedSessionStaysStoppedAcrossRepeatedReconciles() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val id = uuid('b')
            store.upsertSession(meta("stopd", SessionState.stopped, providerId = id, paneId = PaneId("%9")))
            val reconciler = Reconciler(FakeTmux(), store, VendorStoreProbe { _, _, _ -> true }, PaneRegistry(), now = { 3L })

            reconciler.reconcile()
            assertEquals(SessionState.stopped, store.getSession(SessionId("stopd"))!!.state, "a cached stop intent is honored")
            reconciler.reconcile()
            assertEquals(SessionState.stopped, store.getSession(SessionId("stopd"))!!.state, "and reconciliation is idempotent")
        }
    }
}
