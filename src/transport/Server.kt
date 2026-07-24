package io.kotgent.transport

import io.kotgent.currentUiVersion
import io.kotgent.daemon.SessionManager
import io.kotgent.exe.NativeExe
import io.kotgent.pty.PtyFactory
import io.kotgent.pty.TerminalBridge
import io.kotgent.pty.realPtyFactory
import io.kotgent.pty.terminalBridgeForSession
import io.kotgent.store.EventStore
import io.kotgent.sys.markOpenFdsCloexec
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
 *  - The **hook ingresses** ([claudeHookRoutes], [codexHookRoutes]) are mounted at the root, restricted to
 *    a loopback `Host` ([loopbackOnly] — they are `curl`s from this machine, never tunnel traffic) and do
 *    their own header-token check (Task 12) against the **same** master token — the plan's "one token on
 *    all". They read it through [TokenHolder.current], never as a captured string, so `kotgent token
 *    rotate` reaches every gate at once.
 *  - The **login routes** ([authRoutes]) carry their own layering: ticket issuance and token rotation are
 *    `Bearer` + loopback-only, the login page is open, and the exchange is gated by `Host`/`Origin` alone
 *    because the ticket it spends IS its credential.
 *  - The **control REST + both WebSockets** are wrapped in [authenticated], i.e. the one [authorize] rule:
 *    a `Host` allowlist built from [publicUrl], the `Origin` requirement on non-GET and on WS handshakes,
 *    then a `Bearer` master token or the browser's session cookie. A refusal is written before any upgrade,
 *    so a rejected WS handshake never becomes a socket.
 *  - The **static Web UI** is deliberately UNauthenticated: the browser fetches it before it has any
 *    credential, then the SPA calls the API with the cookie the login flow set. Serving the bootstrap
 *    HTML/JS openly is what makes that first paint possible at all.
 *
 * @param tokens the live master token. A [TokenHolder] rather than a plain `() -> String` because the
 *   server does not only READ the secret any more: `POST /auth/rotate` re-mints it, and every gate below
 *   has to observe the new value on the very next request.
 * @param terminalBridgeFactory builds the lazy [TerminalBridge] for a session id on a given scope; the
 *   server calls it (via a [TerminalRegistry]) on its own application scope. This is where the
 *   `PtyFactory` is injected (production: a real `tmux attach` + `capture-pane` seed; tests: a fake).
 * @param directoryCompleter lists one directory level for the working-directory autocomplete.
 * @param currentVersion the running application's display version exposed by `GET /version`.
 * @param webUiDir directory served at `/` (the Task-17 SPA). `null` disables static serving (tests);
 *   the default serves whatever exists under `resources/webui` — nothing yet, i.e. `404`, until Task 17.
 * @param publicUrl the origin the daemon is published at through the cloudflared tunnel
 *   (`~/.kotgent/config.json`), or `null` for loopback-only. Passed IN by the daemon rather than read here:
 *   the dependency runs cli → transport, and the transport does not read configuration files.
 * @param tickets the outstanding one-shot login tickets; in-memory and per-daemon-run by design.
 * @param port `0` binds an ephemeral port (tests); [port] reports the resolved one.
 */
class KotgentServer(
    private val sessionManager: SessionManager,
    private val store: EventStore,
    private val tokens: TokenHolder,
    private val terminalBridgeFactory: (id: String, scope: CoroutineScope) -> TerminalBridge,
    private val directoryCompleter: DirectoryCompleter = posixDirectoryCompleter,
    private val currentVersion: String = currentUiVersion(),
    private val webUiDir: String? = DEFAULT_WEBUI_DIR,
    private val publicUrl: String? = null,
    private val tickets: TicketStore = TicketStore(),
    host: String = "127.0.0.1",
    port: Int = 0,
    private val json: Json = TRANSPORT_JSON,
) {
    /** The terminal bridge registry, captured so [stop] can tear its bridges (and their ptys) down. */
    private var terminalRegistry: TerminalRegistry? = null

    /**
     * Ktor starts CIO in a root `launch`. On Kotlin/Native an expected bind failure is therefore also
     * sent to the process-wide uncaught-exception handler, which aborts before [start] can wrap it.
     * Suppress that duplicate delivery only while startup is in progress; [start] observes the same
     * failure through Ktor's startup deferred and reports it as [ServerBindException]. Once startup
     * succeeds, an unexpected engine failure keeps Ktor's existing fail-fast behavior.
     */
    @Volatile
    private var startupInProgress: Boolean = true
    private val engineExceptionHandler = CoroutineExceptionHandler { _, cause ->
        if (!startupInProgress) throw cause
    }

    private val server: EmbeddedServer<*, *> =
        CoroutineScope(engineExceptionHandler).embeddedServer(CIO, port = port, host = host) {
            // `this` is the Application (a CoroutineScope): terminal bridges + their reader loops live on it.
            val registry = TerminalRegistry(this, terminalBridgeFactory).also { terminalRegistry = it }
            // Programmatic input: cancel copy-mode first (a wheel scroll by ANY viewer parks the shared
            // pane there, where tmux silently eats every keystroke), then write into the one upstream.
            // BOTH halves answer: `&&` short-circuits, so a pane that would eat the bytes is never written
            // to, and a write that found no upstream (the lazy bridge with zero subscribers — the more
            // common drop of the two) is reported instead of being answered `ok`. The interactive terminal
            // WS deliberately does neither — see `Tmux.leaveCopyMode`.
            val inputSink: TerminalInputSink = { id, bytes ->
                sessionManager.leaveCopyMode(id) && registry.getOrCreate(id.value).write(bytes)
            }
            install(WebSockets)
            routing {
                // Hook ingress, one route per provider: same token, their own header check (Task 12).
                claudeHookRoutes(tokens::current, sessionManager.paneLookup, store, HOOK_JSON)
                codexHookRoutes(tokens::current, sessionManager.paneLookup, store, HOOK_JSON)
                // Login flow: ticket issuance (Bearer + loopback), the open page, the exchange, rotation.
                authRoutes(tokens, tickets, publicUrl, json)
                // Token-gated control plane.
                authenticated(tokens::current, publicUrl) {
                    controlRoutes(sessionManager, store, inputSink, currentVersion, json)
                    directoryCompletionRoutes(directoryCompleter, json)
                    eventsWs(store, json)
                    terminalWs(registry, store, json)
                }
                // Static Web UI at / (open bootstrap — see the auth-layering KDoc).
                staticWebUi(webUiDir)
            }
        }.also {
            // CIO defaults this to false. After serving a browser/WebSocket client, stopping the daemon
            // can leave the old local endpoint in TCP teardown state on macOS; without SO_REUSEADDR an
            // immediate restart then fails with EADDRINUSE even though no process owns the listener.
            it.engineConfig.reuseAddress = true
        }

    /**
     * Start the engine without serving on the caller's thread, returning once the listening socket is
     * actually bound.
     *
     * Binding happens on a CIO engine coroutine. On Native, `start(wait = false)` waits for that startup
     * job, while [io.ktor.server.engine.ApplicationEngine.resolvedConnectors] is the explicit
     * bound-socket contract needed for `port = 0`. Keeping both inside one startup boundary turns any
     * bind failure into [ServerBindException], which the CLI turns into a diagnosis (`Commands.daemon`).
     *
     * With the socket bound, [markOpenFdsCloexec] flags it close-on-exec **before** any `tmux` can be
     * spawned, so it can never be inherited by a `tmux` server that outlives this daemon — the
     * orphaned-listener bug documented on [markOpenFdsCloexec]. The per-spawn sweep in
     * [io.kotgent.tmux.ProcessRunner] covers descriptors opened later.
     */
    fun start(): KotgentServer {
        try {
            server.start(wait = false)
            runBlocking {
                withTimeout(BIND_TIMEOUT_MS) { server.engine.resolvedConnectors() }
            }
        } catch (e: TimeoutCancellationException) {
            throw ServerBindException("timed out after ${BIND_TIMEOUT_MS}ms waiting for the bind", e)
        } catch (e: CancellationException) {
            // CIO reports a failed root server job as JobCancellationException and keeps the actual
            // bind error as its cause. Preserve genuine caller cancellation, but unwrap this startup
            // failure so the CLI receives the promised ServerBindException.
            val cause = e.cause
            if (cause == null || cause === e) throw e
            throw ServerBindException(cause.message ?: cause::class.simpleName ?: "bind failed", cause)
        } catch (e: Throwable) {
            throw ServerBindException(e.message ?: e::class.simpleName ?: "bind failed", e)
        }
        markOpenFdsCloexec()
        startupInProgress = false
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

        /** How long [start] waits for the engine to resolve its connectors before giving up. */
        private const val BIND_TIMEOUT_MS: Long = 10_000

        /**
         * Production wiring: terminal bridges attach the real
         * `tmux -f /dev/null -u -L <socket> attach` upstream with a real `capture-pane -p -e` seed
         * ([terminalBridgeForSession]) over the given [ptyFactory] (default [realPtyFactory] — a real
         * cinterop `Pty`). The web UI directory is resolved to an ABSOLUTE path ([resolveWebUiDir]) so
         * an installed daemon (launchd sets no `WorkingDirectory`, so its cwd is `/`) still serves the
         * SPA instead of 404ing on a cwd-relative default.
         */
        fun production(
            sessionManager: SessionManager,
            store: EventStore,
            tokens: TokenHolder,
            tmux: Tmux,
            currentVersion: String = currentUiVersion(),
            ptyFactory: PtyFactory = realPtyFactory,
            webUiDir: String? = DEFAULT_WEBUI_DIR,
            publicUrl: String? = null,
            host: String = "127.0.0.1",
            port: Int = 0,
        ): KotgentServer = KotgentServer(
            sessionManager = sessionManager,
            store = store,
            tokens = tokens,
            terminalBridgeFactory = { id, scope -> terminalBridgeForSession(tmux, id, scope, ptyFactory) },
            currentVersion = currentVersion,
            webUiDir = webUiDir?.let { resolveWebUiDir(it) },
            publicUrl = publicUrl,
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
 * Thrown by [KotgentServer.start] when the listening socket never came up — most often `EADDRINUSE`
 * because another process holds the port. Carries the engine's own failure as [cause] so the CLI can
 * both report it and add a diagnosis of who holds the port.
 */
class ServerBindException(message: String, cause: Throwable?) :
    RuntimeException("failed to bind the kotgent server: $message", cause)

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
