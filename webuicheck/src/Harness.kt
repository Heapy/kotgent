package io.kotgent.webuicheck

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.CLAUDE_AGENT_KIND
import io.kotgent.daemon.CODEX_AGENT_KIND
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
import io.kotgent.daemon.FakeTmux
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

/**
 * The five doubles one harness run stands on, handed to a [Scenario] so it can seed them.
 *
 * They are constructed together by [newHarnessFakes] rather than one at a time, because two of them
 * are a PAIR: [MemoryProjectFileWriter] publishes its `.kotgent.json` into [projectFs], which is what
 * makes a second `POST /projects` in one directory adopt the first one's uuid instead of minting a
 * second. Building them apart silently breaks that convergence.
 */
class HarnessFakes(
    val tmux: FakeTmux,
    val events: FakeEventStore,
    val tasks: FakeTaskStore,
    val projectFs: FakeProjectFs,
    val projectFiles: MemoryProjectFileWriter,
)

/**
 * One named starting state for the browser: what the doubles hold before the server binds, plus — for
 * the terminal scenarios — the argv the ONE upstream pty should run.
 *
 * ## Why a scenario only DECLARES its upstream
 * The plan's prose has the scenario build the [TerminalBridge] itself, which would give
 * `KotgentServer`'s `terminalBridgeFactory` two owners (the scenario and the harness that assembles
 * the server). The seam is inverted instead: a scenario states its argv here and [Harness] builds the
 * bridge over [realPtyFactory], so the factory has exactly one author and every scenario gets the same
 * lazy open/close semantics for free.
 *
 * @param name the value `--scenario=` selects; the registry of names lives in `Scenarios.kt`.
 * @param seed fills the doubles. Runs BEFORE the server binds, so the browser's very first paint
 *   already sees the finished state — there is no window in which a scenario is half-applied.
 * @param terminalUpstream argv for the upstream pty, or `null` for [DEFAULT_TERMINAL_UPSTREAM]. Keep it
 *   deterministic byte-for-byte (`/bin/sh -c 'printf …; cat'`): a browser assertion reads these bytes.
 */
class Scenario(
    val name: String,
    val seed: suspend (HarnessFakes) -> Unit,
    val terminalUpstream: List<String>? = null,
)

/**
 * What a stdin command is handed: the seeded doubles (so `emit`/`task` can write through them), the
 * bound port, the harness's ONE [TaskService], plus the single lifecycle operation a command may
 * perform.
 *
 * [taskService] is passed rather than rebuilt per command because the class is the sequencing rule
 * between two stores and both of those are the long-lived fakes — so a second instance is only a second
 * CLOCK. It used to be exactly that: the harness's instance ran on the doubles' clock while
 * `TaskCommands.kt` pinned its own to a fixture constant, and the same `transition` then wrote a
 * different `sessions.updated_at` depending on whether the browser or a stdin command asked for it.
 */
class HarnessContext(
    val fakes: HarnessFakes,
    val port: Int,
    val taskService: TaskService,
    private val onRestart: suspend () -> Unit,
) {
    /**
     * Stop the server and bring it back up on the **same** port, with the same [TokenHolder],
     * [TicketStore], doubles and [TaskService] — a daemon restart as the browser experiences one, with
     * every cookie still valid. Prints one further `READY` on stdout when the new listener is bound.
     */
    suspend fun restart() {
        onRestart()
    }
}

/**
 * The harness proper: the real [KotgentServer] over the five doubles, the safe edges, a real
 * [TaskService], and a [TerminalBridge] factory backed by a real pty.
 *
 * ## Why `taskStore` and `taskService` are not optional here
 * `KotgentServer` mounts the task routes only when BOTH are non-null, and `app.js` fetches
 * `GET /api/v1/projects` on every mount regardless of which screen is showing. Leave them out and every
 * scenario — not just the board ones — starts with a red status line in the sidebar, which would make
 * "the sidebar is quiet" unassertable anywhere.
 *
 * ## Threading
 * [KotgentServer.start] and [KotgentServer.stop] both run a nested `runBlocking` internally, so every
 * call to them here is wrapped in `withContext(Dispatchers.Default)`: a nested `runBlocking` on a
 * single-threaded event loop (which is what a plain `runBlocking { }` on the main thread gives) is the
 * one shape that can deadlock.
 */
class Harness(
    private val scenario: Scenario,
    private val webUiDir: String?,
) {
    val fakes: HarnessFakes = newHarnessFakes()

    /** The machine key. Minted in memory and never written to `~/.kotgent/token`. */
    private val tokens = TokenHolder(generateToken())

    /** Outstanding login codes. Survives [restart] — a restart must not invalidate a browser. */
    private val tickets = TicketStore()

    /**
     * The in-memory upload sink. Not inspectable and not meant to be: the browser tier is another
     * process, so what an upload test reads is the route's own answer (a size, or the `409` a repeated
     * name produces) — see [MemoryFileUploader].
     */
    private val uploads: MemoryFileUploader = MemoryFileUploader()

    /**
     * The provider-id capture jobs' scope, and the scope `--self-check` drives a bridge on.
     *
     * Deliberately NOT the Ktor application scope, because [ProviderIdCapture]'s polling must survive a
     * [restart] — it is the daemon-lifetime job, not a per-listener one. The terminal bridges do NOT
     * live here: [terminalBridgeFactory] uses the scope its CALLER passes, and in production that
     * caller is `KotgentServer`, so a bridge's reader loop is torn down with the application that owns
     * it. That is the right shape for a restart (the browser reconnects and re-attaches, which reopens
     * the upstream lazily) and it is why this scope is cancelled only in [stop].
     */
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
        // Never spawns anything: FakeTmux records the argv and hands back a synthetic pane id.
        agentFactory = AgentFactory { _, cwd ->
            object : AgentAdapter {
                override val events: Flow<AgentEvent> = emptyFlow()
                override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                    LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
            }
        },
        idCapture = ProviderIdCapture(fakes.events, background),
        // The browser's Import dialog is a first-class screen, so the probe ANSWERS instead of
        // refusing: an import in a scenario registers a resumable row, which is the state the UI is
        // there to show. Nothing on disk is consulted to say so.
        vendorProbe = VendorStoreProbe { _, _, _ -> true },
        // No cwd discovery: an import without `--cwd` is refused, exactly as it is for a provider with
        // no registered locator. A fabricated path here would be a lie the browser could not tell from
        // a real discovery.
        sessionLocator = VendorSessionLocator { _, _ -> null },
        supportedAgentKinds = setOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND, JUNIE_AGENT_KIND),
        taskStore = fakes.tasks,
        projectFs = fakes.projectFs,
    )

    /**
     * The very lambda handed to [KotgentServer]. Exposed so `--self-check` can drive the real pty
     * through the same factory the server uses, rather than a look-alike built beside it.
     *
     * The session [id] is deliberately ignored: every session in a scenario shows the same
     * deterministic upstream, because what a browser test asserts is the terminal's behaviour, not
     * which pane it came from.
     */
    val terminalBridgeFactory: (String, CoroutineScope) -> TerminalBridge = { _, scope ->
        TerminalBridge(
            upstreamCommand = scenario.terminalUpstream ?: DEFAULT_TERMINAL_UPSTREAM,
            // No `capture-pane` here, so a joiner is seeded with nothing at all: the shortest seed is
            // also the only byte-exact one, and the upstream's own output is what the tests read.
            seedProvider = { EMPTY_SEED },
            ptyFactory = realPtyFactory,
            scope = scope,
            env = terminalAttachEnv(),
        )
    }

    private var server: KotgentServer? = null
    private var boundPort: Int = 0

    /**
     * Seed the doubles, bind the server on an ephemeral port and return the context stdin commands are
     * dispatched with. The caller prints the handshake — [start] writes nothing to stdout itself, so a
     * self-check can use the same path without polluting its `SUMMARY`.
     */
    suspend fun start(): HarnessContext {
        scenario.seed(fakes)
        val started = withContext(Dispatchers.Default) { buildServer(port = 0).start() }
        server = started
        boundPort = started.port()
        return HarnessContext(fakes, boundPort, taskService) { restart() }
    }

    /** A fresh one-shot login code, minted by the real [TicketStore] against the live master token. */
    suspend fun issueTicket(): String = tickets.issue(tokens.current()).value

    /**
     * Stop and re-listen on the SAME port. Everything a browser holds survives: the master token (so
     * every `kotgent_session` cookie's HMAC still verifies), the outstanding tickets, all five doubles
     * and the [TaskService] over them. Re-binding the same port works because `Server.kt` already sets
     * `reuseAddress = true`.
     *
     * The scenario is NOT re-seeded — the doubles are the store, and a store survives a daemon restart.
     */
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

    /** Graceful shutdown: the engine and its terminal bridges first, then the harness's own scope. */
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
        // Both, always — see the class KDoc. Without them `/api/v1/projects` 404s in EVERY scenario.
        taskStore = fakes.tasks,
        taskService = taskService,
        port = port,
    )
}

/**
 * The upstream a scenario that declared none gets: a bare `cat` on a real pty.
 *
 * **Nothing reaches it today, and that is a property of the scenarios rather than of this code.** The
 * only scenarios leaving `terminalUpstream` null are `empty` (no session at all, so the terminal WS
 * refuses before a bridge is ever built) and the five board scenarios (every session seeded
 * `resumable`, so `app.js` never opens a terminal socket for one). It stays because the factory is
 * total and the alternative is a `null` the WS route would have to throw on: a scenario that grows its
 * first live session gets a quiet echoing terminal instead of a 500 in a screen it was not testing.
 */
val DEFAULT_TERMINAL_UPSTREAM: List<String> = listOf("/bin/cat")

/** The per-subscriber seed: nothing. Shared so no scenario allocates a second empty array per attach. */
private val EMPTY_SEED = ByteArray(0)

/** The third and final handshake line, repeated by [HarnessContext.restart]. */
const val READY_LINE: String = "READY"
