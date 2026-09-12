package io.kotgent.cli

import io.kotgent.currentUiVersion
import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.codex.CodexHookConfig
import io.kotgent.adapter.junie.JunieHookConfig
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.defaultClaudeDir
import io.kotgent.daemon.productionVendorStoreProbe
import io.kotgent.exe.NativeExe
import io.kotgent.launchd.DAEMON_LABEL
import io.kotgent.launchd.LaunchdInstaller
import io.kotgent.push.UsageResetNotifier
import io.kotgent.sys.installShutdownSignals
import io.kotgent.sys.pendingShutdownSignal
import io.kotgent.sys.shutdownSignalName
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.TmuxHookConfig
import io.kotgent.transport.KotgentServer
import io.kotgent.transport.ServerBindException
import io.kotgent.transport.SessionDto
import io.kotgent.transport.TICKET_CODE_LENGTH
import io.kotgent.transport.TICKET_TTL_MILLIS
import io.kotgent.transport.TicketResponse
import io.kotgent.transport.readTokenOrNull
import io.kotgent.transport.readFileBytesOrNull
import io.kotgent.transport.writePrivateFile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    fun renameSession(id: String, name: String): Int = withApi { api ->
        runRenameCommand(id, { api.renameSession(id, name) }, ::println, ::eprintln)
    }

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
        val modules = DaemonModules(port, config.publicUrl, vendorProbe)
        val storage = modules.storage
        val sessions = modules.sessions

        // Beans are lazy, so realize them in the order their effects require: the token before the hook
        // artifacts that carry it, the database before the tmux server so a storage failure leaves no
        // server behind, and the hook artifacts before the manager that launches agents against them.
        val tokenHolder = modules.auth.tokens.value
        val eventStore = storage.eventStore.value
        val taskStore = storage.taskStore.value
        val taskService = storage.taskService.value
        val tmux = sessions.tmux.value
        tmux.ensureServer()
        val manager = sessions.manager.value

        // Rebuild pane identity before reconciliation. An in-progress task without a linked session remains
        // valid because a human may have moved it on the board.
        manager.rebuildRegistryFromStore()
        val _ = sessions.reconciler.value.reconcile()

        // Push is optional. Table failure omits its routes; VAPID key and signer failures remain lazy so
        // installations that never enable notifications do not pay for or depend on openssl.
        val runtime = try {
            withStartupCompensation(
                compensate = {
                    sessions.close()
                    storage.close()
                },
            ) {
                storage.startMaintenance(sessions.background.value)
                startDaemonServer(
                    assemblePush = { modules.push.start(sessions.background.value, eventStore) },
                    startUsage = { push ->
                        UsageResetNotifier(
                            usageStore = storage.usageStore.value,
                            inbox = storage.notificationStore.value,
                            wake = if (push == null) null else modules.push.sender.value::send,
                        ).start(sessions.background.value)
                    },
                    createServer = { push ->
                        KotgentServer.production(
                            sessionManager = manager,
                            eventStore = eventStore,
                            preferencesStore = eventStore,
                            tokens = tokenHolder,
                            tmux = tmux,
                            currentVersion = currentUiVersion(),
                            publicUrl = config.publicUrl,
                            pushStore = push?.store,
                            vapidPublicKey = push?.publicKey,
                            onTmuxSessionClosed = manager::onTmuxSessionClosed,
                            taskStore = taskStore,
                            taskService = taskService,
                            usageStore = storage.usageStore.value,
                            notificationStore = storage.notificationStore.value,
                            onCodexTurnCompleted = sessions.codexUsageCapture.value::onTurnCompleted,
                            port = port,
                        )
                    },
                )
            }
        } catch (e: ServerBindException) {
            eprintln("kotgent daemon: ${e.message}")
            reportPortHolder(port)
            return@runBlocking 1
        }
        val server = runtime.server
        println("kotgent daemon listening on http://127.0.0.1:$port  (tmux -L $TMUX_SOCKET)")
        config.publicUrl?.let { println("  also reachable at $it  (Host + Origin allowlisted)") }

        // Ktor replaces SIGINT/SIGTERM handlers during start; install ours afterward to reclaim shutdown.
        installShutdownSignals()
        var signo = pendingShutdownSignal()
        while (signo == 0) {
            delay(SHUTDOWN_POLL_MILLIS.milliseconds)
            signo = pendingShutdownSignal()
        }

        // Stop ingress and terminal bridges before writers, then checkpoint the database. Agent tmux
        // sessions intentionally survive daemon shutdown.
        println("kotgent daemon: ${shutdownSignalName(signo)} — shutting down")
        server.stop()
        sessions.close()
        runtime.push?.close?.invoke()
        storage.close()
        0
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




    /**
     * Signal handlers cannot resume coroutines safely, so shutdown is polled.
     */
    private const val SHUTDOWN_POLL_MILLIS: Long = 100

    /**
     * One provider transcript probe serves import and reconciliation so resumability cannot drift between
     * the initial validation and later restarts.
     */
    internal val vendorProbe: VendorStoreProbe = productionVendorStoreProbe()

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

    internal fun writeClaudeHookHeader(
        token: String,
        home: String = kotgentHome(),
    ): String {
        // Keep the token in an atomic 0600 curl header file, never in process-visible argv.
        val headerPath = "$home/claude-hook-header"
        writePrivateFile(headerPath, ClaudeHookConfig.headerFileContent(token).encodeToByteArray())
        return headerPath
    }

    internal fun writeClaudeHookSettings(
        port: Int,
        home: String = kotgentHome(),
        operatorSettingsPath: String = "${defaultClaudeDir()}/settings.json",
    ): String {
        val headerPath = "$home/claude-hook-header"
        val path = "$home/claude-hooks.json"
        val settings = readFileBytesOrNull(operatorSettingsPath, limit = 1_048_576)?.decodeToString()
        writePrivateFile(
            path,
            ClaudeHookConfig.generate(
                port, headerPath, operatorStatusLineCommand = operatorClaudeStatusLineCommand(settings),
            ).encodeToByteArray(),
        )
        return path
    }

    /**
     * The token stays in a provider-specific atomic `0600` curl header file, never argv. `/bin/sh` reads
     * the `0600` script directly, so it needs no execute bit.
     */
    internal fun writeCodexHookScript(port: Int, token: String): String {
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
    internal fun writeJunieHookConfig(port: Int, token: String): String {
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

internal fun operatorClaudeStatusLineCommand(settings: String?): String? {
    if (settings == null) return null
    val root = try {
        Json.parseToJsonElement(settings) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    val statusLine = root["statusLine"] as? JsonObject ?: return null
    val type = statusLine["type"] as? JsonPrimitive ?: return null
    if (!type.isString || type.content != "command") return null
    return (statusLine["command"] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content
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

private const val HTTP_NOT_FOUND: Int = 404

suspend fun runRenameCommand(
    id: String,
    rename: suspend () -> SessionDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val renamed = try {
        rename()
    } catch (e: ApiException) {
        // The handler's own 404 carries a body; an empty one is Ktor's route miss from a daemon that
        // predates the PATCH route, which is not a missing session.
        if (e.status != HTTP_NOT_FOUND || e.body.isEmpty()) throw e
        stderr("no such session: $id")
        return 1
    }
    stdout("renamed ${renamed.id} → ${renamed.displayName}")
    return 0
}

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
    sb.append("ID        NAME              AGENT      STATE            ATTN  TASK          CWD\n")
    for (s in sessions.sortedByDescending { it.updatedAt }) {
        val attn = if (s.needsAttention) " *  " else "    "
        sb.append(s.id.padEnd(10).take(10))
        sb.append(nameColumn(s.displayName)).append("  ")
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
private fun taskColumn(ref: String?): String =
    ellipsized(ref ?: "-", TASK_COLUMN_WIDTH).padEnd(TASK_COLUMN_WIDTH)

private fun nameColumn(name: String): String =
    ellipsized(name, NAME_COLUMN_WIDTH).padEnd(NAME_COLUMN_WIDTH)

private val SessionDto.displayName: String get() = name.ifEmpty { tmuxSession }

private fun ellipsized(value: String, width: Int): String {
    if (value.length <= width) return value
    // Cutting between the halves of a surrogate pair would print a lone high surrogate; names are astral-capable.
    val kept = if (value[width - 2].isHighSurrogate()) width - 2 else width - 1
    return value.take(kept) + "…"
}

private const val TASK_COLUMN_WIDTH: Int = 12

private const val NAME_COLUMN_WIDTH: Int = 16
