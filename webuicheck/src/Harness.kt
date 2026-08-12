package io.kotgent.webuicheck

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.CLAUDE_AGENT_KIND
import io.kotgent.daemon.CODEX_AGENT_KIND
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.JUNIE_AGENT_KIND
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.pty.TerminalBridge
import io.kotgent.pty.realPtyFactory
import io.kotgent.pty.terminalAttachEnv
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakeTaskStore
import io.kotgent.task.FakeProjectFs
import io.kotgent.task.MemoryProjectFileWriter
import io.kotgent.transport.KotgentServer
import io.kotgent.transport.TicketStore
import io.kotgent.transport.TokenHolder
import io.kotgent.transport.generateToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/** `projectFiles` must publish into this same `projectFs` so concurrent creates converge. */
class HarnessFakes(
    val tmux: FakeTmux,
    val events: FakeEventStore,
    val tasks: FakeTaskStore,
    val projectFs: FakeProjectFs,
    val projectFiles: MemoryProjectFileWriter,
)

/** Seed runs before bind; terminal argv is declared here so Harness remains the bridge's only owner. */
class Scenario(
    val name: String,
    val seed: suspend (HarnessFakes) -> Unit,
    val terminalUpstream: List<String>? = null,
)

class HarnessContext(
    val fakes: HarnessFakes,
    val port: Int,
    val taskService: TaskService,
    private val onRestart: suspend () -> Unit,
) {
    suspend fun restart() {
        onRestart()
    }
}

class Harness(
    private val scenario: Scenario,
    private val webUiDir: String?,
) {
    val fakes: HarnessFakes = newHarnessFakes()

    private val tokens = TokenHolder(generateToken())

    private val tickets = TicketStore()

    private val uploads: MemoryFileUploader = MemoryFileUploader()

    // Daemon-lifetime work survives listener restarts; terminal bridges use the server-provided scope.
    val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val taskService = TaskService(
        tasks = fakes.tasks,
        sessions = fakes.events,
        projectFs = fakes.projectFs,
        projectFiles = fakes.projectFiles,
    )

    private val sessionManager = SessionManager(
        tmux = fakes.tmux,
        store = fakes.events,
        registry = PaneRegistry(),
        agentFactory = AgentFactory { _, cwd ->
            object : AgentAdapter {
                override val events: Flow<AgentEvent> = emptyFlow()
                override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                    LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
            }
        },
        idCapture = ProviderIdCapture(fakes.events, background),
        vendorProbe = VendorStoreProbe { _, _, _ -> true },
        sessionLocator = VendorSessionLocator { _, _ -> null },
        supportedAgentKinds = setOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND, JUNIE_AGENT_KIND),
        taskStore = fakes.tasks,
        projectFs = fakes.projectFs,
    )

    // Exposed for self-check so it exercises the same real-pty factory as the server.
    val terminalBridgeFactory: (String, CoroutineScope) -> TerminalBridge = { _, scope ->
        TerminalBridge(
            upstreamCommand = scenario.terminalUpstream ?: DEFAULT_TERMINAL_UPSTREAM,
            seedProvider = { EMPTY_SEED },
            ptyFactory = realPtyFactory,
            scope = scope,
            env = terminalAttachEnv(),
        )
    }

    private var server: KotgentServer? = null
    private var boundPort: Int = 0

    suspend fun start(): HarnessContext {
        scenario.seed(fakes)
        // Server start/stop nest runBlocking internally; move them off the caller's event loop.
        val started = withContext(Dispatchers.Default) { buildServer(port = 0).start() }
        server = started
        boundPort = started.port()
        return HarnessContext(fakes, boundPort, taskService) { restart() }
    }

    suspend fun issueTicket(): String = tickets.issue(tokens.current()).value

    // Rebind the same port without reseeding; tokens, tickets, stores, and cookies survive a daemon restart.
    private suspend fun restart() {
        val current = server
        withContext(Dispatchers.Default) {
            current?.stop()
            val next = buildServer(port = boundPort)
            next.start()
            server = next
        }
        writeStdoutLine(READY_LINE)
    }

    suspend fun stop() {
        val current = server
        server = null
        withContext(Dispatchers.Default) { current?.stop() }
        background.cancel()
    }

    private fun buildServer(port: Int): KotgentServer = KotgentServer(
        sessionManager = sessionManager,
        store = fakes.events,
        preferencesStore = fakes.events,
        tokens = tokens,
        terminalBridgeFactory = terminalBridgeFactory,
        directoryCompleter = harnessDirectoryCompleter(fakes.projectFs),
        fileUploader = uploads,
        webUiDir = webUiDir,
        tickets = tickets,
        taskStore = fakes.tasks,
        taskService = taskService,
        port = port,
    )
}

val DEFAULT_TERMINAL_UPSTREAM: List<String> = listOf("/bin/cat")

private val EMPTY_SEED = ByteArray(0)

const val READY_LINE: String = "READY"

/** Prefix for commands whose browser-visible effects have no event-frame synchronization. */
const val COMMAND_ACK_PREFIX: String = "OK "
