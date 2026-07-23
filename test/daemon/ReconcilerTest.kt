package io.kotgent.daemon

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

/**
 * Reconciliation TDD (plan Task 13) — host-free with a [FakeTmux], an in-memory [SqliteEventStore], and
 * a fake [VendorStoreProbe]. Covers the classification truth table (running / resumable / crashed /
 * stopped) over the (tmux liveness × stop-intent × vendor-transcript) combinations, and that the
 * [PaneRegistry] is rebuilt from the LIVE panes tmux reports (stale entries pruned, `pane_id`
 * correlation refreshed). Every body is bounded by [withTimeout] as an anti-hang tripwire.
 */
class ReconcilerTest {

    private fun meta(
        idV: String,
        state: SessionState,
        providerId: ProviderSessionId? = null,
        paneId: PaneId? = null,
    ) = SessionMeta(
        id = SessionId(idV),
        name = "kt-$idV",
        agent = "claude",
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

    // ---- the classification is a pure function: exhaustive, deterministic ----

    @Test
    fun classifyIsExhaustiveOverTheTruthTable() {
        // paneAlive: keep a finer live state from the log; correct a stale dead classification up to running.
        assertEquals(SessionState.running, Reconciler.classify(true, SessionState.running, stopIntent = false, transcriptExists = false))
        assertEquals(SessionState.needs_approval, Reconciler.classify(true, SessionState.needs_approval, false, false), "alive keeps a finer live state")
        assertEquals(SessionState.running, Reconciler.classify(true, SessionState.crashed, false, false), "alive corrects a stale dead state up to running")

        // dead + clean-stop intent -> stopped (wins over a surviving transcript).
        assertEquals(SessionState.stopped, Reconciler.classify(false, SessionState.running, stopIntent = true, transcriptExists = true))
        assertEquals(SessionState.stopped, Reconciler.classify(false, SessionState.running, stopIntent = true, transcriptExists = false))

        // dead + transcript -> resumable ; dead + neither -> crashed.
        assertEquals(SessionState.resumable, Reconciler.classify(false, SessionState.running, stopIntent = false, transcriptExists = true))
        assertEquals(SessionState.crashed, Reconciler.classify(false, SessionState.running, stopIntent = false, transcriptExists = false))
    }

    // ---- reconcile() over a mixed store: classify each + rebuild the registry from live panes ----

    @Test
    fun reconcileClassifiesEachCombinationAndRebuildsRegistryFromLivePanes() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })

            val idA = uuid('a') // alive
            val idB = uuid('b') // cleanly stopped, transcript survives
            val idC = uuid('c') // dead, transcript survives -> resumable
            val idD = uuid('d') // dead, no transcript      -> crashed

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
            // A, B, C have transcripts on disk; D does not (idD absent). The "noid" session has no id at all.
            val transcripts = setOf(idA, idB, idC)
            val vendorProbe = VendorStoreProbe { it in transcripts }

            val registry = PaneRegistry()
            registry.register(PaneId("%999"), SessionId("ghost")) // a stale entry that must be pruned

            val reconciler = Reconciler(tmux, store, vendorProbe, registry, now = { 2L })
            val result = reconciler.reconcile()

            assertEquals(SessionState.running, store.getSession(SessionId("alive"))!!.state)
            assertEquals(SessionState.stopped, store.getSession(SessionId("stopd"))!!.state, "clean-stop intent wins over a surviving transcript")
            assertEquals(SessionState.resumable, store.getSession(SessionId("resum"))!!.state)
            assertEquals(SessionState.crashed, store.getSession(SessionId("crash"))!!.state, "dead + no transcript -> crashed")
            assertEquals(SessionState.crashed, store.getSession(SessionId("noid"))!!.state, "dead + no provider id -> crashed")

            // pane_id correlation rebuilt from tmux's live pane (was null in the store).
            assertEquals(livePane, store.getSession(SessionId("alive"))!!.paneId, "reconcile recaptured the live pane_id")

            // Registry rebuilt from LIVE panes only: the stale %999 ghost is gone, the live pane present.
            val expected = mapOf(livePane to SessionId("alive"))
            assertEquals(expected, registry.snapshot(), "registry rebuilt from live panes, stale entries pruned")
            assertEquals(expected, result.livePanes)
            assertEquals(SessionId("alive"), registry.lookup(livePane))
            assertEquals(null, registry.lookup(PaneId("%999")))

            // Result reports per-session outcomes with liveness.
            val byId = result.sessions.associateBy { it.sessionId }
            assertEquals(true, byId.getValue(SessionId("alive")).paneAlive)
            assertEquals(false, byId.getValue(SessionId("crash")).paneAlive)
            assertEquals(SessionState.running to SessionState.resumable, byId.getValue(SessionId("resum")).let { it.previousState to it.newState })
        }
    }
}
