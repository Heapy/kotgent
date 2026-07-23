package io.kotgent.cli

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.kotgent.adapter.claude.ClaudeAdapter
import io.kotgent.adapter.claude.ClaudeCli
import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.Reconciler
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.claudeVendorStoreProbe
import io.kotgent.db.KotgentDatabase
import io.kotgent.exe.NativeExe
import io.kotgent.launchd.LaunchdInstaller
import io.kotgent.store.SqliteEventStore
import io.kotgent.tmux.Tmux
import io.kotgent.transport.KotgentServer
import io.kotgent.transport.SessionDto
import io.kotgent.transport.readOrCreateToken
import io.kotgent.transport.readTokenOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.mkdir

/**
 * The `kotgent` subcommand handlers (plan Task 15). The network verbs (`list`/`start`/`stop`/`resume`/
 * `interrupt`) go through [ApiClient] against the running daemon and print human output; `daemon` wires
 * and runs the real [KotgentServer]; `attach` runs the interactive raw passthrough; `install`/
 * `uninstall` are Task-16 stubs.
 *
 * Each handler returns a process exit code (0 = success). Network errors (daemon down, missing token,
 * non-2xx) are turned into a one-line stderr message + a non-zero code, never a stack trace.
 */
object Commands {

    // --- network verbs (via the daemon's control REST) -------------------------------------------

    fun list(): Int = withApi { api ->
        val sessions = api.listSessions()
        print(renderSessions(sessions))
        0
    }

    fun start(agent: String, cwd: String, name: String?, tags: List<String>): Int = withApi { api ->
        val s = api.startSession(agent, cwd, name, tags)
        println("started ${s.id}  (${s.agent})  ${s.state}  cwd=${s.cwd}  tmux=${s.tmuxSession}")
        0
    }

    fun stop(id: String): Int = withApi { api -> report("stopped", id, api.stop(id)) }
    fun resume(id: String): Int = withApi { api -> report("resumed", id, api.resume(id)) }
    fun interrupt(id: String): Int = withApi { api -> report("interrupted", id, api.interrupt(id)) }

    private fun report(verb: String, id: String, updated: SessionDto?): Int {
        println(if (updated != null) "$verb ${updated.id} → ${updated.state}" else "$verb $id")
        return 0
    }

    // --- interactive attach ----------------------------------------------------------------------

    /** `attach <id>` — raw terminal passthrough over the terminal WS. INTERACTIVE (see [AttachClient]). */
    fun attach(id: String): Int = runBlocking {
        val token = readTokenOrNull() ?: run {
            eprintln("no kotgent token found — is the daemon running? start it with: kotgent daemon")
            return@runBlocking 1
        }
        try {
            AttachClient(defaultBaseUrl(), id, token).run()
            0
        } catch (e: Throwable) {
            eprintln("attach failed: ${e.message}")
            1
        }
    }

    // --- launchd (Task 16) -----------------------------------------------------------------------

    /**
     * `install` — install (and start) the daemon as a per-user launchd LaunchAgent. Resolves the
     * running binary's absolute path (so the plist's `ProgramArguments` points at THIS binary), writes
     * `~/Library/LaunchAgents/io.kotgent.daemon.plist`, and bootstraps it via the real [ProcessRunner].
     * Returns after bootstrapping — it does NOT run the daemon in-process (launchd starts `kotgent
     * daemon` per the plist's `RunAtLoad`).
     */
    fun install(): Int {
        val binaryPath = NativeExe.path() ?: run {
            eprintln("install: cannot resolve the kotgent binary path")
            return 1
        }
        return try {
            val plistPath = LaunchdInstaller().install(binaryPath)
            println("installed launchd agent → $plistPath")
            println("  runs: $binaryPath daemon   (RunAtLoad + KeepAlive)")
            0
        } catch (e: Throwable) {
            eprintln("install failed: ${e.message}")
            1
        }
    }

    /** `uninstall` — boot out the LaunchAgent and remove its plist. Idempotent. */
    fun uninstall(): Int = try {
        val installer = LaunchdInstaller()
        installer.uninstall()
        println("uninstalled launchd agent (${installer.plistPath})")
        0
    } catch (e: Throwable) {
        eprintln("uninstall failed: ${e.message}")
        1
    }

    // --- daemon (the real control-plane server) --------------------------------------------------

    /**
     * `daemon [--port N]` — assemble and run the production [KotgentServer]. This is the launchd entry
     * point (Task 16 will `ProgramArguments = [<binary>, daemon]`). It NEVER returns: after wiring the
     * store / tmux / session manager and reconciling on start, it starts the server and parks forever.
     *
     * ⚠️ Never invoke this from a test or a shell — the awaiting server blocks by design.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun daemon(port: Int): Int = runBlocking {
        val token = readOrCreateToken()
        ensureDir(kotgentHome())

        // File-backed store (restart-safety = the whole point of the control plane).
        val store = SqliteEventStore.using(
            NativeSqliteDriver(
                schema = KotgentDatabase.Schema,
                name = DB_FILENAME,
                onConfiguration = { config ->
                    config.copy(
                        extendedConfig = config.extendedConfig.copy(basePath = kotgentHome()),
                    )
                },
            ),
        )
        val tmux = Tmux(TMUX_SOCKET)
        tmux.ensureServer()

        val registry = PaneRegistry()
        val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val idCapture = ProviderIdCapture(store, bgScope)

        // Generate + persist the Claude hook settings, then a factory that builds a ClaudeAdapter per
        // session. adapter.events is emptyFlow(): the hook ingress appends straight into the store
        // (the source of truth), so the adapter does not need to re-surface events (Task 12 decision).
        val settingsPath = writeClaudeHookSettings(port, token)
        val claudeCli = ClaudeCli()
        val sessionIdSupported = claudeCli.supportsSessionId()
        // Resolve claude to an absolute path (like tmux, which is already absolute) so the tmux launch
        // does not depend on the child shell's PATH under launchd's minimal env. Falls back to the bare
        // name (found via the child's PATH) if it cannot be located.
        val claudePath = claudeCli.locate() ?: "claude"
        val agentFactory = AgentFactory { _, cwd ->
            ClaudeAdapter(
                cwd = cwd,
                settingsPath = settingsPath,
                events = emptyFlow(),
                sessionIdSupported = sessionIdSupported,
                binaryName = claudePath,
            )
        }
        val manager = SessionManager(tmux, store, registry, agentFactory, idCapture)

        // Restart-safe reconciliation: reclassify persisted sessions against tmux reality and rebuild
        // the pane→session registry from live panes (Task 13). Terminal bridges stay lazy (Task 9).
        manager.rebuildRegistryFromStore()
        Reconciler(tmux, store, vendorProbe, registry).reconcile()

        KotgentServer.production(manager, store, token, tmux, port = port).start()
        println("kotgent daemon listening on http://127.0.0.1:$port  (tmux -L $TMUX_SOCKET)")
        awaitCancellation()
    }

    /** The daemon's SQLite database file name (kept next to the token under `~/.kotgent`). */
    private const val DB_FILENAME: String = "kotgent.db"

    /**
     * The reconciler's vendor-store transcript probe (Task 18): the real Claude probe stats
     * `~/.claude/projects/<encoded-cwd>/<provider-session-id>.jsonl` for each dead session, so a session
     * whose transcript survives on disk classifies as `resumable` (revivable via `claude --resume`)
     * rather than a dead-end `crashed`. Roots at the real `~/.claude` ([defaultClaudeDir]); host-free by
     * injection (see [claudeVendorStoreProbe]). Replaces the Task-15 `{ false }` stub.
     */
    private val vendorProbe: VendorStoreProbe = claudeVendorStoreProbe()

    // --- helpers ---------------------------------------------------------------------------------

    private fun withApi(block: suspend (ApiClient) -> Int): Int = runBlocking {
        try {
            ApiClient().use { block(it) }
        } catch (e: MissingTokenException) {
            eprintln(e.message ?: "missing token")
            1
        } catch (e: ApiException) {
            eprintln(e.message ?: "daemon error")
            1
        } catch (e: Throwable) {
            eprintln("cannot reach kotgent daemon at ${defaultBaseUrl()}: ${e.message}")
            1
        }
    }

    private fun writeClaudeHookSettings(port: Int, token: String): String {
        val path = "${kotgentHome()}/claude-hooks.json"
        // Written 0600 atomically (it carries the hook token) — never a brief 0644 window.
        writePrivateFile(path, ClaudeHookConfig.generate(port, token).encodeToByteArray())
        return path
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ensureDir(path: String) {
        mkdir(path, (S_IRUSR or S_IWUSR or S_IXUSR).convert()) // 0700; ignore EEXIST
    }
}

/**
 * Render sessions as a compact human table (the `list` output). Pure so it is unit-testable without a
 * daemon; a `needs_approval`/attention state is flagged so the queue is obvious at a glance.
 */
fun renderSessions(sessions: List<SessionDto>): String {
    if (sessions.isEmpty()) return "no sessions\n"
    val sb = StringBuilder()
    sb.append("ID        AGENT      STATE            ATTN  CWD\n")
    for (s in sessions.sortedByDescending { it.updatedAt }) {
        val attn = if (s.needsAttention) " *  " else "    "
        sb.append(s.id.padEnd(10).take(10))
        sb.append(s.agent.padEnd(11).take(11))
        sb.append(s.state.padEnd(17).take(17))
        sb.append(attn)
        sb.append("  ").append(s.cwd)
        sb.append('\n')
    }
    return sb.toString()
}
