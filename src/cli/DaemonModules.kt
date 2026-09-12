package io.kotgent.cli

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.heapy.komok.tech.di.delegate.bean
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.claude.ClaudeAdapter
import io.kotgent.adapter.claude.ClaudeCli
import io.kotgent.adapter.codex.CodexAdapter
import io.kotgent.adapter.codex.CodexCli
import io.kotgent.adapter.junie.JunieAdapter
import io.kotgent.adapter.junie.JunieCli
import io.kotgent.adapter.shell.ShellAdapter
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.CLAUDE_AGENT_KIND
import io.kotgent.daemon.CODEX_AGENT_KIND
import io.kotgent.daemon.CodexRolloutScan
import io.kotgent.daemon.CodexUsageCapture
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
import io.kotgent.daemon.requireAbsoluteBinary
import io.kotgent.db.KotgentDatabase
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
import io.kotgent.store.SqliteUsageStore
import io.kotgent.store.SqliteNotificationStore
import io.kotgent.sys.currentLoginShell
import io.kotgent.task.PosixProjectFileWriter
import io.kotgent.task.PosixProjectFs
import io.kotgent.tmux.Tmux
import io.kotgent.transport.TokenHolder
import io.kotgent.transport.defaultTokenPath
import io.kotgent.transport.readOrCreateToken
import io.kotgent.transport.writePrivateFile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// The daemon's object graph. A bean holds construction only; lifecycle and the order of the startup
// effects stay in `Commands.daemon`, which realizes the beans in the order those effects require.

private const val DB_FILENAME: String = "kotgent.db"

private const val MODEL_CAPTURE_ATTEMPTS: Int = 10

private const val MODEL_CAPTURE_INTERVAL_MILLIS: Long = 3_000

internal class AuthModule(
    private val port: Int,
) {
    /**
     * Gates read the holder per request. Persist hook headers before the CLI token: TokenHolder publishes
     * only after this callback succeeds, so a partial failure leaves both memory and the CLI on the old
     * token instead of locking the control plane out. Hook headers heal on the next successful rotation.
     */
    val tokens by bean {
        val initial = readOrCreateToken()
        // Launch-time settings refresh must never race token rotation by rewriting this shared header.
        val _ = Commands.writeClaudeHookHeader(initial)
        TokenHolder(initial) { rotated ->
            val _ = Commands.writeClaudeHookHeader(rotated)
            val _ = Commands.writeCodexHookScript(port, rotated)
            val _ = Commands.writeJunieHookConfig(port, rotated)
            val _ = Commands.writeTmuxHookScript(port, rotated)
            writePrivateFile(defaultTokenPath(), rotated.encodeToByteArray())
        }
    }
}

/**
 * Resolving a provider's hook path writes its artifacts. Claude refreshes on every launch because the
 * chained operator status command may have changed since the previous launch.
 */
internal class HookFilesModule(
    private val port: Int,
    private val auth: AuthModule,
) {
    private val token: String get() = auth.tokens.value.current()

    fun claudeSettings(): String {
        val _ = auth.tokens.value
        return Commands.writeClaudeHookSettings(port)
    }

    val codexScript by bean { Commands.writeCodexHookScript(port, token) }

    val junieConfig by bean { Commands.writeJunieHookConfig(port, token) }

    val tmuxScript by bean { Commands.writeTmuxHookScript(port, token) }
}

internal class StorageModule {
    private var maintenance: Job? = null

    /** Kept for an explicit shutdown checkpoint. */
    val driver by bean {
        NativeSqliteDriver(
            schema = KotgentDatabase.Schema,
            name = DB_FILENAME,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(basePath = kotgentHome()),
                )
            },
        )
    }

    val eventStore by bean { SqliteEventStore.using(driver.value) }

    // Task and session writes share a driver but remain sequential; sessions retain a single writer.
    val taskStore by bean { SqliteTaskStore.using(driver.value) }

    val usageStore by bean { SqliteUsageStore(driver.value) }

    val notificationStore by bean { SqliteNotificationStore(driver.value) }

    val projectFs by bean { PosixProjectFs() }

    val taskService by bean {
        TaskService(taskStore.value, eventStore.value, projectFs.value, PosixProjectFileWriter())
    }

    suspend fun startMaintenance(scope: CoroutineScope) {
        check(maintenance == null) { "storage maintenance already started" }
        maintenance = startStorageMaintenance(scope, prune = {
            usageStore.value.prune()
            notificationStore.value.prune()
        })
    }

    suspend fun close() {
        maintenance?.cancelAndJoin()
        if (driver.isInitialized) driver.value.close()
    }
}

internal class AgentModule(
    private val hooks: HookFilesModule,
) {
    val claudeCli by bean { ClaudeCli() }

    val codexCli by bean { CodexCli() }

    val junieCli by bean { JunieCli() }

    val claudeVersion by bean { claudeCli.value.detectVersion() }

    val codexVersion by bean { codexCli.value.detectVersion() }

    val junieVersion by bean { junieCli.value.detectVersion() }

    // Codex cannot preallocate an id; discover it from the post-launch rollout without relying on hooks.
    val rolloutScan by bean { CodexRolloutScan() }

    // Junie's SessionStart payload also omits the id; its session directory exists before index rows do.
    val junieScan by bean { JunieSessionScan() }

    /**
     * The keys are the launch allowlist and the source for the narrower import allowlist. Hook ingress
     * writes the source-of-truth store directly, so adapters must not re-emit those events.
     */
    val builders by bean<Map<String, (cwd: String) -> AgentAdapter>> {
        // Probe every CLI here rather than inside a builder: a version detected on first launch would
        // report the CLI the operator installed later, not the one the daemon started against.
        val claude = claudeVersion.value
        val sessionIdSupported = ClaudeCli.supportsSessionId(claude)
        val codex = codexVersion.value
        val junie = junieVersion.value
        // Launchd has a minimal PATH, and tmux changes cwd before exec. Require absolute CLI paths before
        // any tmux side effect so a relative lookup cannot launch a cwd-local binary or leave a phantom row.
        val claudePath: String? = claudeCli.value.locate()
        val codexPath: String? = codexCli.value.locate()
        val juniePath: String? = junieCli.value.locate()
        mapOf(
            CLAUDE_AGENT_KIND to { cwd: String ->
                ClaudeAdapter(
                    cwd = cwd,
                    settingsPath = hooks.claudeSettings(),
                    events = emptyFlow(),
                    sessionIdSupported = sessionIdSupported,
                    binaryName = requireAbsoluteBinary(CLAUDE_AGENT_KIND, claudePath),
                    cliVersion = claude?.toString(),
                    cliPath = claudePath,
                )
            },
            CODEX_AGENT_KIND to { cwd: String ->
                CodexAdapter(
                    cwd = cwd,
                    hookScriptPath = hooks.codexScript.value,
                    events = emptyFlow(),
                    binaryName = requireAbsoluteBinary(CODEX_AGENT_KIND, codexPath),
                    cliVersion = codex?.toString(),
                    cliPath = codexPath,
                )
            },
            JUNIE_AGENT_KIND to { cwd: String ->
                JunieAdapter(
                    cwd = cwd,
                    hookConfigPath = hooks.junieConfig.value,
                    events = emptyFlow(),
                    binaryName = requireAbsoluteBinary(JUNIE_AGENT_KIND, juniePath),
                    cliVersion = junie?.toString(),
                    cliPath = juniePath,
                )
            },
            SHELL_AGENT_KIND to { cwd: String ->
                ShellAdapter(cwd = cwd, shell = currentLoginShell())
            },
        )
    }

    val factory by bean<AgentFactory> { agentFactoryOf(builders.value) }
}

internal class SessionModule(
    private val storage: StorageModule,
    private val agents: AgentModule,
    private val hooks: HookFilesModule,
    private val vendorProbe: VendorStoreProbe,
) {
    val tmux by bean { Tmux(TMUX_SOCKET, hookScriptPath = hooks.tmuxScript.value) }

    val panes by bean { PaneRegistry() }

    val background by bean { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val idCapture by bean { ProviderIdCapture(storage.eventStore.value, background.value) }

    val codexUsageCapture by bean {
        CodexUsageCapture(
            scope = background.value,
            events = storage.eventStore.value,
            usage = storage.usageStore.value,
            rateLimitsOf = agents.rolloutScan.value::rateLimitsOf,
            onError = { failure -> eprintln("kotgent daemon: Codex usage capture failed: ${failure.message}") },
        )
    }

    val manager by bean {
        val rollout = agents.rolloutScan.value
        val junie = agents.junieScan.value
        SessionManager(
            tmux.value,
            storage.eventStore.value,
            panes.value,
            agents.factory.value,
            idCapture.value,
            // Import and reconciliation must classify transcripts through the same probe.
            vendorProbe,
            productionSessionLocator(),
            // Shell has no external provider session or transcript to import.
            importableAgentKinds(agents.builders.value.keys),
            discoverProviderId = { meta ->
                when (meta.agent) {
                    CODEX_AGENT_KIND -> rollout.discoverSessionId(meta.cwd, meta.createdAt)
                    JUNIE_AGENT_KIND -> junie.discoverSessionId(meta.cwd, meta.createdAt)
                    else -> null
                }
            },
            // Codex and Junie expose models only after the first turn, so capture polls provider storage.
            captureModelInBackground = { meta ->
                if (meta.agent == CODEX_AGENT_KIND) {
                    background.value.launch {
                        repeat(MODEL_CAPTURE_ATTEMPTS) {
                            // Re-read the provider id each attempt: background discovery may land mid-poll.
                            // Never guess by cwd+mtime because a late first bind would not correct it.
                            if (captureCodexModelOnce(storage.eventStore.value, rollout, meta)) {
                                return@launch
                            }
                            delay(MODEL_CAPTURE_INTERVAL_MILLIS.milliseconds)
                        }
                    }
                }
                if (meta.agent == JUNIE_AGENT_KIND) {
                    // Junie's modelUsage mixes primary and helper models; its extractor uses frequency.
                    background.value.launch {
                        repeat(MODEL_CAPTURE_ATTEMPTS) {
                            if (captureJunieModelOnce(storage.eventStore.value, junie, meta)) {
                                return@launch
                            }
                            delay(MODEL_CAPTURE_INTERVAL_MILLIS.milliseconds)
                        }
                    }
                }
            },
            taskStore = storage.taskStore.value,
            projectFs = storage.projectFs.value,
        )
    }

    val reconciler by bean {
        Reconciler(
            tmux.value,
            storage.eventStore.value,
            vendorProbe,
            panes.value,
            taskStore = storage.taskStore.value,
            projectFs = storage.projectFs.value,
        )
    }

    suspend fun close() {
        if (background.isInitialized) background.value.coroutineContext[Job]?.cancelAndJoin()
    }
}

internal class PushModule(
    private val storage: StorageModule,
    private val publicUrl: String?,
) {
    val subscriptions by bean { SqlitePushStore(storage.driver.value) }

    val key by bean { VapidKey() }

    // PushSender resolves the public key before signing, ensuring this path has been created.
    val signer by bean { OpensslVapidSigner(keyPath = key.value.keyPath) }

    val tokens by bean { VapidTokenCache(subject = vapidSubject(publicUrl), sign = signer.value::sign) }

    val transport by bean { DarwinPushTransport() }

    val sender by bean {
        PushSender(
            store = subscriptions.value,
            publicKey = key.value::publicKeyBase64Url,
            vapidToken = tokens.value::tokenFor,
            transport = transport.value,
        )
    }

    /**
     * Subscription storage failure disables push; later startup failures propagate after closing the
     * already-created Darwin transport. VAPID key and signer errors remain deferred until first use.
     */
    suspend fun start(scope: CoroutineScope, events: EventStore): DaemonPush? {
        val store = try {
            subscriptions.value
        } catch (e: Throwable) {
            eprintln("kotgent daemon: push notifications disabled (no subscription table): ${e.message}")
            return null
        }
        // Realize the transport before the compensation closes over it, so a failure below cannot make
        // the compensation open the very resource it exists to release.
        val vapid = key.value
        val darwin = transport.value
        return withStartupCompensation(
            compensate = { darwin.close() },
        ) {
            val fanOut = sender.value
            // Seed after reconciliation and await subscription before exposing hook ingress.
            val notifier = PushNotifier(events, send = fanOut::send).start(scope)
            DaemonPush(
                store,
                vapid::publicKeyBase64Url,
                close = {
                    notifier.cancelAndJoin()
                    darwin.close()
                },
            )
        }
    }
}

internal class DaemonModules(
    port: Int,
    publicUrl: String?,
    vendorProbe: VendorStoreProbe,
) {
    val auth by lazy {
        AuthModule(port)
    }

    val hooks by lazy {
        HookFilesModule(port, auth)
    }

    val storage by lazy {
        StorageModule()
    }

    val agents by lazy {
        AgentModule(hooks = hooks)
    }

    val sessions by lazy {
        SessionModule(
            storage = storage,
            agents = agents,
            hooks = hooks,
            vendorProbe = vendorProbe,
        )
    }

    val push by lazy {
        PushModule(
            storage = storage,
            publicUrl = publicUrl,
        )
    }
}
