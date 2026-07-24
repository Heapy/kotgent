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
import io.ktor.client.statement.readRawBytes
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Web UI serving tests (plan Task 18) — proof that the assembled [KotgentServer] actually serves the
 * static SPA from `resources/webui`: `GET /` returns `index.html`, and the vendored xterm.js / `app.js`
 * / CSS are reachable with sensible content-types.
 *
 * ## What this covers (and what it can't)
 * The browser JS itself (token parse, live updates, xterm.js terminal) cannot run in the macosArm64 test
 * binary. What IS automatable — and what these tests lock down — is the **serving/source contract**: the
 * real files exist, the daemon serves them with 200s and correct content-types (including nested
 * `/vendor/` paths), browser-only features carry all of their required wiring, the static catch-all is
 * mounted UNauthenticated (the browser must fetch the bootstrap before it has the token), and it does
 * NOT shadow the token-gated API routes. Behaviour is exercised by the plan's manual device checklist.
 */
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
        // The unread-badge wiring. There is no JS harness, so these greps are what stops the whole feature
        // from being deleted with a green suite: the guard, its POST target, and the visibility trigger.
        assertTrue(body.contains("markReadIfViewing"), "app.js marks the viewed session read")
        assertTrue(body.contains("/read"), "…by POSTing the displayed seq to the mark-read route")
        assertTrue(body.contains("visibilitychange"), "…and re-checks when the tab becomes visible again")
        // The per-session throttle Map is module-level and long-lived; this page stays open for days.
        assertTrue(body.contains("pruneReadPosters"), "…and drops the throttle of a session that vanished")
        assertTrue(body.contains("apiRequest(\"/version\")"), "app.js fetches the daemon version")
        assertTrue(body.contains("currentVersion=\${currentVersion}"), "app.js passes the version to the sidebar")
        val loadStart = body.indexOf("const loadSessions = useCallback(async () => {")
        val loadEnd = body.indexOf("\n  }, [cancelReattach", startIndex = loadStart.coerceAtLeast(0))
        assertTrue(loadStart >= 0 && loadEnd > loadStart, "the session loader is present and bounded")
        val loadSessions = body.substring(loadStart, loadEnd)
        val versionCaptureAt = loadSessions.indexOf("const version = ++sessionsLoadVersionRef.current")
        val responseAt = loadSessions.indexOf("const list = await apiRequest(\"/sessions\")")
        val staleGuard = "if (version !== sessionsLoadVersionRef.current) return"
        val successGuardAt = loadSessions.indexOf(staleGuard)
        val setSessionsAt = loadSessions.indexOf("setSessions(list)")
        assertTrue(
            body.contains("const sessionsLoadVersionRef = useRef(0)") &&
                versionCaptureAt in 0 until responseAt &&
                successGuardAt in (responseAt + 1) until setSessionsAt,
            "only the newest concurrent /sessions response may mutate the session list",
        )
        val catchAt = loadSessions.indexOf("} catch (e) {")
        val errorGuardAt = loadSessions.indexOf(
            staleGuard,
            startIndex = (successGuardAt + staleGuard.length).coerceAtLeast(0),
        )
        val redirectAt = loadSessions.indexOf("window.location.replace(AUTH_PATH)")
        val errorStatusAt = loadSessions.indexOf("say(\"Could not load sessions:")
        assertTrue(
            errorGuardAt in (catchAt + 1) until minOf(redirectAt, errorStatusAt),
            "an older failed /sessions request cannot overwrite a newer response or redirect the page",
        )
        assertTrue(
            Regex(
                """(?s)if \(isFirstLoad && isUnauthenticated\(e\)\) \{\s*""" +
                    """window\.location\.replace\(AUTH_PATH\);\s*return;""",
            ).containsMatchIn(loadSessions),
            "only the first unauthenticated /sessions load redirects an unsigned installed PWA to /auth",
        )
        assertContentTypeContains(resp, "javascript")
    }

    @Test
    fun webUiRendersTheCurrentVersionInTheSidebarFooter() = withServer { ctx ->
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(sidebar.contains("id=\"sidebar-footer\""), "the sidebar has a stable footer")
        assertTrue(sidebar.contains("id=\"current-version\""), "the footer includes the current version")
        assertTrue(sidebar.contains("\${currentVersion}"), "the version label renders the value from the API")

        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(css.contains("#sidebar-footer"), "the sidebar footer is styled")
        assertTrue(css.contains("#current-version"), "the current version label is styled")
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
            "/lib/throttle.js", "/lib/notify.js", "/lib/push.js",
            "/components/Sidebar.js", "/components/TerminalPane.js", "/components/KeyBar.js",
            "/components/dialogs.js",
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
        // app.js imports this by name — a rename (or an empty file) would break the entire SPA at load
        // time, which the 200 + content-type loop above cannot see.
        assertTrue(
            ctx.get("/lib/throttle.js").bodyAsText().contains("export function throttleLeading"),
            "the mark-read throttle is exported under the name app.js imports",
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
        assertTrue(pane.contains("id=\"copy-tmux-button\""), "the desktop UI includes copy tmux")
        assertTrue(
            pane.contains("\${alive && tmuxCommand && html`"),
            "copy tmux is offered only while the tmux session is alive",
        )
        assertTrue(
            pane.contains("tmuxAttachCommand(session.tmuxSession)"),
            "copy tmux targets the selected session's canonical tmux name",
        )
        assertTrue(
            ctx.get("/lib/sessions.js").bodyAsText()
                .contains("\"tmux -u -L kotgent attach -t \" + tmuxSession"),
            "copy tmux uses kotgent's dedicated socket and UTF-8 attach command",
        )
        assertTrue(
            ctx.get("/style.css").bodyAsText().contains(".copy-tmux-button { display: none; }"),
            "copy tmux is omitted from the mobile terminal head",
        )
        assertTrue(
            ctx.get("/components/dialogs.js").bodyAsText().let { dialogs ->
                dialogs.contains("id=\"new-session-form\"") &&
                    dialogs.contains("role=\"combobox\"") &&
                    dialogs.contains("id=\"session-cwd-options\"") &&
                    dialogs.contains("/directories/complete")
            },
            "the new-session form includes the working-directory autocomplete",
        )
        assertTrue(
            ctx.get("/style.css").bodyAsText().contains(".path-suggestions"),
            "the autocomplete dropdown is styled",
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
        assertTrue(dialogs.contains("id=\"help-tmux\""), "help includes a tmux and copying section")
        assertTrue(
            dialogs.contains("<kbd>Ctrl</kbd>+<kbd>B</kbd>") &&
                dialogs.contains("<kbd>Option</kbd>-drag") &&
                dialogs.contains("<kbd>Cmd</kbd>+<kbd>C</kbd>"),
            "help documents the tmux prefix and macOS browser-copy gesture",
        )
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
        assertTrue(dialogs.contains("qrSvg"), "the phone dialog renders the public install URL as a QR code")
        assertTrue(
            dialogs.contains("String(ticketUrl || \"\").split(\"#\", 1)[0]") &&
                dialogs.contains("qrSvg(publicInstallUrl)") &&
                !dialogs.contains("qrSvg(ticket.publicUrl)"),
            "the QR opens the credential-free /auth install page instead of spending the displayed code in Safari",
        )

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

    /**
     * The PWA install surface (plan Task 11). On iOS this is not decoration: Web Push exists only inside
     * an *installed* PWA, so a manifest Chrome/Safari refuses (wrong media type, an icon that 404s) costs
     * the whole notification half of the feature — and nothing else in the suite would notice.
     */
    @Test
    fun daemonServesTheWebManifestWithItsOwnMediaType() = withServer { ctx ->
        val resp = ctx.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /manifest.webmanifest is served")
        assertContentTypeContains(resp, "application/manifest+json")

        // Parse rather than substring-match: a manifest that is not valid JSON is silently ignored by
        // every browser, which looks exactly like "install prompt never appears".
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

        // Every declared icon must actually be served, at the declared size, as a real PNG: the
        // no-build-step contract means a typo'd or uncommitted icon fails only in a browser otherwise.
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
        // 180x180 is what iOS asks for; anything else gets rescaled and looks soft on the home screen.
        assertPngOfSize(apple.readRawBytes(), 180, "/icons/apple-touch-icon.png")

        // The PNGs are rendered from this file and committed (there is no build step), so it has to stay.
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
    }

    /**
     * `index.html` is the shell every other asset is fetched from and `/sw.js` will be the service worker
     * (browsers cap a worker script at 24h of freshness), so both must revalidate. Everything else keeps
     * the default so this stays a targeted rule, not a blanket "never cache anything".
     */
    @Test
    fun theAppShellIsServedNoCacheAndTheRestIsNot() = withServer { ctx ->
        for (path in listOf("/", "/index.html")) {
            assertEquals(
                "no-cache",
                ctx.get(path).headers[HttpHeaders.CacheControl],
                "GET $path revalidates so a deploy is never pinned behind a cached shell",
            )
        }
        for (path in listOf("/app.js", "/style.css", "/manifest.webmanifest", "/icons/icon-192.png")) {
            assertEquals(
                null,
                ctx.get(path).headers[HttpHeaders.CacheControl],
                "GET $path keeps the default caching — the no-cache rule is targeted",
            )
        }
    }

    /**
     * The service worker (plan Task 12) — the half of the notification path that runs when no tab does.
     * Three things about it can only be checked here: that it is served at all (a 404 makes
     * `register("/sw.js")` reject and push silently never works), that it is served from the ROOT so its
     * scope covers `/` (a worker under `/lib/` could never control the app), and that it revalidates (a
     * cached worker keeps an old push handler alive for up to 24h after a deploy).
     */
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
        for (handler in listOf("push", "pushsubscriptionchange", "notificationclick", "fetch")) {
            assertTrue(
                body.contains("addEventListener(\"$handler\""),
                "the worker handles the '$handler' event",
            )
        }
        // Payload-less push: the worker is told only THAT something happened and asks the daemon what.
        assertTrue(body.contains("\"/sessions\""), "the worker learns which sessions are waiting from /sessions")
        assertTrue(body.contains("credentials: \"include\""), "that fetch carries the session cookie")
        val waitingSessions = body.substringAfter("async function waitingSessions() {")
            .substringBefore("\n}\n\nfunction sessionName")
        val fetchAt = waitingSessions.indexOf("await fetch(SESSIONS_URL")
        val jsonAt = waitingSessions.indexOf("await resp.json()")
        val finallyAt = waitingSessions.indexOf("} finally {")
        assertTrue(
            body.contains("const SESSIONS_TIMEOUT_MS = 10_000") &&
                waitingSessions.contains("const controller = new AbortController()") &&
                waitingSessions.contains(
                    "setTimeout(() => controller.abort(), SESSIONS_TIMEOUT_MS)",
                ) &&
                waitingSessions.contains("signal: controller.signal") &&
                waitingSessions.contains("clearTimeout(timeout)") &&
                fetchAt >= 0 && jsonAt > fetchAt && finallyAt > jsonAt,
            "a stalled sessions response is aborted through body decoding so the generic banner can still run",
        )
        assertTrue(
            body.contains("needsAttention") && body.contains("archived"),
            "it filters on the same needsAttention && !archived rule the daemon's tracker uses",
        )
        // userVisibleOnly: true — a push that ends without a banner is a broken promise to the browser.
        val pushHandler = body.substringAfter("self.addEventListener(\"push\", (event) => {")
            .substringBefore("\n});")
        assertTrue(
            pushHandler.contains("event.waitUntil(showAttention());"),
            "the push event keeps the worker alive until its notification path completes",
        )
        val subscriptionChangeHandler = body.substringAfter(
            "self.addEventListener(\"pushsubscriptionchange\", (event) => {",
        ).substringBefore("\n});")
        assertTrue(
            subscriptionChangeHandler.contains("event.waitUntil(syncPushSubscription(event));"),
            "subscription rotation keeps the worker alive through daemon synchronization",
        )
        val rotation = body.substringAfter("async function syncPushSubscription(event) {")
            .substringBefore("\n}\n\n/**")
        val saveReplacementAt = rotation.indexOf("await registerPushSubscription(replacement)")
        val dropObsoleteAt = rotation.indexOf(
            "await unregisterPushSubscription(oldSubscription.endpoint)",
        )
        assertTrue(
            rotation.contains("event.newSubscription") &&
                rotation.contains("event.oldSubscription") &&
                rotation.contains(
                    "self.registration.pushManager.subscribe(oldSubscription.options)",
                ) &&
                saveReplacementAt >= 0 &&
                dropObsoleteAt > saveReplacementAt &&
                rotation.contains("oldSubscription.endpoint !== replacement.endpoint"),
            "rotation registers a replacement before dropping a distinct old endpoint and retries expiry",
        )
        val registerRotated = body.substringAfter(
            "async function registerPushSubscription(subscription) {",
        ).substringBefore("\n}")
        val postPushState = body.substringAfter("async function postPushState(url, body) {")
            .substringBefore("\n}")
        assertTrue(
            registerRotated.contains("subscription.toJSON()") &&
                registerRotated.contains("p256dh: keys.p256dh") &&
                registerRotated.contains("auth: keys.auth") &&
                registerRotated.contains("postPushState(PUSH_SUBSCRIBE_URL"),
            "the worker sends the complete replacement endpoint and keys to the daemon",
        )
        assertTrue(
            postPushState.contains("credentials: \"include\"") &&
                postPushState.contains("\"Content-Type\": \"application/json\"") &&
                postPushState.contains("if (!response.ok)"),
            "subscription rotation is authenticated and treats an HTTP rejection as a failed save",
        )
        val showAttention = body.substringAfter("async function showAttention() {")
            .substringBefore("\n}\n\n/**")
        assertTrue(
            Regex(
                """(?s)if \(waiting\.length === 0\) \{.*?""" +
                    """self\.registration\.showNotification\(TITLE, \{.*?body: GENERIC_BODY,.*?return;""",
            ).containsMatchIn(showAttention),
            "an empty/error session lookup still produces the required generic notification",
        )
        assertTrue(
            showAttention.contains("waiting.map((s) => self.registration.showNotification"),
            "known waiting sessions each produce their own notification",
        )
        assertTrue(body.contains("renotify: false"), "repeat pushes for one session replace quietly")
        assertTrue(
            body.contains("/?session=") && body.contains("openWindow"),
            "a tapped notification opens the app deep-linked at that session",
        )
        // Registered as a CLASSIC worker (the broader path on Safari), so it must not import anything —
        // a bare specifier would throw on the very first line and take the whole push path with it.
        assertTrue(
            body.lineSequence().none { it.trimStart().startsWith("import ") },
            "the worker is a classic script and imports nothing",
        )
    }

    /**
     * The browser half of the subscription handshake: `lib/push.js` must talk to the exact routes
     * `PushRoutes.kt` mounts. Asserted against the Kotlin constants, so renaming a route on the server and
     * forgetting the page fails here instead of in a browser nobody is watching.
     */
    @Test
    fun theWebUiWiresTheBrowserPushSubscriptionFlow() = withServer { ctx ->
        val push = ctx.get("/lib/push.js")
        assertEquals(HttpStatusCode.OK, push.status, "GET /lib/push.js is served")
        assertContentTypeContains(push, "javascript")
        val body = push.bodyAsText()
        for (route in listOf(PUSH_VAPID_KEY_PATH, PUSH_SUBSCRIBE_PATH, PUSH_UNSUBSCRIBE_PATH)) {
            assertTrue(body.contains("\"$route\""), "the page calls the daemon's $route route")
        }
        assertTrue(body.contains("\"/sw.js\""), "it registers the root-scope worker")
        assertTrue(
            body.contains("userVisibleOnly: true"),
            "the subscription promises a visible notification for every push (iOS requires it)",
        )
        assertTrue(body.contains("applicationServerKey"), "the VAPID key is passed as applicationServerKey")

        val subscribe = body.substringAfter("export async function subscribe(")
            .substringBefore("\n}\n\n/**")
        val permissionAt = subscribe.indexOf("ensurePermission()")
        val permissionAwaitAt = subscribe.indexOf("await permission")
        val registrationAt = subscribe.indexOf("await activeRegistration(context)")
        assertTrue(
            permissionAt >= 0 && permissionAwaitAt > permissionAt && registrationAt > permissionAwaitAt,
            "notification permission is requested before the first service-worker/network await (required by iOS)",
        )
        val vapidKey = body.substringAfter("async function vapidKeyOrNull(context) {")
            .substringBefore("\n}\n\n/**")
        assertTrue(
            vapidKey.contains("await apiRequest(VAPID_KEY_URL, { signal: context.signal })") &&
                vapidKey.contains("catch (_)") &&
                vapidKey.contains("return null") &&
                vapidKey.indexOf("context.isCurrent() ? responseKey(response) : null") >
                vapidKey.indexOf("catch (_)"),
            "an unavailable VAPID route downgrades to no push while a malformed success remains an error",
        )
        assertTrue(
            subscribe.contains("const key = await vapidKeyOrNull(context)") &&
                subscribe.contains("if (!key) return false"),
            "subscribe reports the daemon's unavailable-push response as false instead of rejecting",
        )
        val activateAt = subscribe.indexOf("setPushActive(true)")
        assertTrue(
            subscribe.lastIndexOf("if (!context.isCurrent()) return false", startIndex = activateAt) in
                0 until activateAt,
            "a superseded subscribe cannot restore the active mirror flag",
        )
        val mutation = body.substringAfter("async function settleMutation(promise, context) {")
            .substringBefore("\n}\n\n/**")
        val mutationResultAt = mutation.indexOf("return await promise")
        val repairSignalAt = mutation.indexOf("signalPushRepair()")
        val localRepairAt = mutation.indexOf("context.repairLatest()")
        assertTrue(
            body.contains(
                "export const PUSH_REPAIR_SIGNAL_KEY = \"kotgent.push.repair.v1\"",
            ) &&
                mutationResultAt >= 0 &&
                mutation.contains("finally") &&
                repairSignalAt > mutationResultAt &&
                localRepairAt > repairSignalAt,
            "a stale irreversible operation asks both this tab and every other tab to repair the latest choice",
        )
        val keyComparison = body.substringAfter(
            "function applicationServerKeyDiffers(subscription, requestedKey) {",
        ).substringBefore("\n}\n\n/**")
        assertTrue(
            keyComparison.contains("if (!storedKey) return false") &&
                keyComparison.contains("stored.length !== requestedKey.length") &&
                keyComparison.contains("stored.some((value, index) => value !== requestedKey[index])"),
            "an existing subscription is considered mismatched only from comparable application-key bytes",
        )
        val subscribeWith = body.substringAfter("async function subscribeWith(registration, key, context) {")
            .substringBefore("\n}\n\n/**")
        val getExistingAt = subscribeWith.indexOf("await registration.pushManager.getSubscription()")
        val currentBeforeMismatchAt = subscribeWith.indexOf(
            "if (!context.isCurrent()) return null",
            startIndex = getExistingAt.coerceAtLeast(0),
        )
        val mismatchGuardAt = subscribeWith.indexOf(
            "!applicationServerKeyDiffers(existing, options.applicationServerKey)",
        )
        val dropExistingAt = subscribeWith.indexOf("settleMutation(existing.unsubscribe(), context)")
        val currentAfterDropAt = subscribeWith.indexOf(
            "if (!context.isCurrent()) return null",
            startIndex = dropExistingAt.coerceAtLeast(0),
        )
        assertTrue(
            currentBeforeMismatchAt in (getExistingAt + 1) until mismatchGuardAt &&
                mismatchGuardAt in (currentBeforeMismatchAt + 1) until dropExistingAt &&
                currentAfterDropAt > dropExistingAt &&
                subscribeWith.split("settleMutation(").size - 1 == 3,
            "a subscribe rejection drops an existing endpoint only for a proven VAPID-key mismatch",
        )
        val register = body.substringAfter("async function registerSubscription(subscription, context) {")
            .substringBefore("\n}\n\n/**")
        val remember = body.substringAfter("function rememberEndpoint(endpoint) {")
            .substringBefore("\n}")
        val remembered = body.substringAfter("function rememberedEndpoints() {")
            .substringBefore("\n}")
        val rememberAt = register.indexOf("rememberEndpoint(json.endpoint)")
        val registerPostAt = register.indexOf("apiRequest(SUBSCRIBE_URL")
        assertTrue(
            rememberAt in 0 until registerPostAt &&
                remember.indexOf("endpointMemory = endpoint") in
                0 until remember.indexOf("window.localStorage.setItem(ENDPOINT_KEY, endpoint)") &&
                register.contains("settleMutation(") &&
                register.contains("endpoint: json.endpoint") &&
                register.contains("p256dh: keys.p256dh") &&
                register.contains("auth: keys.auth") &&
                register.contains("return context.isCurrent()"),
            "registration remembers the endpoint before its repairable daemon write",
        )
        assertTrue(
            remembered.contains("if (endpointMemory) endpoints.add(endpointMemory)") &&
                remembered.contains("window.localStorage.getItem(ENDPOINT_KEY)") &&
                remembered.contains("if (stored) endpoints.add(stored)") &&
                remembered.contains("return endpoints"),
            "OFF reads both this tab's endpoint and the latest cross-tab endpoint from origin storage",
        )
        val unsubscribe = body.substringAfter("export async function unsubscribe(transition = DEFAULT_TRANSITION) {")
            .substringBefore("\n}\n\n/**")
        val cachedDropAt = unsubscribe.indexOf("rememberedEndpoints().forEach(startDaemonDrop)")
        val browserLookupAt = unsubscribe.indexOf("await navigator.serviceWorker.getRegistration()")
        val discoveredDropAt = unsubscribe.indexOf("startDaemonDrop(endpoint)")
        val browserUnsubscribeAt = unsubscribe.indexOf(
            "settleMutation(subscription.unsubscribe(), context)",
        )
        val cleanupFinallyAt = unsubscribe.indexOf("} finally {")
        val waitForDaemonAt = unsubscribe.indexOf("await Promise.allSettled(", cleanupFinallyAt)
        assertTrue(
            cachedDropAt in 0 until browserLookupAt &&
                discoveredDropAt in (browserLookupAt + 1) until browserUnsubscribeAt &&
                unsubscribe.contains("apiRequest(UNSUBSCRIBE_URL") &&
                unsubscribe.contains("body: JSON.stringify({ endpoint: endpoint })") &&
                unsubscribe.split("settleMutation(").size - 1 == 2 &&
                cleanupFinallyAt > browserUnsubscribeAt &&
                waitForDaemonAt > cleanupFinallyAt &&
                unsubscribe.contains("catch (_)") &&
                !body.contains("removeItem(ENDPOINT_KEY)"),
            "OFF repairs late daemon/browser writes and retains the endpoint for a stale subscribe compensation",
        )
        val refresh = body.substringAfter("export async function refreshActive(transition = DEFAULT_TRANSITION) {")
            .substringBefore("\n}")
        val refreshRememberAt = refresh.indexOf("rememberEndpoint(existing.endpoint)")
        val refreshKeyAt = refresh.indexOf("await vapidKeyOrNull(context)")
        val missingRegistrationAt = refresh.indexOf("if (!registration || !existing) {")
        val repairWithoutPromptAt = refresh.indexOf("subscribe(Promise.resolve(true), context)")
        assertTrue(
            refresh.indexOf("await registerSubscription(subscription, context)") in
                0 until refresh.indexOf("setPushActive(true)") &&
                refreshRememberAt in 0 until refreshKeyAt &&
                missingRegistrationAt >= 0 &&
                repairWithoutPromptAt > missingRegistrationAt &&
                refresh.substring(missingRegistrationAt, repairWithoutPromptAt)
                    .contains("Notification.permission === \"granted\"") &&
                !refresh.substringBefore("if (!registration || !existing) {")
                    .contains("if (!registration"),
            "reload caches its endpoint, activates after daemon registration, and registers an absent worker without prompting",
        )

        // The toggle that drives it is the sidebar's, and the two notification paths must not both fire.
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(sidebar.contains("id=\"notify-toggle\""), "the toggle lives in the sidebar")
        assertTrue(sidebar.contains("../lib/push.js"), "the toggle drives the push subscription")
        val toggle = sidebar.substringAfter("const toggleNotifications = () => {")
            .substringBefore("\n  };")
        assertTrue(
            toggle.indexOf("const permission = next ? ensurePermission() : null") in
                0 until toggle.indexOf("queuePushTransition("),
            "an enable click claims notification permission synchronously before entering the transition queue",
        )
        val boundedTransition = sidebar.substringAfter(
            "function boundedPushTransition(operation, isGenerationCurrent, repairLatest, onController) {",
        ).substringBefore("\n}\n\nfunction SessionRow")
        assertTrue(
            sidebar.contains("const PUSH_TRANSITION_TIMEOUT_MS = 10_000") &&
                boundedTransition.contains("Promise.race([task, deadline])") &&
                boundedTransition.contains("isCurrent: isGenerationCurrent") &&
                !boundedTransition.contains("active = false") &&
                !boundedTransition.contains("controller.abort()") &&
                boundedTransition.contains("signal.addEventListener(\"abort\"") &&
                boundedTransition.contains(".finally(() => onController(null, controller))") &&
                boundedTransition.contains("clearTimeout(timeout)"),
            "a deadline releases the queue without poisoning a still-current user choice",
        )
        val queue = sidebar.substringAfter(
            "const queuePushTransition = useCallback((transition, desired, operation, warning) => {",
        ).substringBefore("\n  }, []);")
        val repair = sidebar.substringAfter("repairPushRef.current = () => {")
            .substringBefore("\n  const toggleGroup")
        assertTrue(
            sidebar.contains("const pushTransitionRef = useRef(Promise.resolve())") &&
                queue.contains("pushTransitionRef.current = pushTransitionRef.current") &&
                queue.contains("transition === pushTransitionIdRef.current") &&
                queue.contains("notifyEnabled() === desired") &&
                queue.contains("boundedPushTransition(") &&
                queue.contains("() => repairPushRef.current()") &&
                toggle.contains("transition,\n      next,") &&
                toggle.contains("pushSubscribe(permission, context)") &&
                toggle.contains("pushUnsubscribe(context)") &&
                toggle.contains(
                    "Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort())",
                ) &&
                repair.contains("const desired = notifyEnabled()") &&
                repair.contains("const repairGeneration = transition + \":\" + desired") &&
                repair.contains("pushRepairGenerationRef.current === repairGeneration") &&
                repair.contains("transition,\n      desired,") &&
                repair.contains("refreshPush(context)") &&
                repair.contains("pushUnsubscribe(context)"),
            "local ordering and the origin-wide choice guard mutations while stale repairs reread current intent",
        )
        val storageSync = sidebar.substringAfter(
            "const syncNotificationPreference = (event = null) => {",
        )
            .substringBefore("\n    };")
        val addStorageListenerAt = sidebar.indexOf(
            "window.addEventListener(\"storage\", syncNotificationPreference)",
        )
        val closeListenerGapAt = sidebar.indexOf("if (!syncNotificationPreference())")
        assertTrue(
            storageSync.contains("const next = notifyEnabled()") &&
                storageSync.contains("event.key === PUSH_REPAIR_SIGNAL_KEY") &&
                storageSync.contains("if (!preferenceChanged && !repairSignalled) return false") &&
                storageSync.contains("notifyOnRef.current = next") &&
                storageSync.contains("setNotifyOn(next)") &&
                storageSync.contains("++pushTransitionIdRef.current") &&
                storageSync.contains(
                    "Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort())",
                ) &&
                storageSync.contains("syncedTransition,\n        next,") &&
                storageSync.contains("next ? refreshPush(context) : pushUnsubscribe(context)") &&
                addStorageListenerAt >= 0 &&
                closeListenerGapAt > addStorageListenerAt &&
                sidebar.contains("window.removeEventListener(\"storage\", syncNotificationPreference)"),
            "another tab's choice or stale-mutation signal supersedes work, including the listener gap",
        )
        val notify = ctx.get("/lib/notify.js").bodyAsText()
        assertTrue(
            notify.contains("isPushActive"),
            "the in-tab notification stands down while push is active — otherwise an open tab shows two",
        )

        // The deep link is the only thing app.js and the (import-less) worker share, so pin both ends.
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(app.contains("DEEP_LINK_PARAM = \"session\""), "app.js reads ?session= on load")
        assertTrue(app.contains("select-session"), "app.js honours the worker's focus-and-switch message")
        val workerLayoutEffectAt = app.indexOf("useLayoutEffect(() => {")
        val addWorkerListenerAt = app.indexOf(
            "navigator.serviceWorker.addEventListener(\"message\", onMessage)",
        )
        val removeWorkerListenerAt = app.indexOf(
            "navigator.serviceWorker.removeEventListener(\"message\", onMessage)",
        )
        val workerLayoutEffectEndAt = app.indexOf(
            "\n  }, [selectSession]);",
            startIndex = addWorkerListenerAt.coerceAtLeast(0),
        )
        assertTrue(
            workerLayoutEffectAt >= 0 &&
                addWorkerListenerAt > workerLayoutEffectAt &&
                removeWorkerListenerAt in (addWorkerListenerAt + 1) until workerLayoutEffectEndAt,
            "the worker listener is installed during commit, before DOMContentLoaded can release queued messages",
        )
        val workerMessage = app.substringAfter("const onMessage = (event) => {")
            .substringBefore("\n    };")
        assertTrue(
            workerMessage.contains("deepLinkRef.current = msg.sessionId") &&
                workerMessage.contains("loadRef.current()"),
            "a notification target missing from a stale snapshot is retained and selected after a reload",
        )
        val knownTarget = workerMessage.substringAfter(
            "if (sessionsRef.current.some((session) => session.id === msg.sessionId)) {",
        ).substringBefore("\n      }")
        assertTrue(
            knownTarget.indexOf("deepLinkRef.current = null") in
                0 until knownTarget.indexOf("sessionsLoadVersionRef.current += 1") &&
                knownTarget.indexOf("sessionsLoadVersionRef.current += 1") in
                0 until knownTarget.indexOf("selectSession(msg.sessionId)"),
            "a known notification target invalidates older loads before it is selected immediately",
        )
        val reloadSelection = app.substringAfter("const wanted = deepLinkRef.current;")
            .substringBefore("\n    } catch (e) {")
        val targetGuard = reloadSelection.indexOf("if (target) {")
        assertTrue(
            targetGuard >= 0 &&
                reloadSelection.indexOf("deepLinkRef.current = null") > targetGuard &&
                reloadSelection.indexOf("showSession(target)") > targetGuard,
            "a list that does not contain the notification target leaves it retained for a later refresh",
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
    fun daemonServesTheStylesheets() = withServer { ctx ->
        val appCss = ctx.get("/style.css")
        assertEquals(HttpStatusCode.OK, appCss.status, "GET /style.css is served")
        assertContentTypeContains(appCss, "css")

        val xtermCss = ctx.get("/vendor/xterm.css")
        assertEquals(HttpStatusCode.OK, xtermCss.status, "GET /vendor/xterm.css (nested) is served")
        assertContentTypeContains(xtermCss, "css")
    }

    /**
     * The mobile layer (plan Task 16). A viewport cannot be observed from this binary, but every piece of
     * it is a file the daemon serves, and each failure is silent *here*: a drawer whose toggle was never
     * rendered, or a shell that lost `100dvh`, looks exactly like today's desktop UI in every other test
     * and only falls over on a phone. So pin both ends — the CSS that defines the narrow-screen behaviour
     * and the markup it needs to find.
     */
    @Test
    fun theWebUiShipsTheMobileDrawerAndViewportRules() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(css.contains("100dvh"), "the app shell is sized against the DYNAMIC viewport height")
        assertTrue(css.contains("env(safe-area-inset-"), "the shell pads the notch / home-indicator insets")
        assertTrue(
            css.contains("overscroll-behavior: none"),
            "pull-to-refresh must not reload the page and drop a live terminal attach",
        )
        assertTrue(css.contains("@media (max-width: 720px)"), "one width breakpoint drives the mobile layout")
        assertTrue(css.contains("#sidebar.open"), "below it the sidebar is a drawer with an open state")
        assertTrue(
            css.contains("content: attr(data-icon)"),
            "and the lifecycle controls collapse to the icons the markup declares",
        )

        // Every lifecycle control must carry BOTH halves of that collapse: the icon the narrow layout
        // draws, and an aria-label so the accessible name survives the label being sized to 0.
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        val buttons = pane.split("<button")
        val drawerButton = assertNotNull(
            buttons.firstOrNull { it.contains("id=\"drawer-toggle\"") },
            "the terminal header carries the drawer opener",
        )
        assertTrue(
            drawerButton.contains("onClick=\${onToggleDrawer}"),
            "the visible drawer opener is bound to the handler supplied by App",
        )
        for (id in listOf("attach-button", "interrupt-button", "resume-button",
                          "detach-button", "stop-button", "done-button")) {
            val markup = assertNotNull(
                buttons.firstOrNull { it.contains("id=\"$id\"") },
                "the terminal header still renders #$id",
            )
            assertTrue(markup.contains("data-icon="), "#$id declares the icon its narrow-screen form shows")
            assertTrue(markup.contains("aria-label="), "#$id keeps its name when the label collapses")
        }

        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(sidebar.contains("drawerOpen"), "the sidebar takes the drawer state from the app")
        assertTrue(sidebar.contains("\"open\""), "and turns it into the class the media query styles")
        assertTrue(sidebar.contains("id=\"drawer-close\""), "the drawer can be dismissed from inside it")

        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(app.contains("drawer-scrim"), "a tap outside the drawer closes it")
        assertTrue(
            app.contains("onToggleDrawer=\${toggleDrawer}"),
            "App connects its drawer state transition to TerminalPane's opener prop",
        )
        assertTrue(
            app.contains("setDrawerOpen(false)"),
            "selecting a session closes the drawer — the terminal is behind it",
        )
    }

    /**
     * The phone keyboard and xterm both live outside this native test process, so pin the complete
     * browser-side contract: the visual viewport drives a bounded host and a fit/resize report, every
     * listener has a matching teardown, focus is entered only through a tap, and the persisted three-step
     * font preference reaches both a fresh and an already-open terminal.
     */
    @Test
    fun theWebUiShipsKeyboardAwareTerminalSizingAndFontPreferences() = withServer { ctx ->
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        val css = ctx.get("/style.css").bodyAsText()

        assertTrue(pane.contains("window.visualViewport"), "terminal sizing reads the visual viewport")
        for (event in listOf("resize", "scroll")) {
            assertTrue(
                pane.contains("viewport.addEventListener(\"$event\", viewportChanged)"),
                "visualViewport $event changes refit the terminal",
            )
            assertTrue(
                pane.contains("viewport.removeEventListener(\"$event\", viewportChanged)"),
                "visualViewport $event listener is detached with the terminal",
            )
        }
        val initialSizingAt = pane.indexOf("sizeForVisualViewport();")
        val socketAt = pane.indexOf("const ws = new WebSocket")
        assertTrue(
            initialSizingAt >= 0 && socketAt >= 0 && initialSizingAt < socketAt,
            "the visible height is applied before the terminal WebSocket captures its OPEN geometry",
        )
        val viewportChanged = pane.substringAfter("const viewportChanged = () => {")
            .substringBefore("\n    };")
        assertTrue(
            viewportChanged.contains("sizeForVisualViewport();") &&
                viewportChanged.contains("refit();"),
            "each visualViewport change reapplies the height and schedules a terminal fit/report",
        )
        assertTrue(pane.contains("refit.cancel()"), "a pending debounced fit cannot run after unmount")
        assertTrue(
            pane.contains("host.addEventListener(\"click\", focusTerminal)") &&
                pane.contains("host.removeEventListener(\"click\", focusTerminal)"),
            "the tap-to-focus listener has a matching teardown",
        )
        assertEquals(
            1,
            Regex("""term\.focus\(\)""").findAll(pane).count(),
            "xterm is focused only inside the synchronous tap handler, never when the socket opens",
        )
        assertTrue(
            pane.contains("host.classList.add(\"visual-viewport-sized\")") &&
                pane.contains("host.classList.remove(\"visual-viewport-sized\")"),
            "the visual-viewport host state is applied and removed with the terminal",
        )
        assertTrue(
            css.contains("#terminal-host.visual-viewport-sized") &&
                css.contains("max-height: var(--terminal-visible-height)"),
            "CSS caps the growing terminal host at the height calculated from visualViewport",
        )
        assertTrue(
            css.contains(".xterm-helper-textarea { font-size: 16px !important; }"),
            "Safari cannot auto-zoom xterm's hidden textarea and corrupt visualViewport geometry",
        )

        val prefs = ctx.get("/lib/prefs.js").bodyAsText()
        assertTrue(
            prefs.contains("TERMINAL_FONT_SIZES = [11, 13, 16]"),
            "the terminal preference has exactly the three supported steps",
        )
        assertTrue(
            prefs.contains("Number.parseInt(raw && raw.terminalFontSize, 10)") &&
                prefs.contains("TERMINAL_FONT_SIZES.includes(fontSize)"),
            "stored string values are coerced and accepted only when they are a supported step",
        )
        assertTrue(
            prefs.contains(": DEFAULT_PREFS.terminalFontSize"),
            "missing, corrupt and out-of-range stored values fall back to the default",
        )

        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(
            dialogs.contains("id=\"prefs-terminal-font-size\"") &&
                dialogs.contains("TERMINAL_FONT_SIZES.map") &&
                dialogs.contains("TERMINAL_FONT_LABELS.get(size)"),
            "Preferences exposes all three terminal font steps",
        )
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("""terminalFontSize=${'$'}{prefs.terminalFontSize}"""),
            "the sanitized preference is threaded into TerminalPane",
        )
        assertTrue(
            pane.contains("fontSize: fontSizeRef.current"),
            "a newly attached xterm starts at the preferred font size",
        )
        assertTrue(
            pane.contains("term.options.fontSize = terminalFontSize") &&
                pane.contains("}, [terminalFontSize]);"),
            "changing the preference updates and re-fits the live xterm without reconnecting it",
        )
    }

    /**
     * The special-key bar is browser-only, so pin both its byte-exact terminal protocol and the edge
     * contracts around it: a closed socket drops input, stale teardown cannot clear a replacement sender,
     * non-printable/multi-character input cannot consume sticky Ctrl, and the toolbar never takes pointer
     * focus.
     */
    @Test
    fun theWebUiShipsTheMobileSpecialKeysBar() = withServer { ctx ->
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        val keyBar = ctx.get("/components/KeyBar.js").bodyAsText()
        val css = ctx.get("/style.css").bodyAsText()

        assertTrue(
            pane.contains("import { KeyBar } from \"./KeyBar.js\"") &&
                pane.contains("const sendBytesRef = useRef(null)"),
            "TerminalPane imports KeyBar and owns its live WebSocket send seam",
        )
        assertTrue(
            pane.contains("if (ws.readyState === WebSocket.OPEN) ws.send(bytes)") &&
                pane.contains("sendBytesRef.current = sendBytes"),
            "special keys become binary frames only while this terminal socket is open",
        )
        assertTrue(
            pane.contains("if (sendBytesRef.current === sendBytes) sendBytesRef.current = null"),
            "terminal teardown clears only its own sender, never a replacement socket's seam",
        )
        for (prop in listOf(
            "barRef=\${keyBarRef}",
            "sendBytesRef=\${sendBytesRef}",
            "ctrlActive=\${ctrlActive}",
            "onToggleCtrl=\${toggleCtrl}",
            "onReleaseCtrl=\${releaseCtrl}",
        )) {
            assertTrue(pane.contains(prop), "TerminalPane wires KeyBar's $prop prop")
        }

        val expectedKeys = mapOf(
            "Escape" to "0x1b",
            "Tab" to "0x09",
            "Shift Tab" to "0x1b, 0x5b, 0x5a",
            "Up arrow" to "0x1b, 0x5b, 0x41",
            "Down arrow" to "0x1b, 0x5b, 0x42",
            "Left arrow" to "0x1b, 0x5b, 0x44",
            "Right arrow" to "0x1b, 0x5b, 0x43",
            "Control C" to "0x03",
        )
        for ((name, bytes) in expectedKeys) {
            assertTrue(
                keyBar.contains("name: \"$name\", bytes: [$bytes]"),
                "$name sends the planned raw terminal bytes",
            )
        }
        assertTrue(
            keyBar.contains("const NAVIGATION_KEYS") &&
                keyBar.contains("const CONTROL_KEYS") &&
                !keyBar.contains("SPECIAL_KEYS.slice"),
            "the keys around the Ctrl modifier are explicit groups rather than a hidden array index",
        )
        assertTrue(
            keyBar.contains("Uint8Array.from(key.bytes)"),
            "the toolbar sends binary data, not WebSocket text frames reserved for resize controls",
        )
        assertTrue(
            keyBar.contains("releasesCtrl: true") && keyBar.contains("onReleaseCtrl()"),
            "the dedicated Ctrl-C key disarms an already-pressed Ctrl modifier",
        )

        assertTrue(
            pane.contains("const ctrlActiveRef = useRef(false)") &&
                pane.contains("const ctrlBytes = ctrlBytesFor(data)") &&
                Regex(
                    """(?s)if \(ctrlBytes !== null\) \{.*?ctrlActiveRef\.current = false;""" +
                        """.*?setCtrlActive\(false\);.*?sendBytes\(ctrlBytes\);""",
                ).containsMatchIn(pane),
            "sticky Ctrl is consumed synchronously by the next printable xterm input",
        )
        assertTrue(
            pane.contains("if (chars.length !== 1) return null") &&
                pane.contains("codePoint < 0x20") &&
                pane.contains("return new TextEncoder().encode(char)"),
            "multi-character paste, escape/control input, and unsupported printable keys take safe paths",
        )
        assertTrue(
            keyBar.contains("aria-pressed=\${ctrlActive ? \"true\" : \"false\"}") &&
                css.contains(".key-bar-key[aria-pressed=\"true\"]"),
            "the one-shot Ctrl state is both accessible and visibly pressed",
        )
        assertTrue(
            keyBar.contains("onPointerDown=\${preserveTerminalFocus}") &&
                keyBar.contains("event.preventDefault()") &&
                !keyBar.contains(".focus("),
            "pointer use of the bar preserves xterm's focused textarea",
        )

        assertTrue(
            Regex(
                """(?s)\${'$'}\{attached\s*&&\s*html`\s*<\${'$'}\{KeyBar\}""",
            ).containsMatchIn(pane),
            "the toolbar is rendered only while the selected session has an attached terminal",
        )
        assertTrue(
            pane.contains("const keyBarHeight = keyBarRef.current?.getBoundingClientRect().height || 0") &&
                pane.contains("visibleBottom - bounds.top - keyBarHeight"),
            "visualViewport sizing reserves room for the toolbar above the software keyboard",
        )

        val breakpoint = css.indexOf("@media (max-width: 720px)")
        assertTrue(breakpoint > 0, "the mobile breakpoint exists")
        val nextMedia = css.indexOf("@media ", breakpoint + 1).let { if (it < 0) css.length else it }
        val desktopCss = css.substring(0, breakpoint)
        val mobileCss = css.substring(breakpoint, nextMedia)
        assertTrue(
            Regex("""(?s)\.key-bar\s*\{[^}]*display:\s*none""").containsMatchIn(desktopCss),
            "the toolbar consumes no terminal height on wider screens",
        )
        assertTrue(
            Regex("""(?s)\.key-bar\s*\{[^}]*display:\s*flex""").containsMatchIn(mobileCss),
            "the toolbar is visible at the mobile breakpoint",
        )
    }

    /**
     * A suspended mobile page can lose only its terminal WebSocket while the events stream later heals
     * itself. There is no browser runner in this native suite, so pin the complete source-side lifecycle:
     * the exact closed attachment is remembered, foregrounding schedules one render-separated attempt,
     * and the attempt rechecks selection/liveness before it can open a replacement.
     */
    @Test
    fun theWebUiReattachesAClosedAliveTerminalAfterBackgrounding() = withServer { ctx ->
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        val app = ctx.get("/app.js").bodyAsText()

        assertTrue(
            pane.contains("closedRef.current(attachedId)"),
            "a terminal close reports the socket's id instead of whichever session is active later",
        )
        assertTrue(
            app.contains("const reattachIdRef = useRef(null)") &&
                app.contains("const reattachTimerRef = useRef(null)") &&
                app.contains("const reattachRequestRef = useRef(null)") &&
                app.contains("const reattachAvailableRef = useRef(false)") &&
                !app.contains("reattachPendingRef"),
            "the app keeps one candidate, one queued timer, one owned async request, and one attempt grant",
        )
        assertTrue(
            app.contains("document.addEventListener(\"visibilitychange\", reconnectWhenVisible)") &&
                app.contains("document.removeEventListener(\"visibilitychange\", reconnectWhenVisible)"),
            "the foreground listener has a matching teardown",
        )

        val schedule = app.substringAfter("const scheduleReattach = useCallback(() => {")
            .substringBefore("\n  }, []);")
        val freshSessionAt = schedule.indexOf("\"/sessions/\" + encodeURIComponent(id)")
        val attachAt = schedule.indexOf("setAttachedId(id)")
        assertTrue(
            freshSessionAt >= 0 && attachAt > freshSessionAt,
            "a foreground retry asks the daemon for current liveness before restoring the attachment",
        )
        assertTrue(
            app.contains("const REATTACH_LIVENESS_TIMEOUT_MS = 10_000") &&
                schedule.contains("const controller = new AbortController()") &&
                schedule.indexOf("reattachRequestRef.current = controller") in
                0 until schedule.indexOf("if (previousRequest) previousRequest.abort()") &&
                schedule.contains("() => controller.abort()") &&
                schedule.contains("{ signal: controller.signal }") &&
                schedule.contains("clearTimeout(livenessTimeout)") &&
                schedule.contains(
                    "if (reattachRequestRef.current === controller) reattachRequestRef.current = null",
                ),
            "a stalled liveness request is bounded and only its current owner may clean up the attempt",
        )
        for (guard in listOf(
            "reattachRequestRef.current !== controller",
            "reattachIdRef.current !== id",
            "document.visibilityState !== \"visible\"",
            "activeRef.current !== id",
            "pendingRef.current",
            "!isAliveState(s.state)",
        )) {
            assertTrue(
                schedule.indexOf(guard, startIndex = freshSessionAt) in (freshSessionAt + 1) until attachAt,
                "$guard is re-checked after the daemon response and before a replacement terminal is attached",
            )
        }
        assertTrue(
            schedule.indexOf("reattachTimerRef.current = setTimeout(async () => {") >= 0 &&
                schedule.indexOf("!reattachAvailableRef.current") in 0 until freshSessionAt &&
                schedule.indexOf("reattachTimerRef.current !== null") in 0 until freshSessionAt,
            "the timer handle guards the queued callback before controller identity owns the async work",
        )
        assertTrue(
            schedule.indexOf("if (!id || document.visibilityState !== \"visible\") return") in
                0 until schedule.indexOf("reattachAvailableRef.current = false"),
            "a foreground timer that wins the race with the close callback preserves the one available retry",
        )
        assertTrue(
            Regex(
                """(?s)if \(!s \|\| !isAliveState\(s\.state\)\) \{.*?""" +
                    """setHint\(deadHint\(s && s\.state\)\);.*?return;""",
            )
                .containsMatchIn(schedule),
            "a fresh daemon response that says the session died is explained and never reattached",
        )
        val failedRefresh = schedule.substringAfter("} catch (_) {")
            .substringBefore("\n      } finally {")
        assertTrue(
            failedRefresh.indexOf("reattachRequestRef.current !== controller") in
                0 until failedRefresh.indexOf("reattachIdRef.current = null") &&
                failedRefresh.indexOf("reattachIdRef.current = null") in
                0 until failedRefresh.indexOf("setHint(detachedHint(null))"),
            "only the owning failed refresh consumes the retry, without claiming stale liveness",
        )
        assertTrue(
            schedule.indexOf("reattachIdRef.current = null") in 0 until attachAt,
            "the one-shot candidate is consumed before opening its replacement",
        )

        val visible = app.substringAfter("const reconnectWhenVisible = () => {")
            .substringBefore("\n    };")
        assertTrue(
            visible.contains("const visible = document.visibilityState === \"visible\"") &&
                visible.contains("reattachAvailableRef.current = visible") &&
                visible.contains("if (!visible)") &&
                visible.contains("reattachRequestRef.current = null") &&
                visible.contains("if (request) request.abort()") &&
                !visible.contains("reattachIdRef.current = null") &&
                visible.contains("scheduleReattach()"),
            "hiding aborts stale async work but retains its candidate; foregrounding grants a fresh attempt",
        )

        val cancel = app.substringAfter("const cancelReattach = useCallback(() => {")
            .substringBefore("\n  }, []);")
        assertTrue(
            cancel.contains("clearTimeout(reattachTimerRef.current)") &&
                cancel.contains("reattachAvailableRef.current = false") &&
                cancel.contains("reattachIdRef.current = null") &&
                cancel.indexOf("reattachRequestRef.current = null") in
                0 until cancel.indexOf("if (request) request.abort()"),
            "explicit intent invalidates ownership before aborting async work and clears the candidate/timer",
        )

        val closed = app.substringAfter("const onTerminalClosed = useCallback((id) => {")
            .substringBefore("\n  }, [scheduleReattach]);")
        assertTrue(
            closed.contains("sessionsRef.current.find((session) => session.id === id)") &&
                closed.contains("setAttachedId((current) => (current === id ? null : current))"),
            "a stale close can clear only its own attachment",
        )
        assertTrue(
            closed.contains("reattachIdRef.current = id") &&
                closed.contains("setHint(detachedHint(s))") &&
                closed.contains("document.visibilityState === \"visible\"") &&
                closed.contains("scheduleReattach()"),
            "a close arriving after the foreground timer fills the candidate and schedules the retained retry",
        )
        assertTrue(
            app.contains("onTerminalClosed=\${onTerminalClosed}"),
            "App connects TerminalPane's close callback to the foreground reattachment handler",
        )
        assertTrue(
            app.contains("setHint(detachedHint(s))") &&
                app.contains("\"Detached from \"") &&
                app.contains("\"Terminal detached.\""),
            "manual and failed detaches share the existing user-facing hint",
        )
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

    @Test
    fun versionApiIsAuthenticatedAndOutranksTheStaticCatchAll() = withServer { ctx ->
        // No credential reaches the literal, authenticated API route and is rejected there. If the open
        // static catch-all had won instead, this missing file would answer 404.
        assertEquals(HttpStatusCode.Unauthorized, ctx.get("/version").status)

        val resp = ctx.get("/version") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "the literal /version API route outranks the static catch-all")
        assertContentTypeContains(resp, "json")
        val body = resp.bodyAsText()
        assertEquals("""{"version":"$currentVersion"}""", body, "the server returns the injected display version")
        assertEquals(
            VersionDto(currentVersion),
            TRANSPORT_JSON.decodeFromString(VersionDto.serializer(), body),
            "the response is the public VersionDto wire shape",
        )
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

    /**
     * Assert [bytes] really are a square PNG of [size] pixels, read out of the file's own IHDR rather than
     * trusted from the manifest or the filename. The icons are rendered by hand (`qlmanage` + `sips`) and
     * committed, so "the 192 slot holds a 512 render" is a mistake nothing else in the repo would catch.
     */
    private fun assertPngOfSize(bytes: ByteArray, size: Int, what: String) {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // \x89 P N G \r \n \x1a \n
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
