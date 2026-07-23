package io.kotgent.transport

import io.kotgent.daemon.SessionManager
import io.kotgent.exe.NativeExe
import io.kotgent.pty.PtyFactory
import io.kotgent.pty.TerminalBridge
import io.kotgent.pty.realPtyFactory
import io.kotgent.pty.terminalBridgeForSession
import io.kotgent.store.EventStore
import io.kotgent.tmux.Tmux
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * The real kotgent transport server (plan Task 14) — assembles the control REST, the events WS, the
 * terminal WS, the Claude hook ingress, and the static Web UI onto one Ktor CIO server on
 * `127.0.0.1:<port>`, all behind the shared token.
 *
 * Everything is constructor-injected so the whole server is testable end-to-end against fakes: a
 * [SessionManager] over a fake tmux + fake agent, an in-memory [EventStore], and a
 * [terminalBridgeFactory] backed by a fake `PtyFactory`. The production wiring is [production].
 *
 * ## Auth layering
 *  - The **hook ingress** ([claudeHookRoutes]) is mounted at the root and does its own header-token check
 *    (Task 12) against the **same** [token] — the plan's "one token on all".
 *  - The **control REST + both WebSockets** are wrapped in [authenticated], which rejects a missing/wrong
 *    token with `401` (including on the WS handshake, before any upgrade).
 *  - The **static Web UI** is deliberately UNauthenticated: the browser fetches it before it has the token
 *    (which lives in the URL fragment `#token=`, never sent to the server), then uses the token for the
 *    API/WS. Serving the bootstrap HTML/JS openly is what lets the token-in-fragment flow work.
 *
 * @param terminalBridgeFactory builds the lazy [TerminalBridge] for a session id on a given scope; the
 *   server calls it (via a [TerminalRegistry]) on its own application scope. This is where the
 *   `PtyFactory` is injected (production: a real `tmux attach` + `capture-pane` seed; tests: a fake).
 * @param webUiDir directory served at `/` (the Task-17 SPA). `null` disables static serving (tests);
 *   the default serves whatever exists under `resources/webui` — nothing yet, i.e. `404`, until Task 17.
 * @param port `0` binds an ephemeral port (tests); [port] reports the resolved one.
 */
class KotgentServer(
    private val sessionManager: SessionManager,
    private val store: EventStore,
    private val token: String,
    private val terminalBridgeFactory: (id: String, scope: CoroutineScope) -> TerminalBridge,
    private val webUiDir: String? = DEFAULT_WEBUI_DIR,
    host: String = "127.0.0.1",
    port: Int = 0,
    private val json: Json = TRANSPORT_JSON,
) {
    /** The terminal bridge registry, captured so [stop] can tear its bridges (and their ptys) down. */
    private var terminalRegistry: TerminalRegistry? = null

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = host) {
        // `this` is the Application (a CoroutineScope): terminal bridges + their reader loops live on it.
        val registry = TerminalRegistry(this, terminalBridgeFactory).also { terminalRegistry = it }
        val inputSink: TerminalInputSink = { id, bytes -> registry.getOrCreate(id.value).write(bytes) }
        install(WebSockets)
        routing {
            // Hook ingress: same token, its own header check (Task 12).
            claudeHookRoutes(token, sessionManager.paneLookup, store, HOOK_JSON)
            // Token-gated control plane.
            authenticated(token) {
                controlRoutes(sessionManager, store, inputSink, json)
                eventsWs(store, json)
                terminalWs(registry, store, json)
            }
            // Static Web UI at / (open bootstrap — see the auth-layering KDoc).
            staticWebUi(webUiDir)
        }
    }

    /** Start the engine without blocking. */
    fun start(): KotgentServer {
        server.start(wait = false)
        return this
    }

    /** The actual OS-assigned port (resolves an ephemeral `port = 0` binding). */
    suspend fun port(): Int = server.engine.resolvedConnectors().first().port

    fun stop() {
        // Tear the terminal bridges (and their real ptys/reader threads) down before stopping the
        // engine, so a server stop reclaims those resources instead of leaking them.
        terminalRegistry?.let { runBlocking { it.shutdownAll() } }
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    }

    companion object {
        /** Default directory served at `/` (the Task-17 Web UI). Cwd-relative; see [resolveWebUiDir]. */
        const val DEFAULT_WEBUI_DIR: String = "resources/webui"

        /**
         * Production wiring: terminal bridges attach the real `tmux -L <socket> attach` upstream with a
         * real `capture-pane -e` seed ([terminalBridgeForSession]) over the given [ptyFactory] (default
         * [realPtyFactory] — a real cinterop `Pty`). The web UI directory is resolved to an ABSOLUTE
         * path ([resolveWebUiDir]) so an installed daemon (launchd sets no `WorkingDirectory`, so its
         * cwd is `/`) still serves the SPA instead of 404ing on a cwd-relative default.
         */
        fun production(
            sessionManager: SessionManager,
            store: EventStore,
            token: String,
            tmux: Tmux,
            ptyFactory: PtyFactory = realPtyFactory,
            webUiDir: String? = DEFAULT_WEBUI_DIR,
            host: String = "127.0.0.1",
            port: Int = 0,
        ): KotgentServer = KotgentServer(
            sessionManager = sessionManager,
            store = store,
            token = token,
            terminalBridgeFactory = { id, scope -> terminalBridgeForSession(tmux, id, scope, ptyFactory) },
            webUiDir = webUiDir?.let { resolveWebUiDir(it) },
            host = host,
            port = port,
        )

        /**
         * Resolve a (possibly cwd-relative) web UI [dir] to an absolute path anchored at the running
         * executable's location, so an installed daemon whose cwd is `/` still finds the SPA. An
         * already-absolute path is returned as-is; otherwise the executable's directory and its
         * ancestors are searched for `<ancestor>/<dir>`, falling back to the cwd-relative [dir] (which
         * resolves for a dev `./kotlin run` launched from the repo root).
         */
        @OptIn(ExperimentalForeignApi::class)
        internal fun resolveWebUiDir(dir: String): String {
            if (dir.startsWith("/")) return dir
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

/**
 * Serve a static SPA from [dir] at `/` (Task 14 mounts it now; Task 17 fills [dir] with the UI). Reads
 * files with posix I/O — Ktor's JVM `staticFiles`/`staticResources` are unavailable on native (the Task-3
 * decision). `null` [dir] mounts nothing. The catch-all is lower routing priority than the literal API
 * routes, so it never shadows them; `..` is rejected to prevent path traversal.
 */
fun Route.staticWebUi(dir: String?) {
    if (dir == null) return
    get("/") { serveStaticFile(dir, "index.html") }
    get("/{path...}") {
        val rel = call.parameters.getAll("path").orEmpty().joinToString("/")
        serveStaticFile(dir, rel.ifBlank { "index.html" })
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.serveStaticFile(dir: String, rel: String) {
    if (rel.contains("..") || rel.startsWith("/")) {
        call.respondText("bad path", status = HttpStatusCode.Forbidden)
        return
    }
    val bytes = readFileBytesOrNull("$dir/$rel")
    if (bytes == null) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return
    }
    call.respondBytes(bytes, contentTypeFor(rel))
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "")) {
    "html" -> ContentType.Text.Html
    "js" -> ContentType.Text.JavaScript
    "css" -> ContentType.Text.CSS
    "json" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    else -> ContentType.Application.OctetStream
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytesOrNull(path: String): ByteArray? {
    val fp = fopen(path, "rb") ?: return null
    try {
        fseek(fp, 0, SEEK_END)
        val size = ftell(fp)
        fseek(fp, 0, SEEK_SET)
        if (size <= 0L) return ByteArray(0)
        val buffer = ByteArray(size.toInt())
        buffer.usePinned { fread(it.addressOf(0), 1.convert(), size.convert(), fp) }
        return buffer
    } finally {
        fclose(fp)
    }
}
