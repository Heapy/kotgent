package io.kotgent.transport

import io.kotgent.currentUiVersion
import io.kotgent.core.SessionId
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.exe.NativeExe
import io.kotgent.push.PushStore
import io.kotgent.pty.PtyFactory
import io.kotgent.pty.TerminalBridge
import io.kotgent.pty.realPtyFactory
import io.kotgent.pty.terminalBridgeForSession
import io.kotgent.store.EventStore
import io.kotgent.store.PreferencesStore
import io.kotgent.store.TaskStore
import io.kotgent.sys.markOpenFdsCloexec
import io.kotgent.tmux.Tmux
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import platform.posix.F_OK
import platform.posix.access

// Programmatic endpoints are versioned; hook and auth handlers also expose root aliases for older clients.
const val API_PREFIX: String = "/api/v1"

class KotgentServer(
    private val sessionManager: SessionManager,
    private val store: EventStore,
    private val preferencesStore: PreferencesStore,
    private val tokens: TokenHolder,
    private val terminalBridgeFactory: (id: String, scope: CoroutineScope) -> TerminalBridge,
    private val directoryCompleter: DirectoryCompleter = posixDirectoryCompleter,
    private val fileUploader: FileUploader = posixFileUploader,
    private val currentVersion: String = currentUiVersion(),
    private val webUiDir: String? = DEFAULT_WEBUI_DIR,
    private val publicUrl: String? = null,
    private val tickets: TicketStore = TicketStore(),
    private val pushStore: PushStore? = null,
    private val vapidPublicKey: (suspend () -> String)? = null,
    private val onTmuxSessionClosed: suspend (SessionId) -> Unit = {},
    private val taskStore: TaskStore? = null,
    private val taskService: TaskService? = null,
    host: String = "127.0.0.1",
    port: Int = 0,
    private val json: Json = TRANSPORT_JSON,
) {
    private var terminalRegistry: TerminalRegistry? = null

    @Volatile
    private var startupInProgress: Boolean = true
    // Kotlin/Native also sends an expected CIO bind failure to the uncaught handler. Suppress only
    // that duplicate during startup; start() observes and wraps the same failure.
    private val engineExceptionHandler = CoroutineExceptionHandler { _, cause ->
        if (!startupInProgress) throw cause
    }
    private val engineScope = CoroutineScope(engineExceptionHandler)

    private val server: EmbeddedServer<*, *> =
        embeddedServer(
            factory = CIO,
            rootConfig = serverConfig(
                environment = applicationEnvironment {
                    log = websocketDisconnectAwareLogger(KtorSimpleLogger(KTOR_APPLICATION_LOGGER_NAME))
                },
            ) {
                parentCoroutineContext = engineScope.coroutineContext
                module {
                    val registry = TerminalRegistry(this, terminalBridgeFactory).also { terminalRegistry = it }
                    val inputSink: TerminalInputSink = { id, bytes ->
                        // Programmatic writes must leave shared tmux copy-mode first or bytes are silently eaten.
                        sessionManager.leaveCopyMode(id) && registry.getOrCreate(id.value).write(bytes)
                    }
                    install(WebSockets)
                    routing {
                        claudeHookRoutes(tokens::current, sessionManager.paneLookup, store, HOOK_JSON)
                        codexHookRoutes(
                            tokens::current, sessionManager.paneLookup, store, HOOK_JSON,
                            onProviderIdRebound = sessionManager::onProviderIdRebound,
                        )
                        junieHookRoutes(
                            tokens::current, sessionManager.paneLookup, store, HOOK_JSON,
                            onProviderIdRebound = sessionManager::onProviderIdRebound,
                        )
                        tmuxHookRoutes(tokens::current, onTmuxSessionClosed)
                        authRoutes(tokens, tickets, publicUrl, json)
                        authenticated(tokens::current, publicUrl) {
                            route(API_PREFIX) {
                                fileUploadRoutes(store, fileUploader, json)
                                controlRoutes(
                                    sessionManager,
                                    store,
                                    inputSink,
                                    currentVersion,
                                    taskService,
                                    json,
                                    taskStore,
                                )
                                directoryCompletionRoutes(directoryCompleter, json)
                                preferencesRoutes(preferencesStore, json)
                                eventsWs(store, preferencesStore, taskStore, json)
                                terminalWs(registry, store, json)
                                val backlog = taskStore
                                val coordinator = taskService
                                if (backlog != null && coordinator != null) {
                                    taskRoutes(
                                        TaskRouting(
                                            tasks = backlog,
                                            service = coordinator,
                                            sessions = store,
                                            paneLookup = sessionManager.paneLookup,
                                            json = json,
                                        ),
                                    )
                                }
                                val subscriptions = pushStore
                                val key = vapidPublicKey
                                if (subscriptions != null && key != null) pushRoutes(subscriptions, key, json)
                            }
                        }
                        staticWebUi(webUiDir)
                    }
                }
            },
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        this.host = host
                        this.port = port
                    },
                )
            },
        ).also {
            // macOS can otherwise reject an immediate daemon restart while the prior socket tears down.
            it.engineConfig.reuseAddress = true
        }

    fun start(): KotgentServer {
        try {
            server.start(wait = false)
            runBlocking {
                withTimeout(BIND_TIMEOUT_MS) { server.engine.resolvedConnectors() }
            }
        } catch (e: TimeoutCancellationException) {
            throw ServerBindException("timed out after ${BIND_TIMEOUT_MS}ms waiting for the bind", e)
        } catch (e: CancellationException) {
            val cause = e.cause
            if (cause == null || cause === e) throw e
            throw ServerBindException(cause.message ?: cause::class.simpleName ?: "bind failed", cause)
        } catch (e: Throwable) {
            throw ServerBindException(e.message ?: e::class.simpleName ?: "bind failed", e)
        }
        // Prevent a spawned tmux server from inheriting a listener that outlives this daemon.
        markOpenFdsCloexec()
        startupInProgress = false
        return this
    }

    suspend fun port(): Int = server.engine.resolvedConnectors().first().port

    fun stop() {
        terminalRegistry?.let { runBlocking { it.shutdownAll() } }
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    }

    companion object {
        const val DEFAULT_WEBUI_DIR: String = "resources/webui"

        private const val BIND_TIMEOUT_MS: Long = 10_000

        private const val KTOR_APPLICATION_LOGGER_NAME: String = "io.ktor.server.Application"

        fun production(
            sessionManager: SessionManager,
            store: EventStore,
            preferencesStore: PreferencesStore,
            tokens: TokenHolder,
            tmux: Tmux,
            currentVersion: String = currentUiVersion(),
            ptyFactory: PtyFactory = realPtyFactory,
            fileUploader: FileUploader = posixFileUploader,
            webUiDir: String? = DEFAULT_WEBUI_DIR,
            publicUrl: String? = null,
            pushStore: PushStore? = null,
            vapidPublicKey: (suspend () -> String)? = null,
            onTmuxSessionClosed: suspend (SessionId) -> Unit = {},
            taskStore: TaskStore? = null,
            taskService: TaskService? = null,
            host: String = "127.0.0.1",
            port: Int = 0,
        ): KotgentServer = KotgentServer(
            sessionManager = sessionManager,
            store = store,
            preferencesStore = preferencesStore,
            tokens = tokens,
            terminalBridgeFactory = { id, scope -> terminalBridgeForSession(tmux, id, scope, ptyFactory) },
            fileUploader = fileUploader,
            currentVersion = currentVersion,
            webUiDir = webUiDir?.let { resolveWebUiDir(it) },
            publicUrl = publicUrl,
            pushStore = pushStore,
            vapidPublicKey = vapidPublicKey,
            onTmuxSessionClosed = onTmuxSessionClosed,
            taskStore = taskStore,
            taskService = taskService,
            host = host,
            port = port,
        )

        @OptIn(ExperimentalForeignApi::class)
        internal fun resolveWebUiDir(dir: String): String {
            if (dir.startsWith("/")) return dir
            // launchd starts with cwd `/`; anchor installed assets to the executable hierarchy.
            val exe = NativeExe.path() ?: return dir
            var d: String = exe.substringBeforeLast('/', missingDelimiterValue = "")
            while (d.isNotEmpty()) {
                val candidate = "$d/$dir"
                if (access(candidate, F_OK) == 0) return candidate
                val parent = d.substringBeforeLast('/', missingDelimiterValue = "")
                if (parent == d) break
                d = parent
            }
            return dir
        }
    }
}

class ServerBindException(message: String, cause: Throwable?) :
    RuntimeException("failed to bind the kotgent server: $message", cause)

fun Route.staticWebUi(dir: String?) {
    if (dir == null) return
    get("/") { serveStaticFile(dir, "index.html") }
    get("/{path...}") {
        val rel = call.parameters.getAll("path").orEmpty().joinToString("/")
        serveStaticFile(dir, rel.ifBlank { "index.html" })
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.serveStaticFile(dir: String, rel: String) {
    // Strip the cache prefix before traversal validation so `_v/<rev>/../../…` cannot bypass it.
    val (rev, stripped) = stripRevPrefix(rel)
    if (stripped.contains("..") || stripped.startsWith("/")) {
        call.respondText("bad path", status = HttpStatusCode.Forbidden)
        return
    }
    val direct = readFileBytesOrNull("$dir/$stripped")
    val path = if (direct == null && isSpaRoute(rel)) "index.html" else stripped
    val bytes = direct ?: if (path != stripped) readFileBytesOrNull("$dir/$path") else null
    if (bytes == null) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return
    }
    val body = if (path == "index.html") {
        bytes.decodeToString().replace(WEBUI_REV_PLACEHOLDER, webUiRevision(dir)).encodeToByteArray()
    } else {
        bytes
    }
    val immutable = rev != null && isRevToken(rev) && !neverImmutable(path)
    call.response.headers.append(
        HttpHeaders.CacheControl,
        if (immutable) IMMUTABLE_CACHE_CONTROL else "no-cache",
    )
    call.respondBytes(body, contentTypeFor(path))
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "")) {
    "html" -> ContentType.Text.Html
    "js" -> ContentType.Text.JavaScript
    "css" -> ContentType.Text.CSS
    "json" -> ContentType.Application.Json
    "webmanifest" -> MANIFEST_CONTENT_TYPE
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    else -> ContentType.Application.OctetStream
}

// Chrome rejects installation when a manifest is served as generic octet-stream.
private val MANIFEST_CONTENT_TYPE: ContentType = ContentType("application", "manifest+json")
