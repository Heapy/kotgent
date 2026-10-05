package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchOptions
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.isDirectory
import io.kotgent.daemon.listDir
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePreferencesStore
import io.ktor.client.HttpClient
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebUiServingTest {

    private val token = "webui-serving-token-abc123"
    private val currentVersion = "9.8.7+deadbee"

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun missingBuildOutputExplainsHowToBuildTheWebUi() {
        val dir = makeTempDir()
        val locators = listOf(::locateWebUiDir, ::locateSpaWebUiDir)
        fun assertMissingBuild() {
            for (locate in locators) {
                val failure = assertFailsWith<IllegalStateException> { locate(dir) }
                assertEquals(
                    "missing Web UI build at $dir; run npm ci and npm run build in webui/",
                    failure.message,
                )
            }
        }
        try {
            writeFile("$dir/project.yaml", "modules: []\n")
            assertMissingBuild()
            for (path in listOf("resources", "resources/webui")) {
                assertEquals(0, mkdir("$dir/$path", MODE_0700.convert()))
            }
            assertMissingBuild()
            writeFile("$dir/resources/webui/index.html", "<!doctype html>")
            assertMissingBuild()
            assertEquals(0, mkdir("$dir/resources/webui/assets", MODE_0700.convert()))
            for (locate in locators) assertEquals("$dir/resources/webui", locate(dir))
            assertEquals(0, unlink("$dir/resources/webui/index.html"))
            assertMissingBuild()
        } finally {
            unlink("$dir/project.yaml")
            unlink("$dir/resources/webui/index.html")
            for (path in listOf("resources/webui/assets", "resources/webui", "resources")) {
                rmdir("$dir/$path")
            }
            rmdir(dir)
        }
    }

    @Test
    fun daemonServesIndexHtmlAtRoot() = withServer { ctx ->
        val resp = ctx.get("/")
        assertEquals(HttpStatusCode.OK, resp.status, "GET / serves the SPA index")
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent-webui"), "index.html carries the known serving marker")
        assertTrue(body.contains("type=\"module\""), "index.html bootstraps the app as an ES module")
        assertEquals(body, ctx.get("/index.html").bodyAsText(), "the root serves the built shell")
        assertContentTypeContains(resp, "html")
    }

    @Test
    fun everyBuiltShellReferenceResolvesToAServedFile() = withServer { ctx ->
        val shell = ctx.get("/").bodyAsText()
        val paths = shellReferences(shell)
        assertTrue(paths.any { it.startsWith("/assets/") && it.endsWith(".js") }, "the shell loads built JavaScript")
        assertTrue(paths.any { it.startsWith("/assets/") && it.endsWith(".css") }, "the shell loads built CSS")
        for (path in paths + listOf(
            "/manifest.webmanifest", "/icons/logo.svg", "/icons/apple-touch-icon.png",
            "/icons/icon-192.png", "/icons/icon-512.png", "/sw.js",
        )) {
            assertTrue(path.startsWith("/"), "$path resolves from deep links as well as the root")
            val response = ctx.get(path)
            assertEquals(HttpStatusCode.OK, response.status, "GET $path is served")
            assertEquals(
                if (path.startsWith("/assets/") && !path.endsWith(".map")) IMMUTABLE_CACHE_CONTROL else "no-cache",
                response.headers[HttpHeaders.CacheControl],
                "GET $path uses the cache policy for its built path",
            )
            assertTrue(response.readRawBytes().isNotEmpty(), "$path is not empty")
            when {
                path.endsWith(".js") -> assertContentTypeContains(response, "javascript")
                path.endsWith(".css") -> assertContentTypeContains(response, "css")
            }
        }
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
        assertTrue(
            body.contains("name=\"color-scheme\" content=\"dark\""),
            "the installed iOS app declares dark system UI",
        )
        assertTrue(
            body.contains("href=\"/manifest.webmanifest\"") &&
                body.contains("href=\"/icons/apple-touch-icon.png\"") &&
                body.contains("href=\"/icons/logo.svg\""),
            "the install surface stays on stable, root-absolute URLs the installed app can keep referring to",
        )
    }

    @Test
    fun builtAssetsAreImmutableExceptSourceMaps() = withServer { ctx ->
        fun filesUnder(dir: String): List<String> = listDir(dir).flatMap { name ->
            val path = "$dir/$name"
            if (isDirectory(path)) filesUnder(path) else listOf(path)
        }
        val webUiDir = locateWebUiDir()
        val files = filesUnder(webUiDir).map { it.removePrefix(webUiDir) }
        assertTrue(files.any { it.startsWith("/assets/") && !it.endsWith(".map") }, "the build produces hashed assets")
        assertTrue(files.any { it.endsWith(".map") }, "the build produces source maps")
        val hashedName = Regex(""".+-[A-Za-z0-9_-]{8,}\.[A-Za-z0-9]+""")
        for (path in files.filter { it.startsWith("/assets/") || it.endsWith(".map") }) {
            if (path.endsWith(".br") || path.endsWith(".gz")) {
                val original = path.dropLast(3)
                assertTrue(original in files, "$path belongs to an emitted original")
                assertTrue(hashedName.matches(original.substringAfterLast('/')), "$path belongs to a hashed original")
                val response = ctx.get(path) { header(HttpHeaders.AcceptEncoding, "br, gzip") }
                assertEquals(HttpStatusCode.NotFound, response.status, "$path is an internal representation")
                assertFalse(response.headers[HttpHeaders.CacheControl].orEmpty().contains("immutable"), path)
                assertNull(response.headers[HttpHeaders.Vary], path)
                continue
            }
            val isMap = path.endsWith(".map")
            if (!isMap) {
                assertTrue(
                    hashedName.matches(path.substringAfterLast('/')),
                    "$path needs a content-hashed name because non-map files under assets/ are immutable",
                )
            }
            val response = ctx.get(path)
            assertEquals(HttpStatusCode.OK, response.status, "GET $path is served")
            assertEquals(
                if (isMap) "no-cache" else IMMUTABLE_CACHE_CONTROL,
                response.headers[HttpHeaders.CacheControl],
                "GET $path uses the cache policy for its built path",
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun negotiatesPrecompressedAssetsAndKeepsTheirOriginalMetadata() {
        val dir = makeTempDir()
        val files = mapOf(
            "index.html" to "<!doctype html>\n",
            "sw.js" to "self.addEventListener('fetch', () => {});\n",
            "assets/app-abcdefgh.js" to "export const app = 1;\n",
            "assets/app-abcdefgh.js.br" to "brotli script bytes\u0000",
            "assets/app-abcdefgh.js.gz" to "gzip script bytes\u0000",
            "assets/app-abcdefgh.css" to "body { color: white; }\n",
            "assets/app-abcdefgh.css.br" to "brotli stylesheet bytes\u0000",
            "assets/app-abcdefgh.css.gz" to "gzip stylesheet bytes\u0000",
            "assets/partial-abcdefgh.js" to "export const partial = 2;\n",
            "assets/partial-abcdefgh.js.gz" to "gzip only bytes\u0000",
            "assets/plain-abcdefgh.js" to "export const plain = 3;\n",
            "assets/app-abcdefgh.js.map" to "{}\n",
            "assets/app-abcdefgh.js.map.br" to "brotli map bytes\u0000",
            "assets/app-abcdefgh.js.map.gz" to "gzip map bytes\u0000",
        )
        data class Case(val file: String, val header: String?, val coding: String?)
        val cases = listOf(
            Case("app-abcdefgh.js", "br, gzip", "br"),
            Case("app-abcdefgh.js", "gzip", "gzip"),
            Case("app-abcdefgh.js", "br;q=0, gzip", "gzip"),
            Case("app-abcdefgh.js", "identity", null),
            Case("app-abcdefgh.js", null, null),
            Case("app-abcdefgh.js", "", null),
            Case("app-abcdefgh.js", "*;q=0, identity;q=0.5", null),
            Case("app-abcdefgh.js", "*;q=0, gzip;q=0.5", "gzip"),
            Case("app-abcdefgh.js", "x-gzip", "gzip"),
            Case("app-abcdefgh.js", "br;q=0, br, gzip", "gzip"),
            Case("app-abcdefgh.css", "br, gzip", "br"),
            Case("app-abcdefgh.css", "gzip", "gzip"),
            Case("app-abcdefgh.css", "identity", null),
            Case("partial-abcdefgh.js", "br, gzip", "gzip"),
            Case("partial-abcdefgh.js", "identity;q=0, br, gzip;q=0.5", "gzip"),
            Case("partial-abcdefgh.js", "br", null),
            Case("plain-abcdefgh.js", "br, gzip", null),
            Case("app-abcdefgh.js.map", "br, gzip", null),
        )
        try {
            assertEquals(0, mkdir("$dir/assets", MODE_0700.convert()))
            for ([path, body] in files) writeFile("$dir/$path", body)
            withServer(webUiDir = dir) { ctx ->
                for (case in cases) {
                    val response = ctx.get("/assets/${case.file}") {
                        case.header?.let { header(HttpHeaders.AcceptEncoding, it) }
                    }
                    val label = "${case.file} with Accept-Encoding: ${case.header}"
                    val suffix = when (case.coding) {
                        "br" -> ".br"
                        "gzip" -> ".gz"
                        else -> ""
                    }
                    assertEquals(HttpStatusCode.OK, response.status, label)
                    val expectedBytes = files.getValue("assets/${case.file}$suffix").encodeToByteArray()
                    assertContentEquals(
                        expectedBytes,
                        response.readRawBytes(),
                        label,
                    )
                    assertEquals(expectedBytes.size.toString(), response.headers[HttpHeaders.ContentLength], label)
                    assertEquals(case.coding, response.headers[HttpHeaders.ContentEncoding], label)
                    assertEquals(
                        when {
                            case.file.endsWith(".js") -> "text/javascript"
                            case.file.endsWith(".css") -> "text/css"
                            else -> "application/octet-stream"
                        },
                        response.headers[HttpHeaders.ContentType],
                        label,
                    )
                    val isMap = case.file.endsWith(".map")
                    assertEquals(
                        if (isMap) "no-cache" else IMMUTABLE_CACHE_CONTROL,
                        response.headers[HttpHeaders.CacheControl],
                        label,
                    )
                    assertEquals(
                        if (isMap) null else HttpHeaders.AcceptEncoding,
                        response.headers[HttpHeaders.Vary],
                        label,
                    )
                }
                val rejected = listOf(
                    "/assets/app-abcdefgh.js" to "*;q=0",
                    "/assets/app-abcdefgh.js" to "br;q=0, gzip;q=0, identity;q=0",
                    "/assets/partial-abcdefgh.js" to "br, identity;q=0",
                    "/assets/partial-abcdefgh.js" to "x-gzip;q=0, *, identity;q=0",
                    "/assets/plain-abcdefgh.js" to "br, gzip, identity;q=0",
                    "/assets/app-abcdefgh.js.map" to "br, gzip, identity;q=0",
                    "/index.html" to "identity;q=0",
                    "/sw.js" to "*;q=0",
                )
                for ([path, acceptEncoding] in rejected) {
                    val response = ctx.get(path) { header(HttpHeaders.AcceptEncoding, acceptEncoding) }
                    val label = "$path with Accept-Encoding: $acceptEncoding"
                    assertEquals(HttpStatusCode.NotAcceptable, response.status, label)
                    assertEquals(HttpHeaders.AcceptEncoding, response.headers[HttpHeaders.Vary], label)
                    assertFalse(response.headers[HttpHeaders.CacheControl].orEmpty().contains("immutable"), label)
                    assertNull(response.headers[HttpHeaders.ContentEncoding], label)
                    assertEquals("not acceptable", response.bodyAsText(), label)
                }
                for (file in files.keys.filter { it.endsWith(".br") || it.endsWith(".gz") }) {
                    val response = ctx.get("/$file") { header(HttpHeaders.AcceptEncoding, "br, gzip") }
                    assertEquals(HttpStatusCode.NotFound, response.status, file)
                    assertFalse(response.headers[HttpHeaders.CacheControl].orEmpty().contains("immutable"), file)
                    assertNull(response.headers[HttpHeaders.Vary], file)
                }
            }
        } finally {
            for (path in files.keys) unlink("$dir/$path")
            rmdir("$dir/assets")
            rmdir(dir)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun nonMapAssetsAreImmutableAndEverythingElseRevalidates() {
        val dir = makeTempDir()
        val files = mapOf(
            "index.html" to "<!doctype html>\r\n<title>Kotgent — test</title>\r\n",
            "assets/x-abcdefgh.js" to "export const a = 1;\n",
            "assets/x-abcdefgh.js.map" to "{}\n",
            "assets/nested/chunk-89abcdef.js" to "export const b = 2;\n",
            "sw.js" to "self.addEventListener('fetch', () => {});\n",
            "manifest.webmanifest" to "{}\n",
            "icons/logo.svg" to "<svg/>\n",
            "app.js" to "export const a = 3;\n",
            "assets-other.js" to "export const b = 4;\n",
        )
        val directories = listOf("assets", "assets/nested", "icons")
        try {
            for (path in directories) assertEquals(0, mkdir("$dir/$path", MODE_0700.convert()))
            for ([path, body] in files) writeFile("$dir/$path", body)
            withServer(webUiDir = dir) { ctx ->
                for (path in listOf("/assets/x-abcdefgh.js", "/assets/nested/chunk-89abcdef.js")) {
                    val response = ctx.get(path)
                    assertEquals(HttpStatusCode.OK, response.status, "GET $path is served before any shell")
                    assertEquals(IMMUTABLE_CACHE_CONTROL, response.headers[HttpHeaders.CacheControl], path)
                    assertContentEquals(files.getValue(path.drop(1)).encodeToByteArray(), response.readRawBytes(), path)
                }
                for (path in listOf(
                    "/", "/index.html", "/tasks/local:42", "/sw.js", "/manifest.webmanifest",
                    "/icons/logo.svg", "/app.js", "/assets-other.js", "/assets/x-abcdefgh.js.map",
                )) {
                    val response = ctx.get(path)
                    assertEquals(HttpStatusCode.OK, response.status, "GET $path is served")
                    assertEquals("no-cache", response.headers[HttpHeaders.CacheControl], path)
                    val file = if (path == "/" || path == "/tasks/local:42") "index.html" else path.drop(1)
                    assertContentEquals(files.getValue(file).encodeToByteArray(), response.readRawBytes(), path)
                }

                val traversal = ctx.get("/assets/../index.html")
                assertEquals(HttpStatusCode.Forbidden, traversal.status)
                assertEquals("bad path", traversal.bodyAsText())

                for (path in listOf(
                    "/index.html%00", "/sw.js%00/anything", "/icons/logo.svg%00",
                    "/assets/x-abcdefgh.js%00", "/assets/nested/chunk-89abcdef.js%00.gz",
                    "/tasks/%00", "/missing%00file",
                )) {
                    val response = ctx.get(path)
                    assertEquals(HttpStatusCode.Forbidden, response.status, "GET $path contains a NUL")
                    assertEquals("bad path", response.bodyAsText(), path)
                }

                for (path in listOf(
                    "/_v/0123456789ab/app.js", "/_v/0123456789ab/assets/x-abcdefgh.js",
                    "/assets/tasks", "/assets/does-not-exist.js",
                )) {
                    val response = ctx.get(path)
                    assertEquals(HttpStatusCode.NotFound, response.status, "GET $path names no file")
                    assertEquals("not found", response.bodyAsText(), path)
                    assertFalse(response.headers[HttpHeaders.CacheControl].orEmpty().contains("immutable"), path)
                    assertNull(response.headers[HttpHeaders.Vary], path)
                }
            }
        } finally {
            for (path in files.keys) unlink("$dir/$path")
            for (path in directories.asReversed()) rmdir("$dir/$path")
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
    }

    @Test
    fun aMissingStaticFileIs404() = withServer { ctx ->
        assertEquals(
            HttpStatusCode.NotFound,
            ctx.get("/assets/does-not-exist.js").status,
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

    private class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
            client.get("http://127.0.0.1:$port$path", block)
    }

    private fun withServer(webUiDir: String = locateWebUiDir(), block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(40.seconds) {
            val eventStore = FakeEventStore()
            val preferencesStore = FakePreferencesStore()
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                tmux = FakeTmux(),
                store = eventStore,
                registry = PaneRegistry(),
                agentFactory = { _, cwd ->
                    object : AgentAdapter {
                        override val events: Flow<AgentEvent> = emptyFlow()
                        override fun buildLaunchSpec(mode: LaunchMode, options: LaunchOptions): LaunchSpec =
                            LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                    }
                },
                idCapture = ProviderIdCapture(store = eventStore, scope = idScope),
                vendorProbe = { _, _, _ -> false },
                sessionLocator = { _, _ -> null },
                supportedAgentKinds = setOf("claude", "codex"),
                now = { 1L },
            )
            val server = KotgentServer(
                sessionManager = manager,
                eventStore = eventStore,
                preferencesStore = preferencesStore,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in the serving test") },
                currentVersion = currentVersion,
                webUiDir = webUiDir,
                port = 0,
            ).start()
            val client = routeTestClient()
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

    @OptIn(ExperimentalForeignApi::class)
    private fun makeTempDir(): String = memScoped {
        val template = "/tmp/kotgent-webui-serving-test-XXXXXX"
        val encoded = template.encodeToByteArray()
        val chars = allocArray<ByteVar>(encoded.size + 1)
        encoded.forEachIndexed { index, byte -> chars[index] = byte }
        chars[encoded.size] = 0
        mkdtemp(chars)?.toKString() ?: error("could not create the Web UI serving test directory")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            val _ = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
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

internal fun locateWebUiDir(startDir: String = currentDir()): String {
    var dir = startDir
    repeat(6) {
        if (fileExists("$dir/project.yaml")) {
            val candidate = "$dir/resources/webui"
            check(
                fileExists("$candidate/index.html") && fileExists("$candidate/assets/"),
            ) { "missing Web UI build at $dir; run npm ci and npm run build in webui/" }
            return candidate
        }
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) {
            error("could not locate the Web UI from $startDir; run npm ci and npm run build in webui/")
        }
        dir = parent
    }
    error("could not locate the Web UI from $startDir; run npm ci and npm run build in webui/")
}

internal fun shellReferences(shell: String): List<String> =
    Regex("""(?:src|href)="([^"]+)"""").findAll(shell).map { it.groupValues[1] }.toList()
