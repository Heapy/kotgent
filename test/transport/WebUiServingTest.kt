package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.Projection
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.cli.TMUX_SOCKET
import io.kotgent.core.SessionMeta
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.store.EventStore
import io.kotgent.store.PreferencesStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.UiPreferences
import io.kotgent.task.PROJECT_NAME_MAX_LENGTH
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getcwd
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiServingTest {

    private val token = "webui-serving-token-abc123"
    private val currentVersion = "9.8.7+deadbee"

    @Test
    fun daemonServesIndexHtmlAtRoot() = withServer { ctx ->
        val resp = ctx.get("/")
        assertEquals(HttpStatusCode.OK, resp.status, "GET / serves the SPA index")
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent-webui"), "index.html carries the known serving marker")
        assertTrue(body.contains("type=\"module\""), "index.html bootstraps the app as an ES module")
        val rev = revisionOf(body)
        assertTrue(
            body.contains("src=\"/_v/$rev/app.js\""),
            "index.html bootstraps app.js through its content-revisioned URL",
        )
        assertTrue(body.contains("/_v/$rev/vendor/xterm.js"), "index.html loads the vendored xterm.js")
        assertContentTypeContains(resp, "html")
    }

    @Test
    fun daemonServesTheAppEntryModule() = withServer { ctx ->
        val resp = ctx.get("/app.js")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /app.js is served")
        assertContentTypeContains(resp, "javascript")
        assertTrue(resp.bodyAsText().isNotEmpty(), "GET /app.js is not an empty file")
        assertEquals(
            "no-cache",
            resp.headers[HttpHeaders.CacheControl],
            "unprefixed, the entry module revalidates rather than being pinned in a browser cache",
        )
    }

    @Test
    fun theImportMapResolvesToVendoredModulesThatAreActuallyServed() = withServer { ctx ->
        val index = ctx.get("/").bodyAsText()
        assertTrue(index.contains("type=\"importmap\""), "index.html declares an import map")
        val rev = revisionOf(index)

        val mapped = mapOf(
            "preact" to "/_v/$rev/vendor/preact.module.js",
            "preact/hooks" to "/_v/$rev/vendor/preact-hooks.module.js",
            "htm" to "/_v/$rev/vendor/htm.module.js",
            "htm/preact" to "/_v/$rev/vendor/htm-preact.module.js",
            "qrcode" to "/_v/$rev/vendor/qrcode.module.js",
        )
        for ((specifier, path) in mapped) {
            assertTrue(
                index.contains("\"$specifier\": \"$path\""),
                "the import map wires '$specifier' to $path",
            )
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path (import-map target) is served")
            assertContentTypeContains(resp, "javascript")
            assertTrue(resp.bodyAsText().isNotEmpty(), "$path is not empty")
        }

        val htmPreact = ctx.get("/_v/$rev/vendor/htm-preact.module.js").bodyAsText()
        assertTrue(htmPreact.contains("\"preact\""), "htm/preact imports the bare 'preact' specifier")
        assertTrue(htmPreact.contains("\"htm\""), "htm/preact imports the bare 'htm' specifier")
        assertTrue(
            ctx.get("/_v/$rev/vendor/preact-hooks.module.js").bodyAsText().contains("\"preact\""),
            "the hooks build imports the bare 'preact' specifier",
        )
    }

    @Test
    fun daemonServesTheComponentAndLibModules() = withServer { ctx ->
        for (path in listOf(
            "/lib/paths.js", "/lib/prefs.js", "/lib/api.js", "/lib/sessions.js", "/lib/qr.js",
            "/lib/notify.js", "/lib/push.js", "/lib/agents.js", "/lib/commands.js",
            "/lib/clipboard.js", "/lib/unicode.js",
            "/lib/router.js", "/lib/tasks.js",
            "/components/Sidebar.js", "/components/TerminalPane.js", "/components/KeyBar.js",
            "/components/dialogs.js", "/components/CommandPalette.js",
            "/components/Board.js", "/components/TaskCard.js", "/components/TaskDetail.js",
        )) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path (nested module) is served")
            assertContentTypeContains(resp, "javascript")
            assertTrue(resp.bodyAsText().isNotEmpty(), "GET $path is not an empty file")
        }
        assertTrue(
            ctx.get("/lib/paths.js").bodyAsText().contains("export function groupSessions"),
            "the grouping helpers are exported for the sidebar (and for out-of-browser checks)",
        )
        assertTrue(
            ctx.get("/lib/prefs.js").bodyAsText().contains("export function loadPrefs"),
            "the stored preferences are exported",
        )
        val sessionHelpers = ctx.get("/lib/sessions.js").bodyAsText()
        assertTrue(
            sessionHelpers.contains("export function upsertIfNewer") &&
                sessionHelpers.contains("export function patchIfNewer"),
            "the newest-rev-wins appliers are exported under the names app.js imports",
        )
    }

    @Test
    fun daemonServesTheWebManifestWithItsOwnMediaType() = withServer { ctx ->
        val resp = ctx.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /manifest.webmanifest is served")
        assertContentTypeContains(resp, "application/manifest+json")

        val manifest = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("Kotgent", manifest["name"]?.jsonPrimitive?.content, "manifest name")
        assertEquals("Kotgent", manifest["short_name"]?.jsonPrimitive?.content, "manifest short_name")
        assertEquals("/", manifest["start_url"]?.jsonPrimitive?.content, "start_url is the app root")
        assertEquals("/", manifest["scope"]?.jsonPrimitive?.content, "scope covers the whole origin")
        assertEquals(
            "standalone",
            manifest["display"]?.jsonPrimitive?.content,
            "display: standalone is what makes the home-screen launch chrome-less (and push-capable on iOS)",
        )
        for (key in listOf("background_color", "theme_color")) {
            val colour = manifest[key]?.jsonPrimitive?.content
            assertTrue(colour != null && colour.startsWith("#"), "$key is a concrete colour, was $colour")
        }

        val icons = manifest["icons"]?.jsonArray.orEmpty()
        assertEquals(2, icons.size, "the manifest declares the 192 and 512 icons")
        val declaredSizes = mutableSetOf<String>()
        for (icon in icons) {
            val obj = icon.jsonObject
            val src = obj["src"]!!.jsonPrimitive.content
            val sizes = obj["sizes"]!!.jsonPrimitive.content
            declaredSizes += sizes
            assertEquals("image/png", obj["type"]?.jsonPrimitive?.content, "$src declares its type")
            assertEquals(
                "any maskable",
                obj["purpose"]?.jsonPrimitive?.content,
                "$src is usable both as-is and under an Android mask",
            )
            assertTrue(src.startsWith("/"), "$src is scope-absolute so it resolves from any start URL")
            val iconResp = ctx.get(src)
            assertEquals(HttpStatusCode.OK, iconResp.status, "GET $src (manifest icon) is served")
            assertContentTypeContains(iconResp, "image/png")
            assertPngOfSize(iconResp.readRawBytes(), sizes.substringBefore('x').toInt(), src)
        }
        assertEquals(setOf("192x192", "512x512"), declaredSizes, "the two required install sizes")
    }

    @Test
    fun daemonServesTheAppleTouchIconAndTheSourceArtwork() = withServer { ctx ->
        val apple = ctx.get("/icons/apple-touch-icon.png")
        assertEquals(HttpStatusCode.OK, apple.status, "GET /icons/apple-touch-icon.png is served")
        assertContentTypeContains(apple, "image/png")
        assertPngOfSize(apple.readRawBytes(), 180, "/icons/apple-touch-icon.png")

        val svg = ctx.get("/icons/logo.svg")
        assertEquals(HttpStatusCode.OK, svg.status, "GET /icons/logo.svg (the icon source) is served")
        assertContentTypeContains(svg, "svg")
        assertTrue(svg.bodyAsText().contains("<svg"), "the source artwork is really an SVG")
    }

    @Test
    fun indexHtmlDeclaresThePwaInstallSurface() = withServer { ctx ->
        val body = ctx.get("/").bodyAsText()
        assertTrue(body.contains("rel=\"manifest\""), "index.html links the web manifest")
        assertTrue(body.contains("manifest.webmanifest"), "index.html links THIS manifest file")
        assertTrue(body.contains("rel=\"apple-touch-icon\""), "index.html declares the iOS home-screen icon")
        assertTrue(
            body.contains("name=\"apple-mobile-web-app-capable\"") && body.contains("content=\"yes\""),
            "iOS only treats the home-screen launch as a standalone app (and allows push) with this tag",
        )
        assertTrue(
            body.contains("apple-mobile-web-app-status-bar-style"),
            "index.html picks an iOS status-bar style rather than inheriting Safari's",
        )
        assertTrue(
            body.contains("viewport-fit=cover"),
            "the viewport reaches under the notch — the safe-area padding depends on it",
        )
        val rev = revisionOf(body)
        assertTrue(
            body.contains("name=\"color-scheme\" content=\"dark\"") &&
                body.contains("href=\"/_v/$rev/style.css\"") &&
                body.contains("src=\"/_v/$rev/app.js\""),
            "the installed iOS app declares dark system UI and fetches content-revisioned assets",
        )
        assertTrue(
            body.contains("href=\"/manifest.webmanifest\"") &&
                body.contains("href=\"/icons/apple-touch-icon.png\"") &&
                body.contains("href=\"/icons/logo.svg\""),
            "the install surface stays on stable, root-absolute URLs the installed app can keep referring to",
        )
    }

    @Test
    fun revisionedAssetsAreImmutableAndEverythingElseRevalidates() = withServer { ctx ->
        val rev = revisionOf(ctx.get("/").bodyAsText())
        for (path in listOf(
            "/_v/$rev/app.js", "/_v/$rev/style.css",
            "/_v/$rev/lib/api.js", "/_v/$rev/vendor/xterm.js",
        )) {
            assertEquals(
                IMMUTABLE_CACHE_CONTROL,
                ctx.get(path).headers[HttpHeaders.CacheControl],
                "GET $path is content-addressed, so it never has to be fetched twice",
            )
        }
        for (path in listOf(
            "/", "/index.html", "/sw.js", "/manifest.webmanifest", "/icons/icon-192.png",
            "/app.js", "/style.css",
        )) {
            assertEquals(
                "no-cache",
                ctx.get(path).headers[HttpHeaders.CacheControl],
                "GET $path revalidates so a deploy is never pinned behind a cached copy",
            )
        }
        for (path in listOf("/_v/$rev/index.html", "/_v/$rev/sw.js")) {
            assertEquals(
                "no-cache",
                ctx.get(path).headers[HttpHeaders.CacheControl],
                "GET $path revalidates even through the revision prefix",
            )
        }
    }

    @Test
    fun theServedShellCarriesARealRevisionAndNoHandBumpedToken() = withServer { ctx ->
        val index = ctx.get("/").bodyAsText()
        assertFalse(index.contains(WEBUI_REV_PLACEHOLDER), "the daemon substituted the revision placeholder")
        assertFalse(index.contains("?v="), "no asset is fetched with a hand-bumped cache-busting query")
        assertTrue(isRevToken(revisionOf(index)), "the substituted revision is a real content hash")

        assertFalse(ctx.get("/app.js").bodyAsText().contains("?v="), "app.js imports carry no query version")
    }

    @Test
    fun theRevisionPrefixOnlyChangesTheAddress() = withServer { ctx ->
        val rev = revisionOf(ctx.get("/").bodyAsText())
        assertEquals(
            ctx.get("/app.js").bodyAsText(),
            ctx.get("/_v/$rev/app.js").bodyAsText(),
            "the revisioned URL serves the very same module",
        )
        val stale = ctx.get("/_v/0123456789ab/app.js")
        assertEquals(HttpStatusCode.OK, stale.status, "an older revision's URL still serves its asset")

        val bogus = ctx.get("/_v/${WEBUI_REV_PLACEHOLDER}/app.js")
        assertEquals(HttpStatusCode.OK, bogus.status, "a malformed revision still serves the asset")
        assertEquals(
            "no-cache",
            bogus.headers[HttpHeaders.CacheControl],
            "a revision this server never minted must revalidate, not pin the asset forever",
        )
    }

    @Test
    fun strippingTheRevisionPrefixLeavesTraversalVisibleToTheGuard() {
        val (rev, path) = stripRevPrefix("_v/0123456789ab/../../etc/passwd")
        assertEquals("0123456789ab", rev, "the prefix is recognised")
        assertTrue(path.contains(".."), "the traversal stays in the path the guard inspects")

        assertEquals(null to "app.js", stripRevPrefix("app.js"), "an unprefixed path is untouched")
        assertEquals(null to "_v/app.js", stripRevPrefix("_v/app.js"), "a prefix with no revision is untouched")
        assertEquals(null to "_v/abc/", stripRevPrefix("_v/abc/"), "a prefix naming no file is untouched")
        assertEquals("abc" to "lib/api.js", stripRevPrefix("_v/abc/lib/api.js"), "a nested path keeps its shape")

        assertFalse(isRevToken("__REV__"), "the placeholder is not a revision")
        assertFalse(isRevToken("0123456789AB"), "a revision is lowercase hex")
        assertFalse(isRevToken("0123456789abc"), "a revision is exactly $WEBUI_REV_LENGTH characters")
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun anyChangedByteChangesTheRevision() {
        val dir = makeTempDir()
        try {
            writeFile("$dir/index.html", "<html>$WEBUI_REV_PLACEHOLDER</html>")
            assertEquals(0, mkdir("$dir/lib", MODE_0700.convert()), "could not create the nested directory")
            writeFile("$dir/lib/api.js", "export const a = 1;\n")

            val before = webUiRevision(dir)
            assertTrue(isRevToken(before), "a revision is a $WEBUI_REV_LENGTH-character lowercase hex token")
            assertEquals(before, webUiRevision(dir), "an unchanged tree keeps its revision")

            writeFile("$dir/lib/api.js", "export const a = 2;\n")
            val afterEdit = webUiRevision(dir)
            assertTrue(afterEdit != before, "editing a nested module changes the revision")

            writeFile("$dir/lib/renamed.js", "export const a = 2;\n")
            unlink("$dir/lib/api.js")
            assertTrue(webUiRevision(dir) != afterEdit, "renaming a module changes the revision")
        } finally {
            unlink("$dir/index.html")
            unlink("$dir/lib/api.js")
            unlink("$dir/lib/renamed.js")
            rmdir("$dir/lib")
            rmdir(dir)
        }
    }

    @Test
    fun daemonServesTheServiceWorkerAtTheRootScope() = withServer { ctx ->
        val resp = ctx.get("/sw.js")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /sw.js is served from the root scope")
        assertContentTypeContains(resp, "javascript")
        assertEquals(
            "no-cache",
            resp.headers[HttpHeaders.CacheControl],
            "the worker script revalidates so a deploy is not pinned behind a cached push handler",
        )
        val body = resp.bodyAsText()
        assertTrue(
            body.lineSequence().none { it.trimStart().startsWith("import ") },
            "the classic worker imports nothing — a bare specifier throws at parse time and takes the " +
                "whole push path down with it",
        )
    }

    @Test
    fun theBrowserPushModuleCallsTheDaemonsOwnPushRoutes() = withServer { ctx ->
        val push = ctx.get("/lib/push.js")
        assertEquals(HttpStatusCode.OK, push.status, "GET /lib/push.js is served")
        assertContentTypeContains(push, "javascript")
        val body = push.bodyAsText()
        for (route in listOf(PUSH_VAPID_KEY_PATH, PUSH_SUBSCRIBE_PATH, PUSH_UNSUBSCRIBE_PATH)) {
            assertTrue(body.contains("\"$route\""), "the page calls the daemon's $route route")
        }
        assertTrue(
            body.contains("\"/sw.js\""),
            "it registers the worker by the root path its scope depends on",
        )
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
    fun theUnicodeAddonsAreVendoredAndServed() = withServer { ctx ->
        for (path in listOf(
            "/vendor/addon-unicode11.module.js",
            "/vendor/addon-unicode-graphemes.module.js",
        )) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path (the vendored addon) is served")
            assertContentTypeContains(resp, "javascript")
            assertTrue(resp.bodyAsText().length > 20_000, "$path carries the real width tables")
        }
        assertTrue(
            ctx.get("/vendor/addon-unicode11.module.js").bodyAsText().contains("as Unicode11Addon"),
            "the vendored ESM build exports the name lib/unicode.js constructs",
        )
        assertTrue(
            ctx.get("/vendor/addon-unicode-graphemes.module.js").bodyAsText()
                .contains("as UnicodeGraphemesAddon"),
            "the vendored graphemes ESM build exports the name lib/unicode.js constructs",
        )
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
        val resp = ctx.get("$API_PREFIX/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "the literal API route outranks the static catch-all")
        assertEquals("[]", resp.bodyAsText().trim(), "the API (empty session list), not a static file, answered")

        val bare = ctx.get("/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, bare.status, "the bare /sessions now falls through to the SPA route")
        assertEquals("not found", bare.bodyAsText().trim(), "the static catch-all answered it, not the API")
    }

    @Test
    fun versionApiIsAuthenticatedAndOutranksTheStaticCatchAll() = withServer { ctx ->
        assertEquals(HttpStatusCode.Unauthorized, ctx.get("$API_PREFIX/version").status)

        val resp = ctx.get("$API_PREFIX/version") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "the literal API route outranks the static catch-all")
        assertContentTypeContains(resp, "json")
        val body = resp.bodyAsText()
        assertEquals("""{"version":"$currentVersion"}""", body, "the server returns the injected display version")
        assertEquals(
            VersionDto(currentVersion),
            TRANSPORT_JSON.decodeFromString(VersionDto.serializer(), body),
            "the response is the public VersionDto wire shape",
        )

        assertEquals(HttpStatusCode.NotFound, ctx.get("/version").status, "the bare path is the SPA's, and 404s")
    }


    @Test
    fun theCopyableTmuxCommandNamesTheDaemonsOwnSocketInUtf8() = withServer { ctx ->
        val sessions = ctx.get("/lib/sessions.js").bodyAsText()
        assertTrue(
            sessions.contains("\"tmux -u -L $TMUX_SOCKET attach -t \""),
            "lib/sessions.js builds `tmux -u -L $TMUX_SOCKET attach -t <name>`; the socket label is the " +
                "daemon's own and `-u` is what keeps the pane's non-ASCII cells from becoming underscores",
        )
    }

    @Test
    fun theServiceWorkerHandWritesTheSameApiPrefixTheModuleDeclares() = withServer { ctx ->
        val api = ctx.get("/lib/api.js").bodyAsText()
        val worker = ctx.get("/sw.js").bodyAsText()
        assertTrue(
            api.contains("const API_PREFIX = \"$API_PREFIX\";"),
            "lib/api.js names the prefix once, and it is the daemon's own $API_PREFIX",
        )
        for (url in listOf("/sessions", "/push/subscribe", "/push/unsubscribe")) {
            assertTrue(
                worker.contains("\"$API_PREFIX$url\""),
                "the service worker still spells `$API_PREFIX$url`; the two spellings must agree",
            )
        }
    }

    @Test
    fun theDeepLinkParameterIsTheOneTheServiceWorkerBuilds() = withServer { ctx ->
        val router = ctx.get("/lib/router.js").bodyAsText()
        val worker = ctx.get("/sw.js").bodyAsText()
        assertTrue(
            router.contains("""export const DEEP_LINK_PARAM = "session";"""),
            "the router names the deep-link parameter once",
        )
        assertTrue(
            worker.contains("""openWindow(sessionId ? "/?session=" + encodeURIComponent(sessionId) : "/")"""),
            "the service worker still opens `/?session=<id>`; the two spellings must agree",
        )
    }

    @Test
    fun theAppReachesHistoryOnlyThroughTheRouter() = withServer { ctx ->
        val app = ctx.get("/app.js").bodyAsText()
        assertFalse(app.contains("pushState"), "no screen change is hand-rolled outside the router")
    }

    @Test
    fun theBoardComponentsEmitOnlyTheSharedVocabularyAndTheStylesheetDressesAllOfIt() = withServer { ctx ->
        val sources = mapOf(
            "Board.js" to ctx.get("/components/Board.js").bodyAsText(),
            "TaskCard.js" to ctx.get("/components/TaskCard.js").bodyAsText(),
            "TaskDetail.js" to ctx.get("/components/TaskDetail.js").bodyAsText(),
        )
        val emitted = mutableSetOf<String>()
        for ((name, source) in sources) {
            val tokens = vocabularyTokensIn(source)
            emitted += tokens
            for (className in tokens) {
                assertTrue(
                    BOARD_VOCABULARY.contains(className),
                    "$name emits '$className', which is not in the plan's Board CSS vocabulary — " +
                        "style.css is written from that list and would never style it",
                )
            }
        }
        for (owned in BOARD_OWNED_CLASSES) {
            assertTrue(
                emitted.contains(owned),
                "the board emits the '$owned' class the stylesheet is dressing",
            )
        }
        val css = ctx.get("/style.css").bodyAsText()
        for (className in BOARD_VOCABULARY) {
            assertTrue(
                Regex("\\.${Regex.escape(className)}(?![-\\w])").containsMatchIn(css),
                "style.css carries no rule for '$className' — a vocabulary word nothing draws",
            )
        }
    }

    @Test
    fun theProjectNameFieldAcceptsEveryNameTheApiDoes() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        assertTrue(
            board.contains("const PROJECT_NAME_MAX_LENGTH = $PROJECT_NAME_MAX_LENGTH;"),
            "the board's cap is io.kotgent.task.PROJECT_NAME_MAX_LENGTH ($PROJECT_NAME_MAX_LENGTH), " +
                "spelled once",
        )
        assertTrue(
            board.contains("maxlength=\${PROJECT_NAME_MAX_LENGTH}"),
            "and the field is bound to that constant rather than to a second literal beside it",
        )
        assertFalse(
            board.contains("maxlength=\"80\""),
            "the old hard-coded 80 is gone — it was 20 characters short of what the daemon accepts",
        )
    }


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
                VendorStoreProbe { _, _, _ -> false },
                VendorSessionLocator { _, _ -> null },
                setOf("claude", "codex"),
                now = { 1L },
            )
            val server = KotgentServer(
                sessionManager = manager,
                store = store,
                preferencesStore = store,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in the serving test") },
                currentVersion = currentVersion,
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

    private fun revisionOf(index: String): String {
        val marker = "src=\"/_v/"
        val at = index.indexOf(marker)
        assertTrue(at >= 0, "index.html fetches its assets through the revision prefix")
        val rest = index.substring(at + marker.length)
        val end = rest.indexOf('/')
        assertTrue(end > 0, "the revision prefix names a path underneath it")
        return rest.substring(0, end)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun makeTempDir(): String = memScoped {
        val template = "/tmp/kotgent-webui-rev-test-XXXXXX"
        val encoded = template.encodeToByteArray()
        val chars = allocArray<ByteVar>(encoded.size + 1)
        encoded.forEachIndexed { index, byte -> chars[index] = byte }
        chars[encoded.size] = 0
        mkdtemp(chars)?.toKString() ?: error("could not create the revision test directory")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
    }

    private fun assertPngOfSize(bytes: ByteArray, size: Int, what: String) {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertTrue(bytes.size > 24, "$what is too short to be a PNG (${bytes.size} bytes)")
        assertTrue(
            bytes.copyOfRange(0, 8).contentEquals(signature),
            "$what does not start with the PNG signature",
        )
        assertEquals("IHDR", bytes.copyOfRange(12, 16).decodeToString(), "$what has no IHDR first chunk")
        fun beInt(at: Int): Int = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xFF) }
        assertEquals(size, beInt(16), "$what pixel width")
        assertEquals(size, beInt(20), "$what pixel height")
    }

    private fun vocabularyTokensIn(source: String): Set<String> {
        val found = mutableSetOf<String>()
        for (value in classAttributeValues(source)) {
            for (token in CLASS_TOKEN.findAll(value).map { it.value }) {
                if (token == "board" || token.startsWith("board-") || token.startsWith("task-")) {
                    found.add(token)
                }
            }
        }
        return found
    }

    private fun classAttributeValues(source: String): List<String> {
        val values = mutableListOf<String>()
        var at = source.indexOf(CLASS_ATTRIBUTE)
        while (at >= 0) {
            val start = at + CLASS_ATTRIBUTE.length
            when {
                start >= source.length -> Unit
                source[start] == '"' -> values += readQuotedValue(source, start + 1)
                source.startsWith("\${", start) -> values += readInterpolation(source, start + 2)
            }
            at = source.indexOf(CLASS_ATTRIBUTE, at + 1)
        }
        return values
    }

    private fun readQuotedValue(source: String, from: Int): String {
        var depth = 0
        var at = from
        while (at < source.length) {
            when {
                source.startsWith("\${", at) -> { depth++; at += 2 }
                source[at] == '}' && depth > 0 -> { depth--; at++ }
                depth == 0 && source[at] == '"' -> return source.substring(from, at)
                else -> at++
            }
        }
        return source.substring(from)
    }

    private fun readInterpolation(source: String, from: Int): String {
        var depth = 1
        var at = from
        while (at < source.length) {
            when (source[at]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(from, at)
                }
            }
            at++
        }
        return source.substring(from)
    }

    private class NoopEventStore : EventStore, PreferencesStore {
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow<SessionUpdate>()
        private val preferenceState = MutableStateFlow(UiPreferences("", 1, 0))
        override val preferences: StateFlow<UiPreferences> get() = preferenceState
        override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
            UiPreferences(basePath, groupingLevel, preferenceState.value.revision + 1).also {
                preferenceState.value = it
            }
        override suspend fun upsertSession(meta: SessionMeta) {}
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: io.kotgent.core.SessionState,
            stateSource: EventSource,
            paneId: io.kotgent.core.PaneId?,
            updatedAt: Long,
        ) {}
        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {}
        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) {}
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: io.kotgent.core.ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = false
        override suspend fun markRead(sessionId: SessionId, seq: Seq) {}
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun listSessions(): List<SessionMeta> = emptyList()
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = Seq(0L)
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())
    buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean = access(path, F_OK) == 0

private const val MODE_0700: Int = 0b111_000_000

private const val CLASS_ATTRIBUTE: String = "class="

private val CLASS_TOKEN = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")

private val BOARD_VOCABULARY: Set<String> = setOf(
    "board", "board-head", "board-identity", "board-project", "board-project-path",
    "board-new-task",
    "board-columns", "board-column", "board-column-head", "board-column-switch",
    "board-show-all-done", "board-drop-target",
    "task-card", "task-card-handle", "task-card-title", "task-card-meta",
    "task-blocked", "task-dep-count", "task-sessions", "task-session-dot", "task-card-menu",
    "task-detail", "task-detail-head", "task-detail-body", "task-deps",
    "task-activity", "task-activity-row",
    "task-badge", "task-badge-unknown",
)

private val BOARD_BADGE_CLASSES: Set<String> = setOf("task-badge", "task-badge-unknown")

private val BOARD_OWNED_CLASSES: Set<String> = BOARD_VOCABULARY - BOARD_BADGE_CLASSES

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
