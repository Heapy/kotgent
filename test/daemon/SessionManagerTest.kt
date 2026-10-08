package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchOptions
import io.kotgent.adapter.LaunchSpec
import io.kotgent.adapter.UnsupportedLaunchOptionException
import io.kotgent.adapter.codex.CodexAdapter
import io.kotgent.adapter.shell.ShellAdapter
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
import io.kotgent.tmux.TmuxPane
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class SessionManagerTest {

    private val cat = listOf("cat")

    private val importProbe = VendorStoreProbe { _, _, _ -> false }

    private val liveTranscriptProbe = VendorStoreProbe { _, _, _ -> true }
    private val importLocator = VendorSessionLocator { _, _ -> null }
    private val importKinds = setOf("claude", "codex")

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

    private fun CoroutineScope.shellManager(
        store: EventStore,
        tmux: TmuxControl,
        registry: PaneRegistry = PaneRegistry(),
        vendorProbe: VendorStoreProbe = shellVendorStoreProbe(),
        now: () -> Long = { 2_000L },
    ): SessionManager {
        val syntheticId = ProviderSessionId("12121212-1212-4212-8212-121212121212")
        val builders = mapOf<String, (String) -> AgentAdapter>(
            SHELL_AGENT_KIND to { cwd ->
                ShellAdapter(cwd, "/bin/zsh", generateSessionId = { syntheticId })
            },
        )
        return SessionManager(
            tmux, store, registry, agentFactoryOf(builders),
            ProviderIdCapture(store, this),
            vendorProbe, importLocator, importableAgentKinds(builders.keys),
            newSessionId = { SessionId("shell999") },
            now = now,
        )
    }

    private fun codexAdapter(cwd: String) = CodexAdapter(
        cwd = cwd,
        hookScriptPath = "/tmp/hooks.sh",
        events = emptyFlow(),
        binaryName = "/opt/codex",
        cliVersion = "0.156.0",
        cliPath = "/opt/codex",
    )

    private fun codexLaunchCommand(mode: LaunchMode, options: LaunchOptions = LaunchOptions()): String =
        SessionManager.shellCommand(codexAdapter("/tmp").buildLaunchSpec(mode, options).command)

    private class RecordingPromptFiles : PromptFiles {
        val written = mutableMapOf<SessionId, String>()

        override fun pathFor(sessionId: SessionId): String = "/prompts/${sessionId.value}.md"

        override fun write(sessionId: SessionId, prompt: String) {
            written[sessionId] = prompt
        }
    }

    private fun CoroutineScope.codexManager(
        store: EventStore,
        tmux: TmuxControl,
        registry: PaneRegistry = PaneRegistry(),
        vendorProbe: VendorStoreProbe = importProbe,
        probeCliVersion: (String, String?) -> String? = { _, _ -> null },
        discoverProviderId: suspend (SessionMeta) -> ProviderSessionId? = { null },
        captureModelInBackground: (SessionMeta) -> Unit = {},
        newSessionId: () -> SessionId = { SessionId("codex999") },
        now: () -> Long = { 2_000L },
        promptFiles: PromptFiles? = null,
    ): SessionManager {
        val builders = mapOf<String, (String) -> AgentAdapter>(CODEX_AGENT_KIND to ::codexAdapter)
        return SessionManager(
            tmux, store, registry, agentFactoryOf(builders),
            ProviderIdCapture(store, this, maxAttempts = 1, retryDelayMillis = 1),
            vendorProbe, importLocator, importableAgentKinds(builders.keys),
            discoverProviderId = discoverProviderId,
            captureModelInBackground = captureModelInBackground,
            probeCliVersion = probeCliVersion,
            newSessionId = newSessionId,
            now = now,
            promptFiles = promptFiles,
        )
    }

    private fun codexRow(
        idV: String,
        state: SessionState,
        source: EventSource,
        pane: PaneId,
        providerId: ProviderSessionId? = null,
        cliVersion: String? = "0.156.0",
    ) = meta(idV, state, providerId, pane).copy(agent = CODEX_AGENT_KIND, stateSource = source, cliVersion = cliVersion)

    private fun makeClosedSessionTestDirectory(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val path = "$tmp/kotgent-session-close-${getpid()}-${closedSessionDirectoryCounter++}"
        check(mkdir(path, MODE_0700.convert()) == 0) { "cannot create $path" }
        return path
    }

    private companion object {
        const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR
        var closedSessionDirectoryCounter: Int = 0
    }

    // Parks a control operation after its read so a racing stop can commit first.
    private class GatedStore(
        private val delegate: EventStore,
        private val gateFor: SessionId,
    ) : EventStore by delegate {
        val entered = CompletableDeferred<Unit>()

        val release = CompletableDeferred<Unit>()

        private var armed = false
        private var used = false

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

    private class GatedVendorProbe(
        private val result: Boolean,
    ) : VendorStoreProbe {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun hasTranscript(
            agent: String,
            cwd: String,
            providerSessionId: ProviderSessionId,
        ): Boolean {
            entered.complete(Unit)
            release.await()
            return result
        }
    }

    // Failure modes distinguish a write that failed from a later step failing after it committed.
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

    // Parks after upsert commits; the yield makes cancelled compensation require NonCancellable.
    private class TracingStore(
        private val delegate: EventStore,
        private val gateUpsertFor: SessionId? = null,
    ) : EventStore by delegate {
        val entered = CompletableDeferred<Unit>()

        val release = CompletableDeferred<Unit>()

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

    private class StubAgentFactory(
        private val command: List<String>,
        private val preallocated: ProviderSessionId?,
        private val cliVersion: String? = null,
        private val cliPath: String? = null,
    ) : AgentFactory {
        override fun create(agentKind: String, cwd: String): AgentAdapter = object : AgentAdapter {
            override val events: Flow<AgentEvent> = emptyFlow()
            override fun buildLaunchSpec(mode: LaunchMode, options: LaunchOptions): LaunchSpec = when (mode) {
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

    @Test
    fun startWritesThePromptBeforeLaunchingAndRecordsTheLaunchFactsOnTheRow() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val prompts = RecordingPromptFiles()
            val parent = SessionId("parent01")
            val mgr = codexManager(store, tmux, promptFiles = prompts, newSessionId = { SessionId("child01") })

            val started = mgr.start(
                "codex", "/tmp", prompt = "do the task", parentSessionId = parent, readOnly = true,
            )

            val options = LaunchOptions(readOnly = true, promptPath = "/prompts/child01.md")
            assertEquals(mapOf(SessionId("child01") to "do the task"), prompts.written)
            assertEquals(listOf("child01" to codexLaunchCommand(LaunchMode.New, options)), tmux.newSessionCommands)
            val row = store.getSession(started.id)!!
            assertEquals(parent, row.parentSessionId)
            assertTrue(row.readOnly)
            assertEquals("/prompts/child01.md", row.promptPath)
        }
    }

    @Test
    fun aStartWithoutLaunchOptionsLeavesTheRowPlain() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val prompts = RecordingPromptFiles()
            val mgr = codexManager(store, FakeTmux(), promptFiles = prompts, newSessionId = { SessionId("plain01") })

            val _ = mgr.start("codex", "/tmp")

            assertTrue(prompts.written.isEmpty(), "no prompt, no file")
            val row = store.getSession(SessionId("plain01"))!!
            assertNull(row.parentSessionId)
            assertFalse(row.readOnly)
            assertNull(row.promptPath)
        }
    }

    @Test
    fun anAgentThatRejectsTheOptionsLeavesNoPromptPaneOrRow() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val prompts = RecordingPromptFiles()
            val builders = mapOf<String, (String) -> AgentAdapter>(
                SHELL_AGENT_KIND to { cwd -> ShellAdapter(cwd, "/bin/zsh") },
            )
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), agentFactoryOf(builders),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importableAgentKinds(builders.keys),
                newSessionId = { SessionId("shellro1") },
                promptFiles = prompts,
            )

            assertFailsWith<UnsupportedLaunchOptionException> {
                mgr.start(SHELL_AGENT_KIND, "/tmp", prompt = "hello")
            }
            assertFailsWith<UnsupportedLaunchOptionException> {
                mgr.start(SHELL_AGENT_KIND, "/tmp", readOnly = true)
            }

            assertTrue(prompts.written.isEmpty(), "the prompt is written only once the adapter accepts it")
            assertTrue(tmux.newSessionCommands.isEmpty())
            assertNull(store.getSession(SessionId("shellro1")))
        }
    }

    @Test
    fun aPromptNeedsAPromptDirectory() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val mgr = codexManager(store, tmux)

            assertFailsWith<PromptFilesUnavailableException> { mgr.start("codex", "/tmp", prompt = "x") }
            assertTrue(tmux.newSessionCommands.isEmpty())
        }
    }

    @Test
    fun resumeKeepsTheSessionReadOnly() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaac1")
            val mgr = codexManager(store, tmux, vendorProbe = liveTranscriptProbe)
            val options = LaunchOptions(readOnly = true, promptPath = "/prompts/ro01.md")
            store.upsertSession(
                meta("ro01", SessionState.resumable, providerId = provider)
                    .copy(agent = CODEX_AGENT_KIND, readOnly = true, promptPath = options.promptPath),
            )

            val _ = mgr.resume(SessionId("ro01"))

            val command = tmux.newSessionCommands.single().second
            assertEquals(codexLaunchCommand(LaunchMode.Resume(provider), options), command)
            assertTrue(command.contains("'--sandbox' 'read-only'"), "a resumed read-only session stays read-only")
            assertFalse(command.contains("ro01.md"), "the prompt seeded the first conversation only")
        }
    }

    @Test
    fun aCodexUpdateRelaunchBeforeAnyProviderIdReappliesThePromptAndReadOnly() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd10")
            val oldPane = PaneId("%510")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val mgr = codexManager(store, tmux, probeCliVersion = { _, _ -> "0.157.1" })
            val options = LaunchOptions(readOnly = true, promptPath = "/prompts/upd10.md")
            store.upsertSession(
                codexRow(id.value, SessionState.running, EventSource.system, oldPane)
                    .copy(readOnly = true, promptPath = options.promptPath),
            )

            mgr.onTmuxSessionClosed(id)

            val command = tmux.newSessionCommands.single().second
            assertEquals(codexLaunchCommand(LaunchMode.New, options), command)
            assertTrue(command.contains("'--sandbox' 'read-only'"))
            assertTrue(command.contains("/prompts/upd10.md"), "the conversation never started, so it is seeded again")
        }
    }

    @Test
    fun importableKindsSubtractShellAndLeaveEveryOtherKindUntouched() {
        val launchable = setOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND, JUNIE_AGENT_KIND, SHELL_AGENT_KIND)
        assertEquals(
            setOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND, JUNIE_AGENT_KIND),
            importableAgentKinds(launchable),
        )
        val alreadyImportable = setOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND)
        assertEquals(alreadyImportable, importableAgentKinds(alreadyImportable), "subtraction is idempotent")
    }

    @Test
    fun startShellCreatesARunningBoundRowWithTheLoginShellArgv() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val provider = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
            val builders = mapOf<String, (String) -> AgentAdapter>(
                SHELL_AGENT_KIND to { cwd ->
                    ShellAdapter(cwd, "/bin/zsh", generateSessionId = { provider })
                },
            )
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), agentFactoryOf(builders),
                ProviderIdCapture(store, this),
                shellVendorStoreProbe(), importLocator, importableAgentKinds(builders.keys),
                newSessionId = { SessionId("shell001") },
                now = { 1L },
            )

            val started = mgr.start(SHELL_AGENT_KIND, "/tmp")

            assertEquals(SessionState.running, started.state)
            assertEquals(provider, started.providerSessionId)
            assertEquals(SHELL_AGENT_KIND, started.agent)
            assertEquals(listOf("shell001" to "'/bin/zsh' '-l'"), tmux.newSessionCommands)
            assertEquals(provider, store.getSession(SessionId("shell001"))!!.providerSessionId)
        }
    }

    @Test
    fun aDeadShellResumesWithTheSameArgvIntoAFreshReadyPane() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val provider = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
            val builders = mapOf<String, (String) -> AgentAdapter>(
                SHELL_AGENT_KIND to { cwd ->
                    ShellAdapter(cwd, "/bin/zsh", generateSessionId = { provider })
                },
            )
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), agentFactoryOf(builders),
                ProviderIdCapture(store, this),
                shellVendorStoreProbe(), importLocator, importableAgentKinds(builders.keys),
                newSessionId = { SessionId("shell002") },
                now = { 2L },
            )
            val started = mgr.start(SHELL_AGENT_KIND, "/tmp")
            val firstPane = started.paneId
            assertTrue(tmux.killSession(started.id.value), "the first shell pane existed before simulated death")

            val resumed = mgr.resume(started.id)

            assertEquals(SessionState.ready, resumed.state)
            assertTrue(resumed.paneId != firstPane, "resume creates a fresh pane")
            assertEquals(
                listOf(
                    "shell002" to "'/bin/zsh' '-l'",
                    "shell002" to "'/bin/zsh' '-l'",
                ),
                tmux.newSessionCommands,
                "a shell resume has exactly the New argv and embeds no provider id",
            )
            assertEquals(SessionState.ready, store.getSession(started.id)!!.state)
        }
    }


    @Test
    fun aClosedShellWhoseCwdStillExistsBecomesResumableAndLosesItsPaneRegistration() = runBlocking {
        withTimeout(20.seconds) {
            val cwd = makeClosedSessionTestDirectory()
            try {
                val id = SessionId("close01")
                val pane = PaneId("%401")
                val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1")
                val store = SqliteEventStore.inMemory(now = { 1L })
                val registry = PaneRegistry()
                val mgr = shellManager(store, FakeTmux(), registry)
                store.upsertSession(
                    meta(id.value, SessionState.running, provider, pane)
                        .copy(agent = SHELL_AGENT_KIND, cwd = cwd),
                )
                registry.register(pane, id)

                mgr.onTmuxSessionClosed(id)

                val row = store.getSession(id)!!
                assertEquals(SessionState.resumable, row.state)
                assertEquals(EventSource.liveness, row.stateSource)
                assertNull(registry.lookup(pane), "a gone pane is no longer routable to provider hooks")
            } finally {
                assertEquals(0, rmdir(cwd), "the close-callback test left its cwd behind")
            }
        }
    }

    @Test
    fun aClosedShellWhoseCwdWasDeletedBecomesLost() = runBlocking {
        withTimeout(20.seconds) {
            val cwd = makeClosedSessionTestDirectory()
            assertEquals(0, rmdir(cwd), "the test models a cwd deleted before the shell exits")
            val id = SessionId("close02")
            val pane = PaneId("%402")
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val mgr = shellManager(store, FakeTmux(), registry)
            store.upsertSession(
                meta(id.value, SessionState.running, provider, pane)
                    .copy(agent = SHELL_AGENT_KIND, cwd = cwd),
            )
            registry.register(pane, id)

            mgr.onTmuxSessionClosed(id)

            val row = store.getSession(id)!!
            assertEquals(SessionState.lost, row.state)
            assertEquals(EventSource.liveness, row.stateSource)
            assertNull(registry.lookup(pane))
        }
    }

    @Test
    fun aRepeatedCloseTriggerLeavesAnAlreadyStoppedRowUntouched() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("close03")
            val pane = PaneId("%403")
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val mgr = shellManager(store, FakeTmux(), registry, now = { error("no state write expected") })
            val initial = meta(id.value, SessionState.stopped, provider, pane).copy(
                agent = SHELL_AGENT_KIND,
                stateSource = EventSource.user,
            )
            store.upsertSession(initial)
            registry.register(pane, id)
            val committed = store.getSession(id)!!

            mgr.onTmuxSessionClosed(id)
            mgr.onTmuxSessionClosed(id)

            assertEquals(committed, store.getSession(id), "duplicate tmux hooks do not rewrite an unchanged row")
            assertNull(registry.lookup(pane), "unregister remains idempotent across duplicate hooks")
        }
    }

    @Test
    fun aDelayedCloseTriggerLeavesAStillAlivePaneAndRowUntouched() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("close04")
            val pane = PaneId("%404")
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4")
            val livePane = TmuxPane("kt-${id.value}", pane, 4242, false, 120, 40)
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val mgr = shellManager(
                store,
                FakeTmux(listOf(livePane)),
                registry,
                now = { error("no state write expected") },
            )
            val initial = meta(id.value, SessionState.needs_approval, provider, pane)
                .copy(agent = SHELL_AGENT_KIND, stateSource = EventSource.hook)
            store.upsertSession(initial)
            registry.register(pane, id)
            val committed = store.getSession(id)!!

            mgr.onTmuxSessionClosed(id)

            assertEquals(committed, store.getSession(id), "tmux truth wins over a stale close notification")
            assertEquals(id, registry.lookup(pane), "a pane that is still alive stays registered")
        }
    }

    @Test
    fun closeReclassificationCannotRaceAResumeIntoLeavingADeadRowOverALivePane() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("close05")
            val oldPane = PaneId("%405")
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5")
            val backing = SqliteEventStore.inMemory(now = { 1L })
            val store = TracingStore(backing)
            val registry = PaneRegistry()
            val probe = GatedVendorProbe(result = true)
            val tmux = FakeTmux()
            val mgr = shellManager(store, tmux, registry, vendorProbe = probe)
            store.upsertSession(
                meta(id.value, SessionState.running, provider, oldPane)
                    .copy(agent = SHELL_AGENT_KIND),
            )
            registry.register(oldPane, id)

            val closing = async(start = CoroutineStart.UNDISPATCHED) { mgr.onTmuxSessionClosed(id) }
            probe.entered.await()
            val resuming = async(start = CoroutineStart.UNDISPATCHED) { mgr.resume(id) }
            repeat(10) { yield() }
            assertTrue(
                tmux.newSessionCommands.isEmpty(),
                "resume waits for the close callback's complete read/probe/write critical section",
            )

            probe.release.complete(Unit)
            closing.await()
            val resumed = resuming.await()

            assertEquals(
                listOf(SessionState.resumable, SessionState.ready),
                store.stateWrites,
                "the close classification lands before resume, never after its live-state write",
            )
            assertEquals(SessionState.ready, backing.getSession(id)!!.state)
            assertFalse(backing.getSession(id)!!.state.isDead, "the durable row is live when its pane is live")
            assertTrue(tmux.listPanes().any { it.session == "kt-${id.value}" && !it.dead })
            assertEquals(id, registry.lookup(resumed.paneId!!))
        }
    }

    @Test
    fun closeStateWritePreservesHooksThatAdvanceSeqAndReplaceTheProviderIdMidProbe() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("close06")
            val pane = PaneId("%406")
            val provisional = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6")
            val authoritative = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb6")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val probe = GatedVendorProbe(result = true)
            val mgr = shellManager(store, FakeTmux(), vendorProbe = probe)
            store.upsertSession(
                meta(id.value, SessionState.running, provisional, pane)
                    .copy(agent = SHELL_AGENT_KIND),
            )

            val closing = async(start = CoroutineStart.UNDISPATCHED) { mgr.onTmuxSessionClosed(id) }
            probe.entered.await()
            val _ = store.append(id, AgentEvent.SessionBound(authoritative), EventSource.hook)
            val _ = store.append(id, AgentEvent.ToolCall("after-close-observation"), EventSource.hook)
            probe.release.complete(Unit)
            closing.await()

            val row = store.getSession(id)!!
            assertEquals(SessionState.resumable, row.state)
            assertEquals(Seq(2), row.lastSeq, "the liveness write cannot regress concurrently appended events")
            assertEquals(
                authoritative,
                row.providerSessionId,
                "the liveness write cannot restore the stale provisional provider id",
            )
        }
    }

    @Test
    fun aCodexPaneClosedBeforeAnyHookUnderANewerCliIsRelaunchedWithTheSameArguments() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd01")
            val oldPane = PaneId("%501")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val discovered = mutableListOf<SessionMeta>()
            val captured = CompletableDeferred<SessionMeta>()
            val mgr = codexManager(
                store, tmux, registry,
                probeCliVersion = { _, _ -> "0.157.1" },
                discoverProviderId = { m -> discovered += m; null },
                captureModelInBackground = { m -> captured.complete(m) },
                now = { 5_000L },
            )
            store.upsertSession(codexRow(id.value, SessionState.running, EventSource.system, oldPane))
            registry.register(oldPane, id)

            mgr.onTmuxSessionClosed(id)

            assertEquals(
                listOf(id.value to codexLaunchCommand(LaunchMode.New)), tmux.newSessionCommands,
                "the launch is repeated under the same tmux session with the same argv",
            )
            val newPane = tmux.listPanes().single { it.session == "kt-${id.value}" && !it.dead }.paneId
            val row = store.getSession(id)!!
            assertEquals(SessionState.running, row.state)
            assertEquals(EventSource.system, row.stateSource, "a relaunch is the daemon's own write, like start")
            assertEquals(newPane, row.paneId, "the row follows the new pane")
            assertEquals("0.157.1", row.cliVersion, "the row records the version the new pane runs")
            assertEquals(5_000L, row.updatedAt, "a relaunch is activity")
            assertEquals(id, registry.lookup(newPane), "hooks from the new pane route to the session")
            assertNull(registry.lookup(oldPane), "the dead pane is forgotten")
            assertEquals(newPane, captured.await().paneId, "model capture restarts for the new incarnation")
            repeat(5) { yield() }
            assertEquals(listOf(newPane), discovered.map { it.paneId }, "a New launch re-arms the rollout id discovery")
        }
    }

    @Test
    fun aResumedCodexPaneClosedBeforeAnyHookUnderANewerCliIsRelaunchedAsAResume() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd02")
            val oldPane = PaneId("%502")
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaab2")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            var discoveries = 0
            val mgr = codexManager(
                store, tmux,
                vendorProbe = VendorStoreProbe { _, _, _ -> error("a relaunch trusts the transcript the launch had") },
                probeCliVersion = { _, _ -> "0.157.1" },
                discoverProviderId = { discoveries++; null },
            )
            // What resume() leaves behind: ready, written by the operator's control action.
            store.upsertSession(codexRow(id.value, SessionState.ready, EventSource.user, oldPane, provider))

            mgr.onTmuxSessionClosed(id)

            assertEquals(listOf(id.value to codexLaunchCommand(LaunchMode.Resume(provider))), tmux.newSessionCommands)
            val row = store.getSession(id)!!
            assertEquals(SessionState.running, row.state)
            assertEquals("0.157.1", row.cliVersion)
            repeat(5) { yield() }
            assertEquals(0, discoveries, "a bound id is kept: no discovery for a resume relaunch")
        }
    }

    @Test
    fun aCodexPaneClosedUnderTheSameCliVersionIsClassifiedAsBefore() = runBlocking {
        withTimeout(20.seconds) {
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaab3")
            for ([withId, expected] in listOf(false to SessionState.lost, true to SessionState.resumable)) {
                val id = SessionId("upd03")
                val pane = PaneId("%503")
                val store = SqliteEventStore.inMemory(now = { 1L })
                val registry = PaneRegistry()
                val tmux = FakeTmux()
                val mgr = codexManager(
                    store, tmux, registry,
                    vendorProbe = liveTranscriptProbe,
                    probeCliVersion = { _, _ -> "0.156.0" },
                )
                store.upsertSession(
                    codexRow(id.value, SessionState.running, EventSource.system, pane, provider.takeIf { withId }),
                )
                registry.register(pane, id)

                mgr.onTmuxSessionClosed(id)

                assertTrue(tmux.newSessionCommands.isEmpty(), "an unchanged version is not an update exit")
                val row = store.getSession(id)!!
                assertEquals(expected, row.state)
                assertEquals(EventSource.liveness, row.stateSource)
                assertNull(registry.lookup(pane))
            }
        }
    }

    @Test
    fun aCodexRowLastWrittenByAHookIsClassifiedNotRelaunched() = runBlocking {
        withTimeout(20.seconds) {
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaab4")
            for (state in listOf(SessionState.ready, SessionState.needs_approval, SessionState.running)) {
                val id = SessionId("upd04")
                val store = SqliteEventStore.inMemory(now = { 1L })
                val tmux = FakeTmux()
                val mgr = codexManager(
                    store, tmux,
                    vendorProbe = liveTranscriptProbe,
                    probeCliVersion = { _, _ -> error("a hook-written row is never probed") },
                )
                store.upsertSession(codexRow(id.value, state, EventSource.hook, PaneId("%504"), provider))

                mgr.onTmuxSessionClosed(id)

                assertTrue(tmux.newSessionCommands.isEmpty(), "$state from a hook means the conversation existed")
                assertEquals(SessionState.resumable, store.getSession(id)!!.state)
            }
        }
    }

    @Test
    fun aDeadCodexRowIsNeverRelaunched() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd05")
            val pane = PaneId("%505")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val mgr = codexManager(
                store, tmux,
                vendorProbe = liveTranscriptProbe,
                probeCliVersion = { _, _ -> error("a dead row is never probed") },
                now = { error("no state write expected") },
            )
            // What SessionEnd and Stop leave behind.
            store.upsertSession(
                codexRow(
                    id.value, SessionState.stopped, EventSource.user, pane,
                    providerId = ProviderSessionId("55555555-5555-4555-8555-555555555555"),
                ),
            )
            val committed = store.getSession(id)!!

            mgr.onTmuxSessionClosed(id)

            assertTrue(tmux.newSessionCommands.isEmpty())
            assertEquals(committed, store.getSession(id))
        }
    }

    @Test
    fun aCodexRelaunchNeedsBothVersionsToBeKnown() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd06")
            val pane = PaneId("%506")
            for ([launched, installed] in listOf("0.156.0" to null, null to "0.157.1")) {
                val store = SqliteEventStore.inMemory(now = { 1L })
                val tmux = FakeTmux()
                val mgr = codexManager(
                    store, tmux,
                    probeCliVersion = { _, _ -> installed },
                )
                store.upsertSession(
                    codexRow(id.value, SessionState.running, EventSource.system, pane, cliVersion = launched),
                )

                mgr.onTmuxSessionClosed(id)

                assertTrue(tmux.newSessionCommands.isEmpty(), "launched=$launched installed=$installed")
                assertEquals(SessionState.lost, store.getSession(id)!!.state)
            }
        }
    }

    @Test
    fun otherAgentsAreNeverRelaunchedAfterAVersionChange() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd07")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val mgr = codexManager(
                store, tmux,
                probeCliVersion = { _, _ -> error("only codex is probed") },
            )
            store.upsertSession(
                codexRow(id.value, SessionState.running, EventSource.system, PaneId("%507")).copy(agent = CLAUDE_AGENT_KIND),
            )

            mgr.onTmuxSessionClosed(id)

            assertTrue(tmux.newSessionCommands.isEmpty())
            assertEquals(SessionState.lost, store.getSession(id)!!.state)
        }
    }

    @Test
    fun aLateCloseTriggerForALiveCodexPaneDoesNotRelaunch() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd08")
            val pane = PaneId("%508")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux(listOf(TmuxPane("kt-${id.value}", pane, 4242, false, 120, 40)))
            val mgr = codexManager(
                store, tmux,
                probeCliVersion = { _, _ -> error("a live pane is never probed") },
                now = { error("no state write expected") },
            )
            store.upsertSession(codexRow(id.value, SessionState.running, EventSource.system, pane))
            val committed = store.getSession(id)!!

            mgr.onTmuxSessionClosed(id)

            assertTrue(tmux.newSessionCommands.isEmpty())
            assertEquals(committed, store.getSession(id))
        }
    }

    @Test
    fun aFailedCodexRelaunchFallsBackToClassification() = runBlocking {
        withTimeout(20.seconds) {
            val id = SessionId("upd09")
            val pane = PaneId("%509")
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failAfterCreate = true)
            val mgr = codexManager(
                store, tmux, registry,
                probeCliVersion = { _, _ -> "0.157.1" },
            )
            store.upsertSession(codexRow(id.value, SessionState.running, EventSource.system, pane))
            registry.register(pane, id)

            mgr.onTmuxSessionClosed(id)

            val row = store.getSession(id)!!
            assertEquals(SessionState.lost, row.state, "the row is classified as it was before relaunches existed")
            assertEquals(EventSource.liveness, row.stateSource)
            assertEquals("0.156.0", row.cliVersion, "a launch that did not happen records no version")
            assertNull(registry.lookup(pane))
        }
    }

    @Test
    fun aCloseTriggerForAnUnknownIdIsASilentNoOp() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val mgr = shellManager(
                store,
                FakeTmux(),
                registry,
                vendorProbe = VendorStoreProbe { _, _, _ -> error("an unknown id must not reach the probe") },
                now = { error("an unknown id must not write") },
            )

            mgr.onTmuxSessionClosed(SessionId("not-ours"))

            assertTrue(store.listSessions().isEmpty())
            assertTrue(registry.snapshot().isEmpty())
        }
    }


    @Test
    fun startPersistsTheCliVersionAndPathFromTheSpec() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null, cliVersion = "2.1.218", cliPath = "/usr/local/bin/claude"),
                ProviderIdCapture(store, this, maxAttempts = 1, retryDelayMillis = 1),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("sess09") },
                now = { 1L },
            )

            val _ = mgr.start("claude", "/tmp/work")

            val stored = store.getSession(SessionId("sess09"))!!
            assertEquals("2.1.218", stored.cliVersion, "start persisted the spec's cliVersion")
            assertEquals("/usr/local/bin/claude", stored.cliPath, "start persisted the spec's cliPath")
        }
    }

    @Test
    fun startLeavesCliVersionNullWhenTheSpecHasNone() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this, maxAttempts = 1, retryDelayMillis = 1),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("sess10") },
                now = { 1L },
            )

            val _ = mgr.start("claude", "/tmp/work")

            assertNull(store.getSession(SessionId("sess10"))!!.cliVersion, "no version in spec -> null, no crash")
        }
    }

    @Test
    fun startRecordsTheProbedCliVersionInsteadOfTheDaemonStartCache() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val probed = mutableListOf<Pair<String, String?>>()
            val mgr = codexManager(
                store, FakeTmux(),
                probeCliVersion = { agent, path -> probed += agent to path; "0.157.1" },
                newSessionId = { SessionId("cliv01") },
            )

            val started = mgr.start("codex", "/tmp/work")

            assertEquals("0.157.1", started.cliVersion, "the returned row carries the probed version")
            assertEquals(
                "0.157.1", store.getSession(SessionId("cliv01"))!!.cliVersion,
                "the row records the version the pane runs, not the daemon-start cache",
            )
            assertEquals(listOf<Pair<String, String?>>("codex" to "/opt/codex"), probed, "the probe targets the launched binary")
        }
    }

    @Test
    fun resumeRecordsTheCliVersionTheNewIncarnationRuns() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa7")
            val mgr = codexManager(
                store, tmux,
                vendorProbe = liveTranscriptProbe,
                probeCliVersion = { _, _ -> "0.157.1" },
            )
            store.upsertSession(
                meta("cliv02", SessionState.resumable, providerId = provider)
                    .copy(agent = CODEX_AGENT_KIND, cliVersion = "0.156.0"),
            )
            val before = store.getSession(SessionId("cliv02"))!!

            val revived = mgr.resume(SessionId("cliv02"))

            assertEquals("0.157.1", revived.cliVersion, "the returned row carries the probed version")
            val row = store.getSession(SessionId("cliv02"))!!
            assertEquals("0.157.1", row.cliVersion, "resume rewrites the version with what it launched")
            assertTrue(row.rev > before.rev, "the rewrite is a revisioned row change")
            assertEquals(SessionState.ready, row.state)
            assertTrue(tmux.newSessionCommands.single().second.contains("'resume' '${provider.value}'"))
        }
    }

    @Test
    fun aFailedProbeKeepsTheVersionTheRowAlreadyHas() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa8")
            val mgr = codexManager(
                store, FakeTmux(),
                vendorProbe = liveTranscriptProbe,
                probeCliVersion = { _, _ -> null },
                newSessionId = { SessionId("cliv03") },
            )

            val _ = mgr.start("codex", "/tmp/work")
            assertEquals(
                "0.156.0", store.getSession(SessionId("cliv03"))!!.cliVersion,
                "no probe answer at start: the launch spec's daemon-start version stays",
            )

            store.upsertSession(
                meta("cliv04", SessionState.resumable, providerId = provider)
                    .copy(agent = CODEX_AGENT_KIND, cliVersion = "0.150.0"),
            )
            val revived = mgr.resume(SessionId("cliv04"))
            assertEquals("0.150.0", revived.cliVersion, "no probe answer at resume: the recorded version stays")
            assertEquals("0.150.0", store.getSession(SessionId("cliv04"))!!.cliVersion)
        }
    }

    @Test
    fun startInvokesTheBackgroundModelCaptureForTheNewSession() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val captured = CompletableDeferred<SessionMeta>()
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this, maxAttempts = 1, retryDelayMillis = 1),
                importProbe, importLocator, importKinds,
                captureModelInBackground = { meta -> captured.complete(meta) },
                newSessionId = { SessionId("mdl01") },
                now = { 1L },
            )

            val _ = mgr.start("codex", "/work/x")

            val meta = captured.await()
            assertEquals(SessionId("mdl01"), meta.id, "start wired model capture for the new session")
            assertEquals("/work/x", meta.cwd, "…with the session's cwd for the rollout scan")
        }
    }

    @Test
    fun aProviderIdRebindClearsTheSuspectModelAndRetriggersTheCapture() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val captured = CompletableDeferred<SessionMeta>()
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                captureModelInBackground = { m -> captured.complete(m) },
                now = { 9L },
            )
            val hookId = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            store.upsertSession(
                meta("rbnd01", SessionState.running, providerId = hookId)
                    .copy(agent = "codex", model = "gpt-6"),
            )

            mgr.onProviderIdRebound(SessionId("rbnd01"))

            assertNull(store.getSession(SessionId("rbnd01"))!!.model, "the displaced id's model is cleared")
            val m = captured.await()
            assertEquals(SessionId("rbnd01"), m.id, "the capture was re-triggered for the session")
            assertEquals(hookId, m.providerSessionId, "…with the row meta carrying the hook's own id")
        }
    }


    @Test
    fun startBindsThePreallocatedProviderIdIntoTheLogImmediately() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("sess01") },
                now = { 1L },
            )

            val meta = mgr.start("claude", "/tmp/work")

            assertEquals(listOf("sess01" to "'cat'"), tmux.newSessionCommands)
            assertEquals("kt-sess01", meta.tmuxSession)
            val pane = meta.paneId!!
            assertEquals(SessionId("sess01"), registry.lookup(pane), "start registered pane->session")

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
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val discovered = ProviderSessionId("cccccccc-cccc-7ccc-8ccc-cccccccccccc")
            val seen = mutableListOf<SessionMeta>()
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this, maxAttempts = 5, retryDelayMillis = 1),
                importProbe, importLocator, importKinds,
                discoverProviderId = { meta -> discovered.also { seen += meta } },
                newSessionId = { SessionId("cx0001") },
                now = { 1L },
            )

            val _ = mgr.start("codex", "/work/repo")

            withTimeout(5.seconds) {
                while (store.projectionOf(SessionId("cx0001")).providerSessionId == null) delay(5.milliseconds)
            }
            val events = store.read(SessionId("cx0001"), Seq(0))
            assertEquals(1, events.size, "exactly one event: the discovered SessionBound")
            assertEquals(AgentEvent.SessionBound(discovered), events[0].event)
            assertEquals(discovered, store.getSession(SessionId("cx0001"))!!.providerSessionId, "resume is unblocked")

            assertEquals("codex", seen.first().agent)
            assertEquals("/work/repo", seen.first().cwd)
        }
    }

    @Test
    fun aHookDeliveredIdWinsOverProviderDiscovery() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val fromHook = ProviderSessionId("dddddddd-dddd-7ddd-8ddd-dddddddddddd")
            val fromDisk = ProviderSessionId("eeeeeeee-eeee-7eee-8eee-eeeeeeeeeeee")
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this, maxAttempts = 5, retryDelayMillis = 20),
                importProbe, importLocator, importKinds,
                discoverProviderId = { fromDisk },
                newSessionId = { SessionId("cx0002") },
                now = { 1L },
            )

            val _ = mgr.start("codex", "/work/repo")
            val _ = store.append(SessionId("cx0002"), AgentEvent.SessionBound(fromHook), EventSource.hook)

            withTimeout(5.seconds) {
                while (store.projectionOf(SessionId("cx0002")).providerSessionId == null) delay(5.milliseconds)
            }
            assertEquals(fromHook, store.projectionOf(SessionId("cx0002")).providerSessionId)
        }
    }


    @Test
    fun providerIdCaptureRetriesAStallThenBindsOnDiscovery() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val idCapture = ProviderIdCapture(store, this, maxAttempts = 5, retryDelayMillis = 1)
            val provider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            var calls = 0
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
        withTimeout(20.seconds) {
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
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                now = { 1L },
            )
            store.upsertSession(meta("dead01", SessionState.crashed, providerId = null))

            val ex = assertFailsWith<ResumeBlockedException> { mgr.resume(SessionId("dead01")) }
            assertEquals(SessionId("dead01"), ex.sessionId)
            assertTrue(tmux.newSessionCommands.isEmpty(), "resume must not spawn tmux while the id is pending")
        }
    }


    @Test
    fun markDoneKillsTheAgentAndArchivesTheSession() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("done01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            mgr.markDone(SessionId("done01"))

            assertEquals(listOf("done01"), tmux.killed, "Done kills the agent")
            val row = store.getSession(SessionId("done01"))!!
            assertEquals(SessionState.stopped, row.state, "the killed session is stopped")
            assertTrue(row.archived, "and archived off the sidebar")
            assertEquals(null, registry.lookup(pane), "the pane is unregistered")
        }
    }

    @Test
    fun undoneUnarchivesWithoutTouchingTmux() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("done02") },
                now = { 1L },
            )
            store.upsertSession(meta("done02", SessionState.stopped, providerId = null).copy(archived = true))

            mgr.undone(SessionId("done02"))

            assertEquals(false, store.getSession(SessionId("done02"))!!.archived, "Restore clears archived")
            assertTrue(tmux.killed.isEmpty(), "Restore touches no tmux")
        }
    }

    @Test
    fun resumingADoneSessionBringsItBackToTheSidebar() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                liveTranscriptProbe, importLocator, importKinds,
                now = { 7L },
            )
            store.upsertSession(
                meta("done03", SessionState.stopped, providerId = provider, paneId = PaneId("%1"))
                    .copy(archived = true),
            )

            val revived = mgr.resume(SessionId("done03"))

            assertEquals(SessionState.ready, revived.state, "resume revives the archived session")
            assertEquals(false, revived.archived, "the answered DTO reports it un-archived (clients merge it verbatim)")
            assertEquals(
                false, store.getSession(SessionId("done03"))!!.archived,
                "a resumed session is not Done — its row is visible again, not a live agent nobody can see",
            )
        }
    }

    @Test
    fun resumingAnArchivedSessionWhosePaneIsAliveStillUnarchivesIt() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux(
                listOf(TmuxPane(session = "kt-done04", paneId = PaneId("%1"), pid = 4242, dead = false, width = 80, height = 24)),
            )
            val provider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                now = { 7L },
            )
            store.upsertSession(
                meta("done04", SessionState.running, providerId = provider, paneId = PaneId("%1"))
                    .copy(archived = true),
            )

            val updated = mgr.resume(SessionId("done04"))

            assertTrue(tmux.newSessionCommands.isEmpty(), "a live pane keeps the launch a no-op")
            assertEquals(false, updated.archived, "…but the un-archive still runs, so the row is recoverable")
            assertEquals(false, store.getSession(SessionId("done04"))!!.archived)
            assertEquals(SessionState.running, store.getSession(SessionId("done04"))!!.state, "and its state is untouched")
        }
    }


    @Test
    fun stopKillsTheTmuxSessionAndCachesStopped() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("stop01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            mgr.stop(SessionId("stop01"))

            assertEquals(listOf("stop01"), tmux.killed, "a clean kill-session")
            val row = store.getSession(SessionId("stop01"))!!
            assertEquals(SessionState.stopped, row.state, "a clean kill is classified stopped, not crashed")
            assertEquals(EventSource.user, row.stateSource)
            assertEquals(null, registry.lookup(pane), "a stopped session's pane is unregistered")
        }
    }

    @Test
    fun interruptSendsCtrlCAndCachesReadyKeepingThePaneRegistered() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
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
        withTimeout(20.seconds) {
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
                importProbe, importLocator, importKinds,
                newSessionId = { id },
                now = { 1L },
            )
            val _ = mgr.start("claude", "/tmp")
            tracing.stateWrites.clear()
            store.arm()

            val interrupting = launch { mgr.interrupt(id) }
            try {
                store.entered.await()
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
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                liveTranscriptProbe, importLocator, importKinds,
                now = { 7L },
            )
            store.upsertSession(meta("resu01", SessionState.resumable, providerId = provider, paneId = PaneId("%1")))

            val updated = mgr.resume(SessionId("resu01"))

            assertEquals(SessionState.ready, updated.state, "resume revives a dead session to ready")
            val [id, cmd] = tmux.newSessionCommands.single()
            assertEquals("resu01", id, "the fresh session reuses the logical id")
            assertTrue(cmd.contains("--resume") && cmd.contains(provider.value), "the resume launch carries --resume <providerId>: $cmd")
            val pane = updated.paneId!!
            assertEquals(SessionId("resu01"), registry.lookup(pane), "the fresh pane is registered")
            assertEquals(SessionState.ready, store.getSession(SessionId("resu01"))!!.state)
        }
    }

    @Test
    fun aCrashedSessionWhoseTranscriptSurvivedStillResumes() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                liveTranscriptProbe, importLocator, importKinds,
                now = { 7L },
            )
            store.upsertSession(meta("crsh01", SessionState.crashed, providerId = provider, paneId = PaneId("%1")))

            val updated = mgr.resume(SessionId("crsh01"))

            assertEquals(SessionState.ready, updated.state, "a failed exit is resumable while its transcript lives")
            assertEquals(1, tmux.newSessionCommands.size, "and the guard let the launch through")
        }
    }

    @Test
    fun resumeIsRefusedForASessionWhoseTranscriptTheProviderDeleted() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                now = { 7L },
            )
            store.upsertSession(meta("lost01", SessionState.stopped, providerId = provider, paneId = PaneId("%1")))

            val ex = assertFailsWith<TranscriptGoneException> { mgr.resume(SessionId("lost01")) }

            assertEquals(provider, ex.providerSessionId)
            assertTrue(tmux.newSessionCommands.isEmpty(), "a pane the provider would reject is never opened")
            val row = store.getSession(SessionId("lost01"))!!
            assertEquals(SessionState.lost, row.state, "the refusal records that the stopped session is gone for good")
            assertEquals(EventSource.liveness, row.stateSource)
            assertNull(registry.lookup(PaneId("%1")), "and registers no pane")
        }
    }

    @Test
    fun aRefusedResumeLeavesARowWithALivePaneAlone() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val pane = tmux.seedPane("live01")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                now = { 7L },
            )
            store.upsertSession(
                meta("live01", SessionState.stopped, providerId = ProviderSessionId("ffffffff-ffff-4fff-8fff-ffffffffffff"), paneId = pane),
            )

            assertFailsWith<TranscriptGoneException> { mgr.resume(SessionId("live01")) }

            assertEquals(
                SessionState.stopped, store.getSession(SessionId("live01"))!!.state,
                "a session whose pane still runs is not lost",
            )
        }
    }

    @Test
    fun detachIsANoOpThatLeavesTheSessionRunning() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("ffffffff-ffff-4fff-8fff-ffffffffffff")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
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


    @Test
    fun anInterruptRacingAStopDoesNotResurrectTheStoppedSession() = runBlocking {
        withTimeout(20.seconds) {
            val store = GatedStore(SqliteEventStore.inMemory(now = { 1L }), SessionId("race01"))
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("11111111-1111-4111-8111-111111111111")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("race01") },
                now = { 1L },
            )
            val started = mgr.start("claude", "/tmp")
            val pane = started.paneId!!

            store.arm()
            val interrupting = launch { mgr.interrupt(SessionId("race01")) }
            store.entered.await()

            val stopping = launch { mgr.stop(SessionId("race01")) }
            repeat(50) { yield() }
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
        withTimeout(20.seconds) {
            val store = FailingStore(SqliteEventStore.inMemory(now = { 1L }), failAppend = true)
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("22222222-2222-4222-8222-222222222222")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
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
        withTimeout(20.seconds) {
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
                liveTranscriptProbe, importLocator, importKinds,
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
        withTimeout(20.seconds) {
            val store = TracingStore(SqliteEventStore.inMemory(now = { 1L }), gateUpsertFor = SessionId("strt01"))
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("44444444-4444-4444-8444-444444444444")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("strt01") },
                now = { 1L },
            )

            val starting = launch { val _ = mgr.start("claude", "/tmp") }
            store.entered.await()

            val stopping = launch { mgr.stop(SessionId("strt01")) }
            repeat(50) { yield() }
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
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failAfterCreate = true)
            val provider = ProviderSessionId("55555555-5555-4555-8555-555555555555")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
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
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failAfterCreate = true)
            val provider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                liveTranscriptProbe, importLocator, importKinds,
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
        withTimeout(20.seconds) {
            val store = TracingStore(SqliteEventStore.inMemory(now = { 1L }), gateUpsertFor = SessionId("cncl01"))
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("66666666-6666-4666-8666-666666666666")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("cncl01") },
                now = { 1L },
            )

            val starting = launch { val _ = runCatching { mgr.start("claude", "/tmp") } }
            store.entered.await()
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
        withTimeout(20.seconds) {
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
                importProbe, importLocator, importKinds,
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
        withTimeout(20.seconds) {
            val store = TracingStore(SqliteEventStore.inMemory(now = { 1L }))
            var persistedAtKill: List<SessionState> = listOf(SessionState.needs_answer)
            val tmux = ObservingTmux(onKill = { persistedAtKill = store.stateWrites.toList() })
            val provider = ProviderSessionId("88888888-8888-4888-8888-888888888888")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(),
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("ordr01") },
                now = { 1L },
            )
            val _ = mgr.start("claude", "/tmp")

            mgr.stop(SessionId("ordr01"))

            assertEquals(listOf(SessionState.stopped), persistedAtKill, "the stop intent is persisted BEFORE the kill")
            assertEquals(SessionState.stopped, store.getSession(SessionId("ordr01"))!!.state)
        }
    }

    @Test
    fun aStopWhoseKillFailsRollsTheStopIntentBack() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = ObservingTmux(failKill = true)
            val provider = ProviderSessionId("99999999-9999-4999-8999-999999999999")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
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


    @Test
    fun startRegeneratesTheSessionIdOnACollisionWithAnExistingSessionOrLog() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            store.upsertSession(meta("dup00001", SessionState.crashed, providerId = null))
            val _ = store.append(SessionId("dup00001"), AgentEvent.TurnStarted, EventSource.hook)
            val ids = ArrayDeque(listOf("dup00001", "dup00001", "fresh001"))
            val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = provider),
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId(ids.removeFirst()) },
                now = { 1L },
            )

            val started = mgr.start("claude", "/tmp")

            assertEquals("fresh001", started.id.value, "the colliding id is rejected; a free id is used")
            assertEquals(SessionState.crashed, store.getSession(SessionId("dup00001"))!!.state, "historical row untouched")
            assertEquals(1, store.read(SessionId("dup00001"), Seq(0)).size, "the historical log is untouched")
        }
    }

    @Test
    fun agentFactoryRejectsUnsupportedAgentsBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val factory = agentFactoryOf(
                mapOf(
                    "claude" to { cwd: String -> StubAgentFactory(cat, null).create("claude", cwd) },
                    "codex" to { cwd: String -> StubAgentFactory(cat, null).create("codex", cwd) },
                ),
            )
            val direct = assertFailsWith<UnsupportedAgentException> { factory.create("aider", "/tmp") }
            assertEquals("aider", direct.agentKind)
            assertEquals(setOf("claude", "codex"), direct.supported)
            assertTrue(direct.message!!.contains("claude, codex"), "the error names the supported kinds")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), factory,
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("x0000001") }, now = { 1L },
            )
            assertFailsWith<UnsupportedAgentException> { mgr.start("aider", "/tmp") }
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux session is created for an unsupported agent")
        }
    }

    @Test
    fun agentFactoryFailsFastWhenTheAgentBinaryIsNotFoundBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val factory = agentFactoryOf(
                mapOf(
                    "claude" to { _: String -> throw AgentBinaryNotFoundException("claude") },
                    "codex" to { cwd: String -> StubAgentFactory(cat, null).create("codex", cwd) },
                ),
            )
            val direct = assertFailsWith<AgentBinaryNotFoundException> { factory.create("claude", "/tmp") }
            assertEquals("claude", direct.agentKind)
            assertTrue(direct.message!!.contains("claude"), "the message names the agent kind")
            assertTrue(direct.message!!.contains("kotgent install"), "the message points at `kotgent install`")
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), factory,
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("nf000001") }, now = { 1L },
            )
            assertFailsWith<AgentBinaryNotFoundException> { mgr.start("claude", "/tmp") }
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux session is created for an unresolvable agent")
            assertNull(store.getSession(SessionId("nf000001")), "no phantom `running` row is persisted")
        }
    }

    @Test
    fun agentFactoryFailsFastWhenTheResolvedBinaryPathIsNotAbsoluteBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val tmux = FakeTmux()
            val direct = assertFailsWith<AgentBinaryNotFoundException> { requireAbsoluteBinary("claude", "claude") }
            assertEquals("claude", direct.agentKind)
            assertTrue(direct.message!!.contains("kotgent install"), "the message points at `kotgent install`")
            assertFailsWith<AgentBinaryNotFoundException> { requireAbsoluteBinary("claude", "./claude") }
            assertEquals("/opt/homebrew/bin/claude", requireAbsoluteBinary("claude", "/opt/homebrew/bin/claude"))

            val factory = agentFactoryOf(
                mapOf(
                    "claude" to { cwd: String ->
                        val _ = requireAbsoluteBinary("claude", "claude")
                        StubAgentFactory(cat, null).create("claude", cwd)
                    },
                ),
            )
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), factory,
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                newSessionId = { SessionId("na000001") }, now = { 1L },
            )
            assertFailsWith<AgentBinaryNotFoundException> { mgr.start("claude", "/tmp") }
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux session is created for a non-absolute agent path")
            assertNull(store.getSession(SessionId("na000001")), "no phantom `running` row is persisted")
        }
    }

    @Test
    fun resumePropagatesAgentBinaryNotFoundBeforeAnyTmuxSideEffect() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val factory = agentFactoryOf(
                mapOf("claude" to { _: String -> throw AgentBinaryNotFoundException("claude") }),
            )
            val provider = ProviderSessionId("ffffffff-ffff-4fff-8fff-ffffffffffff")
            store.upsertSession(meta("nfr01", SessionState.crashed, providerId = provider))
            val mgr = SessionManager(
                tmux, store, registry, factory,
                ProviderIdCapture(store, this),
                importProbe, importLocator, importKinds,
                now = { 1L },
            )

            val ex = assertFailsWith<AgentBinaryNotFoundException> { mgr.resume(SessionId("nfr01")) }
            assertEquals("claude", ex.agentKind)
            assertTrue(tmux.newSessionCommands.isEmpty(), "resume must not spawn tmux for an unresolvable agent")
            assertEquals(
                SessionState.crashed, store.getSession(SessionId("nfr01"))!!.state,
                "the failed resume leaves the stored row unchanged (no phantom state transition)",
            )
        }
    }

    @Test
    fun resumeRevivesASessionWhoseCacheSaysAliveButWhosePaneActuallyDied() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val provider = ProviderSessionId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
            val mgr = SessionManager(
                tmux, store, registry,
                StubAgentFactory(cat, preallocated = null),
                ProviderIdCapture(store, this),
                liveTranscriptProbe, importLocator, importKinds,
                now = { 1L },
            )
            store.upsertSession(meta("zomb01", SessionState.running, providerId = provider, paneId = PaneId("%1")))

            val updated = mgr.resume(SessionId("zomb01"))

            assertEquals(SessionState.ready, updated.state, "a dead-but-cached-alive session is revived, not a no-op")
            assertEquals(1, tmux.newSessionCommands.size, "resume spawned a fresh tmux session for the dead pane")
            assertEquals(SessionState.ready, store.getSession(SessionId("zomb01"))!!.state)
        }
    }


    @Test
    fun missingWorkingDirectoryCannotCreateOrResumeASession() = runBlocking {
        val realTmux = Tmux(socket = "kotgent-test")
        if (!realTmux.isAvailable()) return@runBlocking
        val _ = ProcessRunner.run(listOf(realTmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
        val dir = makeClosedSessionTestDirectory()
        val id = SessionId("cwd00001")
        try {
            withTimeout(30.seconds) {
                val store = SqliteEventStore.inMemory()
                val registry = PaneRegistry()
                val provider = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
                val mgr = SessionManager(
                    realTmux, store, registry,
                    StubAgentFactory(cat, preallocated = provider),
                    ProviderIdCapture(store, this),
                    liveTranscriptProbe, importLocator, importKinds,
                    newSessionId = { id },
                )

                assertFailsWith<TmuxException> { mgr.start("claude", "$dir/missing") }
                assertNull(store.getSession(id), "a refused launch leaves no running session row")
                assertTrue(registry.snapshot().isEmpty())
                assertTrue(realTmux.listPanes().isEmpty())

                val _ = mgr.start("claude", dir)
                mgr.stop(id)
                assertEquals(0, rmdir(dir))
                assertFailsWith<TmuxException> { mgr.resume(id) }
                assertEquals(SessionState.stopped, store.getSession(id)?.state, "failed resume stays stopped")
                assertTrue(registry.snapshot().isEmpty())
                assertTrue(realTmux.listPanes().isEmpty(), "a transcript does not make a missing cwd safe")
            }
        } finally {
            val _ = realTmux.killSession(id.value)
            val _ = ProcessRunner.run(listOf(realTmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
            rmdir(dir)
        }
    }

    @Test
    fun startCreatesARealTmuxSessionThenAReconcilerRestoresItAndRebuildsTheRegistry() = runBlocking {
        val realTmux = Tmux(socket = "kotgent-test")
        if (!realTmux.isAvailable()) return@runBlocking
        val _ = ProcessRunner.run(listOf(realTmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
        try {
            withTimeout(30.seconds) {
                val store = SqliteEventStore.inMemory()
                val registry = PaneRegistry()
                val provider = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
                val mgr = SessionManager(
                    realTmux, store, registry,
                    StubAgentFactory(cat, preallocated = provider),
                    ProviderIdCapture(store, this),
                    importProbe, importLocator, importKinds,
                    newSessionId = { SessionId("itg01") },
                )

                val started = mgr.start("claude", "/tmp")
                val pane = started.paneId!!
                assertTrue(Regex("^%\\d+$").matches(pane.value), "captured a real %<n> pane id, was ${pane.value}")
                assertEquals(SessionId("itg01"), registry.lookup(pane), "start registered the real pane->session")
                assertEquals(provider, store.projectionOf(SessionId("itg01")).providerSessionId, "SessionBound captured")
                assertTrue(realTmux.listPanes().any { it.session == "kt-itg01" }, "a real tmux session exists")

                val freshRegistry = PaneRegistry()
                val reconciler = Reconciler(realTmux, store, VendorStoreProbe { _, _, _ -> false }, freshRegistry)
                val result = reconciler.reconcile()

                assertEquals(SessionState.running, store.getSession(SessionId("itg01"))!!.state, "the live session is reclassified running")
                assertEquals(SessionId("itg01"), freshRegistry.lookup(pane), "the registry is rebuilt from the live pane")
                assertEquals(mapOf(pane to SessionId("itg01")), result.livePanes)
            }
        } finally {
            val _ = realTmux.killSession("itg01")
            val _ = ProcessRunner.run(listOf(realTmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
        }
    }
}
