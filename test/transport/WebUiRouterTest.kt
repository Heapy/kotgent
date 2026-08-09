package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `resources/webui/lib/router.js` — the SPA's first router (plan Task 22).
 *
 * The browser JS cannot run in the macosArm64 test binary, so what is automatable is the **serving and
 * source contract**, the pattern `WebUiServingTest` established: the daemon really serves the module,
 * and its text carries every export `app.js` imports plus the rules that cannot be re-derived from
 * anywhere else. Behaviour in a real browser stays on the manual checklist.
 *
 * Two contracts here span files and are the reason this is a *serving* test rather than a source read.
 * The route grammar must be the same one `isSpaRoute` (`src/transport/WebUiAssets.kt`) answers with the
 * shell — a path the router understands but the daemon 404s is a link that dies on reload. And
 * `DEEP_LINK_PARAM` must be the parameter `sw.js` actually builds: the service worker is a classic
 * script with no module graph, so nothing but a test keeps the two spellings in step.
 *
 * The harness mounts [staticWebUi] alone rather than a whole [KotgentServer]. Nothing here is about the
 * API/UI collision (that is Task 17's `SpaRoutingTest`), so a bare static route makes a failure point at
 * the file rather than at any of the daemon's fakes.
 */
class WebUiRouterTest {

    @Test
    fun theDaemonServesTheRouterModuleAsRevalidatedJavaScript() = withStaticWebUi { ctx ->
        val resp = ctx.get("/lib/router.js")
        assertEquals(HttpStatusCode.OK, resp.status, "the router module is served")
        val contentType = resp.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(
            contentType.contains("javascript", ignoreCase = true),
            "an ES module must arrive as JavaScript or the browser refuses to import it; got '$contentType'",
        )
        assertEquals(
            "no-cache",
            resp.headers[HttpHeaders.CacheControl],
            "an unprefixed asset revalidates — only a `/_v/<rev>/` URL is immutable",
        )
    }

    @Test
    fun theRouterExportsEverySymbolTheAppImports() = withStaticWebUi { ctx ->
        val js = ctx.text("/lib/router.js")
        val exports = listOf(
            "export const DEEP_LINK_PARAM",
            "export const SCREEN_TASKS",
            "export const SCREEN_TASK ",
            "export const SCREEN_SESSIONS",
            "export function parseRoute(pathname, search)",
            "export function routePath(route)",
            "export function taskPath(ref)",
            "export function sessionPath(id)",
            "export function navigate(path)",
            "export function subscribeToRoute(handler)",
        )
        for (declaration in exports) {
            assertTrue(js.contains(declaration), "the router still declares `$declaration`")
        }
        // The stub shipped with the contracts answered "the session view" for every path and returned "/"
        // for every route. Both markers are gone, and so is every stub body's `// Task 22.`
        assertFalse(js.contains("// Task 22."), "no stub body is left in the implemented module")
        assertFalse(js.contains("STUB:"), "the stub header is gone")
    }

    @Test
    fun theRouterParsesTheSameExactSegmentGrammarTheDaemonServes() = withStaticWebUi { ctx ->
        val js = ctx.text("/lib/router.js")
        // The daemon's half of the pair, compiled in so a reader of either side finds the other. An asset
        // URL is never a History-API route — true both before and after Task 17 fills the grammar in, so
        // this states the coupling without depending on that task having landed.
        assertFalse(isSpaRoute("lib/router.js"), "isSpaRoute never claims an asset path")
        assertTrue(
            js.contains("""segments.length === 1 && segments[0] === SCREEN_TASKS"""),
            "`/tasks` is the board",
        )
        assertTrue(
            js.contains("""segments.length === 2 && segments[0] === SCREEN_TASKS"""),
            "`/tasks/{ref}` is one task",
        )
        assertTrue(
            js.contains("""segments.length === 2 && segments[0] === "s""""),
            "`/s/{id}` is one session",
        )
        // EXACT, not a prefix match: every arm counts its segments, so `/s/id/extra` cannot parse as a
        // session — which matters because the daemon 404s that path and would never serve the shell for it.
        assertEquals(
            3,
            js.split("segments.length ===").size - 1,
            "each arm of the grammar counts segments; a prefix match would serve a dead route",
        )
        assertTrue(
            js.contains("""filter((segment) => segment.length > 0)"""),
            "empty segments are dropped, so a trailing or doubled slash cannot change the arm",
        )
        // Anything outside the grammar degrades to the session view rather than throwing or blanking.
        assertTrue(
            js.contains("""return { screen: SCREEN_SESSIONS, id: deepLinkId(search) };"""),
            "an unrecognised path parses as the session view",
        )
        // A TaskRef carries a mandatory `:`, which `encodeURIComponent` writes as `%3A` — so the segment
        // has to be decoded on the way back in, and a malformed escape may not take the app down.
        assertTrue(js.contains("""encodeURIComponent(ref)"""), "a ref is encoded into the path")
        assertTrue(js.contains("""decodeURIComponent(value)"""), "and decoded back out of it")
        assertTrue(js.contains("""return value;"""), "a malformed escape falls back to the raw segment")
    }

    @Test
    fun theDeepLinkParameterIsTheOneTheServiceWorkerBuilds() = withStaticWebUi { ctx ->
        val js = ctx.text("/lib/router.js")
        val sw = ctx.text("/sw.js")
        assertTrue(
            js.contains("""export const DEEP_LINK_PARAM = "session";"""),
            "the router names the deep-link parameter once",
        )
        assertTrue(
            sw.contains("""openWindow(sessionId ? "/?session=" + encodeURIComponent(sessionId) : "/")"""),
            "the service worker still opens `/?session=<id>`; the two spellings must agree",
        )
        assertTrue(
            js.contains("""new URLSearchParams(search || "").get(DEEP_LINK_PARAM)"""),
            "the router reads that parameter out of the search string",
        )
        // The path wins over the query: `/s/{id}?session=other` is a request for `{id}`, and the leftover
        // query is from the tap that opened the window. So the deep link is read on the fallback arm only.
        assertEquals(
            1,
            js.split("id: deepLinkId(search)").size - 1,
            "the deep link is consulted only on the arm where the path named no session",
        )
    }

    @Test
    fun navigationRunsOverPushStateAndPopstate() = withStaticWebUi { ctx ->
        val js = ctx.text("/lib/router.js")
        assertTrue(
            js.contains("""window.history.pushState(null, "", target)"""),
            "navigate() pushes a history entry instead of reloading",
        )
        assertTrue(
            js.contains("""window.addEventListener("popstate", emitRoute)"""),
            "Back and Forward reach the same subscribers as navigate()",
        )
        assertTrue(
            js.contains("""if (target === window.location.pathname + window.location.search) return;"""),
            "navigating to where the page already is pushes nothing and notifies nobody",
        )
        assertTrue(
            js.contains("""window.location.assign(target)"""),
            "a location with no usable History API still reaches the screen, by a full load",
        )
        assertTrue(
            js.contains("""return () => { routeHandlers.delete(handler); };"""),
            "subscribeToRoute answers with its own unsubscribe",
        )
        assertTrue(
            js.contains("Array.from(routeHandlers)"),
            "the notify loop iterates a copy, so a handler may unsubscribe from inside it",
        )
    }

    @Test
    fun theAppReachesHistoryOnlyThroughTheRouter() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        assertTrue(app.contains("""} from "./lib/router.js";"""), "app.js imports the router module")
        assertTrue(app.contains("parseRoute(window.location.pathname"), "the first route is parsed on mount")
        assertTrue(app.contains("subscribeToRoute(setRoute)"), "and every later one arrives by subscription")
        // `replaceState` survives on purpose — `clearDeepLink` rewrites the address bar without changing
        // screens. A `pushState` would be a second owner of navigation, which is what this forbids.
        assertFalse(app.contains("pushState"), "no screen change is hand-rolled outside the router")
    }

    // --- harness -------------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String): HttpResponse = client.get("http://127.0.0.1:$port$path")

        suspend fun text(path: String): String {
            val resp = get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path is served")
            return resp.bodyAsText()
        }
    }

    /** A server whose only route is the real static handler over the real `resources/webui` tree. */
    private fun withStaticWebUi(block: suspend (Ctx) -> Unit) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing { staticWebUi(webUiDir()) }
        }
        try {
            withTimeout(30_000) {
                server.start(wait = false)
                val port = server.engine.resolvedConnectors().first().port
                val client = HttpClient(CIO)
                try {
                    block(Ctx(port, client))
                } finally {
                    client.close()
                }
            }
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    /**
     * Locate `resources/webui` by walking up from the cwd: `./kotlin test` runs from the module root, but
     * the walk keeps the test independent of where a runner starts it.
     */
    private fun webUiDir(): String {
        var dir = cwd()
        repeat(6) {
            val candidate = "$dir/resources/webui"
            if (exists("$candidate/index.html")) return candidate
            val parent = dir.substringBeforeLast('/', "")
            if (parent.isEmpty() || parent == dir) return "resources/webui"
            dir = parent
        }
        return "resources/webui"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun cwd(): String = memScoped {
        val size = 4096
        val buffer = allocArray<ByteVar>(size)
        getcwd(buffer, size.convert())
        buffer.toKString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun exists(path: String): Boolean = access(path, F_OK) == 0
}
