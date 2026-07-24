package io.kotgent.cli

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.kotgent.currentUiVersion
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
import io.kotgent.daemon.requireAbsoluteBinary
import io.kotgent.daemon.byAgentVendorStoreProbe
import io.kotgent.daemon.claudeVendorStoreProbe
import io.kotgent.daemon.codexVendorStoreProbe
import io.kotgent.daemon.daemonEpochMillis
import io.kotgent.db.KotgentDatabase
import io.kotgent.exe.NativeExe
import io.kotgent.launchd.DAEMON_LABEL
import io.kotgent.launchd.LaunchdInstaller
import io.kotgent.push.DarwinPushTransport
import io.kotgent.push.OpensslVapidSigner
import io.kotgent.push.PushNotifier
import io.kotgent.push.PushSender
import io.kotgent.push.PushStore
import io.kotgent.push.SqlitePushStore
import io.kotgent.push.VapidKey
import io.kotgent.push.VapidTokenCache
import io.kotgent.push.vapidSubject
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import io.kotgent.sys.installShutdownSignals
import io.kotgent.sys.pendingShutdownSignal
import io.kotgent.sys.shutdownSignalName
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.Tmux
import io.kotgent.transport.KotgentServer
import io.kotgent.transport.ServerBindException
import io.kotgent.transport.SessionDto
import io.kotgent.transport.TICKET_CODE_LENGTH
import io.kotgent.transport.TICKET_TTL_MILLIS
import io.kotgent.transport.TicketResponse
import io.kotgent.transport.TokenHolder
import io.kotgent.transport.defaultTokenPath
import io.kotgent.transport.readOrCreateToken
import io.kotgent.transport.readTokenOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

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

    // --- web / token / config (Task 10) ----------------------------------------------------------

    /**
     * `web [--print]` — mint a one-shot login ticket from the daemon and open the local sign-in form in the
     * default browser (`open <formUrl>` via [ProcessRunner], whose cloexec sweep keeps `open` from inheriting
     * any daemon descriptor). The form has no credential in its URL: the code printed below it stays unused,
     * so it can be typed into that browser or an installed home-screen app.
     *
     * `--print` deliberately prints the credentialed URL instead of opening it (a headless host, or pasting
     * the one-shot link somewhere by hand). If `open` cannot launch a browser, the credential-free form URL
     * is printed as a fallback so the code remains the one thing to type.
     *
     * The same ticket is ALSO printed as a typable code ([renderSignInCode]), because a link cannot reach an
     * installed home-screen app: it launches at its own `start_url` with its own cookie jar, so the code
     * typed into `/auth` is the only way to sign that app in. Under `--print` the code goes to stderr so
     * stdout stays exactly the URL and `kotgent web --print | pbcopy` keeps working; the operator still sees
     * it on the terminal.
     */
    fun web(print: Boolean): Int = withApi { api ->
        runWebCommand(
            print = print,
            issueTicket = api::issueTicket,
            open = { url -> ProcessRunner.run(listOf("open", url)).exitCode },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /**
     * `token rotate` — ask the daemon to re-mint the master token and print the new value. By the time this
     * returns the daemon has already rewritten `~/.kotgent/token` and both hook-header files, so live
     * sessions keep delivering events; what changes is that the OLD key no longer authenticates NEW requests
     * or NEW WebSocket handshakes. Sockets already open (an events stream, a terminal, a live `kotgent
     * attach`) survive until they reconnect — authorization is evaluated once, at handshake time. Rotation is
     * "revoke all browser credentials": every session cookie is signed with the master token, so all of them
     * stop verifying at once, and an outstanding, unredeemed sign-in link is bound to the OLD token, so any
     * cookie it could still exchange for is dead on arrival. The warning states that plainly rather than
     * implying an instant, total cut-off.
     */
    fun tokenRotate(): Int = withApi { api ->
        val token = api.rotateToken()
        println(token)
        eprintln("rotated the kotgent master token.")
        eprintln("  new requests and new connections with the old key are now rejected;")
        eprintln("  sockets already open (events stream, terminals, a live `kotgent attach`) keep working")
        eprintln("  until they reconnect. all browser session cookies are now invalid, and any outstanding")
        eprintln("  sign-in links (from `kotgent web`) can no longer sign you in — sign in again with:")
        eprintln("    kotgent web")
        0
    }

    /** `config get` — print the persisted config (currently just the public URL, or a clear "not set"). */
    fun configGet(): Int = try {
        println("public-url = ${readConfig().publicUrl ?: "(not set)"}")
        0
    } catch (e: ConfigException) {
        eprintln("config get: ${e.message}")
        1
    }

    /**
     * `config set <key> <value>` — persist a config value. Only `public-url` is understood today; the value
     * is validated ([publicUrlProblem], via [writeConfig]) BEFORE anything is written, so a bad URL is
     * rejected without disturbing the existing config. The value is read once at daemon startup, so a
     * running daemon needs a restart to pick it up — the hint spells that out.
     */
    fun configSet(key: String, value: String): Int {
        if (key != "public-url") {
            eprintln("config set: unknown key '$key' (only 'public-url' is supported)")
            return 2
        }
        val path = defaultConfigPath()
        // Reading the existing config can fail if the file on disk is unparseable — that is a runtime error,
        // not something the user typed wrong, so it exits 1 (the same code `config get` returns for the same
        // corrupt file), NOT the usage code 2.
        val existing = try {
            readConfig(path)
        } catch (e: ConfigException) {
            eprintln("config set: ${e.message}")
            return 1
        }
        val updated = existing.copy(publicUrl = value)
        // Writing validates the user-supplied URL; a bad value IS a usage error (like an unknown key), so it
        // exits 2.
        return try {
            writeConfig(path, updated) // validates + canonicalises + writes 0600 atomically
            println("public-url = ${updated.normalized().publicUrl}")
            eprintln("restart the daemon to apply: launchctl kickstart -k gui/\$(id -u)/$DAEMON_LABEL")
            0
        } catch (e: ConfigException) {
            eprintln("config set: ${e.message}")
            2
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
     * point (`ProgramArguments = [<binary>, daemon]`). After wiring the store / tmux / session manager and
     * reconciling on start, it starts the server and parks until SIGINT or SIGTERM ([installShutdownSignals]
     * — which must be called AFTER the server starts, because Ktor's native `start()` hijacks both
     * signals), then tears everything down and returns 0.
     *
     * ⚠️ Never invoke this from a test or a shell — it blocks for the daemon's whole lifetime by design.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun daemon(port: Int): Int = runBlocking {
        mkdir0700(kotgentHome())
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
        // files) — hence the persist callback rewrites all three.
        //
        // ORDER MATTERS: the hook-header files are written FIRST and `~/.kotgent/token` LAST. The token file
        // is both the CLI's view of the secret and the value `TokenHolder.rotate` publishes into memory only
        // AFTER this callback returns; if a hook-header write throws partway, `ref.store` is skipped and the
        // daemon keeps authenticating the OLD token. Writing the token file last means such a failure leaves
        // it holding the OLD value too, so the CLI stays consistent with the daemon and can re-run rotation
        // (idempotent). Were the token file written first, a mid-persist failure would strand the NEW token
        // on disk while memory kept the OLD one, and the CLI would 401 until a restart — a control-plane
        // lockout. A partially-updated hook header is self-healing on the next successful rotate.
        val tokenHolder = TokenHolder(readOrCreateToken()) { rotated ->
            writeClaudeHookSettings(port, rotated)
            writeCodexHookScript(port, rotated)
            writePrivateFile(defaultTokenPath(), rotated.encodeToByteArray())
        }
        val token = tokenHolder.current()

        // File-backed store (restart-safety = the whole point of the control plane). The driver is a local
        // so the shutdown path can close it (WAL checkpoint) instead of relying on process death.
        val driver = NativeSqliteDriver(
            schema = KotgentDatabase.Schema,
            name = DB_FILENAME,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(basePath = kotgentHome()),
                )
            },
        )
        val store = SqliteEventStore.using(driver)
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
        // Detect the version ONCE (one binary call) and reuse it for both the `--session-id` gate and the
        // per-session `cliVersion` metadata surfaced in the UI.
        val claudeVersion = claudeCli.detectVersion()
        val sessionIdSupported = ClaudeCli.supportsSessionId(claudeVersion)
        val codexCli = CodexCli()
        val codexVersion = codexCli.detectVersion()
        // Resolve each CLI to an absolute path (like tmux, which is already absolute) so the tmux launch
        // does not depend on the child shell's PATH under launchd's minimal env. `locate()` returns null
        // when the agent is NOT resolvable on the daemon's PATH; it can also return a NON-absolute path
        // (a name resolved via a relative PATH dir, or a name with a slash). Both are unusable: tmux does
        // `new-session -c <cwd>` (cd into the session cwd) before exec, so a relative path would exec a
        // wrong cwd-local binary or die at exec (127) after a `running` row was persisted — a phantom
        // session and the 1006 attach failure this path exists to prevent. So `requireAbsoluteBinary`
        // fails fast with AgentBinaryNotFoundException (from the factory builder, BEFORE any tmux side
        // effect) unless the path is absolute, pointing the user at `kotgent install` (which snapshots the
        // shell PATH into the plist). The same located path is what we persist as `cliPath` metadata.
        val claudePath: String? = claudeCli.locate()
        val codexPath: String? = codexCli.locate()
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
                        binaryName = requireAbsoluteBinary(CLAUDE_AGENT_KIND, claudePath),
                        cliVersion = claudeVersion?.toString(),
                        cliPath = claudePath,
                    )
                },
                CODEX_AGENT_KIND to { cwd: String ->
                    CodexAdapter(
                        cwd = cwd,
                        hookScriptPath = codexHookScriptPath,
                        events = emptyFlow(),
                        binaryName = requireAbsoluteBinary(CODEX_AGENT_KIND, codexPath),
                        cliVersion = codexVersion?.toString(),
                        cliPath = codexPath,
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
            // Codex records its model in the rollout's turn_context — written only once the session takes
            // its first turn — so poll a few times after launch and persist the first hit. Claude captures
            // its model via the hook path instead, so this is scoped to codex.
            captureModelInBackground = { meta ->
                if (meta.agent == CODEX_AGENT_KIND) {
                    bgScope.launch {
                        repeat(MODEL_CAPTURE_ATTEMPTS) {
                            val model = rolloutScan.discoverModel(meta.cwd, meta.createdAt)
                            if (model != null) {
                                store.setModel(meta.id, model, daemonEpochMillis())
                                return@launch
                            }
                            delay(MODEL_CAPTURE_INTERVAL_MILLIS)
                        }
                    }
                }
            },
        )

        // Restart-safe reconciliation: reclassify persisted sessions against tmux reality and rebuild
        // the pane→session registry from live panes (Task 13). Terminal bridges stay lazy (Task 9).
        manager.rebuildRegistryFromStore()
        Reconciler(tmux, store, vendorProbe, registry).reconcile()

        // Web Push (optional). It needs a subscription table, `/usr/bin/openssl` for the VAPID keypair and
        // its ES256 signatures, and outbound HTTPS — none of which the daemon's actual job depends on. So
        // every part of it degrades instead of failing: an unusable table leaves `push` null (the `/push`
        // routes are then not mounted at all, which the page reads as "this daemon cannot do push"), and a
        // missing openssl or an unwritable `~/.kotgent/vapid.pem` surfaces later as a 503 on the key route
        // and one stderr line per attempted notification. The keypair is NOT generated here: it is minted
        // lazily on the first `GET /push/vapid-key`, so a daemon nobody enables notifications on never
        // shells out at all.
        val runtime = try {
            startDaemonServer(
                assemblePush = { startPush(driver, config.publicUrl, bgScope, store) },
                createServer = { push ->
                    KotgentServer.production(
                        manager,
                        store,
                        tokenHolder,
                        tmux,
                        currentVersion = currentUiVersion(),
                        publicUrl = config.publicUrl,
                        pushStore = push?.store,
                        vapidPublicKey = push?.publicKey,
                        port = port,
                    )
                },
            )
        } catch (e: ServerBindException) {
            eprintln("kotgent daemon: ${e.message}")
            reportPortHolder(port)
            bgScope.cancel()
            driver.close()
            return@runBlocking 1
        }
        val server = runtime.server
        println("kotgent daemon listening on http://127.0.0.1:$port  (tmux -L $TMUX_SOCKET)")
        config.publicUrl?.let { println("  also reachable at $it  (Host + Origin allowlisted)") }

        // ORDER MATTERS: this must come AFTER the server started. Ktor's native `EmbeddedServer.start()`
        // installs its own SIGINT/SIGTERM handlers that only stop the engine and never exit the process —
        // so before this line Ctrl+C silently killed the HTTP server and left the daemon running as a
        // useless husk. `signal(2)` keeps the last handler, so installing ours here takes them back; see
        // [installShutdownSignals].
        installShutdownSignals()
        var signo = pendingShutdownSignal()
        while (signo == 0) {
            delay(SHUTDOWN_POLL_MILLIS)
            signo = pendingShutdownSignal()
        }

        // Graceful teardown, in dependency order: stop accepting (and tear the terminal bridges down, so
        // their `tmux attach` upstreams end instead of being orphaned), then stop the background jobs that
        // can still write, then close the database so its WAL is checkpointed. The agents themselves live
        // on inside tmux — that is the whole point of the design.
        println("kotgent daemon: ${shutdownSignalName(signo)} — shutting down")
        server.stop()
        bgScope.cancel()
        // The push client owns an NSURLSession with pooled connections to Apple/Google; releasing it after
        // the collector that uses it is cancelled keeps the teardown in dependency order.
        runtime.push?.close?.invoke()
        driver.close()
        0
    }

    /**
     * Assemble the Web Push stack over the daemon's existing [driver] and start its [PushNotifier] on
     * [scope], returning what [KotgentServer] and the shutdown path need — or `null` when push cannot be
     * wired at all.
     *
     * Push is an OPTIONAL capability, so this never throws: the only failure that can happen here and now
     * is the subscription table (everything else is lazy), and it degrades to "no `/push` routes, no
     * notifier" plus one line on stderr. A missing `/usr/bin/openssl` or an unwritable
     * `~/.kotgent/vapid.pem` cannot be detected without generating the key, which would make every daemon
     * pay for a feature most never enable — so those surface at first use instead: a `503` from
     * `GET /push/vapid-key` (the browser then simply cannot turn notifications on) and one
     * [PushSender] diagnostic per attempted send.
     *
     * @param events the store the notifier watches — the same [EventStore] the rest of the daemon uses.
     */
    private suspend fun startPush(
        driver: SqlDriver,
        publicUrl: String?,
        scope: CoroutineScope,
        events: EventStore,
    ): DaemonPush? {
        val subscriptions = try {
            SqlitePushStore(driver)
        } catch (e: Throwable) {
            eprintln("kotgent daemon: push notifications disabled (no subscription table): ${e.message}")
            return null
        }
        val key = VapidKey()
        // The signer is pointed at the key's PATH, not at a loaded key: the PEM is written by the first
        // `publicKeyBase64Url()` call, and PushSender always resolves the public key before it asks for a
        // token, so the file exists by the time openssl is asked to sign with it.
        val signer = OpensslVapidSigner(keyPath = key.keyPath)
        val tokens = VapidTokenCache(subject = vapidSubject(publicUrl), sign = signer::sign)
        val transport = DarwinPushTransport()
        val sender = PushSender(
            store = subscriptions,
            publicKey = key::publicKeyBase64Url,
            vapidToken = tokens::tokenFor,
            transport = transport,
        )
        // Started here — i.e. AFTER rebuildRegistryFromStore() + Reconciler.reconcile() — so the baseline
        // already contains the startup reclassifications. start() is a readiness barrier: do not return
        // until the flow is subscribed, because the server binds immediately after this helper.
        val notifier = PushNotifier(events, send = { id -> sender.send(id) }).start(scope)
        return DaemonPush(
            subscriptions,
            key::publicKeyBase64Url,
            close = {
                notifier.cancelAndJoin()
                transport.close()
            },
        )
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

    /** How many times to poll a codex rollout for its model after launch (see the capture wiring). */
    private const val MODEL_CAPTURE_ATTEMPTS: Int = 10

    /** Delay between codex model-capture polls (10 × 3s ≈ 30s covers a promptly-started first turn). */
    private const val MODEL_CAPTURE_INTERVAL_MILLIS: Long = 3_000

    /**
     * How often the parked daemon checks the shutdown flag. A signal handler cannot resume a coroutine
     * (nothing allocating is async-signal-safe), so the wait is a poll; 100ms is imperceptible to an
     * operator pressing Ctrl+C and costs ten wakeups a second on an otherwise idle process.
     */
    private const val SHUTDOWN_POLL_MILLIS: Long = 100

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
}

/**
 * The sign-in code block `kotgent web` prints after opening the form (or to stderr beside the `--print`
 * URL). Pure so it is unit-testable without a daemon.
 *
 * An installed home-screen app opens at its `start_url` with its own empty cookie jar and cannot be handed
 * a link fragment, so the credential must also be shown in a form a human can retype. The value is grouped
 * ([groupLoginCode]) for reading; the daemon strips whitespace before looking it up, so what is typed back
 * can carry the space or not.
 */
fun renderSignInCode(ticket: TicketResponse): String =
    "sign-in code: ${groupLoginCode(ticket.ticket)}\n" +
        "  type it into the browser form, or into an app already installed on a home screen — it has its\n" +
        "  own cookie jar, so signing in another browser does not sign the installed app in.\n" +
        "  one-time, and good for ${TICKET_TTL_MILLIS / 60_000} minutes."

/**
 * Split the fixed-width login code in the middle (`A1B2C3D4` → `A1B2 C3D4`), the way a human reads eight
 * symbols anyway. Display only: [io.kotgent.transport.normalizeTicketCode] drops whitespace before the
 * lookup, so the grouped form redeems exactly like the ungrouped one.
 */
fun groupLoginCode(code: String): String {
    require(code.length == TICKET_CODE_LENGTH) { "login code must be $TICKET_CODE_LENGTH characters" }
    val half = TICKET_CODE_LENGTH / 2
    return code.substring(0, half) + " " + code.substring(half)
}

/**
 * Execute the `web` handler through explicit seams so all output/open branches are testable without a real
 * daemon or GUI process. Normal mode opens only the credential-free form; `--print` is the one mode that
 * emits the credentialed ticket URL for intentional hand-off.
 */
suspend fun runWebCommand(
    print: Boolean,
    issueTicket: suspend () -> TicketResponse,
    open: (String) -> Int,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val ticket = issueTicket()
    if (print) {
        stdout(ticket.localUrl)
        stderr(renderSignInCode(ticket))
        return 0
    }

    val formUrl = ticket.localUrl.substringBefore('#')
    val exitCode = open(formUrl)
    if (exitCode == 0) {
        stdout("opening the kotgent sign-in form in your browser…")
    } else {
        stderr("could not launch a browser (open exited $exitCode); open this form yourself:")
        stdout(formUrl)
    }
    stdout(renderSignInCode(ticket))
    return 0
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
