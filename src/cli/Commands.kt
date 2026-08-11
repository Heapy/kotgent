package io.kotgent.cli

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.kotgent.currentUiVersion
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.claude.ClaudeAdapter
import io.kotgent.adapter.claude.ClaudeCli
import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.codex.CodexAdapter
import io.kotgent.adapter.codex.CodexCli
import io.kotgent.adapter.codex.CodexHookConfig
import io.kotgent.adapter.junie.JunieAdapter
import io.kotgent.adapter.junie.JunieCli
import io.kotgent.adapter.junie.JunieHookConfig
import io.kotgent.adapter.shell.ShellAdapter
import io.kotgent.daemon.CLAUDE_AGENT_KIND
import io.kotgent.daemon.CODEX_AGENT_KIND
import io.kotgent.daemon.CodexRolloutScan
import io.kotgent.daemon.JUNIE_AGENT_KIND
import io.kotgent.daemon.JunieSessionScan
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.Reconciler
import io.kotgent.daemon.SHELL_AGENT_KIND
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.daemon.captureCodexModelOnce
import io.kotgent.daemon.captureJunieModelOnce
import io.kotgent.daemon.importableAgentKinds
import io.kotgent.daemon.productionSessionLocator
import io.kotgent.daemon.productionVendorStoreProbe
import io.kotgent.daemon.requireAbsoluteBinary
import io.kotgent.db.KotgentDatabase
import io.kotgent.exe.NativeExe
import io.kotgent.launchd.DAEMON_LABEL
import io.kotgent.launchd.LaunchdInstaller
import io.kotgent.push.DarwinPushTransport
import io.kotgent.push.OpensslVapidSigner
import io.kotgent.push.PushNotifier
import io.kotgent.push.PushSender
import io.kotgent.push.SqlitePushStore
import io.kotgent.push.VapidKey
import io.kotgent.push.VapidTokenCache
import io.kotgent.push.vapidSubject
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.SqliteTaskStore
import io.kotgent.task.PosixProjectFileWriter
import io.kotgent.task.PosixProjectFs
import io.kotgent.sys.installShutdownSignals
import io.kotgent.sys.currentLoginShell
import io.kotgent.sys.pendingShutdownSignal
import io.kotgent.sys.shutdownSignalName
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.Tmux
import io.kotgent.tmux.TmuxHookConfig
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
 * CLI handlers return process exit codes and render expected failures on stderr without stack traces.
 */
object Commands {

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

    fun importSession(
        agent: String,
        providerSessionId: String,
        cwd: String?,
        name: String?,
        tags: List<String>,
        noStart: Boolean,
    ): Int = withApi { api ->
        runImportCommand(
            noStart = noStart,
            importSession = { api.importSession(agent, providerSessionId, cwd, name, tags) },
            resume = api::resume,
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    fun stop(id: String): Int = withApi { api -> report("stopped", id, api.stop(id)) }
    fun resume(id: String): Int = withApi { api -> report("resumed", id, api.resume(id)) }
    fun interrupt(id: String): Int = withApi { api -> report("interrupted", id, api.interrupt(id)) }

    private fun report(verb: String, id: String, updated: SessionDto?): Int {
        println(if (updated != null) "$verb ${updated.id} → ${updated.state}" else "$verb $id")
        return 0
    }

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

    /**
     * Normal mode opens only the credential-free form and prints a code usable by home-screen apps with
     * separate cookie jars. `--print` intentionally exposes the ticket URL on stdout and keeps the code on
     * stderr so URL pipelines remain exact.
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
     * Rotation rejects the old key for new requests and handshakes and invalidates cookies and outstanding
     * tickets. Already-open sockets remain authorized until reconnect because authentication occurs at the
     * handshake.
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

    fun configGet(): Int = try {
        println("public-url = ${readConfig().publicUrl ?: "(not set)"}")
        0
    } catch (e: ConfigException) {
        eprintln("config get: ${e.message}")
        1
    }

    /**
     * Invalid values leave the current config untouched. The daemon reads configuration only at startup.
     */
    fun configSet(key: String, value: String): Int {
        if (key != "public-url") {
            eprintln("config set: unknown key '$key' (only 'public-url' is supported)")
            return 2
        }
        val path = defaultConfigPath()
        // Corrupt persisted state is a runtime failure; an invalid new value is a usage failure.
        val existing = try {
            readConfig(path)
        } catch (e: ConfigException) {
            eprintln("config set: ${e.message}")
            return 1
        }
        val updated = existing.copy(publicUrl = value)
        return try {
            writeConfig(path, updated)
            println("public-url = ${updated.normalized().publicUrl}")
            eprintln("restart the daemon to apply: launchctl kickstart -k gui/\$(id -u)/$DAEMON_LABEL")
            0
        } catch (e: ConfigException) {
            eprintln("config set: ${e.message}")
            2
        }
    }

    /** Installs the current absolute binary as a launchd-owned daemon rather than running it in-process. */
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

    /** Idempotent. */
    fun uninstall(): Int = try {
        val installer = LaunchdInstaller()
        installer.uninstall()
        println("uninstalled launchd agent (${installer.plistPath})")
        0
    } catch (e: Throwable) {
        eprintln("uninstall failed: ${e.message}")
        1
    }

    /**
     * Runs for the daemon's lifetime. Shutdown handlers must be installed after Ktor starts because its
     * native engine replaces SIGINT and SIGTERM handlers.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun daemon(port: Int): Int = runBlocking {
        mkdir0700(kotgentHome())
        // Invalid authorization configuration must stop startup rather than silently ignore public access.
        val config = try {
            readConfig()
        } catch (e: ConfigException) {
            eprintln("kotgent daemon: ${e.message}")
            return@runBlocking 1
        }
        // Gates read the holder per request. Persist hook headers before the CLI token: TokenHolder publishes
        // only after this callback succeeds, so a partial failure leaves both memory and the CLI on the old
        // token instead of locking the control plane out. Hook headers heal on the next successful rotation.
        val tokenHolder = TokenHolder(readOrCreateToken()) { rotated ->
            writeClaudeHookSettings(port, rotated)
            writeCodexHookScript(port, rotated)
            writeJunieHookConfig(port, rotated)
            writeTmuxHookScript(port, rotated)
            writePrivateFile(defaultTokenPath(), rotated.encodeToByteArray())
        }
        val token = tokenHolder.current()

        // Keep the driver for an explicit shutdown checkpoint.
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
        // Task and session writes share a driver but remain sequential; sessions retain a single writer.
        val taskStore = SqliteTaskStore.using(driver)
        val projectFs = PosixProjectFs()
        val taskService = TaskService(taskStore, store, projectFs, PosixProjectFileWriter())
        val tmuxHookScriptPath = writeTmuxHookScript(port, token)
        val tmux = Tmux(TMUX_SOCKET, hookScriptPath = tmuxHookScriptPath)
        tmux.ensureServer()

        val registry = PaneRegistry()
        val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val idCapture = ProviderIdCapture(store, bgScope)

        // Hook ingress writes the source-of-truth store directly, so adapters must not re-emit those events.
        val settingsPath = writeClaudeHookSettings(port, token)
        val codexHookScriptPath = writeCodexHookScript(port, token)
        val junieHookConfigPath = writeJunieHookConfig(port, token)
        val claudeCli = ClaudeCli()
        val claudeVersion = claudeCli.detectVersion()
        val sessionIdSupported = ClaudeCli.supportsSessionId(claudeVersion)
        val codexCli = CodexCli()
        val codexVersion = codexCli.detectVersion()
        val junieCli = JunieCli()
        val junieVersion = junieCli.detectVersion()
        // Launchd has a minimal PATH, and tmux changes cwd before exec. Require absolute CLI paths before
        // any tmux side effect so a relative lookup cannot launch a cwd-local binary or leave a phantom row.
        val claudePath: String? = claudeCli.locate()
        val codexPath: String? = codexCli.locate()
        val juniePath: String? = junieCli.locate()
        // The builder keys are the launch allowlist and the source for the narrower import allowlist.
        val agentBuilders: Map<String, (cwd: String) -> AgentAdapter> =
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
                JUNIE_AGENT_KIND to { cwd: String ->
                    JunieAdapter(
                        cwd = cwd,
                        hookConfigPath = junieHookConfigPath,
                        events = emptyFlow(),
                        binaryName = requireAbsoluteBinary(JUNIE_AGENT_KIND, juniePath),
                        cliVersion = junieVersion?.toString(),
                        cliPath = juniePath,
                    )
                },
                SHELL_AGENT_KIND to { cwd: String ->
                    ShellAdapter(cwd = cwd, shell = currentLoginShell())
                },
            )
        val agentFactory = agentFactoryOf(agentBuilders)
        // Codex cannot preallocate an id; discover it from the post-launch rollout without relying on hooks.
        val rolloutScan = CodexRolloutScan()
        // Junie's SessionStart payload also omits the id; its session directory exists before index rows do.
        val junieScan = JunieSessionScan()
        val manager = SessionManager(
            tmux,
            store,
            registry,
            agentFactory,
            idCapture,
            // Import and reconciliation must classify transcripts through the same probe.
            vendorProbe,
            productionSessionLocator(),
            // Shell has no external provider session or transcript to import.
            importableAgentKinds(agentBuilders.keys),
            discoverProviderId = { meta ->
                when (meta.agent) {
                    CODEX_AGENT_KIND -> rolloutScan.discoverSessionId(meta.cwd, meta.createdAt)
                    JUNIE_AGENT_KIND -> junieScan.discoverSessionId(meta.cwd, meta.createdAt)
                    else -> null
                }
            },
            // Codex and Junie expose models only after the first turn, so capture polls provider storage.
            captureModelInBackground = { meta ->
                if (meta.agent == CODEX_AGENT_KIND) {
                    bgScope.launch {
                        repeat(MODEL_CAPTURE_ATTEMPTS) {
                            // Re-read the provider id each attempt: background discovery may land mid-poll.
                            // Never guess by cwd+mtime because a late first bind would not correct it.
                            if (captureCodexModelOnce(store, rolloutScan, meta)) return@launch
                            delay(MODEL_CAPTURE_INTERVAL_MILLIS)
                        }
                    }
                }
                if (meta.agent == JUNIE_AGENT_KIND) {
                    // Junie's modelUsage mixes primary and helper models; its extractor uses frequency.
                    bgScope.launch {
                        repeat(MODEL_CAPTURE_ATTEMPTS) {
                            if (captureJunieModelOnce(store, junieScan, meta)) return@launch
                            delay(MODEL_CAPTURE_INTERVAL_MILLIS)
                        }
                    }
                }
            },
            taskStore = taskStore,
            projectFs = projectFs,
        )

        // Rebuild pane identity before reconciliation. An in-progress task without a linked session remains
        // valid because a human may have moved it on the board.
        manager.rebuildRegistryFromStore()
        Reconciler(tmux, store, vendorProbe, registry, taskStore = taskStore, projectFs = projectFs)
            .reconcile()

        // Push is optional. Table failure omits its routes; VAPID key and signer failures remain lazy so
        // installations that never enable notifications do not pay for or depend on openssl.
        val runtime = try {
            startDaemonServer(
                assemblePush = { startPush(driver, config.publicUrl, bgScope, store) },
                createServer = { push ->
                    KotgentServer.production(
                        manager,
                        store,
                        store,
                        tokenHolder,
                        tmux,
                        currentVersion = currentUiVersion(),
                        publicUrl = config.publicUrl,
                        pushStore = push?.store,
                        vapidPublicKey = push?.publicKey,
                        onTmuxSessionClosed = manager::onTmuxSessionClosed,
                        taskStore = taskStore,
                        taskService = taskService,
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

        // Ktor replaces SIGINT/SIGTERM handlers during start; install ours afterward to reclaim shutdown.
        installShutdownSignals()
        var signo = pendingShutdownSignal()
        while (signo == 0) {
            delay(SHUTDOWN_POLL_MILLIS)
            signo = pendingShutdownSignal()
        }

        // Stop ingress and terminal bridges before writers, then checkpoint the database. Agent tmux
        // sessions intentionally survive daemon shutdown.
        println("kotgent daemon: ${shutdownSignalName(signo)} — shutting down")
        server.stop()
        bgScope.cancel()
        runtime.push?.close?.invoke()
        driver.close()
        0
    }

    /**
     * Subscription storage failure disables push; later startup failures propagate after closing the
     * already-created Darwin transport. VAPID key and signer errors remain deferred until first use.
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
        // PushSender resolves the public key before signing, ensuring this path has been created.
        val signer = OpensslVapidSigner(keyPath = key.keyPath)
        val tokens = VapidTokenCache(subject = vapidSubject(publicUrl), sign = signer::sign)
        val transport = DarwinPushTransport()
        return withStartupCompensation(
            compensate = { transport.close() },
        ) {
            val sender = PushSender(
                store = subscriptions,
                publicKey = key::publicKeyBase64Url,
                vapidToken = tokens::tokenFor,
                transport = transport,
            )
            // Seed after reconciliation and await subscription before exposing hook ingress.
            val notifier = PushNotifier(events, send = { id -> sender.send(id) }).start(scope)
            DaemonPush(
                subscriptions,
                key::publicKeyBase64Url,
                close = {
                    notifier.cancelAndJoin()
                    transport.close()
                },
            )
        }
    }

    /**
     * An older orphaned tmux may still hold an inherited listener. Diagnosis is best-effort because lsof
     * may be unavailable; killing that tmux would also kill its agents.
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

    private const val DB_FILENAME: String = "kotgent.db"

    private const val MODEL_CAPTURE_ATTEMPTS: Int = 10

    private const val MODEL_CAPTURE_INTERVAL_MILLIS: Long = 3_000

    /**
     * Signal handlers cannot resume coroutines safely, so shutdown is polled.
     */
    private const val SHUTDOWN_POLL_MILLIS: Long = 100

    /**
     * One provider transcript probe serves import and reconciliation so resumability cannot drift between
     * the initial validation and later restarts.
     */
    private val vendorProbe: VendorStoreProbe = productionVendorStoreProbe()

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
        // Keep the token in an atomic 0600 curl header file, never in process-visible argv.
        val headerPath = "${kotgentHome()}/claude-hook-header"
        writePrivateFile(headerPath, ClaudeHookConfig.headerFileContent(token).encodeToByteArray())
        val path = "${kotgentHome()}/claude-hooks.json"
        writePrivateFile(path, ClaudeHookConfig.generate(port, headerPath).encodeToByteArray())
        return path
    }

    /**
     * The token stays in a provider-specific atomic `0600` curl header file, never argv. `/bin/sh` reads
     * the `0600` script directly, so it needs no execute bit.
     */
    private fun writeCodexHookScript(port: Int, token: String): String {
        val headerPath = "${kotgentHome()}/codex-hook-header"
        writePrivateFile(headerPath, CodexHookConfig.headerFileContent(token).encodeToByteArray())
        val path = "${kotgentHome()}/codex-hook.sh"
        writePrivateFile(path, CodexHookConfig.hookScript(port, headerPath).encodeToByteArray())
        return path
    }

    /**
     * Junie hooks use kotgent's per-launch config rather than mutating the user's config. The token stays
     * in an atomic `0600` curl header file and never appears in argv.
     */
    private fun writeJunieHookConfig(port: Int, token: String): String {
        val headerPath = "${kotgentHome()}/junie-hook-header"
        writePrivateFile(headerPath, JunieHookConfig.headerFileContent(token).encodeToByteArray())
        val scriptPath = "${kotgentHome()}/junie-hook.sh"
        writePrivateFile(scriptPath, JunieHookConfig.hookScript(port, headerPath).encodeToByteArray())
        val path = "${kotgentHome()}/junie-hooks.json"
        writePrivateFile(path, JunieHookConfig.configJson(scriptPath).encodeToByteArray())
        return path
    }

    /**
     * Both artifacts are atomic `0600` files. The token stays in the curl header rather than argv, and
     * `/bin/sh` reads the non-executable script directly.
     */
    fun writeTmuxHookScript(port: Int, token: String, home: String = kotgentHome()): String {
        val headerPath = "$home/tmux-hook-header"
        writePrivateFile(headerPath, TmuxHookConfig.headerFileContent(token).encodeToByteArray())
        val path = "$home/tmux-hook.sh"
        writePrivateFile(path, TmuxHookConfig.hookScript(port, headerPath).encodeToByteArray())
        return path
    }
}

/**
 * A home-screen app has its own cookie jar and cannot receive another browser's link fragment, so the
 * ticket is also rendered as a code it can redeem directly.
 */
fun renderSignInCode(ticket: TicketResponse): String =
    "sign-in code: ${groupLoginCode(ticket.ticket)}\n" +
        "  type it into the browser form, or into an app already installed on a home screen — it has its\n" +
        "  own cookie jar, so signing in another browser does not sign the installed app in.\n" +
        "  one-time, and good for ${TICKET_TTL_MILLIS / 60_000} minutes."

/**
 * Grouping is display-only; the daemon removes whitespace before redemption.
 */
fun groupLoginCode(code: String): String {
    require(code.length == TICKET_CODE_LENGTH) { "login code must be $TICKET_CODE_LENGTH characters" }
    val half = TICKET_CODE_LENGTH / 2
    return code.substring(0, half) + " " + code.substring(half)
}

/**
 * Normal mode opens only the credential-free form; [print] intentionally emits the credentialed URL.
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
 * Cross-layer contract with the import route's 409 body, used to recover the existing session id.
 */
val DUPLICATE_IMPORT_ID_IN_BODY: Regex = Regex("kotgent session '([^']+)'")

/**
 * Imports then resumes unless [noStart]. A resume failure leaves the row truthfully resumable; duplicate
 * 409 responses produce a concrete resume or restore hint from the server's existing-session id.
 */
suspend fun runImportCommand(
    noStart: Boolean,
    importSession: suspend () -> SessionDto,
    resume: suspend (String) -> SessionDto?,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val s = try {
        importSession()
    } catch (e: ApiException) {
        stderr(e.body.trim().ifEmpty { e.message ?: "import failed" })
        if (e.status == 409) {
            val existing = DUPLICATE_IMPORT_ID_IN_BODY.find(e.body)?.groupValues?.get(1)
            stderr(
                if ("archived" in e.body) {
                    "hint: that session is archived — Restore it in the Web UI instead of importing again"
                } else {
                    "hint: continue the existing session with `kotgent resume ${existing ?: "<id>"}`"
                },
            )
        }
        return 1
    }
    stdout("imported ${s.id}  (${s.agent})  ${s.state}  cwd=${s.cwd}")
    if (noStart) {
        stdout("registered only — start it later with `kotgent resume ${s.id}`")
        return 0
    }
    val resumed = resume(s.id)
    stdout(if (resumed != null) "resumed ${resumed.id} → ${resumed.state}" else "resumed ${s.id}")
    return 0
}

/**
 * The task column uses refs because the session endpoint has no backlog titles; resolving them would add
 * one request per row.
 */
fun renderSessions(sessions: List<SessionDto>): String {
    if (sessions.isEmpty()) return "no sessions\n"
    val sb = StringBuilder()
    sb.append("ID        AGENT      STATE            ATTN  TASK          CWD\n")
    for (s in sessions.sortedByDescending { it.updatedAt }) {
        val attn = if (s.needsAttention) " *  " else "    "
        sb.append(s.id.padEnd(10).take(10))
        sb.append(s.agent.padEnd(11).take(11))
        sb.append(s.state.padEnd(17).take(17))
        sb.append(attn)
        sb.append("  ").append(taskColumn(s.taskRef))
        sb.append("  ").append(s.cwd)
        sb.append('\n')
    }
    return sb.toString()
}

/**
 * Truncation uses an ellipsis because a plain prefix can itself be a valid ref naming another task.
 */
private fun taskColumn(ref: String?): String {
    val value = ref ?: "-"
    val cell = if (value.length > TASK_COLUMN_WIDTH) value.take(TASK_COLUMN_WIDTH - 1) + "…" else value
    return cell.padEnd(TASK_COLUMN_WIDTH)
}

private const val TASK_COLUMN_WIDTH: Int = 12
