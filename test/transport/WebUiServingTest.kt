package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.Projection
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Web UI serving tests (plan Task 17) — proof that the assembled [KotgentServer] actually serves the
 * static SPA from `resources/webui`: `GET /` returns `index.html`, and the vendored xterm.js / `app.js`
 * / CSS are reachable with sensible content-types.
 *
 * ## What this covers (and what it can't)
 * The browser JS itself (token parse, live updates, xterm.js terminal) cannot run in the macosArm64 test
 * binary, so it is verified MANUALLY in Task 18. What IS automatable — and what these tests lock down —
 * is the **serving path**: the real files exist, the daemon serves them with 200s and correct
 * content-types (including nested `/vendor/` paths), the static catch-all is mounted UNauthenticated (the
 * browser must fetch the bootstrap before it has the token), and it does NOT shadow the token-gated API
 * routes. This is the same real [KotgentServer] the daemon runs, wired to host-free fakes.
 */
class WebUiServingTest {

    private val token = "webui-serving-token-abc123"

    @Test
    fun daemonServesIndexHtmlAtRoot() = withServer { ctx ->
        val resp = ctx.get("/")
        assertEquals(HttpStatusCode.OK, resp.status, "GET / serves the SPA index")
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent-webui"), "index.html carries the known serving marker")
        assertTrue(body.contains("type=\"module\""), "index.html bootstraps the app as an ES module")
        assertTrue(body.contains("src=\"app.js\""), "index.html bootstraps app.js")
        assertTrue(body.contains("vendor/xterm.js"), "index.html loads the vendored xterm.js")
        assertContentTypeContains(resp, "html")
    }

    @Test
    fun daemonServesTheAppEntryModule() = withServer { ctx ->
        val resp = ctx.get("/app.js")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /app.js is served")
        val body = resp.bodyAsText()
        assertTrue(body.contains("from \"preact\""), "the entry module imports the vendored Preact")
        assertTrue(body.contains("from \"htm/preact\""), "the entry module imports htm's Preact binding")
        assertTrue(body.contains("session_update"), "app.js handles the live session_update messages")
        assertTrue(body.contains("startSession"), "app.js can create sessions")
        assertTrue(body.contains("controlSession"), "app.js can run lifecycle controls")
        assertContentTypeContains(resp, "javascript")
    }

    /**
     * The no-build-step contract: bare specifiers (`preact`, `htm/preact`) are resolved by the browser
     * through the import map in `index.html`, so a typo there — or a vendored file that was never
     * committed — breaks the whole page with nothing to catch it. Assert every mapped target is really
     * served, and that the modules' own bare imports are covered by the map.
     */
    @Test
    fun theImportMapResolvesToVendoredModulesThatAreActuallyServed() = withServer { ctx ->
        val index = ctx.get("/").bodyAsText()
        assertTrue(index.contains("type=\"importmap\""), "index.html declares an import map")

        val mapped = mapOf(
            "preact" to "/vendor/preact.module.js",
            "preact/hooks" to "/vendor/preact-hooks.module.js",
            "htm" to "/vendor/htm.module.js",
            "htm/preact" to "/vendor/htm-preact.module.js",
            "qrcode" to "/vendor/qrcode.module.js",
        )
        for ((specifier, path) in mapped) {
            assertTrue(
                index.contains("\"$specifier\"") && index.contains(path.removePrefix("/")),
                "the import map wires '$specifier' to $path",
            )
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path (import-map target) is served")
            assertContentTypeContains(resp, "javascript")
            assertTrue(resp.bodyAsText().isNotEmpty(), "$path is not empty")
        }

        // htm's Preact binding re-exports from both bare specifiers; the hooks build imports 'preact'.
        val htmPreact = ctx.get("/vendor/htm-preact.module.js").bodyAsText()
        assertTrue(htmPreact.contains("\"preact\""), "htm/preact imports the bare 'preact' specifier")
        assertTrue(htmPreact.contains("\"htm\""), "htm/preact imports the bare 'htm' specifier")
        assertTrue(
            ctx.get("/vendor/preact-hooks.module.js").bodyAsText().contains("\"preact\""),
            "the hooks build imports the bare 'preact' specifier",
        )
    }

    @Test
    fun daemonServesTheComponentAndLibModules() = withServer { ctx ->
        for (path in listOf(
            "/lib/paths.js", "/lib/prefs.js", "/lib/api.js", "/lib/sessions.js", "/lib/qr.js",
            "/components/Sidebar.js", "/components/TerminalPane.js", "/components/dialogs.js",
        )) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path (nested module) is served")
            assertContentTypeContains(resp, "javascript")
        }
        assertTrue(
            ctx.get("/lib/paths.js").bodyAsText().contains("export function groupSessions"),
            "the grouping helpers are exported for the sidebar (and for out-of-browser checks)",
        )
        assertTrue(
            ctx.get("/lib/prefs.js").bodyAsText().contains("export function loadPrefs"),
            "the stored preferences are exported",
        )
    }

    @Test
    fun webUiExposesSessionCreationAndLifecycleControls() = withServer { ctx ->
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        assertTrue(pane.contains("id=\"attach-button\""), "the UI includes attach")
        assertTrue(pane.contains("id=\"interrupt-button\""), "the UI includes interrupt")
        assertTrue(pane.contains("id=\"resume-button\""), "the UI includes resume")
        assertTrue(pane.contains("id=\"detach-button\""), "the UI includes detach")
        assertTrue(pane.contains("id=\"stop-button\""), "the UI includes stop")
        assertTrue(
            ctx.get("/components/dialogs.js").bodyAsText().contains("id=\"new-session-form\""),
            "the UI includes the new-session form",
        )
    }

    @Test
    fun webUiExposesThePreferencesScreen() = withServer { ctx ->
        assertTrue(
            ctx.get("/components/Sidebar.js").bodyAsText().contains("id=\"prefs-button\""),
            "the sidebar has the preferences (gear) entry point",
        )
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(dialogs.contains("id=\"prefs-dialog\""), "the UI includes the preferences screen")
        assertTrue(dialogs.contains("id=\"prefs-base-path\""), "preferences expose the base path")
        assertTrue(dialogs.contains("id=\"prefs-grouping-level\""), "preferences expose the grouping level")
    }

    @Test
    fun webUiExposesTheHelpScreen() = withServer { ctx ->
        assertTrue(
            ctx.get("/components/Sidebar.js").bodyAsText().contains("id=\"help-button\""),
            "the sidebar has the help entry point",
        )
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(dialogs.contains("id=\"help-dialog\""), "the UI includes the help screen")
        // The help text explains the controls and states it documents — a rename that leaves the help
        // stale should fail here rather than quietly ship a wrong explanation.
        for (control in listOf("New session", "Attach", "Interrupt", "Resume", "Detach", "Stop")) {
            assertTrue(dialogs.contains("[\"$control\","), "help documents the $control control")
        }
        for (state in listOf("running", "ready", "needs approval", "needs answer",
                             "stopped", "crashed", "resumable")) {
            assertTrue(dialogs.contains("[\"$state\","), "help documents the '$state' state")
        }
    }

    @Test
    fun webUiExposesThePhoneAccessScreen() = withServer { ctx ->
        assertTrue(
            ctx.get("/components/Sidebar.js").bodyAsText().contains("id=\"phone-button\""),
            "the sidebar has the phone (QR) entry point next to help and preferences",
        )
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(dialogs.contains("id=\"phone-dialog\""), "the UI includes the phone sign-in screen")
        assertTrue(dialogs.contains("/auth/ticket"), "the phone dialog mints a one-time ticket")
        assertTrue(dialogs.contains("qrSvg"), "the phone dialog renders the ticket URL as a QR code")

        // The QR is drawn by the vendored generator through the `lib/qr.js` wrapper — prove both are
        // served and wired, since neither can run in this test binary.
        val qr = ctx.get("/lib/qr.js")
        assertEquals(HttpStatusCode.OK, qr.status, "GET /lib/qr.js (the SVG wrapper) is served")
        assertContentTypeContains(qr, "javascript")
        assertTrue(qr.bodyAsText().contains("export function qrSvg"), "the wrapper exports qrSvg")

        val gen = ctx.get("/vendor/qrcode.module.js")
        assertEquals(HttpStatusCode.OK, gen.status, "GET /vendor/qrcode.module.js is served")
        assertContentTypeContains(gen, "javascript")
        assertTrue(gen.bodyAsText().contains("export class QrCode"), "the vendored generator exports QrCode")
    }

    @Test
    fun daemonServesTheVendoredXtermFromANestedPath() = withServer { ctx ->
        val resp = ctx.get("/vendor/xterm.js")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /vendor/xterm.js (nested) is served")
        val body = resp.bodyAsText()
        assertTrue(body.length > 50_000, "the real vendored xterm.js bundle is substantial, was ${body.length} bytes")
        assertTrue(body.contains("Terminal"), "the vendored bundle exposes the Terminal API")
        assertContentTypeContains(resp, "javascript")
    }

    @Test
    fun daemonServesTheStylesheets() = withServer { ctx ->
        val appCss = ctx.get("/style.css")
        assertEquals(HttpStatusCode.OK, appCss.status, "GET /style.css is served")
        assertContentTypeContains(appCss, "css")

        val xtermCss = ctx.get("/vendor/xterm.css")
        assertEquals(HttpStatusCode.OK, xtermCss.status, "GET /vendor/xterm.css (nested) is served")
        assertContentTypeContains(xtermCss, "css")
    }

    @Test
    fun aMissingStaticFileIs404() = withServer { ctx ->
        assertEquals(
            HttpStatusCode.NotFound,
            ctx.get("/vendor/does-not-exist.js").status,
            "an unknown static path is a clean 404, not a crash",
        )
    }

    @Test
    fun theStaticCatchAllDoesNotShadowTheTokenGatedApi() = withServer { ctx ->
        // With the static catch-all mounted, an authenticated GET /sessions must still route to the API
        // (200 + JSON list), NOT be swallowed by the `/{path...}` file route (which would 404 "sessions").
        val resp = ctx.get("/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "the literal /sessions API route outranks the static catch-all")
        assertEquals("[]", resp.bodyAsText().trim(), "the API (empty session list), not a static file, answered")
    }

    // --- harness -------------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
            client.get("http://127.0.0.1:$port$path", block)
    }

    private fun withServer(block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(40_000) {
            val store = NoopEventStore()
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                FakeTmux(),
                store,
                PaneRegistry(),
                AgentFactory { _, cwd ->
                    object : AgentAdapter {
                        override val events: Flow<AgentEvent> = emptyFlow()
                        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                            LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                    }
                },
                ProviderIdCapture(store, idScope),
                now = { 1L },
            )
            val server = KotgentServer(
                sessionManager = manager,
                store = store,
                tokens = TokenHolder(token),
                // Never invoked in a serving test (no terminal WS connects); throwing makes that explicit.
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in the serving test") },
                webUiDir = locateWebUiDir(),
                port = 0,
            ).start()
            val client = HttpClient(CIO)
            try {
                block(Ctx(server.port(), client))
            } finally {
                client.close()
                server.stop()
                idScope.cancel()
            }
        }
    }

    private suspend fun assertContentTypeContains(resp: HttpResponse, needle: String) {
        val ct = resp.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(ct.contains(needle, ignoreCase = true), "content-type '$ct' should mention '$needle'")
    }

    /**
     * A no-op [EventStore] good enough to construct the server: the static-serving path never touches it,
     * and the one API call the shadowing test makes ([listSessions]) returns an empty list. Everything
     * else is unused, so it returns empties / throws.
     */
    private class NoopEventStore : EventStore {
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow<SessionUpdate>()
        override suspend fun upsertSession(meta: SessionMeta) {}
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: io.kotgent.core.SessionState,
            stateSource: EventSource,
            paneId: io.kotgent.core.PaneId?,
            updatedAt: Long,
        ) {}
        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {}
        override suspend fun setModel(sessionId: SessionId, model: String, updatedAt: Long) {}
        override suspend fun markRead(sessionId: SessionId, seq: Seq) {}
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun listSessions(): List<SessionMeta> = emptyList()
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = Seq(0L)
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
    }
}

/** The current working directory (via `getcwd`), for locating `resources/webui` at test time. */
@OptIn(ExperimentalForeignApi::class)
private fun currentDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())
    buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean = access(path, F_OK) == 0

/**
 * Locate the `resources/webui` directory robustly: `./kotlin test` runs from the module root (so the
 * relative default resolves), but we also walk up from the cwd looking for `resources/webui/index.html`
 * so the test is not fragile to where the runner starts.
 */
private fun locateWebUiDir(): String {
    var dir = currentDir()
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (fileExists("$candidate/index.html")) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}
