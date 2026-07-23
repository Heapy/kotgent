package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.SqliteEventStore
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.Tmux
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** An [AgentFactory] that yields a canned launch spec ([command] + optional [preallocated] id). */
    private class StubAgentFactory(
        private val command: List<String>,
        private val preallocated: ProviderSessionId?,
    ) : AgentFactory {
        override fun create(agentKind: String, cwd: String): AgentAdapter = object : AgentAdapter {
            override val events: Flow<AgentEvent> = emptyFlow()
            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
                is LaunchMode.New -> LaunchSpec(command, emptyMap(), cwd, preallocated)
                is LaunchMode.Resume ->
                    LaunchSpec(command + listOf("--resume", mode.providerSessionId.value), emptyMap(), cwd, null)
            }
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
                val reconciler = Reconciler(realTmux, store, VendorStoreProbe { _, _ -> false }, freshRegistry)
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
