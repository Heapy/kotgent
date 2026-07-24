package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.Tmux
import io.kotgent.tmux.TmuxControl
import io.kotgent.tmux.TmuxException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SessionManager] + [ProviderIdCapture] TDD (plan Task 13). The provider-id-capture and control-op
 * cases are host-free ([FakeTmux] + in-memory [SqliteEventStore]); one guarded INTEGRATION test drives
 * the real [Tmux] against a throwaway `tmux -L kotgent-test` server (a harmless `cat` pane — never
 * `claude`), then simulates a daemon restart with a fresh [Reconciler] over the same tmux + store.
 * Every body is bounded by [withTimeout] as an anti-hang tripwire.
 */
class SessionManagerTest {

    private val cat = listOf("cat") // a harmless, long-lived pane command

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

    /**
     * An [EventStore] decorator that PARKS the first `projectionOf` after [arm] — i.e. a control op is
     * held between its READ of the session row and its WRITE of the derived state. That is the exact
     * window in which a racing `stop` used to be overwritten: the parked op resumes with a pre-stop
     * snapshot and writes `ready` over the committed `stopped`.
     */
    private class GatedStore(
        private val delegate: EventStore,
        private val gateFor: SessionId,
    ) : EventStore by delegate {
        /** Completed once the gated call has been entered (the op is parked mid-flight). */
        val entered = CompletableDeferred<Unit>()

        /** Complete this to let the parked op continue. */
        val release = CompletableDeferred<Unit>()

        private var armed = false
        private var used = false

        /** Arm the gate — done AFTER setup so the store calls made by `start()` are not parked. */
        fun arm() {
            armed = true
        }

        override suspend fun projectionOf(sessionId: SessionId): Projection {
            if (armed && !used && sessionId == gateFor) {
                used = true
                entered.complete(Unit)
                release.await()
            }
            return delegate.projectionOf(sessionId)
        }
    }

    /**
     * An [EventStore] decorator that simulates a step failing AFTER a durable write committed — the
     * window a compensation path has to clean up. [failAppend] fails `start`'s `SessionBound` append
     * (the session row is already `running` by then); [failAfterStateWrite] lets a state write commit
     * and then throws (as a later step in the same op would); [failStateWrite] makes the state write
     * itself fail, which is how a COMPENSATION's own cleanup fails.
     */
    private class FailingStore(
        private val delegate: EventStore,
        private val failAppend: Boolean = false,
        private val failStateWrite: Boolean = false,
        private val failAfterStateWrite: (SessionState) -> Boolean = { false },
    ) : EventStore by delegate {
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq {
            if (failAppend) throw IllegalStateException("simulated durable append failure")
            return delegate.append(sessionId, event, source)
        }

        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ) {
            if (failStateWrite) throw IllegalStateException("simulated state-write failure ($state)")
            delegate.updateSessionState(sessionId, state, stateSource, paneId, updatedAt)
            if (failAfterStateWrite(state)) throw IllegalStateException("simulated failure after writing $state")
        }
    }

    /**
     * An [EventStore] decorator that (a) records every `updateSessionState` in order and (b) optionally
     * PARKS the first `upsertSession` for [gateUpsertFor] AFTER the row has committed — the window in
     * which `start` has published a listable session row but has not yet registered its pane.
     *
     * `updateSessionState` opens with a real suspension point ([yield]), which is what makes a
     * compensation running in a CANCELLED coroutine observable: without `NonCancellable` that `yield`
     * throws and the cleanup write silently never happens.
     */
    private class TracingStore(
        private val delegate: EventStore,
        private val gateUpsertFor: SessionId? = null,
    ) : EventStore by delegate {
        /** Completed once the gated upsert has been entered (start is parked, row already committed). */
        val entered = CompletableDeferred<Unit>()

        /** Complete this to let the parked start continue. */
        val release = CompletableDeferred<Unit>()

        /** States passed to [updateSessionState], in call order. */
        val stateWrites = mutableListOf<SessionState>()

        private var used = false

        override suspend fun upsertSession(meta: SessionMeta) {
            delegate.upsertSession(meta)
            if (!used && meta.id == gateUpsertFor) {
                used = true
                entered.complete(Unit)
                release.await()
            }
        }

        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ) {
            yield()
            stateWrites += state
            delegate.updateSessionState(sessionId, state, stateSource, paneId, updatedAt)
        }
    }

    /**
     * A [TmuxControl] wrapper over [FakeTmux] that observes/fails the calls a compensation depends on:
     * [onKill] runs before each `kill-session` (so a test can snapshot what was already persisted at
     * that moment), [failKill] makes it throw, and [failAfterCreate] makes `new-session` create the
     * session and THEN throw — the real "tmux created it but the pane id never came back" failure.
     */
    private class ObservingTmux(
        val inner: FakeTmux = FakeTmux(),
        private val failKill: Boolean = false,
        private val failAfterCreate: Boolean = false,
        private val onKill: (String) -> Unit = {},
    ) : TmuxControl by inner {
        override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId {
            val pane = inner.newSession(id, cwd, cmd, cols, rows)
            if (failAfterCreate) throw TmuxException("tmux new-session for '$id' returned no pane id")
            return pane
        }

        override fun killSession(id: String): Boolean {
            onKill(id)
            if (failKill) throw TmuxException("simulated kill-session failure for '$id'")
            return inner.killSession(id)
        }
    }

    /** An [AgentFactory] that yields a canned launch spec ([command] + optional [preallocated] id). */
    private class StubAgentFactory(
        private val command: List<String>,
        private val preallocated: ProviderSessionId?,
        private val cliVersion: String? = null,
        private val cliPath: String? = null,
    ) : AgentFactory {
        override fun create(agentKind: String, cwd: String): AgentAdapter = object : AgentAdapter {
            override val events: Flow<AgentEvent> = emptyFlow()
            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
                is LaunchMode.New ->
                    LaunchSpec(command, emptyMap(), cwd, preallocated, cliVersion = cliVersion, cliPath = cliPath)
                is LaunchMode.Resume ->
                    LaunchSpec(
                        command + listOf("--resume", mode.providerSessionId.value),
                        emptyMap(), cwd, null, cliVersion = cliVersion, cliPath = cliPath,
                    )
            }
        }
    }

    // ---- cli version/path from the spec are persisted onto the session ----

    @Test
    fun startPersistsTheCliVersionAndPathFromTheSpec() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null, cliVersion = "2.1.218", cliPath = "/usr/local/bin/claude"),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("sess09") },
                now = { 1L },
            )

            mgr.start("claude", "/tmp/work")

            val stored = store.getSession(SessionId("sess09"))!!
            assertEquals("2.1.218", stored.cliVersion, "start persisted the spec's cliVersion")
            assertEquals("/usr/local/bin/claude", stored.cliPath, "start persisted the spec's cliPath")
        }
    }

    @Test
    fun startLeavesCliVersionNullWhenTheSpecHasNone() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("sess10") },
                now = { 1L },
            )

            mgr.start("claude", "/tmp/work")

            assertNull(store.getSession(SessionId("sess10"))!!.cliVersion, "no version in spec -> null, no crash")
        }
    }

    @Test
    fun startInvokesTheBackgroundModelCaptureForTheNewSession() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val captured = CompletableDeferred<SessionMeta>()
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                captureModelInBackground = { meta -> captured.complete(meta) },
                newSessionId = { SessionId("mdl01") },
                now = { 1L },
            )

            mgr.start("codex", "/work/x")

            val meta = captured.await()
            assertEquals(SessionId("mdl01"), meta.id, "start wired model capture for the new session")
            assertEquals("/work/x", meta.cwd, "…with the session's cwd for the rollout scan")
        }
    }

    // ---- provider-id capture: preallocated -> SessionBound in the log immediately ----

    @Test
    fun startBindsThePreallocatedProviderIdIntoTheLogImmediately() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("sess01") },
                now = { 1L },
            )

            val meta = mgr.start("claude", "/tmp/work")

            // The tmux session was created with the harmless command; the pane is captured + registered.
            assertEquals(listOf("sess01" to "'cat'"), tmux.newSessionCommands)
            assertEquals("kt-sess01", meta.tmuxSession)
            val pane = meta.paneId!!
            assertEquals(SessionId("sess01"), registry.lookup(pane), "start registered pane->session")

            // SessionBound is in the log IMMEDIATELY (source = system), and the caches reflect it.
            val events = store.read(SessionId("sess01"), Seq(0))
            assertEquals(1, events.size, "exactly one event: the preallocated SessionBound")
            assertEquals(AgentEvent.SessionBound(provider), events[0].event)
            assertEquals(EventSource.system, events[0].source)
            assertEquals(provider, store.projectionOf(SessionId("sess01")).providerSessionId)
            assertEquals(provider, store.getSession(SessionId("sess01"))!!.providerSessionId)
        }
    }

    @Test
    fun startFallsBackToProviderDiscoveryWhenNothingIsPreallocated() = runBlocking {
        withTimeout(20_000) {
            // The codex shape: no `--session-id` to preallocate, and (here) no SessionStart hook either,
            // so the id can only come from the provider's own store — the rollout scan in production.
            val store = SqliteEventStore.inMemory(now = { 1L })
            val discovered = ProviderSessionId("cccccccc-cccc-7ccc-8ccc-cccccccccccc")
            val seen = mutableListOf<SessionMeta>()
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this, maxAttempts = 5, retryDelayMillis = 1),
                discoverProviderId = { meta -> discovered.also { seen += meta } },
                newSessionId = { SessionId("cx0001") },
                now = { 1L },
            )

            mgr.start("codex", "/work/repo")

            // The background capture is bounded and fire-and-forget; give it its first poll.
            withTimeout(5_000) {
                while (store.projectionOf(SessionId("cx0001")).providerSessionId == null) delay(5)
            }
            val events = store.read(SessionId("cx0001"), Seq(0))
            assertEquals(1, events.size, "exactly one event: the discovered SessionBound")
            assertEquals(AgentEvent.SessionBound(discovered), events[0].event)
            assertEquals(discovered, store.getSession(SessionId("cx0001"))!!.providerSessionId, "resume is unblocked")

            // Discovery is handed the session's own meta — that is what lets it scope the search to this
            // session's cwd and launch time instead of grabbing whatever the provider wrote most recently.
            assertEquals("codex", seen.first().agent)
            assertEquals("/work/repo", seen.first().cwd)
        }
    }

    @Test
    fun aHookDeliveredIdWinsOverProviderDiscovery() = runBlocking {
        withTimeout(20_000) {
            // Both sources can answer; the hook is authoritative for THIS session, whereas discovery
            // infers from what the provider happened to write on disk. Binding the wrong id would point
            // `resume` at someone else's conversation, so the order matters.
            val store = SqliteEventStore.inMemory(now = { 1L })
            val fromHook = ProviderSessionId("dddddddd-dddd-7ddd-8ddd-dddddddddddd")
            val fromDisk = ProviderSessionId("eeeeeeee-eeee-7eee-8eee-eeeeeeeeeeee")
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this, maxAttempts = 5, retryDelayMillis = 20),
                discoverProviderId = { fromDisk },
                newSessionId = { SessionId("cx0002") },
                now = { 1L },
            )

            mgr.start("codex", "/work/repo")
            // The SessionStart hook lands first (this is what the ingress does).
            store.append(SessionId("cx0002"), AgentEvent.SessionBound(fromHook), EventSource.hook)

            withTimeout(5_000) {
                while (store.projectionOf(SessionId("cx0002")).providerSessionId == null) delay(5)
            }
            assertEquals(fromHook, store.projectionOf(SessionId("cx0002")).providerSessionId)
        }
    }

    // ---- provider-id capture: fallback stalls -> retry -> Bound / never -> Pending ----

    @Test
    fun providerIdCaptureRetriesAStallThenBindsOnDiscovery() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val idCapture = ProviderIdCapture(store, this, maxAttempts = 5, retryDelayMillis = 1)
            val provider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            var calls = 0
            // Null on the first two polls (the SessionStart hook has not arrived yet), then the id.
            val result = idCapture.captureWithFallback(SessionId("late01")) {
                if (++calls >= 3) provider else null
            }

            assertEquals(CaptureResult.Bound(provider), result)
            assertEquals(3, calls, "polled until discovery succeeded")
            assertEquals(provider, store.projectionOf(SessionId("late01")).providerSessionId, "the discovered id is bound in the log")
        }
    }

    @Test
    fun providerIdCaptureStaysPendingWhenDiscoveryNeverSucceeds() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val idCapture = ProviderIdCapture(store, this, maxAttempts = 4, retryDelayMillis = 1)
            var calls = 0
            val result = idCapture.captureWithFallback(SessionId("never01")) { calls++; null }

            assertEquals(CaptureResult.Pending, result, "a stalled discovery leaves the session id pending")
            assertEquals(4, calls, "polled exactly maxAttempts times")
            assertEquals(null, store.projectionOf(SessionId("never01")).providerSessionId, "nothing bound -> id pending")
        }
    }

    @Test
    fun resumeIsBlockedWhileTheProviderIdIsPending() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                now = { 1L },
            )
            // A dead session whose provider id was never captured (id pending).
            store.upsertSession(meta("dead01", SessionState.crashed, providerId = null))

            val ex = assertFailsWith<ResumeBlockedException> { mgr.resume(SessionId("dead01")) }
            assertEquals(SessionId("dead01"), ex.sessionId)
            assertTrue(tmux.newSessionCommands.isEmpty(), "resume must not spawn tmux while the id is pending")
        }
    }

    // ---- Done: kill + archive, and Restore ----

    @Test
    fun markDoneKillsTheAgentAndArchivesTheSession() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("done01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            mgr.markDone(SessionId("done01")) // completes (does NOT hang — terminate locks internally)

            assertEquals(listOf("done01"), tmux.killed, "Done kills the agent")
            val row = store.getSession(SessionId("done01"))!!
            assertEquals(SessionState.stopped, row.state, "the killed session is stopped")
            assertTrue(row.archived, "and archived off the sidebar")
            assertEquals(null, registry.lookup(pane), "the pane is unregistered")
        }
    }

    @Test
    fun undoneUnarchivesWithoutTouchingTmux() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("done02") },
                now = { 1L },
            )
            store.upsertSession(meta("done02", SessionState.stopped, providerId = null).copy(archived = true))

            mgr.undone(SessionId("done02"))

            assertEquals(false, store.getSession(SessionId("done02"))!!.archived, "Restore clears archived")
            assertTrue(tmux.killed.isEmpty(), "Restore touches no tmux")
        }
    }

    // ---- control ops: stop / interrupt / resume / detach ----

    @Test
    fun stopKillsTheTmuxSessionAndCachesStopped() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("stop01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            mgr.stop(SessionId("stop01")) // default StopMode.Kill

            assertEquals(listOf("stop01"), tmux.killed, "a clean kill-session")
            val row = store.getSession(SessionId("stop01"))!!
            assertEquals(SessionState.stopped, row.state, "a clean kill is classified stopped, not crashed")
            assertEquals(EventSource.user, row.stateSource)
            assertEquals(null, registry.lookup(pane), "a stopped session's pane is unregistered")
        }
    }

    @Test
    fun interruptSendsCtrlCAndCachesReadyKeepingThePaneRegistered() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("intr01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            mgr.interrupt(SessionId("intr01"))

            assertEquals(1, tmux.sentKeys.size)
            assertEquals("intr01", tmux.sentKeys.single().first)
            assertEquals(listOf(0x03.toByte()), tmux.sentKeys.single().second.toList(), "Ctrl-C byte sent to un-stick")
            assertEquals(SessionState.ready, store.getSession(SessionId("intr01"))!!.state, "interrupt resets a stuck running to ready")
            assertEquals(SessionId("intr01"), registry.lookup(pane), "the session stays alive -> pane stays registered")
            assertTrue(tmux.killed.isEmpty(), "interrupt does not kill")
        }
    }

    @Test
    fun cancellationAfterCtrlCStillPersistsTheInterruptStateExactlyOnce() = runBlocking {
        withTimeout(20_000) {
            val id = SessionId("intr02")
            val tracing = TracingStore(SqliteEventStore.inMemory(now = { 1L }))
            val store = GatedStore(tracing, id)
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("d2d2d2d2-d2d2-4d2d-8d2d-d2d2d2d2d2d2")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { id },
                now = { 1L },
            )
            mgr.start("claude", "/tmp")
            tracing.stateWrites.clear()
            store.arm()

            val interrupting = launch { mgr.interrupt(id) }
            try {
                store.entered.await() // projection read entered only after verified Ctrl-C delivery
                assertEquals(1, tmux.sentKeys.size, "Ctrl-C was irreversibly delivered exactly once")
                interrupting.cancel()
            } finally {
                store.release.complete(Unit)
            }
            interrupting.join()

            assertEquals(
                listOf(SessionState.ready),
                tracing.stateWrites,
                "the whole post-delivery tail survives cancellation, including the suspending state write",
            )
            assertEquals(SessionState.ready, store.getSession(id)!!.state, "the durable cache matches delivered Ctrl-C")
            assertEquals(1, tmux.sentKeys.size, "cancellation never retries the irreversible Ctrl-C")
        }
    }

    @Test
    fun resumeSpawnsAFreshSessionForADeadSessionWithAProviderId() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                now = { 7L },
            )
            store.upsertSession(meta("resu01", SessionState.resumable, providerId = provider, paneId = PaneId("%1")))

            val updated = mgr.resume(SessionId("resu01"))

            assertEquals(SessionState.ready, updated.state, "resume revives a dead session to ready")
            val (id, cmd) = tmux.newSessionCommands.single()
            assertEquals("resu01", id, "the fresh session reuses the logical id")
            assertTrue(cmd.contains("--resume") && cmd.contains(provider.value), "the resume launch carries --resume <providerId>: $cmd")
            val pane = updated.paneId!!
            assertEquals(SessionId("resu01"), registry.lookup(pane), "the fresh pane is registered")
            assertEquals(SessionState.ready, store.getSession(SessionId("resu01"))!!.state)
        }
    }

    @Test
    fun detachIsANoOpThatLeavesTheSessionRunning() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("ffffffff-ffff-4fff-8fff-ffffffffffff")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("detc01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            mgr.stop(SessionId("detc01"), StopMode.Detach)
            mgr.detach(SessionId("detc01"))

            assertTrue(tmux.killed.isEmpty(), "detach does not kill")
            assertTrue(tmux.sentKeys.isEmpty(), "detach sends nothing")
            assertEquals(SessionState.running, store.getSession(SessionId("detc01"))!!.state, "detach leaves the agent running")
            assertEquals(SessionId("detc01"), registry.lookup(pane), "detach keeps the pane registered")
        }
    }

    // ---- control ops are serialized per session; compensations leave no phantom state ----

    @Test
    fun anInterruptRacingAStopDoesNotResurrectTheStoppedSession() = runBlocking {
        withTimeout(20_000) {
            val store = GatedStore(SqliteEventStore.inMemory(now = { 1L }), SessionId("race01"))
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("11111111-1111-4111-8111-111111111111")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("race01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            // Park `interrupt` between its read of the (live) row and its write of the derived state …
            store.arm()
            val interrupting = launch { mgr.interrupt(SessionId("race01")) }
            store.entered.await()

            // … and issue a full `stop` while it sits there. Per-session serialization must keep the two
            // ops from interleaving: whatever the order, the session must not end up alive again with its
            // tmux session killed (a cache-resurrected row with no live pane, unfixable until a restart).
            val stopping = launch { mgr.stop(SessionId("race01")) }
            repeat(50) { yield() } // give the stop every chance to interleave with the parked op
            store.release.complete(Unit)
            interrupting.join()
            stopping.join()

            assertEquals(listOf("race01"), tmux.killed, "the stop killed the tmux session exactly once")
            assertEquals(
                SessionState.stopped,
                store.getSession(SessionId("race01"))!!.state,
                "an interrupt racing a stop must not write `ready` over the committed `stopped`",
            )
            assertEquals(null, registry.lookup(pane), "the stopped session's pane stays unregistered")
        }
    }

    @Test
    fun aStartThatFailsAfterTheRowIsWrittenLeavesNoPhantomRunningSession() = runBlocking {
        withTimeout(20_000) {
            // The `sessions` row is upserted `running` BEFORE the provider-id bind; failing that append
            // leaves a committed `running` row whose tmux session the compensation then kills.
            val store = FailingStore(SqliteEventStore.inMemory(now = { 1L }), failAppend = true)
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("22222222-2222-4222-8222-222222222222")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("fail01") },
                now = { 1L },
            )

            assertFailsWith<IllegalStateException> { mgr.start("claude", "/tmp") }

            assertEquals(listOf("fail01"), tmux.killed, "the just-launched agent is killed (no orphan)")
            val row = store.getSession(SessionId("fail01"))!!
            assertEquals(
                SessionState.crashed,
                row.state,
                "the compensation must correct the persisted row — a `running` session with no pane is a phantom",
            )
            assertEquals(null, row.paneId, "the killed pane is cleared from the row too")
            assertEquals(emptyMap(), registry.snapshot(), "and the pane is unregistered")
        }
    }

    @Test
    fun aResumeThatFailsAfterTheStateWriteRestoresTheDeadRow() = runBlocking {
        withTimeout(20_000) {
            // The resume write commits `ready` + the fresh pane, then a later step fails.
            val store = FailingStore(
                SqliteEventStore.inMemory(now = { 1L }),
                failAfterStateWrite = { it == SessionState.ready },
            )
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("33333333-3333-4333-8333-333333333333")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                now = { 9L },
            )
            store.upsertSession(meta("resf01", SessionState.resumable, providerId = provider, paneId = PaneId("%1")))

            assertFailsWith<IllegalStateException> { mgr.resume(SessionId("resf01")) }

            assertEquals(listOf("resf01"), tmux.killed, "the freshly launched agent is killed")
            val row = store.getSession(SessionId("resf01"))!!
            assertEquals(
                SessionState.resumable,
                row.state,
                "the compensation restores the pre-resume dead state — no phantom `ready` over a killed pane",
            )
            assertEquals(PaneId("%1"), row.paneId, "the fresh (killed) pane id is rolled back")
            assertEquals(emptyMap(), registry.snapshot(), "the fresh pane is unregistered")
        }
    }

    @Test
    fun aStopCannotInterleaveWithAStartThatHasAlreadyPublishedItsRow() = runBlocking {
        withTimeout(20_000) {
            // Park `start` right after its `sessions` row commits — from that instant the session is
            // listable, so a `stop` can legitimately target it while the start is still mid-flight.
            val store = TracingStore(SqliteEventStore.inMemory(now = { 1L }), gateUpsertFor = SessionId("strt01"))
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("44444444-4444-4444-8444-444444444444")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("strt01") },
                now = { 1L },
            )

            val starting = launch { mgr.start("claude", "/tmp") }
            store.entered.await()

            val stopping = launch { mgr.stop(SessionId("strt01")) }
            repeat(50) { yield() } // give the stop every chance to interleave with the parked start
            // Mutual exclusion: start holds the session's control lock, so the stop cannot act yet.
            assertTrue(tmux.killed.isEmpty(), "a stop must not run while the start still holds the session lock")

            store.release.complete(Unit)
            starting.join()
            stopping.join()

            assertEquals(listOf("strt01"), tmux.killed, "the stop ran — after the start finished")
            assertEquals(SessionState.stopped, store.getSession(SessionId("strt01"))!!.state, "the stop is the last word")
            assertEquals(
                emptyMap(),
                registry.snapshot(),
                "a start that interleaved would re-register the pane AFTER the stop dropped it, " +
                    "leaving a killed session still routable to hooks",
            )
        }
    }

    @Test
    fun aStartWhoseLaunchFailsAfterTheTmuxSessionExistsKillsTheOrphan() = runBlocking {
        withTimeout(20_000) {
            // tmux CREATED the session and the call still failed (`new-session -P` reported no pane id).
            // The agent is live but we never learned its pane — it must not be left running untracked.
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failAfterCreate = true)
            val provider = ProviderSessionId("55555555-5555-4555-8555-555555555555")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("orph01") },
                now = { 1L },
            )

            assertFailsWith<TmuxException> { mgr.start("claude", "/tmp") }

            assertEquals(listOf("orph01"), tmux.inner.killed, "the created-but-unreported session is killed")
            assertNull(store.getSession(SessionId("orph01")), "no row was ever published, so none is left behind")
            assertEquals(emptyMap(), registry.snapshot())
        }
    }

    @Test
    fun aResumeWhoseLaunchFailsAfterTheTmuxSessionExistsKillsTheOrphan() = runBlocking {
        withTimeout(20_000) {
            // The resume mirror of the start case above: tmux CREATED the `kt-<id>` session running
            // `claude --resume` and the call still failed (`new-session -P` reported no pane id). With the
            // launch outside the guarded region that left a live agent nothing tracks — its pane is never
            // registered, so every hook 404s and its events are lost — while the row still asserted the
            // pre-resume dead state with the stale pane id, so the next resume went straight back to
            // `new-session` and collided with the duplicate tmux session name.
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failAfterCreate = true)
            val provider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                now = { 9L },
            )
            store.upsertSession(meta("rorp01", SessionState.resumable, providerId = provider, paneId = PaneId("%1")))

            assertFailsWith<TmuxException> { mgr.resume(SessionId("rorp01")) }

            assertEquals(listOf("rorp01"), tmux.inner.killed, "the created-but-unreported resume session is killed")
            val row = store.getSession(SessionId("rorp01"))!!
            assertEquals(
                SessionState.resumable,
                row.state,
                "a resume that never completed must leave the row at its pre-resume dead state",
            )
            assertEquals(PaneId("%1"), row.paneId, "the pre-resume pane id is what the row keeps")
            assertEquals(9L, row.updatedAt, "the compensation actually rewrote the row (it did not merely go untouched)")
            assertEquals(emptyMap(), registry.snapshot(), "no pane is registered for a resume that never landed")
        }
    }

    @Test
    fun aStartCancelledMidLaunchStillCompensatesTheRowItPublished() = runBlocking {
        withTimeout(20_000) {
            // Cancellation is an ordinary way for a launch to fail (daemon shutting down mid-start). The
            // compensation is itself suspending, so unless it runs under NonCancellable every one of its
            // steps aborts instantly — leaving the `running` row it exists to erase.
            val store = TracingStore(SqliteEventStore.inMemory(now = { 1L }), gateUpsertFor = SessionId("cncl01"))
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("66666666-6666-4666-8666-666666666666")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("cncl01") },
                now = { 1L },
            )

            val starting = launch { runCatching { mgr.start("claude", "/tmp") } }
            store.entered.await() // the row is committed; the start is parked
            starting.cancel()
            store.release.complete(Unit)
            starting.join()

            assertEquals(listOf("cncl01"), tmux.killed, "the launched agent is killed even though the start was cancelled")
            assertEquals(
                SessionState.crashed,
                store.getSession(SessionId("cncl01"))!!.state,
                "the compensation's state write must survive cancellation — a `running` row with no pane is a phantom",
            )
            assertEquals(emptyMap(), registry.snapshot())
        }
    }

    @Test
    fun aCompensationThatItselfFailsIsSurfacedOnThePrimaryError() = runBlocking {
        withTimeout(20_000) {
            // The `SessionBound` append fails (primary), and the cleanup state-write fails too. The
            // primary error must still win, but the failed cleanup must not vanish: a phantom `running`
            // row survived and nothing else will report it.
            val store = FailingStore(
                SqliteEventStore.inMemory(now = { 1L }),
                failAppend = true,
                failStateWrite = true,
            )
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("77777777-7777-4777-8777-777777777777")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("supp01") },
                now = { 1L },
            )

            val error = assertFailsWith<IllegalStateException> { mgr.start("claude", "/tmp") }

            assertEquals("simulated durable append failure", error.message, "the primary failure still wins")
            assertTrue(
                error.suppressedExceptions.any { it is CompensationFailure },
                "the failed cleanup is attached to the primary error, not swallowed: ${error.suppressedExceptions}",
            )
            assertEquals(listOf("supp01"), tmux.killed, "the other cleanup steps still ran")
        }
    }

    @Test
    fun stopPersistsTheStopIntentBeforeKillingTmux() = runBlocking {
        withTimeout(20_000) {
            // `stopped` is the ONLY record that a teardown was intended (control signals are never
            // logged), so it must be durable before the pane can disappear — otherwise a crash between
            // the kill and the write leaves a dead pane with no intent, which the reconciler would
            // misread as `resumable`/`crashed` instead of a clean operator stop.
            val store = TracingStore(SqliteEventStore.inMemory(now = { 1L }))
            // Seeded with a sentinel no code path writes, so a never-invoked observer cannot pass vacuously.
            var persistedAtKill: List<SessionState> = listOf(SessionState.needs_answer)
            val tmux = ObservingTmux(onKill = { persistedAtKill = store.stateWrites.toList() })
            val provider = ProviderSessionId("88888888-8888-4888-8888-888888888888")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("ordr01") },
                now = { 1L },
            )
            mgr.start("claude", "/tmp")

            mgr.stop(SessionId("ordr01"))

            assertEquals(listOf(SessionState.stopped), persistedAtKill, "the stop intent is persisted BEFORE the kill")
            assertEquals(SessionState.stopped, store.getSession(SessionId("ordr01"))!!.state)
        }
    }

    @Test
    fun aStopWhoseKillFailsRollsTheStopIntentBack() = runBlocking {
        withTimeout(20_000) {
            // The intent is written first — so when the kill itself fails (the session may well still be
            // alive) it has to be rolled back, or the cache would claim a `stopped` that never happened.
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failKill = true)
            val provider = ProviderSessionId("99999999-9999-4999-8999-999999999999")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("kfai01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            assertFailsWith<TmuxException> { mgr.stop(SessionId("kfai01")) }

            assertEquals(
                SessionState.running,
                store.getSession(SessionId("kfai01"))!!.state,
                "a failed kill must not leave a phantom `stopped` over a still-live pane",
            )
            assertEquals(SessionId("kfai01"), registry.lookup(pane), "nothing was killed, so the pane stays registered")
        }
    }

    // ---- id uniqueness / agent gating / dead-pane resume ----

    @Test
    fun startRegeneratesTheSessionIdOnACollisionWithAnExistingSessionOrLog() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            // A dead HISTORICAL session already occupies "dup00001" — its row AND event log survive.
            store.upsertSession(meta("dup00001", SessionState.crashed, providerId = null))
            store.append(SessionId("dup00001"), AgentEvent.TurnStarted, EventSource.hook)
            // The id generator hands out the taken id twice, then a free one.
            val ids = ArrayDeque(listOf("dup00001", "dup00001", "fresh001"))
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                newSessionId = { SessionId(ids.removeFirst()) },
                now = { 1L },
            )

            val started = mgr.start("claude", "/tmp")

            assertEquals("fresh001", started.id.value, "the colliding id is rejected; a free id is used")
            // The historical session's row + log are intact — not overwritten, not spliced into.
            assertEquals(SessionState.crashed, store.getSession(SessionId("dup00001"))!!.state, "historical row untouched")
            assertEquals(1, store.read(SessionId("dup00001"), Seq(0)).size, "the historical log is untouched")
        }
    }

    @Test
    fun agentFactoryRejectsUnsupportedAgentsBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val factory = agentFactoryOf(
                mapOf(
                    "claude" to { cwd: String -> StubAgentFactory(cat, null).create("claude", cwd) },
                    "codex" to { cwd: String -> StubAgentFactory(cat, null).create("codex", cwd) },
                ),
            )
            // The factory itself rejects an unregistered kind (and surfaces which one, plus what it knows).
            val direct = assertFailsWith<UnsupportedAgentException> { factory.create("aider", "/tmp") }
            assertEquals("aider", direct.agentKind)
            assertEquals(setOf("claude", "codex"), direct.supported)
            assertTrue(direct.message!!.contains("claude, codex"), "the error names the supported kinds")
            // And a start() with an unsupported agent propagates it WITHOUT creating a tmux session.
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), factory,
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("x0000001") }, now = { 1L },
            )
            assertFailsWith<UnsupportedAgentException> { mgr.start("aider", "/tmp") }
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux session is created for an unsupported agent")
        }
    }

    @Test
    fun agentFactoryFailsFastWhenTheAgentBinaryIsNotFoundBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            // The daemon-bootstrap shape when `locate()` returned null under launchd's minimal PATH: the
            // builder for that kind fails fast instead of falling back to a bare name that dies at exec.
            val factory = agentFactoryOf(
                mapOf(
                    "claude" to { _: String -> throw AgentBinaryNotFoundException("claude") },
                    // The real daemon registers both providers; codex resolves here so the fail-fast for
                    // claude stays scoped to the requested kind and is not masked by a resolvable sibling.
                    "codex" to { cwd: String -> StubAgentFactory(cat, null).create("codex", cwd) },
                ),
            )
            // The factory surfaces the not-found directly, carrying the kind and the `kotgent install` hint.
            val direct = assertFailsWith<AgentBinaryNotFoundException> { factory.create("claude", "/tmp") }
            assertEquals("claude", direct.agentKind)
            assertTrue(direct.message!!.contains("claude"), "the message names the agent kind")
            assertTrue(direct.message!!.contains("kotgent install"), "the message points at `kotgent install`")
            // And a start() with that agent propagates it, leaving NO tmux session and NO phantom row.
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), factory,
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("nf000001") }, now = { 1L },
            )
            assertFailsWith<AgentBinaryNotFoundException> { mgr.start("claude", "/tmp") }
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux session is created for an unresolvable agent")
            assertNull(store.getSession(SessionId("nf000001")), "no phantom `running` row is persisted")
        }
    }

    @Test
    fun agentFactoryFailsFastWhenTheResolvedBinaryPathIsNotAbsoluteBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            // `locate()` (command -v) can print a NON-absolute path (a name resolved via a relative PATH
            // dir / `.`, or a name with a slash). tmux does `new-session -c <cwd>` (cd into the session
            // cwd) before exec, so a relative binaryName would exec a wrong cwd-local binary. The daemon
            // bootstrap runs its `locate()` result through requireAbsoluteBinary, so treat non-absolute
            // exactly like not-found.
            val direct = assertFailsWith<AgentBinaryNotFoundException> { requireAbsoluteBinary("claude", "claude") }
            assertEquals("claude", direct.agentKind)
            assertTrue(direct.message!!.contains("kotgent install"), "the message points at `kotgent install`")
            // A relative-with-slash path is rejected too (the cwd-relative exec hole this closes).
            assertFailsWith<AgentBinaryNotFoundException> { requireAbsoluteBinary("claude", "./claude") }
            // An absolute path passes through unchanged.
            assertEquals("/opt/homebrew/bin/claude", requireAbsoluteBinary("claude", "/opt/homebrew/bin/claude"))

            // The bootstrap factory shape: the builder resolves via requireAbsoluteBinary, so a non-absolute
            // resolved path fails fast with NO tmux side-effect and NO phantom `running` row.
            val factory = agentFactoryOf(
                mapOf(
                    "claude" to { cwd: String ->
                        // requireAbsoluteBinary is the daemon's binaryName resolution — a non-absolute
                        // path throws here, before the adapter is built (mirrors the bootstrap).
                        requireAbsoluteBinary("claude", "claude")
                        StubAgentFactory(cat, null).create("claude", cwd)
                    },
                ),
            )
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), factory,
                ProviderIdCapture(store, this),
                newSessionId = { SessionId("na000001") }, now = { 1L },
            )
            assertFailsWith<AgentBinaryNotFoundException> { mgr.start("claude", "/tmp") }
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux session is created for a non-absolute agent path")
            assertNull(store.getSession(SessionId("na000001")), "no phantom `running` row is persisted")
        }
    }

    @Test
    fun resumePropagatesAgentBinaryNotFoundBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux() // no live pane for this session — it is dead and would be resumed
            // resume() also calls agentFactory.create() (SessionManager.kt:382) to build the resume launch
            // spec, so an unresolvable agent must fail fast there too — before tmux is touched.
            val factory = agentFactoryOf(
                mapOf("claude" to { _: String -> throw AgentBinaryNotFoundException("claude") }),
            )
            val provider = ProviderSessionId("ffffffff-ffff-4fff-8fff-ffffffffffff")
            // A dead session WITH a captured provider id — so resume gets past the alive/pending guards
            // and reaches create().
            store.upsertSession(meta("nfr01", SessionState.crashed, providerId = provider))
            val mgr = SessionManager(
                tmux, store, registry, factory,
                ProviderIdCapture(store, this),
                now = { 1L },
            )

            val ex = assertFailsWith<AgentBinaryNotFoundException> { mgr.resume(SessionId("nfr01")) }
            assertEquals("claude", ex.agentKind)
            assertTrue(tmux.newSessionCommands.isEmpty(), "resume must not spawn tmux for an unresolvable agent")
            // create() throws before any state mutation, so the stored row keeps its pre-resume dead state.
            assertEquals(
                SessionState.crashed, store.getSession(SessionId("nfr01"))!!.state,
                "the failed resume leaves the stored row unchanged (no phantom state transition)",
            )
        }
    }

    @Test
    fun resumeRevivesASessionWhoseCacheSaysAliveButWhosePaneActuallyDied() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux() // NO live pane for this session — it "died" while the daemon was up
            val provider = ProviderSessionId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                now = { 1L },
            )
            // Cache still claims the session is alive (running), but tmux reports no live pane for it.
            store.upsertSession(meta("zomb01", SessionState.running, providerId = provider, paneId = PaneId("%1")))

            val updated = mgr.resume(SessionId("zomb01"))

            assertEquals(SessionState.ready, updated.state, "a dead-but-cached-alive session is revived, not a no-op")
            assertEquals(1, tmux.newSessionCommands.size, "resume spawned a fresh tmux session for the dead pane")
            assertEquals(SessionState.ready, store.getSession(SessionId("zomb01"))!!.state)
        }
    }

    // ---- INTEGRATION (guarded): real tmux start + captured pane, then a restart reconciles it ----

    @Test
    fun startCreatesARealTmuxSessionThenAReconcilerRestoresItAndRebuildsTheRegistry() = runBlocking {
        val realTmux = Tmux(socket = "kotgent-test")
        if (!realTmux.isAvailable()) return@runBlocking // skip-guard: no tmux on this host
        // Isolate: tear down any leftover throwaway server before starting (never the real -L kotgent).
        ProcessRunner.run(listOf(realTmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
        try {
            withTimeout(30_000) {
                val store = SqliteEventStore.inMemory()
                val registry = PaneRegistry()
                val provider = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
                // HARMLESS: `cat` stays alive reading stdin — this never spawns `claude`.
                val mgr = SessionManager(
                    realTmux, store, registry,
                    StubAgentFactory(cat, preallocated = provider),
                    ProviderIdCapture(store, this),
                    newSessionId = { SessionId("itg01") },
                )

                val started = mgr.start("claude", "/tmp")
                val pane = started.paneId!!
                assertTrue(Regex("^%\\d+$").matches(pane.value), "captured a real %<n> pane id, was ${pane.value}")
                assertEquals(SessionId("itg01"), registry.lookup(pane), "start registered the real pane->session")
                assertEquals(provider, store.projectionOf(SessionId("itg01")).providerSessionId, "SessionBound captured")
                assertTrue(realTmux.listPanes().any { it.session == "kt-itg01" }, "a real tmux session exists")

                // Simulate a daemon restart: a FRESH registry (in-memory state lost) + a NEW Reconciler over
                // the SAME real tmux + SAME store. The live `cat` session must reclassify running and the
                // registry rebuild from the live pane.
                val freshRegistry = PaneRegistry()
                val reconciler = Reconciler(realTmux, store, VendorStoreProbe { _, _, _ -> false }, freshRegistry)
                val result = reconciler.reconcile()

                assertEquals(SessionState.running, store.getSession(SessionId("itg01"))!!.state, "the live session is reclassified running")
                assertEquals(SessionId("itg01"), freshRegistry.lookup(pane), "the registry is rebuilt from the live pane")
                assertEquals(mapOf(pane to SessionId("itg01")), result.livePanes)
            }
        } finally {
            // Tear down ONLY the throwaway test server; never the real -L kotgent socket.
            realTmux.killSession("itg01")
            ProcessRunner.run(listOf(realTmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
        }
    }
}
