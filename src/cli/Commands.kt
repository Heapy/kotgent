package io.kotgent.cli

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.kotgent.adapter.claude.ClaudeAdapter
import io.kotgent.adapter.claude.ClaudeCli
import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.codex.CodexAdapter
import io.kotgent.adapter.codex.CodexCli
import io.kotgent.adapter.codex.CodexHookConfig
import io.kotgent.daemon.CLAUDE_AGENT_KIND
import io.kotgent.daemon.CODEX_AGENT_KIND
import io.kotgent.daemon.CodexRolloutScan
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.Reconciler
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.daemon.byAgentVendorStoreProbe
import io.kotgent.daemon.claudeVendorStoreProbe
import io.kotgent.daemon.codexVendorStoreProbe
import io.kotgent.db.KotgentDatabase
import io.kotgent.exe.NativeExe
import io.kotgent.launchd.LaunchdInstaller
import io.kotgent.store.SqliteEventStore
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.Tmux
import io.kotgent.transport.KotgentServer
import io.kotgent.transport.ServerBindException
import io.kotgent.transport.SessionDto
import io.kotgent.transport.TokenHolder
import io.kotgent.transport.defaultTokenPath
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
        ensureDir(kotgentHome())
        // The public origin is load-bearing for authorization (it IS the extra entry in the Host/Origin
        // allowlists), so a config that cannot be understood stops the daemon instead of silently starting
        // one that refuses every request from the tunnel it was configured for.
        val config = try {
            readConfig()
        } catch (e: ConfigException) {
            eprintln("kotgent daemon: ${e.message}")
            return@runBlocking 1
        }
        // The master token lives behind a holder, not in a captured `val`: every gate (both hook
        // ingresses, the Bearer check) resolves it per request, so `kotgent token rotate` takes effect on
        // the next request instead of the next restart. Rotation must also reach the two consumers that
        // read the secret from DISK — the CLI (`~/.kotgent/token`) and the hooks (their 0600 header
        // files) — hence the persist callback rewrites all three (writeClaudeHookSettings /
        // writeCodexHookScript rewrite their header file as their first step).
        val tokenHolder = TokenHolder(readOrCreateToken()) { rotated ->
            writePrivateFile(defaultTokenPath(), rotated.encodeToByteArray())
            writeClaudeHookSettings(port, rotated)
            writeCodexHookScript(port, rotated)
        }
        val token = tokenHolder.current()

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

        // Generate + persist each provider's hook wiring, then a factory that builds the right adapter
        // per session. adapter.events is emptyFlow(): the hook ingress appends straight into the store
        // (the source of truth), so the adapter does not need to re-surface events (Task 12 decision).
        val settingsPath = writeClaudeHookSettings(port, token)
        val codexHookScriptPath = writeCodexHookScript(port, token)
        val claudeCli = ClaudeCli()
        val sessionIdSupported = claudeCli.supportsSessionId()
        // Resolve each CLI to an absolute path (like tmux, which is already absolute) so the tmux launch
        // does not depend on the child shell's PATH under launchd's minimal env. Falls back to the bare
        // name (found via the child's PATH) if it cannot be located.
        val claudePath = claudeCli.locate() ?: CLAUDE_AGENT_KIND
        val codexPath = CodexCli().locate() ?: CODEX_AGENT_KIND
        // Only the kinds registered here are accepted: an unknown kind is rejected with a clear error
        // instead of silently building some other provider's adapter for it (which would launch the wrong
        // agent while persisting the requested name).
        val agentFactory = agentFactoryOf(
            mapOf(
                CLAUDE_AGENT_KIND to { cwd: String ->
                    ClaudeAdapter(
                        cwd = cwd,
                        settingsPath = settingsPath,
                        events = emptyFlow(),
                        sessionIdSupported = sessionIdSupported,
                        binaryName = claudePath,
                    )
                },
                CODEX_AGENT_KIND to { cwd: String ->
                    CodexAdapter(
                        cwd = cwd,
                        hookScriptPath = codexHookScriptPath,
                        events = emptyFlow(),
                        binaryName = codexPath,
                    )
                },
            ),
        )
        // Codex has no `--session-id`, so a fresh codex session's provider id is unknown at launch. The
        // rollout scan is the discovery path that does not depend on hook delivery: it finds the rollout
        // Codex wrote for this cwd after the launch began and reads the id out of its file name. Claude
        // preallocates and needs none of this, so the scan is scoped to codex sessions.
        val rolloutScan = CodexRolloutScan()
        val manager = SessionManager(
            tmux,
            store,
            registry,
            agentFactory,
            idCapture,
            discoverProviderId = { meta ->
                if (meta.agent == CODEX_AGENT_KIND) rolloutScan.discoverSessionId(meta.cwd, meta.createdAt) else null
            },
        )

        // Restart-safe reconciliation: reclassify persisted sessions against tmux reality and rebuild
        // the pane→session registry from live panes (Task 13). Terminal bridges stay lazy (Task 9).
        manager.rebuildRegistryFromStore()
        Reconciler(tmux, store, vendorProbe, registry).reconcile()

        try {
            KotgentServer.production(
                manager,
                store,
                tokenHolder,
                tmux,
                publicUrl = config.publicUrl,
                port = port,
            ).start()
        } catch (e: ServerBindException) {
            eprintln("kotgent daemon: ${e.message}")
            reportPortHolder(port)
            return@runBlocking 1
        }
        println("kotgent daemon listening on http://127.0.0.1:$port  (tmux -L $TMUX_SOCKET)")
        config.publicUrl?.let { println("  also reachable at $it  (Host + Origin allowlisted)") }
        awaitCancellation()
    }

    /**
     * Print who is holding [port] after a failed bind. Usually the answer is an **orphaned `tmux`
     * server**: a `tmux` spawned by an earlier daemon inherited its listening socket and, having
     * daemonized, keeps the port bound after that daemon is gone (see [io.kotgent.sys.markOpenFdsCloexec] —
     * newly spawned tmux servers no longer inherit it, but one started before that fix, or by an older
     * binary, still holds it until killed). Naming the PID turns an opaque `EADDRINUSE` into an
     * actionable message. Best-effort: `lsof` may be absent or blocked, in which case only the hint is
     * printed.
     */
    private fun reportPortHolder(port: Int) {
        val holders = runCatching {
            ProcessRunner.run(listOf("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN"))
        }.getOrNull()?.takeIf { it.isSuccess }?.stdout?.trim().orEmpty()
        if (holders.isEmpty()) {
            eprintln("  nothing reported by: lsof -nP -iTCP:$port -sTCP:LISTEN")
            return
        }
        eprintln("  port $port is held by:")
        holders.lineSequence().forEach { eprintln("    $it") }
        eprintln("  if that is a tmux server, it inherited the socket from a previous daemon;")
        eprintln("  killing it also kills the agents running in it — detach or finish them first.")
    }

    /** The daemon's SQLite database file name (kept next to the token under `~/.kotgent`). */
    private const val DB_FILENAME: String = "kotgent.db"

    /**
     * The reconciler's vendor-store transcript probe (Task 18), dispatched per provider: for a dead
     * session it asks whether the conversation still exists, so it classifies as `resumable` rather than
     * a dead-end `crashed`. Claude stats `~/.claude/projects/<encoded-cwd>/<id>.jsonl`; Codex looks for
     * `~/.codex/sessions/<date>/rollout-<ts>-<id>.jsonl`. Both root at the real user directories and are
     * host-free by injection (see [claudeVendorStoreProbe] / [codexVendorStoreProbe]).
     */
    private val vendorProbe: VendorStoreProbe = byAgentVendorStoreProbe(
        mapOf(
            CLAUDE_AGENT_KIND to claudeVendorStoreProbe(),
            CODEX_AGENT_KIND to codexVendorStoreProbe(),
        ),
    )

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
        // The secret token goes into a SEPARATE 0600 header file that the hook reads via `curl -H @<file>`,
        // so it never appears on a hook command line (visible to other local users via `ps`). Both the
        // header file and the settings are written 0600 atomically — never a brief 0644 window.
        val headerPath = "${kotgentHome()}/claude-hook-header"
        writePrivateFile(headerPath, ClaudeHookConfig.headerFileContent(token).encodeToByteArray())
        val path = "${kotgentHome()}/claude-hooks.json"
        writePrivateFile(path, ClaudeHookConfig.generate(port, headerPath).encodeToByteArray())
        return path
    }

    /**
     * Write the Codex hook script and its header file, returning the script's path (what
     * [CodexAdapter] renders into the launch argv).
     *
     * Same secret discipline as the Claude settings: the token goes into a SEPARATE `0600` header file
     * the script reads via `curl -H @<file>`, never into an argv. The script itself is `0600` too — the
     * hook command names `/bin/sh` explicitly, so it needs no execute bit (see [CodexHookConfig.hooksToml]).
     *
     * The header file is per-provider rather than shared: the two ingress routes validate the same token
     * today, but a provider-scoped file keeps them independently rotatable and makes it obvious which
     * hook reads which file.
     */
    private fun writeCodexHookScript(port: Int, token: String): String {
        val headerPath = "${kotgentHome()}/codex-hook-header"
        writePrivateFile(headerPath, CodexHookConfig.headerFileContent(token).encodeToByteArray())
        val path = "${kotgentHome()}/codex-hook.sh"
        writePrivateFile(path, CodexHookConfig.hookScript(port, headerPath).encodeToByteArray())
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
