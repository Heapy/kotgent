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

/**
 * **What the daemon serves, and at which address** — plus a short, closed list of source-guards that no
 * running page could make about itself.
 *
 * ## What this file is
 * The assembled [KotgentServer] really serves the SPA out of `resources/webui`: `GET /` is `index.html`,
 * every ES module and vendored bundle answers 200 with a sensible content type and a non-empty body, the
 * PWA install surface (manifest, icons, apple tags) is complete and internally consistent, the content
 * revision addresses the whole import graph and drives the one caching rule, the service worker is served
 * from the ROOT so its scope covers the app, the static catch-all is mounted UNauthenticated (the browser
 * fetches the bootstrap before it has any credential) and does NOT shadow the token-gated API. The module
 * list in [daemonServesTheComponentAndLibModules] is a **registry**: every served module is entered there
 * once, the moment the file exists.
 *
 * ## What this file is no longer
 * It used to hold 49 tests, most of them reading the served JavaScript and CSS as TEXT — because the
 * macosArm64 test binary has no JavaScript engine and this repository had no browser harness. It has one
 * now (`webuitest/`, Playwright against a real Chromium driving the `webuicheck` harness), so the palette,
 * the dialogs, the drawer, the swipe bridge, the reattach, the key bar, the uploads, the unicode gate and
 * the layout are exercised as BEHAVIOUR and their greps are gone rather than kept as a second, weaker
 * statement of the same thing. The last to go was the session-list protocol itself — the unread badge and
 * its retry loop, the "Loading sessions…" first paint and the two latched announcements, which had held
 * out in [daemonServesTheAppEntryModule] because nothing else could see them.
 *
 * The clearest artefact of why they had to go is `webUiExposesThePreferencesScreen`, deleted with the
 * rest: it asserted that `app.js` contains the literal `if (sameForm) closeDialogFrom(submittedDialog)` —
 * the exact source line that WAS the Preferences bug the browser tier found. This tier had not merely
 * failed to catch that defect; it had pinned it as a contract, and it broke when the bug was fixed. A test
 * that spells out an implementation line cannot tell a fix from a regression.
 *
 * So the rule for anything added here: if a Chromium could answer it, it belongs in `webuitest/`. Only
 * three kinds of claim stay — an address (what the daemon serves and with which headers), an agreement
 * between two files that never read each other, and a negative claim about the shape of the source.
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
        val rev = revisionOf(body)
        assertTrue(
            body.contains("src=\"/_v/$rev/app.js\""),
            "index.html bootstraps app.js through its content-revisioned URL",
        )
        assertTrue(body.contains("/_v/$rev/vendor/xterm.js"), "index.html loads the vendored xterm.js")
        assertContentTypeContains(resp, "html")
    }

    /**
     * The entry module's serving contract, and deliberately nothing else.
     *
     * `app.js` is the one module `index.html` names directly (through its revisioned URL — see
     * [daemonServesIndexHtmlAtRoot]), so it is not in [daemonServesTheComponentAndLibModules]' registry
     * and owns its address here. It is also the biggest file in the SPA, which is how this test grew to
     * 114 lines and 35 greps: identifier names (`sessionsFrameRef.current(msg)`, `pruneReadPosters`,
     * `READ_RETRY_DELAY_MS`), the relative order of four substrings inside one callback body, and two
     * regex occurrence counts. Its own comment explained why — "there is no JS harness, so these greps are
     * what stops the whole feature from being deleted". There is a harness now, and every one of those
     * claims is made by a Chromium that RUNS the file:
     *
     * - the unread badge, its mark-read POST and the retry loop's three answers, the "Loading sessions…"
     *   first paint, the two latched announcements and the absence of any wholesale `GET /sessions` —
     *   `webuitest/test/SidebarTest.kt`;
     * - the events socket's reconnect and who may spend a reattach candidate — `TerminalReattachTest`;
     * - the notify edge — `TaskBadgeTest`; the deep link — `RouterTest`; the footer's version —
     *   `SidebarTest`; starting and controlling sessions — `SessionDialogsTest` and `CommandPaletteTest`.
     *
     * One claim was dropped without a replacement and is recorded rather than smuggled into the
     * source-guard section below: that no null-guard filters `msg.model` out of an incoming patch (a
     * model the daemon CLEARED must clear on screen). A browser could answer it — the daemon really sends
     * `model: null` — but the `webuicheck` harness has no command that clears a model, so the fixture, not
     * the browser, is what is missing. The daemon's half is `TransportTest`'s, and `lib/sessions.js`
     * taking the field verbatim is asserted in [daemonServesTheComponentAndLibModules].
     */
    @Test
    fun daemonServesTheAppEntryModule() = withServer { ctx ->
        val resp = ctx.get("/app.js")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /app.js is served")
        assertContentTypeContains(resp, "javascript")
        // An empty file is served with a perfectly good 200 and a perfectly good content type.
        assertTrue(resp.bodyAsText().isNotEmpty(), "GET /app.js is not an empty file")
        // Stated here as well as in [revisionedAssetsAreImmutableAndEverythingElseRevalidates] for the
        // same reason [daemonServesTheServiceWorkerAtTheRootScope] states its own: this address is the
        // one an old shell's cached URL would use, and pinning the entry module is how a deploy stops
        // arriving at all.
        assertEquals(
            "no-cache",
            resp.headers[HttpHeaders.CacheControl],
            "unprefixed, the entry module revalidates rather than being pinned in a browser cache",
        )
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
        // Unlike a relative import inside app.js, an import-map target resolves against the DOCUMENT, so
        // it does not inherit app.js's revision prefix and has to carry one of its own.
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

        // htm's Preact binding re-exports from both bare specifiers; the hooks build imports 'preact'.
        val htmPreact = ctx.get("/_v/$rev/vendor/htm-preact.module.js").bodyAsText()
        assertTrue(htmPreact.contains("\"preact\""), "htm/preact imports the bare 'preact' specifier")
        assertTrue(htmPreact.contains("\"htm\""), "htm/preact imports the bare 'htm' specifier")
        assertTrue(
            ctx.get("/_v/$rev/vendor/preact-hooks.module.js").bodyAsText().contains("\"preact\""),
            "the hooks build imports the bare 'preact' specifier",
        )
    }

    /**
     * **The registry of served modules.** Every ES module under `resources/webui` is listed here exactly
     * once, and the loop is the whole point: a browser test that loads the SPA proves a module is served
     * only transitively and silently (an unresolvable import just breaks the page it happens to be on),
     * and a module nothing imports yet — one a later task will fill in — has nothing to prove it at all.
     * A registry entry that exists only as a side effect of some other suite passing is not an entry.
     *
     * Add a new module to the list the moment the file exists. The assertions after the loop are the
     * handful of export names another module imports BY NAME, where a rename breaks the whole SPA at load
     * time in a way the 200 + content-type loop cannot see.
     */
    @Test
    fun daemonServesTheComponentAndLibModules() = withServer { ctx ->
        for (path in listOf(
            "/lib/paths.js", "/lib/prefs.js", "/lib/api.js", "/lib/sessions.js", "/lib/qr.js",
            "/lib/notify.js", "/lib/push.js", "/lib/agents.js", "/lib/commands.js",
            "/lib/clipboard.js", "/lib/unicode.js",
            // The task layer's modules. Registered here from the moment they exist, so a wave-2 task
            // that fills one in never has to touch this shared suite to prove it is served.
            "/lib/router.js", "/lib/tasks.js",
            "/components/Sidebar.js", "/components/TerminalPane.js", "/components/KeyBar.js",
            "/components/dialogs.js", "/components/CommandPalette.js",
            "/components/Board.js", "/components/TaskCard.js", "/components/TaskDetail.js",
        )) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path (nested module) is served")
            assertContentTypeContains(resp, "javascript")
            // An empty file is served with a perfectly good 200 and a perfectly good content type.
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
        // app.js imports these by name — a rename (or an empty file) would break the entire SPA at load
        // time, which the 200 + content-type loop above cannot see. The rev comparison is the load-bearing
        // half of the protocol: helpers that applied frames unconditionally would let a stale HTTP DTO
        // roll back a fresher WS frame, and the applied rev must land ON the row or if-newer self-destructs.
        val sessionHelpers = ctx.get("/lib/sessions.js").bodyAsText()
        assertTrue(
            sessionHelpers.contains("export function upsertIfNewer") &&
                sessionHelpers.contains("export function patchIfNewer"),
            "the newest-rev-wins appliers are exported under the names app.js imports",
        )
        assertTrue(
            sessionHelpers.contains("if (!(row.rev > list[index].rev)) return list;") &&
                sessionHelpers.contains("if (!(msg.rev > prev.rev)) return list;"),
            "both appliers compare revs and keep the fresher row",
        )
        assertTrue(
            sessionHelpers.contains("model: msg.model") && sessionHelpers.contains("rev: msg.rev"),
            "the patch applier takes the model verbatim (null included) and stamps the frame's rev on the row",
        )
        // Before the first snapshot an empty list is not yet a fact: the sidebar must say it is loading
        // rather than show an honest-looking but false "No sessions yet".
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(
            sidebar.contains("!sessionsReady") && sidebar.contains("Loading sessions…"),
            "the sidebar distinguishes 'not loaded yet' from 'genuinely empty'",
        )
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
        val rev = revisionOf(body)
        assertTrue(
            body.contains("name=\"color-scheme\" content=\"dark\"") &&
                body.contains("href=\"/_v/$rev/style.css\"") &&
                body.contains("src=\"/_v/$rev/app.js\""),
            "the installed iOS app declares dark system UI and fetches content-revisioned assets",
        )
        // The manifest and the home-screen icon deliberately keep stable URLs: an installed PWA refers to
        // them by a fixed address, so a revision in their path would be churn, not invalidation.
        // ROOT-absolute, not document-relative: the SPA owns deep paths now, and at `/tasks/local:42` a
        // relative `manifest.webmanifest` would resolve to `/tasks/manifest.webmanifest` and 404 — taking
        // the iOS install path, and therefore push, with it for anyone who arrived by a deep link.
        assertTrue(
            body.contains("href=\"/manifest.webmanifest\"") &&
                body.contains("href=\"/icons/apple-touch-icon.png\"") &&
                body.contains("href=\"/icons/logo.svg\""),
            "the install surface stays on stable, root-absolute URLs the installed app can keep referring to",
        )
    }

    /**
     * The one caching rule. An asset reached through a valid `/_v/<rev>/` prefix is content-addressed, so
     * its bytes can never change under that URL and it is cached forever. Everything else revalidates:
     * the shell (which carries the revision, so caching it would pin every asset URL with it), the worker
     * (browsers cap a worker script at 24h of freshness), the manifest and icons — and any asset reached
     * WITHOUT the prefix, e.g. from a stale bookmark. That last group used to be served with no caching
     * header at all, i.e. under the browser's own heuristic freshness, which is precisely what the
     * hand-bumped `?v=` token existed to escape.
     */
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
        // Neither entry point may become immutable however it was addressed: the shell hands out every
        // other asset URL, and the worker's root scope depends on its own path.
        for (path in listOf("/_v/$rev/index.html", "/_v/$rev/sw.js")) {
            assertEquals(
                "no-cache",
                ctx.get(path).headers[HttpHeaders.CacheControl],
                "GET $path revalidates even through the revision prefix",
            )
        }
    }

    /**
     * The substitution itself, and the proof that the hand-maintained scheme is gone. A surviving `?v=`
     * would mean someone re-introduced a token that has to be bumped by hand — and it would be bumped for
     * three files out of thirty-four, which is what made the old scheme silently miss changes. A surviving
     * `__REV__` would mean the daemon served a URL that never changes.
     */
    @Test
    fun theServedShellCarriesARealRevisionAndNoHandBumpedToken() = withServer { ctx ->
        val index = ctx.get("/").bodyAsText()
        assertFalse(index.contains(WEBUI_REV_PLACEHOLDER), "the daemon substituted the revision placeholder")
        assertFalse(index.contains("?v="), "no asset is fetched with a hand-bumped cache-busting query")
        assertTrue(isRevToken(revisionOf(index)), "the substituted revision is a real content hash")

        // The rest of the graph inherits the prefix from app.js's own URL, so no import may spell a
        // version of its own — one that did would also be a second module instance of the same file.
        assertFalse(ctx.get("/app.js").bodyAsText().contains("?v="), "app.js imports carry no query version")
    }

    /**
     * The prefix is an address, not a filter: the same bytes sit behind it, and an unrecognised revision is
     * still served. Refusing one would break the single real race — a shell fetched just before a daemon
     * update asking for its assets just after it — for no gain, since a client can only hold an old
     * revision's URL from an old shell, which it cannot have (the shell is `no-cache`).
     */
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

        // A revision this server never minted is the one dangerous case: `immutable` on a URL that cannot
        // change — a failed substitution — would pin the file in every cache forever.
        val bogus = ctx.get("/_v/${WEBUI_REV_PLACEHOLDER}/app.js")
        assertEquals(HttpStatusCode.OK, bogus.status, "a malformed revision still serves the asset")
        assertEquals(
            "no-cache",
            bogus.headers[HttpHeaders.CacheControl],
            "a revision this server never minted must revalidate, not pin the asset forever",
        )
    }

    /**
     * The prefix is stripped BEFORE the traversal guard runs, so a `..` underneath it still reaches that
     * guard instead of being hidden by the prefix. Asserted on the pure split rather than over HTTP,
     * because a client normalises `..` out of a URL before it is ever sent.
     */
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

    /**
     * The guarantee itself: a changed byte anywhere under the web UI directory changes the revision, and
     * with it every asset URL the shell hands out — which is what replaces remembering to bump a token.
     * Checked over a throwaway tree, since the served one cannot be mutated from a test.
     */
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

            // The path is hashed alongside the content, so a rename counts even though no byte moved.
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

    /**
     * The service worker — the half of the notification path that runs when no tab does. Three things
     * about it are addresses, and all three are here: that it is served at all (a 404 makes
     * `register("/sw.js")` reject and push silently never works), that it is served from the ROOT so its
     * scope covers `/` (a worker under `/lib/` could never control the app), and that it revalidates (a
     * cached worker keeps an old push handler alive for up to 24h after a deploy).
     *
     * What the worker DOES is not read here any more. Its event handlers, its payload-less `/sessions`
     * fetch, the abort deadline, the rotation ordering and the preference queue used to be asserted as
     * source text; a delivered push is the only thing that can actually exercise them, and that needs a
     * real push service no headless browser has — so they belong to the plan's manual checklist, not to a
     * grep that passes whether or not the handler works. The one exception is the deep-link parameter,
     * whose two spellings live in two files that cannot import each other; see
     * [theDeepLinkParameterIsTheOneTheServiceWorkerBuilds].
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
    }

    /**
     * The one thing about Web Push that is a SERVING statement: the browser module the daemon serves
     * addresses exactly the routes the daemon mounts.
     *
     * `lib/push.js` writes three bare paths (the `/api/v1` prefix is added once, in `lib/api.js`) and the
     * worker's own `/sw.js`; `PushRoutes.kt` declares the same three as constants. Nothing else ties the
     * two files together, so a route renamed on the server leaves a page that fetches a 404 and silently
     * never becomes a push target. Comparing the served text against the Kotlin constants is what fails
     * here instead of in a browser nobody is watching.
     *
     * Everything else this test used to assert now lives where it belongs. That the routes are mounted,
     * sit inside `authenticated`, answer `401` without a credential, refuse a foreign `Origin` and
     * validate their bodies is `PushRoutesTest` — repeating it here would be a second, weaker copy. And
     * the browser half — permission from the click, `pushManager.subscribe`, delivery, unsubscribe — is
     * not automatable at all: it needs a real push service, which no headless browser has, so it stays in
     * the plan's Post-Completion manual checklist.
     */
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

    /**
     * The two Unicode addons are really vendored, really served, and really export the names
     * `lib/unicode.js` constructs.
     *
     * This is the serving half of the opt-in arrangement, and it has to be stated here because the
     * behavioural half proves the opposite: `webuitest/test/MobileFeaturesTest.kt` shows that NOTHING
     * fetches an addon until Preferences selects one, watches the single revisioned request that the
     * choice produces, and reads the live terminal's `unicode.activeVersion` going 6 → 11. A gate that
     * never opens looks identical to a gate over an addon that was never committed — so the file on disk,
     * its real width tables and its export name are what this test answers for, and the browser answers
     * for the gate.
     */
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
        // With the static catch-all mounted, an authenticated GET /api/v1/sessions must still route to the
        // API (200 + JSON list), NOT be swallowed by the `/{path...}` file route (which would 404).
        val resp = ctx.get("$API_PREFIX/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "the literal API route outranks the static catch-all")
        assertEquals("[]", resp.bodyAsText().trim(), "the API (empty session list), not a static file, answered")

        // The other half of the same property, and the one Task 17's SPA fallback is built on: the bare
        // path is now the SPA's, so it reaches the catch-all — which has no such file and says so.
        val bare = ctx.get("/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, bare.status, "the bare /sessions now falls through to the SPA route")
        assertEquals("not found", bare.bodyAsText().trim(), "the static catch-all answered it, not the API")
    }

    @Test
    fun versionApiIsAuthenticatedAndOutranksTheStaticCatchAll() = withServer { ctx ->
        // No credential reaches the literal, authenticated API route and is rejected there. If the open
        // static catch-all had won instead, this missing file would answer 404.
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

        // Bare `/version` is open static ground now: no credential, and the catch-all — not the gate —
        // answers. A 401 here would mean the API is still mounted at the SPA's path.
        assertEquals(HttpStatusCode.NotFound, ctx.get("/version").status, "the bare path is the SPA's, and 404s")
    }

    // --- deliberate source-guards --------------------------------------------------------------------
    //
    // Five statements that a running page cannot make about itself, each for one of three reasons: it is
    // an agreement between two FILES that never read each other (the API prefix, the deep-link parameter,
    // the board's CSS vocabulary), it is a comparison against a Kotlin constant a JVM browser-test module
    // cannot import (the project-name cap), or it is a NEGATIVE claim about the shape of the source —
    // "no second owner of history exists" has no moment at which a browser could observe it. Everything
    // that is not one of those three now lives in `webuitest/`; do not grow this section with anything a
    // Chromium could answer.

    @Test
    fun theBrowserLearnsTheApiPrefixInExactlyOnePlaceAndExemptsTheAuthBootstrap() = withServer { ctx ->
        val api = ctx.get("/lib/api.js").bodyAsText()
        assertTrue(
            api.contains("const API_PREFIX = \"/api/v1\"") &&
                api.contains("function apiPath(path)") &&
                api.contains("path.indexOf(AUTH_PATH) === 0 ? path : API_PREFIX + path"),
            "one registry of the prefix, with the /auth bootstrap surface exempted from it",
        )
        assertTrue(
            api.contains("await fetch(apiPath(path), opts)") &&
                api.contains("return proto + \"//\" + loc.host + apiPath(path);"),
            "both doors — apiRequest and wsUrl — go through it, which is what keeps every call site bare",
        )
        // The exemption has a live caller: the phone dialog mints its ticket through apiRequest.
        assertTrue(
            ctx.get("/components/dialogs.js").bodyAsText().contains("apiRequest(\"/auth/ticket\""),
            "the phone ticket rides the exemption rather than a hand-built URL",
        )
    }

    /**
     * An agreement between two FILES about one constant (was `WebUiRouterTest`).
     *
     * `sw.js` is a classic script with no module graph: it cannot import the router, so it hand-writes
     * `/?session=<id>` in `openWindow`, and nothing but this test keeps that spelling and the router's
     * `DEEP_LINK_PARAM` in step. A browser could only observe the pair through a DELIVERED push
     * notification, which needs a real push service no headless browser has — the routing half that a
     * browser can see (a deep link reaching its screen) is `webuitest/test/RouterTest.kt`.
     */
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

    /**
     * A NEGATIVE constraint on the shape of the source (was `WebUiRouterTest`): no `pushState` outside
     * `lib/router.js`.
     *
     * `replaceState` survives on purpose — `clearDeepLink` rewrites the address bar without changing
     * screens. A `pushState` would be a second owner of navigation, which is exactly what this forbids.
     * The browser tests prove that navigating THROUGH the router works; they can never prove that nothing
     * else navigates, because absence has no moment at which it can be observed.
     */
    @Test
    fun theAppReachesHistoryOnlyThroughTheRouter() = withServer { ctx ->
        val app = ctx.get("/app.js").bodyAsText()
        assertFalse(app.contains("pushState"), "no screen change is hand-rolled outside the router")
    }

    /**
     * The board's frozen CSS vocabulary (was `WebUiBoardTest`).
     *
     * `style.css` and these two components were written by different hands from one frozen class list,
     * and neither side can read the other's file. A class invented on either side is a rule that matches
     * nothing, or an element that draws nothing — and the browser's answer to both is a page that renders,
     * just wrong, which is why `webuitest/test/BoardStyleTest.kt` (which measures real paint) cannot see
     * it. This asserts one end of the contract: every `board-*` / `task-*` class the two modules emit is
     * on the list, and the classes they OWN are all really emitted.
     */
    @Test
    fun theTwoComponentsEmitOnlyTheSharedBoardVocabulary() = withServer { ctx ->
        val sources = mapOf(
            "Board.js" to ctx.get("/components/Board.js").bodyAsText(),
            "TaskCard.js" to ctx.get("/components/TaskCard.js").bodyAsText(),
        )
        for ((name, source) in sources) {
            for (className in vocabularyTokensIn(source)) {
                assertTrue(
                    BOARD_VOCABULARY.contains(className),
                    "$name emits '$className', which is not in the plan's Board CSS vocabulary — " +
                        "style.css is written from that list and would never style it",
                )
            }
        }
        // The other end of the same contract: the classes these two files OWN are all really emitted.
        val combined = sources.values.joinToString("\n")
        for (owned in BOARD_OWNED_CLASSES) {
            assertTrue(
                combined.contains("\"$owned\"") || combined.contains("\"$owned ") ||
                    combined.contains(" $owned\"") || combined.contains(" $owned "),
                "the board emits the '$owned' class the stylesheet is dressing",
            )
        }
    }

    /**
     * The project-name field must accept every name `POST /projects` does (was `WebUiBoardTest`).
     *
     * An `<input maxlength>` shorter than the API's cap is not a stricter client-side rule — it silently
     * refuses the keystroke, so the 81st character of a perfectly legal 100-character name could not be
     * typed at all, with no message anywhere. The number is imported from the daemon rather than repeated
     * here, so this fails if either side moves — and [PROJECT_NAME_MAX_LENGTH] is a constant of this
     * native root module that the JVM `webuitest` module cannot import at all, which is precisely why the
     * check stays in Kotlin.
     */
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
     * The revision the served shell is carrying, read out of a `src="…"` attribute rather than the first
     * `/_v/` in the file — the comment above those tags describes the shape too, and would be matched.
     */
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
     * Every `board-…` / `task-…` token in [source] that could be a class name.
     *
     * The preceding character decides: a letter, digit or hyphen in front means the match is the tail of
     * something else — `keyboard-reachable` and the `new-task-title` element id are both real occurrences
     * in these files, and neither is a class. What survives is the set a stylesheet would have to know.
     */
    private fun vocabularyTokensIn(source: String): Set<String> {
        val found = mutableSetOf<String>()
        for (prefix in listOf("board-", "task-")) {
            var at = source.indexOf(prefix)
            while (at >= 0) {
                val before = if (at == 0) ' ' else source[at - 1]
                if (!before.isLetterOrDigit() && before != '-') {
                    var end = at + prefix.length
                    while (end < source.length && (source[end].isLowerCase() || source[end] == '-')) end++
                    found.add(source.substring(at, end).trimEnd('-'))
                }
                at = source.indexOf(prefix, at + 1)
            }
        }
        return found
    }

    /**
     * A no-op [EventStore] good enough to construct the server: the static-serving path never touches it,
     * and the one API call the shadowing test makes ([listSessions]) returns an empty list. Everything
     * else is unused, so it returns empties / throws.
     */
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

/** `rwx------` for the throwaway directory the revision test builds its tree in. */
private const val MODE_0700: Int = 0b111_000_000

/** Every class name in the plan's frozen "Board CSS vocabulary" — the contract `style.css` is written from. */
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

/** The subset of [BOARD_VOCABULARY] that `Board.js` and `TaskCard.js` themselves own and must emit. */
private val BOARD_OWNED_CLASSES: List<String> = listOf(
    "board", "board-head", "board-identity", "board-project", "board-project-path",
    "board-new-task",
    "board-columns", "board-column", "board-column-head", "board-column-switch",
    "board-show-all-done", "board-drop-target",
    "task-card", "task-card-handle", "task-card-title", "task-card-meta",
    "task-blocked", "task-dep-count", "task-sessions", "task-session-dot", "task-card-menu",
)

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
