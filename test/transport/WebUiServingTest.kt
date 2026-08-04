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
        val body = resp.bodyAsText()
        assertTrue(body.contains("from \"preact\""), "the entry module imports the vendored Preact")
        assertTrue(body.contains("from \"htm/preact\""), "the entry module imports htm's Preact binding")
        assertTrue(body.contains("session_update"), "app.js handles the live session_update messages")
        // The list is built from the /events socket alone: the frame dispatcher routes the three session
        // frame kinds through the ref indirection, and NOTHING in app.js may fetch the whole list — the
        // negative pin below is the regression guard this entire protocol rewrite exists for (206 ×
        // GET /sessions on one reload). The targeted form ("/sessions/" + encodeURIComponent(...)) and
        // the POST forms (`apiRequest("/sessions", {`) do not match the closed literal.
        assertTrue(
            !body.contains("apiRequest(\"/sessions\")"),
            "the session list must never be fetched wholesale over HTTP — the snapshot frame is the list",
        )
        assertTrue(!body.contains("loadSessions"), "the wholesale session loader stays deleted")
        assertTrue(
            body.contains("sessionsFrameRef.current(msg)"),
            "session frames reach the applicators through the ref, not through the socket effect's deps",
        )
        assertTrue(
            body.contains("applySessionsSnapshot(msg.sessions)") &&
                body.contains("applySessionRow(msg.session)") &&
                body.contains("applySessionPatch(msg)"),
            "the dispatcher routes all three session frame kinds",
        )
        // The snapshot applicator's order: per-row notify-edge against the PREVIOUS list first (a session
        // that entered needs-attention while the socket was down must ring), then the wholesale install,
        // then the deep link, and markReadIfViewing for the active row (the reconnect badge heal).
        val snapStart = body.indexOf("const applySessionsSnapshot = useCallback((rows) => {")
        val snapEnd = body.indexOf("\n  }, [cancelReattach", startIndex = snapStart.coerceAtLeast(0))
        assertTrue(snapStart >= 0 && snapEnd > snapStart, "the snapshot applicator is present and bounded")
        val applySnapshot = body.substring(snapStart, snapEnd)
        val notifyAt = applySnapshot.indexOf("notifyAttention(prevRow)")
        val installAt = applySnapshot.indexOf("setSessions(rows)")
        val wantedAt = applySnapshot.indexOf("const wanted = deepLinkRef.current;")
        val readAt = applySnapshot.indexOf("markReadIfViewing(active.id, active.unread, active.lastSeq)")
        assertTrue(
            notifyAt in 0 until installAt && installAt < wantedAt && wantedAt < readAt,
            "the snapshot diffs per row for notify-edge, installs, honours the deep link, then heals the badge",
        )
        // Reconnect announcements are latched: the routine list line only on the FIRST snapshot, the
        // disconnect line once per outage (onclose refires every 2 s while the daemon is down).
        assertTrue(
            applySnapshot.contains("disconnectAnnouncedRef.current = false") &&
                applySnapshot.contains("if (!sessionsReadyRef.current)"),
            "a reconnect snapshot re-arms the outage announcement and stays quiet about the list",
        )
        assertTrue(
            body.contains("if (!disconnectAnnouncedRef.current)") &&
                body.contains("Daemon connection lost"),
            "a lost daemon announces once per outage, not once per 2 s retry",
        )
        // Model correctness moved into the patch itself: patchIfNewer (lib/sessions.js) takes msg.model
        // verbatim. app.js must not reintroduce a null-guard that would keep a cleared model on screen.
        assertTrue(
            !body.contains("msg.model != null"),
            "no null-guard may filter the authoritative patch model (a cleared model must clear)",
        )
        assertTrue(body.contains("startSession"), "app.js can create sessions")
        assertTrue(body.contains("controlSession"), "app.js can run lifecycle controls")
        // The unread-badge wiring. There is no JS harness, so these greps are what stops the whole feature
        // from being deleted with a green suite: the guard, its POST target, and the visibility trigger.
        assertTrue(body.contains("markReadIfViewing"), "app.js marks the viewed session read")
        assertTrue(body.contains("/read"), "…by POSTing the displayed seq to the mark-read route")
        assertTrue(body.contains("visibilitychange"), "…and re-checks when the tab becomes visible again")
        // The per-session poster Map is module-level and long-lived; this page stays open for days.
        assertTrue(body.contains("pruneReadPosters"), "…and drops the poster of a session that vanished")
        // The mark-read retry replaced the 15 s resync heartbeat: it must retry a transient failure and
        // STOP on a definitive one — a 401 after rotation or a 404 for a vanished session never heals,
        // and a page that lives for days must not hammer the daemon with unwinnable POSTs.
        assertTrue(
            body.contains("if (isDefiniteAnswer(e)) return;") &&
                body.contains("READ_RETRY_DELAY_MS"),
            "postRead retries transient failures and stops on a definitive 4xx",
        )
        // The events socket is the list's only source, so BOTH failure paths must reconnect: the
        // constructor throw (which used to give up for the page's life) and the ordinary onclose.
        assertTrue(
            Regex("""setTimeout\(connect, 2000\)""").findAll(body).count() == 2,
            "both the constructor failure and onclose reschedule the events socket",
        )
        // First-paint honesty: before the first snapshot the sidebar says "Loading sessions…", because
        // an empty list is not yet a fact — and the routine announcement fires exactly once.
        assertTrue(
            body.contains("sessionsReady=\${sessionsReady}"),
            "app.js tells the sidebar whether the first snapshot has landed",
        )
        // The one first-load 401 gate lives in the mount-only /preferences effect now (no GET /sessions
        // exists to carry it); a later 401 must not navigate a live page away.
        val prefsStart = body.indexOf("apiRequest(\"/preferences\")")
        val prefsEnd = body.indexOf("}, [applyServerPreferences, say]);", startIndex = prefsStart.coerceAtLeast(0))
        assertTrue(prefsStart >= 0 && prefsEnd > prefsStart, "the preferences effect is present and bounded")
        val prefsEffect = body.substring(prefsStart, prefsEnd)
        assertTrue(
            prefsEffect.contains("if (isUnauthenticated(e))") &&
                prefsEffect.contains("window.location.replace(AUTH_PATH)"),
            "an unsigned installed PWA is routed to /auth from the mount-only preferences load",
        )
        val redirects = Regex("""window\.location\.replace\(AUTH_PATH\)""").findAll(body).count()
        assertTrue(redirects == 1, "exactly one /auth redirect exists (found $redirects)")
        assertTrue(body.contains("apiRequest(\"/version\")"), "app.js fetches the daemon version")
        assertTrue(body.contains("currentVersion=\${currentVersion}"), "app.js passes the version to the sidebar")
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

    @Test
    fun daemonServesTheComponentAndLibModules() = withServer { ctx ->
        for (path in listOf(
            "/lib/paths.js", "/lib/prefs.js", "/lib/api.js", "/lib/sessions.js", "/lib/qr.js",
            "/lib/notify.js", "/lib/push.js", "/lib/agents.js", "/lib/commands.js",
            "/lib/clipboard.js",
            "/components/Sidebar.js", "/components/TerminalPane.js", "/components/KeyBar.js",
            "/components/dialogs.js", "/components/CommandPalette.js",
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

    @Test
    fun webUiGroupsSessionsIntoARecursiveDirectoryTree() = withServer { ctx ->
        val paths = ctx.get("/lib/paths.js").bodyAsText()
        assertTrue(
            paths.contains("const visible = segments.slice(0, depth)") &&
                paths.contains("siblings = node.children") &&
                paths.contains("const children = sortedNodes(node.children).map(finishNode)") &&
                paths.contains(
                    "sessionCount: node.sessions.length + children.reduce(" +
                        "(total, child) => total + child.sessionCount, 0)",
                ),
            "the grouping helper builds sorted recursive nodes with aggregate subtree counts",
        )
        assertTrue(
            paths.contains("inBase.unshift(finishNode(baseNode))") &&
                paths.contains("return inBase.concat(sortedNodes(outside).map(finishNode))") &&
                paths.contains("newNode(path, path || \"(unknown)\", false)"),
            "base-direct sessions get a base node and standalone outside/unknown groups follow the tree",
        )

        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(
            sidebar.contains("const collapsed = collapsedGroups.has(group.path)") &&
                sidebar.contains("collapsedGroups=${'$'}{collapsedGroups}") &&
                sidebar.contains("onClick=${'$'}{() => onToggle(group.path)}"),
            "every recursive folder reads and toggles the existing full-path collapse state independently",
        )
        assertTrue(
            sidebar.contains("group.children.some(groupNeedsAttention)") &&
                sidebar.contains("<span class=\"group-count\">${'$'}{group.sessionCount}</span>"),
            "folder headings aggregate descendant attention and session counts",
        )
        assertTrue(
            sidebar.contains("onClick=${'$'}{() => onNewSession(group.path)}"),
            "each folder's plus action passes that folder's exact full path",
        )
        val directSessionsAt = sidebar.indexOf("${'$'}{group.sessions.map((s) => html`")
        val childFoldersAt = sidebar.indexOf("${'$'}{group.children.map((child) => html`")
        assertTrue(
            directSessionsAt >= 0 && childFoldersAt > directSessionsAt &&
                sidebar.substring(childFoldersAt).contains("<${'$'}{SessionGroup}"),
            "direct sessions render before recursively nested child folders",
        )

        val css = ctx.get("/style.css").bodyAsText()
        val contentsRule = cssRuleOf(css, ".group-contents")
        assertTrue(
            contentsRule.contains("border-left:") && contentsRule.contains("margin-left:") &&
                contentsRule.contains("padding-left:"),
            "nested folder contents have compact indentation and a tree guide",
        )

        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(
            dialogs.contains("<span>Tree depth <small>maximum visible folders below the base path</small></span>") &&
                dialogs.contains("placeholders.join(\" › \")") &&
                dialogs.contains("visible.join(\" › \")"),
            "preferences describe and preview a nested tree depth instead of a combined flat heading",
        )
    }

    @Test
    fun theCommandRegistryIsTheServedSourceOfSearchAndLeaderCommands() = withServer { ctx ->
        val commands = ctx.get("/lib/commands.js").bodyAsText()
        assertTrue(commands.contains("export function buildCommands"), "the command registry is exported")
        assertTrue(commands.contains("export function filterCommands"), "the pure matcher is exported")

        val chords = mapOf(
            "session.interrupt" to "i",
            "session.resume" to "u",
            "session.stop" to "s",
            "session.done" to "d",
            "session.copy-tmux" to "c",
            "session.upload-files" to "f",
            "general.new" to "n",
            "general.import" to "r",
            "general.free-terminal" to "t",
            "general.help" to "h",
            "general.phone" to "m",
            "general.notifications" to "b",
            "general.preferences" to "p",
        )
        for ((id, chord) in chords) {
            assertTrue(
                Regex("""id: "$id", group: "\w+", chord: "$chord"""").containsMatchIn(commands),
                "$id keeps the '$chord' leader mnemonic in the one registry",
            )
        }
        for (id in listOf("general.notifications")) {
            val descriptor = commands.substringAfter("id: \"$id\"").substringBefore("\n    },")
            assertTrue(
                descriptor.contains("disabled: \"not implemented yet\""),
                "$id is reserved but visibly unavailable until its designed stage",
            )
        }
        val freeTerminal = commands.substringAfter("id: \"general.free-terminal\"").substringBefore("\n    },")
        assertTrue(
            freeTerminal.contains("disabled: null") &&
                freeTerminal.contains("run: () => actions.freeTerminal()"),
            "the free-terminal command is enabled and delegates to the app-owned action",
        )
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("() => openNewSession(null, \"start\", \"shell\")") &&
                app.contains("freeTerminal: openFreeTerminal") &&
                app.contains("initialAgent=\${dialog.initialAgent}"),
            "the free-terminal action opens the ordinary New dialog with Shell preselected",
        )
        assertTrue(
            commands.contains("title: \"Resume this session\"") &&
                commands.contains("title: \"Resume a conversation started outside kotgent…\""),
            "the current-session and outside-kotgent resume commands are disambiguated",
        )
        for ((id, title) in mapOf(
            "general.preferences" to "Preferences",
            "general.help" to "Help",
            "general.phone" to "Sign in from your phone",
        )) {
            val descriptor = commands.substringAfter("id: \"$id\"").substringBefore("\n    },")
            assertTrue(
                descriptor.contains("title: \"$title\"") && descriptor.contains("disabled: null"),
                "$title remains available from the command registry after leaving the sidebar header",
            )
        }
    }

    @Test
    fun theCommandPaletteShipsAnAccessibleSearchListbox() = withServer { ctx ->
        val palette = ctx.get("/components/CommandPalette.js").bodyAsText()
        assertTrue(
            palette.contains("import { filterCommands } from \"../lib/commands.js\""),
            "the palette filters the one shared command registry",
        )
        assertTrue(
            palette.contains("import { Dialog } from \"./dialogs.js\"") &&
                ctx.get("/components/dialogs.js").bodyAsText().contains("export function Dialog"),
            "the palette reuses the exported native dialog wrapper",
        )
        assertTrue(
            palette.contains("role=\"combobox\"") &&
                palette.contains("aria-activedescendant=\${activeOptionId}") &&
                palette.contains("role=\"listbox\"") &&
                palette.contains("role=\"option\""),
            "combobox focus drives the listbox through aria-activedescendant",
        )
        assertTrue(
            palette.contains("scrollIntoView({ block: \"nearest\" })") &&
                palette.contains("if (!item.disabled) indexes.push(index)"),
            "wrapped keyboard navigation scrolls and excludes the disabled tail",
        )
        val closeAt = palette.indexOf("if (dialog && dialog.open) dialog.close()")
        val runAt = palette.indexOf("item.run()")
        assertTrue(
            closeAt >= 0 && runAt > closeAt,
            "a command closes the native modal before its action can open another one",
        )

        assertTrue(
            palette.contains("class=\"command-palette-leader-grid\"") &&
                palette.contains("const leaderCommands = commands.filter((item) => item.chord)"),
            "leader mode renders the mnemonic subset of the same descriptors",
        )
        assertTrue(
            palette.contains("event.code === \"Key\" + command.chord.toUpperCase()") &&
                !palette.contains("event.metaKey") &&
                !palette.contains("event.ctrlKey"),
            "leader letters use layout-independent bare codes after the opening modifier is released",
        )
        assertTrue(
            palette.contains("event.code === \"Space\"") &&
                palette.contains("event.code === \"Backspace\"") &&
                palette.contains("onModeChange(\"search\")"),
            "leader mode suppresses Space and returns to search on Backspace or the search row",
        )
        assertTrue(
            palette.contains("setLeaderMessage(item.title + \": \" + item.disabled)") &&
                palette.contains("role=\"status\" aria-live=\"polite\""),
            "reserved disabled mnemonics stay visible and announce why they cannot run",
        )
        // Both modes must own the focus. `leaderKeyDown` is bound to the shell, so it only runs for a
        // keystroke bubbling out of that subtree — and leader mode unmounts the search input, which
        // parks the focus on the <dialog> ABOVE the shell. That dropped every mnemonic while the
        // grid's own click handlers kept working, which is why it read as a chord bug rather than a
        // focus one.
        assertTrue(
            palette.contains("if (queryRef.current) queryRef.current.focus()") &&
                palette.contains("shellRef.current.focus()") &&
                palette.contains("ref=\${shellRef} tabIndex=\"-1\""),
            "search focuses its input and leader focuses the shell that carries the mnemonic handler",
        )
        assertTrue(
            ctx.get("/style.css").bodyAsText().contains(".command-palette-leader-grid"),
            "both palette modes ship with their layout styles",
        )
    }

    @Test
    fun theAppOwnsThePaletteBindingAndCommandContext() = withServer { ctx ->
        val app = ctx.get("/app.js").bodyAsText()
        assertEquals(
            1,
            Regex("""document\.addEventListener\("keydown", handler, true\)""").findAll(app).count(),
            "the app installs one capture-phase palette listener",
        )
        assertTrue(
            app.contains("event.metaKey && event.code === \"KeyK\"") &&
                app.contains("event.ctrlKey && event.shiftKey && event.code === \"KeyK\"") &&
                app.contains("event.preventDefault()") &&
                app.contains("event.stopPropagation()"),
            "the opener matches the physical K key and consumes both supported bindings",
        )
        assertTrue(
            app.contains("if ((!opensPalette && !togglesSidebar) || dialogRef.current) return") &&
                app.contains("current.mode === \"search\" ? \"leader\" : \"search\""),
            "an app dialog owns its keyboard and a repeated opener toggles palette mode",
        )
        assertTrue(
            app.contains("const commands = buildCommands({") &&
                app.contains("copyTmux: copyTmuxCommand") &&
                app.contains("importSession: openImportSession") &&
                app.contains("<\${CommandPalette}") &&
                app.contains("commands=\${commands}"),
            "the rendered palette receives descriptors built from app-owned actions",
        )

        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(
            app.contains("const [showDone, setShowDone] = useState(false)") &&
                app.contains("showDone=\${showDone}") &&
                app.contains("onToggleShowDone=\${toggleShowDone}") &&
                !sidebar.contains("const [showDone, setShowDone]"),
            "the app owns archived-session visibility so the palette and sidebar share one state",
        )
    }

    @Test
    fun mobilePaletteUploadsPickedFilesToTheSelectedSessionsCurrentFolder() = withServer { ctx ->
        val commands = ctx.get("/lib/commands.js").bodyAsText()
        val uploadCommand = commands.substringAfter("id: \"session.upload-files\"")
            .substringBefore("\n    },")
        assertTrue(
            uploadCommand.contains("title: \"Upload files to current folder…\"") &&
                uploadCommand.contains("disabled: disabledWhenNoSession(activeSession)") &&
                uploadCommand.contains("run: () => actions.uploadFiles()"),
            "the one palette registry exposes upload only for a selected session",
        )

        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("setDialog({ kind: \"upload\", session: selected })") &&
                app.contains("uploadFiles: openUpload") &&
                app.contains("<\${UploadFilesDialog} session=\${dialog.session}"),
            "the palette captures the selected session and opens the upload dialog",
        )

        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(
            dialogs.contains("export function UploadFilesDialog") &&
                dialogs.contains("type=\"file\" multiple") &&
                dialogs.contains("Current folder <code>\${session.cwd}</code>"),
            "the dialog uses the native multi-file picker and shows the destination cwd",
        )
        assertTrue(
            dialogs.contains("/files?name=\" +") &&
                dialogs.contains("encodeURIComponent(file.name)") &&
                dialogs.contains("{ method: \"POST\", body: file, signal: controller.signal }") &&
                dialogs.contains("requestRef.current.abort()"),
            "each picked File is posted as raw bytes with an encoded leaf name and cancellable teardown",
        )
        assertTrue(
            dialogs.contains("Existing files are never replaced") &&
                dialogs.contains("failures.join(\"\\n\")"),
            "the no-overwrite rule and per-file failures are visible on the phone",
        )

        val api = ctx.get("/lib/api.js").bodyAsText()
        assertTrue(
            api.contains("typeof opts.body === \"string\"") && api.contains("!hasContentType"),
            "apiRequest defaults JSON only for string bodies, preserving File/Blob uploads",
        )
        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(
            css.contains("#upload-form") && css.contains(".upload-destination") &&
                css.contains(".file-input::file-selector-button"),
            "the upload dialog and native picker affordance are styled",
        )
    }

    @Test
    fun thePaletteReplacesRedundantDesktopChromeWithoutRemovingEntryPoints() = withServer { ctx ->
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        val brandActions = sidebar.substringAfter("<div class=\"brand-actions\">")
            .substringBefore("\n          </div>")
        assertEquals(
            2,
            Regex("<button").findAll(brandActions).count(),
            "the sidebar brand row keeps only notifications and the mobile drawer close",
        )
        assertTrue(
            brandActions.contains("id=\"notify-toggle\"") &&
                brandActions.contains("id=\"drawer-close\"") &&
                !sidebar.contains("id=\"new-session-button\"") &&
                !sidebar.contains("id=\"phone-button\"") &&
                !sidebar.contains("id=\"help-button\"") &&
                !sidebar.contains("id=\"prefs-button\""),
            "general actions no longer duplicate the command palette in the sidebar header",
        )
        assertTrue(
            sidebar.contains("id=\"empty-new-session-button\"") &&
                sidebar.contains(">Start a session</button>") &&
                sidebar.contains("onClick=\${() => onNewSession(null)}"),
            "an empty first run still exposes session creation without knowing the palette shortcut",
        )
        assertTrue(
            sidebar.contains("id=\"base-path-note\"") &&
                sidebar.contains("onClick=\${onOpenPrefs}"),
            "the grouping note remains a direct Preferences entry point",
        )

        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        val paletteButtonAt = pane.indexOf("id=\"palette-button\"")
        val sessionGuardAt = pane.indexOf("\${session && html`")
        assertTrue(
            paletteButtonAt >= 0 && paletteButtonAt < sessionGuardAt,
            "the palette button renders even when there is no selected session",
        )
        assertTrue(
            pane.contains("window.matchMedia(\"(max-width: 720px)\").matches ? \"leader\" : \"search\"") &&
                pane.contains("onOpenPalette(mode)") &&
                ctx.get("/app.js").bodyAsText().contains("onOpenPalette=\${openPalette}"),
            "the header opens the compact leader on phones and search on wider screens",
        )

        val css = ctx.get("/style.css").bodyAsText()
        val desktop = css.substringBefore("@media (max-width: 720px)")
        val mobile = css.substringAfter("@media (max-width: 720px)")
            .substringBefore("@media (prefers-reduced-motion: reduce)")
        assertTrue(
            desktop.contains(".session-actions {\n  display: none;") &&
                mobile.contains(".session-actions {\n    display: flex;"),
            "lifecycle buttons stay in markup but switch from palette-only desktop to direct mobile controls",
        )
    }

    @Test
    fun theDesktopSidebarCollapsesWithoutOverloadingTheMobileDrawer() = withServer { ctx ->
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("const [sidebarCollapsed, setSidebarCollapsed] = useState(loadSidebarCollapsed)") &&
                app.contains("persistSidebarCollapsed(sidebarCollapsed)") &&
                app.contains("event.metaKey && event.code === \"Digit1\"") &&
                app.contains("installed PWA receives it"),
            "app state persists the desktop collapse and records the browser-tab shortcut limitation",
        )

        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        assertTrue(
            pane.contains("id=\"sidebar-toggle\"") &&
                pane.contains("onClick=\${onToggleSidebar}") &&
                pane.contains("id=\"drawer-toggle\"") &&
                pane.contains("onClick=\${onToggleDrawer}"),
            "desktop collapse and mobile drawer retain separate buttons and handlers",
        )
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(
            sidebar.contains("<aside id=\"sidebar\"") &&
                sidebar.contains("collapsed ? \"collapsed\" : \"\"") &&
                !app.contains("id=\"app\""),
            "the collapsed class belongs to #sidebar, while #app remains the external render container",
        )

        val css = ctx.get("/style.css").bodyAsText()
        val collapseRule = cssRuleOf(css, "#sidebar.collapsed")
        for (declaration in listOf(
            "width: 0",
            "min-width: 0",
            "padding: 0",
            "border-right: 0",
            "overflow: hidden",
        )) {
            assertTrue(collapseRule.contains(declaration), "sidebar collapse keeps `$declaration`")
        }
        val mobile = css.substringAfter("@media (max-width: 720px)")
            .substringBefore("@media (prefers-reduced-motion: reduce)")
        assertTrue(
            mobile.contains("#sidebar-toggle { display: none; }") &&
                mobile.contains("#sidebar,\n  #sidebar.collapsed {") &&
                css.contains(".drawer-toggle,\n.drawer-close,\n.drawer-scrim { display: none; }"),
            "the desktop collapse is neutralized for the drawer and leaves its shared visibility rule untouched",
        )
    }

    @Test
    fun theShellFloatsCardsWithoutMovingPaddingOntoTheTerminalHost() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(
            cssRuleOf(css, ":root").contains("color-scheme: dark") &&
                cssRuleOf(css, "html, body").contains("background-color: #000"),
            "WebKit gets an explicit dark scheme and literal black canvas for the iPhone safe-area",
        )
        val appRule = cssRuleOf(css, "#app")
        assertTrue(
            appRule.contains("background: var(--bg)") &&
                appRule.contains("--device-safe-area-top: env(safe-area-inset-top") &&
                appRule.contains("--device-safe-area-bottom: env(safe-area-inset-bottom") &&
                appRule.contains("padding: var(--device-safe-area-top)") &&
                appRule.contains("var(--active-safe-area-bottom)") &&
                appRule.contains("env(safe-area-inset-left)"),
            "the shell owns both its background and the safe-area padding around the cards",
        )

        val sidebarRule = cssRuleOf(css, "#sidebar")
        val terminalRule = cssRuleOf(css, "#terminal-pane")
        for ((name, rule) in listOf("sidebar" to sidebarRule, "terminal pane" to terminalRule)) {
            assertTrue(rule.contains("margin:"), "the $name is inset with margin")
            assertTrue(rule.contains("border-radius:"), "the $name clips as a card")
            assertTrue(rule.contains("box-shadow:"), "the $name floats above the shell")
        }
        assertTrue(!sidebarRule.contains("border-right:"), "the sidebar card has no dividing rail")

        val hostRule = cssRuleOf(css, "#terminal-host")
        assertTrue(!hostRule.contains("padding:"), "card geometry never becomes unmeasured terminal padding")

        val desktopAt = css.indexOf("@media (min-width: 721px)")
        val mobileAt = css.indexOf("@media (max-width: 720px)")
        assertTrue(desktopAt > 0 && mobileAt > desktopAt, "desktop card effects precede the mobile reset")
        val desktop = css.substring(desktopAt, mobileAt)
        val mobile = css.substring(mobileAt)
            .substringBefore("@media (prefers-reduced-motion: reduce)")
        assertTrue(
            Regex("""(?s)#sidebar\s*\{[^}]*backdrop-filter:\s*blur\(20px\)""").containsMatchIn(desktop),
            "only the desktop sidebar pays for its translucent blur",
        )
        assertTrue(!mobile.contains("backdrop-filter"), "the phone drawer never enables a composite blur")
        assertTrue(
            mobile.contains("#terminal-pane {\n    border-radius: 0;") &&
                mobile.contains("box-shadow: none;") &&
                mobile.contains("margin: 0;"),
            "the terminal card returns to the safe-area-sized phone shell",
        )
    }

    @Test
    fun sessionAndPaletteRowsSharePillInteractionStates() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        val session = cssRuleOf(css, ".session-row")
        assertTrue(
            session.contains("margin:") &&
                session.contains("border-radius:") &&
                session.contains("var(--list-inset)"),
            "session pills share one list inset without pinning its decorative measurements",
        )
        assertTrue(
            cssRuleOf(css, ".session-row:hover").contains("background: var(--row-hover)"),
            "hover keeps a neutral fill",
        )
        assertTrue(
            cssRuleOf(css, ".session-row.active").contains("background: var(--pill-active)"),
            "the active pill uses the shared low-alpha accent fill",
        )
        val focus = cssRuleOf(css, ".session-row:focus-visible")
        assertTrue(
            focus.contains("outline:") && focus.contains("var(--accent)"),
            "keyboard focus remains visible around the clickable session pill",
        )

        val section = cssRuleOf(css, ".section-title")
        assertTrue(
            section.contains("text-transform: uppercase") &&
                section.contains("color: var(--muted)") &&
                section.contains("var(--list-inset)"),
            "section labels stay muted and line up with the pills",
        )
        assertTrue(
            cssRuleOf(css, ".group-head").contains("var(--list-inset)") &&
                cssRuleOf(css, ".attn-dot").contains("margin-inline:") &&
                cssRuleOf(css, ".unread-pill").contains("margin-inline:"),
            "group and status affordances follow the same inset rhythm",
        )

        val paletteOption = cssRuleOf(css, ".command-palette-option")
        assertTrue(
            paletteOption.contains("border-radius:") &&
                cssRuleOf(css, ".command-palette-option.active").contains("background: var(--pill-active)") &&
                cssRuleOf(css, ".command-palette-leader-command:hover")
                    .contains("background: var(--row-hover)"),
            "search and leader rows reuse the session pill's active and hover language",
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
        assertTrue(pane.contains("id=\"copy-tmux-button\""), "the UI keeps the direct copy-tmux control")
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
            pane.contains("import { writeClipboard } from \"../lib/clipboard.js\"") &&
                !pane.contains("async function writeClipboard"),
            "TerminalPane imports the shared clipboard helper instead of hiding a local copy",
        )
        val clipboard = ctx.get("/lib/clipboard.js").bodyAsText()
        assertTrue(
            clipboard.contains("export async function writeClipboard") &&
                clipboard.contains("document.execCommand(\"copy\")"),
            "the shared helper keeps the legacy clipboard fallback",
        )
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("const copyTmuxCommand = useCallback(async () => {") &&
                app.contains("say(\"Tmux command copied to clipboard.\")") &&
                app.contains("say(\"Could not copy the tmux command.\", true)"),
            "the app-owned copy action reports through the persistent sidebar status line",
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
    fun webUiUsesOneClickAgentPickerWithoutADefaultSelection() = withServer { ctx ->
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(
            dialogs.contains("initialAgent = \"\"") &&
                dialogs.contains("const [agent, setAgent] = useState(initialAgent)"),
            "the new-session dialog has no general default but accepts an explicit command preselection",
        )

        val choices = ctx.get("/lib/agents.js").bodyAsText()
        assertEquals(5, Regex("value: \"").findAll(choices).count(), "the picker offers five agents")
        assertTrue(choices.contains("value: \"claude\", name: \"Claude\", available: true"), "Claude starts")
        assertTrue(choices.contains("value: \"codex\", name: \"Codex\", available: true"), "Codex starts")
        assertTrue(choices.contains("value: \"junie\", name: \"Junie\", available: true"), "Junie starts")
        assertTrue(
            choices.contains(
                "value: \"shell\", name: \"Shell\", available: true, importable: false",
            ),
            "Shell starts but is explicitly excluded from transcript import",
        )

        // Vendor marks remain their real paths on their own viewBoxes. Shell deliberately has no vendor,
        // so its fifth card carries a generic terminal glyph without weakening that logo invariant.
        assertEquals(5, Regex("viewBox: \"").findAll(choices).count(), "every agent brings its own viewBox")
        val marksByAgent = Regex("""value: "([^"]+)"[\s\S]*?icon: "([^"]+)"""")
            .findAll(choices)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(5, marksByAgent.size, "every agent brings a path")
        val vendorMarks = listOf("claude", "codex", "junie", "cursor").map(marksByAgent::getValue)
        assertTrue(
            vendorMarks.all { it.length > 100 },
            "the shortest vendor mark is ${vendorMarks.minOf { it.length }} chars — Shell is the deliberate generic-glyph exception",
        )
        assertTrue(marksByAgent.getValue("shell").isNotBlank(), "the Shell card carries its terminal glyph")

        val picker = agentPickerOf(dialogs)
        assertTrue(
            dialogs.contains("import { AGENT_CHOICES, FIRST_AVAILABLE_AGENT } from \"../lib/agents.js\";"),
            "the dialog renders from that table rather than carrying its own copy",
        )
        assertTrue(
            picker.contains(".filter((choice) => mode !== \"import\" || choice.importable !== false)") &&
                picker.contains(".map((choice) => html`"),
            "the one table is filtered by mode before its cards render",
        )
        assertTrue(
            dialogs.contains("choice.value === agent && choice.importable === false"),
            "switching a preselected Shell dialog to Import clears the hidden invalid choice",
        )
        assertTrue(picker.contains("viewBox=\${choice.viewBox}"), "each card draws on its mark's own box")
        assertTrue(picker.contains("type=\"radio\""), "an agent is one radio choice, not a menu entry")
        assertTrue(
            picker.contains("name=\"session-agent\""),
            "the choices share one radio group name, or they stop being mutually exclusive",
        )
        // The old control is gone from the WHOLE module, not merely from the fieldset that replaced it —
        // a leftover `<select id="session-agent">` elsewhere would satisfy a picker-scoped assertion.
        assertTrue(
            !dialogs.contains("id=\"session-agent\""),
            "choosing an agent does not require opening a select",
        )

        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(css.contains(".agent-options"), "the icon choices have a dedicated layout")
        assertTrue(
            css.contains(".agent-option input:checked + .agent-option-content"),
            "the selected agent has a visible state",
        )
        assertTrue(
            css.contains(".agent-option input:focus-visible + .agent-option-content"),
            "keyboard focus remains visible on the custom radio choices",
        )
    }

    @Test
    fun theAgentRadiosAreHiddenWithoutLeavingTheKeyboardOrTheDarkTheme() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()

        // A fieldset carries a UA `margin-inline`, which would inset the picker relative to the
        // full-width fields under it.
        assertTrue(
            cssRuleOf(css, ".agent-picker").contains("margin-inline: 0"),
            "the picker lines up with the rest of the form",
        )

        // `.field input` reaches these radios too, and only `opacity` keeps that inherited box from
        // painting over the card. Hiding them by removal instead would take them out of the tab order
        // and silently kill the arrow-key group and the focus outline the test above pins.
        val hidden = cssRuleOf(css, ".agent-option input")
        assertTrue(hidden.contains("  width: 0;"), "the hidden radio drops the inherited full width")
        assertTrue(hidden.contains("  height: 0;"), "the hidden radio drops the inherited height")
        assertTrue(hidden.contains("  min-height: 0;"), "the hidden radio drops the inherited min-height")
        assertTrue(hidden.contains("  border: 0;"), "the hidden radio drops the inherited border")
        assertTrue(
            !hidden.contains("display: none") && !hidden.contains("visibility: hidden"),
            "the radios stay focusable — removing them from the box tree removes them from the keyboard",
        )

        // The marks are the vendors' filled paths, so the chip paints them with `fill`. A stroke setup
        // left over from the hand-drawn glyphs would outline every filled shape instead.
        val mark = cssRuleOf(css, ".agent-icon svg")
        assertTrue(mark.contains("fill: currentColor"), "a brand mark is filled, and takes the chip's colour")
        assertTrue(!mark.contains("stroke-width"), "nothing strokes a filled path")

        assertTrue(
            !css.contains("prefers-color-scheme"),
            "the shell has one dark theme rather than a second conditional palette",
        )

        // Every other colour in the picker comes from a themed variable; the chips are literals, so each
        // must be declared exactly once in the single theme.
        for (agent in listOf("claude", "codex", "junie", "cursor")) {
            assertEquals(
                1,
                Regex("\\.agent-icon-$agent").findAll(css).count(),
                "the $agent chip is declared once in the single theme",
            )
        }
        assertEquals(
            1,
            Regex("\\.agent-icon-shell").findAll(css).count(),
            "the deliberate non-vendor Shell chip is declared once beside the vendor chips",
        )
    }

    @Test
    fun plannedAgentsAreShownWithoutBecomingChoosable() = withServer { ctx ->
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()

        val choices = ctx.get("/lib/agents.js").bodyAsText()
        assertTrue(choices.contains("value: \"cursor\", name: \"Cursor\", available: false"), "Cursor is planned")
        assertEquals(
            1,
            // Anchored on a CARD so the header comment's own mention of the flag is not counted.
            Regex("""name: "\w+", available: false""").findAll(choices).count(),
            "cursor is the only planned card left — claude, codex, junie and shell can all be started",
        )

        // `disabled` is what keeps a planned card out of the tab order and the arrow-key group; without
        // it the card would take a selection the daemon's agentFactoryOf then rejects with a 400.
        val picker = agentPickerOf(dialogs)
        assertTrue(picker.contains("disabled=\${!choice.available}"), "a planned agent cannot be selected")
        assertTrue(
            picker.contains("aria-required=\${choice.available ? \"true\" : null}"),
            "only a choice that can be made is announced as required",
        )
        assertTrue(picker.contains("<small>Soon</small>"), "a planned agent says so on its card")

        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(
            css.contains(".agent-option-unavailable .agent-option-content"),
            "a planned card reads as unavailable",
        )
        // The shared `.agent-option:hover` rule would otherwise keep offering a card the input refuses.
        assertTrue(
            css.contains(".agent-option-unavailable:hover .agent-option-content"),
            "a planned card does not answer the pointer either",
        )
    }

    @Test
    fun webUiReportsAMissingAgentInsteadOfSilentlyRefusingToStart() = withServer { ctx ->
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        val picker = agentPickerOf(dialogs)

        // Focus lands on the choice that has no default, not on the prefilled path field below it — and
        // on a card that can actually take it, since a planned agent's radio is disabled.
        assertTrue(
            ctx.get("/lib/agents.js").bodyAsText().contains(
                "export const FIRST_AVAILABLE_AGENT = AGENT_CHOICES.find((choice) => choice.available).value;",
            ),
            "the focus target is the first agent that can be started, not simply the first card",
        )
        assertTrue(
            picker.contains("ref=\${choice.value === FIRST_AVAILABLE_AGENT ? agentRef : null}"),
            "the picker owns the ref the dialog focuses",
        )
        assertTrue(
            dialogs.contains("const target = agent ? cwdRef.current : agentRef.current;"),
            "an unanswered agent choice takes the initial focus",
        )

        // One mechanism owns the requirement. A disabled submit swallows Enter with no feedback, and a
        // native `required` would anchor its bubble on a radio the stylesheet renders at `opacity: 0`.
        assertTrue(
            dialogs.contains("disabled=\${busy}>"),
            "the start action is gated on the request in flight, not on the agent choice",
        )
        assertTrue(!dialogs.contains("busy || !agent"), "the start action is not silently disabled")
        assertEquals(
            0,
            Regex("(?<![-\\w])required").findAll(picker).count(),
            "no native constraint validation competes with the dialog's own report",
        )
        assertTrue(picker.contains("aria-required="), "the choice is still announced as required")

        // Submitting without a choice reports it and hands focus back, rather than doing nothing.
        assertTrue(dialogs.contains("if (!agent) {"), "submit refuses a missing agent explicitly")
        assertTrue(
            dialogs.contains("\"Pick an agent to start a session.\"") &&
                dialogs.contains("\"Pick the agent that owns the session you are importing.\""),
            "the refusal carries a reason in both dialog modes",
        )
        assertTrue(
            dialogs.contains("if (agentRef.current) agentRef.current.focus();"),
            "the refusal returns focus to the unanswered choice",
        )
        assertTrue(
            dialogs.contains("id=\"new-session-error\" class=\"form-error\" role=\"alert\""),
            "the reason is announced, not merely drawn",
        )

        // The same reason is visible at the point of choice while it is still unanswered.
        assertTrue(
            picker.contains("aria-describedby=\${agent ? null : \"new-session-agent-hint\"}"),
            "the group is described by its hint only while no agent is selected",
        )
        assertTrue(
            picker.contains("<p id=\"new-session-agent-hint\" class=\"field-hint\">"),
            "the hint is rendered inside the group it describes",
        )
    }

    @Test
    fun webUiOffersImportingASessionStartedOutsideKotgent() = withServer { ctx ->
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        val app = ctx.get("/app.js").bodyAsText()
        // The New session dialog's second mode registers a conversation started outside kotgent.
        assertTrue(dialogs.contains("id=\"new-session-mode-import\""), "the dialog has an Import mode")
        assertTrue(
            dialogs.contains("id=\"session-provider-id\""),
            "import mode asks for the provider session id",
        )
        assertTrue(
            dialogs.contains("providerSessionId: sessionId.trim(),"),
            "the id travels under the import route's field name",
        )
        // The daemon discovers the cwd from the provider's transcript, so only start mode requires one.
        assertTrue(
            dialogs.contains("required=\${mode === \"start\"}"),
            "the working directory is optional when importing",
        )
        // …and a prefilled start-mode cwd (a group's "+") must not ride into import mode, where any
        // non-empty cwd is sent as an explicit override of discovery (the codex probe ignores cwd, so
        // the wrong directory would be stored for good).
        assertTrue(
            dialogs.contains("""setCwd(next === "import" ? "" : (initialCwd || ""))"""),
            "switching modes resets the cwd to the mode's own default",
        )
        assertTrue(
            dialogs.contains("const [mode, setMode] = useState(initialMode)") &&
                dialogs.contains(
                    """const [cwd, setCwd] = useState(initialMode === "import" ? "" : (initialCwd || ""))""",
                ),
            "opening directly in import mode cannot inherit a selected session or group cwd",
        )
        assertTrue(
            app.contains("""openNewSession(null, "import")""") &&
                app.contains("initialMode=\${dialog.initialMode}"),
            "the resume mnemonic opens the existing dialog directly in import mode",
        )
        assertTrue(
            dialogs.contains("id=\"session-register-only\""),
            "importing can skip the automatic resume (the --no-start analogue)",
        )
        assertTrue(dialogs.contains("kotgent import <agent> <id>"), "the CLI help names the import command")
        assertTrue(
            dialogs.contains("start a session (claude | codex | junie | shell)"),
            "the CLI help lists Shell among the startable kinds",
        )
        assertTrue(
            dialogs.contains("Shell sessions have no") &&
                dialogs.contains("provider id and cannot be imported."),
            "the provider-id hint records why Shell is absent from Import mode",
        )

        assertTrue(app.contains("apiRequest(\"/sessions/import\""), "the import posts to POST /sessions/import")
        assertTrue(
            app.contains("\"/sessions/\" + encodeURIComponent(created.id) + \"/resume\""),
            "a successful import continues through the ordinary resume endpoint",
        )
        assertTrue(app.contains("if (registerOnly) {"), "register-only stops before the resume")
        // The import→resume flow occupies the same one-action-at-a-time slot as the control verbs, so a
        // Done/Stop cannot slip between the registration and the delayed follow-up resume (which would
        // then restart the session the operator had just stopped or archived).
        assertTrue(
            app.contains("setPendingAction(\"import\")"),
            "the import flow participates in the pending-action guard",
        )
        // A completion only closes the dialog it was submitted from: the dialog's Cancel/×/Esc stay live
        // while the request is in flight, so a dismissed import must not close a dialog opened since.
        assertTrue(
            app.contains("closeDialogFrom(submittedDialog)"),
            "the import completion closes only its own dialog",
        )
        // …and a late import/start FAILURE must not vanish into an unmounted form: the dialog's
        // Cancel/×/Esc stay live while the request is in flight, and a setError on the dismissed
        // instance is a silent no-op. The completion rethrows into the form's error line only while
        // the submitted dialog is still the mounted one, and routes the failure to the status line
        // otherwise — savePreferences' rule, applied to both new-session flows (import keeps the
        // daemon's verbatim text; start keeps its established prefix).
        assertTrue(
            app.contains("if (dialogRef.current === submittedDialog) throw e;") &&
                app.contains("say(errorMessage(e), true);") &&
                app.contains("say(\"Could not start session: \" + errorMessage(e), true);"),
            "a failure landing after the dialog was dismissed reaches the status line, not a dead form",
        )
        // Every HTTP DTO merges through upsertIfNewer: rows carry the daemon-stamped rev, so a DTO racing
        // the /events stream is ordered by comparison — a stale response can never roll a fresher row
        // back. Each step still AWAITS a targeted GET (fetchSessionRow) so the terminal-attach decision
        // reads the freshest known state; the step's own DTO is the fallback for a failed fetch.
        assertTrue(
            app.contains("const fetched = await fetchSessionRow(created.id)") &&
                app.contains("const registered = fetched || created"),
            "the import awaits a targeted row fetch instead of a wholesale list reload",
        )
        // A failed post-import fetch must not leave the imported row INVISIBLE (the 201 committed;
        // no frame lists it until its next change): the DTO is upserted as the fallback.
        assertTrue(
            app.contains("if (!fetched) setSessions((prev) => upsertIfNewer(prev, created))"),
            "the imported row is made visible even when the fetch failed",
        )
        assertTrue(
            app.contains("if (fetched) say(\"Imported \"") &&
                app.contains("if (freshRow) say(\"Imported and resumed \""),
            "a failed fetch's silence survives instead of being masked by a success line",
        )
        // The terminal-attach decision reads the freshest row first; when that fetch fails, the
        // RESUME DTO — post-resume state, alive — is the fallback, never the pre-resume `registered`
        // row, which would report success while silently dropping the terminal attach.
        assertTrue(
            app.contains("const resumedDto = await apiRequest(") &&
                app.contains("const freshRow = await fetchSessionRow(created.id)") &&
                app.contains("|| (resumedDto && resumedDto.id ? resumedDto : registered)"),
            "the post-resume selection prefers the fresh row, then the resume DTO — never the pre-resume row",
        )
        // Every showSession in the flow runs after an await, and in that window the operator can select
        // another session (a sidebar click, a push-notification tap). The flow only auto-selects the
        // imported session while the selection GENERATION is unchanged since submit; the status line
        // still reports the outcome either way. The guard counts selection EVENTS, not the selected id:
        // id equality has an ABA hole (A→B→A, or re-selecting the already-active session, compares
        // equal), so every showSession bumps the generation and the flows compare against a
        // submit-time capture of it.
        assertTrue(
            app.contains("selectionGenRef.current += 1") &&
                app.contains("const selectionAtSubmit = selectionGenRef.current") &&
                app.contains("const selectionUnmoved = () => selectionGenRef.current === selectionAtSubmit"),
            "the selection generation captured at submit time is the steering guard's reference point",
        )
        assertTrue(
            app.contains("if (selectionUnmoved()) showSession(registered);") &&
                app.contains("if (selectionUnmoved()) showSession(row);") &&
                app.contains("if (selectionUnmoved()) showSession(after || registered);"),
            "all three import-flow selections (register-only, resumed, resume-failed) honour a moved selection",
        )
        assertTrue(
            app.contains("if (selectionGenRef.current === selectionAtSubmit) showSession(created);"),
            "startSession honours the same rule — a selection moved during the POST is not yanked back",
        )
        assertTrue(
            !app.contains("activeRef.current === selectionAtSubmit"),
            "no flow guards on id equality — the ABA hole the generation exists to close",
        )
    }

    @Test
    fun webUiExposesThePreferencesScreen() = withServer { ctx ->
        assertTrue(
            ctx.get("/lib/commands.js").bodyAsText().contains("id: \"general.preferences\""),
            "the command palette has the preferences entry point",
        )
        val dialogs = ctx.get("/components/dialogs.js").bodyAsText()
        assertTrue(dialogs.contains("id=\"prefs-dialog\""), "the UI includes the preferences screen")
        assertTrue(dialogs.contains("id=\"prefs-base-path\""), "preferences expose the base path")
        assertTrue(dialogs.contains("id=\"prefs-grouping-level\""), "preferences expose the grouping level")
        assertTrue(
            dialogs.contains("shared by every browser connected to this daemon"),
            "the dialog explains the daemon-wide scope",
        )
        assertTrue(
            dialogs.contains("const [busy, setBusy] = useState(false)") &&
                dialogs.contains("await onSave(") &&
                dialogs.contains("Could not save preferences: ") &&
                dialogs.contains("""${'$'}{busy ? "Saving…" : "Save"}"""),
            "the dialog shows asynchronous save progress and keeps failures visible",
        )

        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("""apiRequest("/preferences")""") &&
                app.contains("""method: "PUT"""") &&
                app.contains("basePath: next.basePath") &&
                app.contains("groupingLevel: next.groupingLevel"),
            "the page loads and saves grouping preferences through the daemon",
        )
        assertTrue(
            app.contains("""msg.type === "preferences_update"""") &&
                app.contains("applyServerPreferences(msg)"),
            "the global events socket applies live preference updates",
        )
        assertTrue(
            app.contains("next.revision < preferencesRevisionRef.current") &&
                app.contains("preferencesRevisionRef.current = next.revision"),
            "older HTTP/WebSocket deliveries cannot roll back a newer persisted revision",
        )
        // The dialog seeds its draft from `prefs` once, at mount. A dialog REOPENED while a save's PUT
        // was still in flight therefore holds a pre-save draft — and closeDialogFrom rightly preserves
        // it. Keying the dialog on the committed revision remounts it when any commit lands (this
        // browser's or another's), re-seeding the draft; without the key, saving the stale draft would
        // roll the committed write back under a fresh revision, past the revision guard above.
        assertTrue(
            app.contains("""<${'$'}{PreferencesDialog} key=${'$'}{prefs.revision}"""),
            "a landed preferences commit re-seeds any open preferences dialog's draft",
        )
        // The key alone is not a SAVE guard: before the first revision arrives, a reopened dialog (or
        // one remounted busy=false by an early preferences_update echo) could land an overlapping PUT
        // from its stale draft, committing pre-save values under a FRESH revision. savePreferences
        // therefore refuses a second PUT while one is in flight — the rejection surfaces in the
        // dialog's own error line — and always releases the guard, success or failure.
        assertTrue(
            app.contains("const prefsSaveInFlightRef = useRef(false)") &&
                app.contains("if (prefsSaveInFlightRef.current) {") &&
                app.contains("prefsSaveInFlightRef.current = true") &&
                app.contains("prefsSaveInFlightRef.current = false"),
            "overlapping preference saves are refused while one PUT is still in flight",
        )
        // A remount keeps the dialog OBJECT identical, so the dialog identity alone cannot protect the
        // remounted form: savePreferences captures the mounted form's revision at submit, and a
        // completion only closes (or throws into) the form it came from — a remounted form keeps its
        // fresher draft open with the outcome routed to the status line.
        assertTrue(
            app.contains("const revisionAtSubmit = prefsRef.current.revision") &&
                app.contains("if (sameForm) closeDialogFrom(submittedDialog)") &&
                app.contains(
                    "if (dialogRef.current === submittedDialog && " +
                        "prefsRef.current.revision === revisionAtSubmit) throw e",
                ),
            "a preferences save completion never closes a remounted form or throws into an unmounted one",
        )

        val prefs = ctx.get("/lib/prefs.js").bodyAsText()
        assertTrue(
            prefs.contains("""LEGACY_PREFS_KEY = "kotgent.prefs.v1"""") &&
                prefs.contains("localStorage.removeItem(LEGACY_PREFS_KEY)") &&
                !prefs.contains("getItem(LEGACY_PREFS_KEY)"),
            "the legacy combined key is deleted but never imported",
        )
        assertTrue(
            prefs.contains("""COLLAPSED_GROUPS_KEY = "kotgent.collapsedGroups.v1"""") &&
                ctx.get("/lib/notify.js").bodyAsText().contains("""const KEY = "kotgent.notifications.v1""""),
            "collapsed groups and notification intent keep their existing device-local keys",
        )
        assertTrue(
            prefs.contains("""SIDEBAR_COLLAPSED_KEY = "kotgent.sidebarCollapsed.v1"""") &&
                prefs.contains("export function loadSidebarCollapsed()") &&
                prefs.contains("export function persistSidebarCollapsed(value)") &&
                prefs.contains("getItem(SIDEBAR_COLLAPSED_KEY) === \"true\"") &&
                prefs.contains(
                    "setItem(SIDEBAR_COLLAPSED_KEY, value === true ? \"true\" : \"false\")",
                ),
            "sidebar collapse has strict boolean helpers under its own device-local key",
        )
        val serverSanitizer = prefs.substringAfter("export function sanitizeServerPreferences(raw) {")
            .substringBefore("\n}\n\n/** Initial UI value")
        assertTrue(
            !serverSanitizer.contains("sidebarCollapsed") &&
                !app.substringAfter("method: \"PUT\"").substringBefore("\n      });")
                    .contains("sidebarCollapsed"),
            "sidebar collapse never enters daemon-wide GET/PUT preference payloads",
        )
    }

    @Test
    fun webUiExposesTheHelpScreen() = withServer { ctx ->
        assertTrue(
            ctx.get("/lib/commands.js").bodyAsText().contains("id: \"general.help\""),
            "the command palette has the help entry point",
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
            ctx.get("/lib/commands.js").bodyAsText().contains("id: \"general.phone\""),
            "the command palette has the phone sign-in entry point",
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
        val rev = revisionOf(body)
        assertTrue(
            body.contains("name=\"color-scheme\" content=\"dark\"") &&
                body.contains("href=\"/_v/$rev/style.css\"") &&
                body.contains("src=\"/_v/$rev/app.js\""),
            "the installed iOS app declares dark system UI and fetches content-revisioned assets",
        )
        // The manifest and the home-screen icon deliberately keep stable URLs: an installed PWA refers to
        // them by a fixed address, so a revision in their path would be churn, not invalidation.
        assertTrue(
            body.contains("href=\"manifest.webmanifest\"") &&
                body.contains("href=\"icons/apple-touch-icon.png\""),
            "the install surface stays on stable URLs the installed app can keep referring to",
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
        for (handler in listOf("push", "pushsubscriptionchange", "message", "notificationclick", "fetch")) {
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
            subscriptionChangeHandler.contains(
                "event.waitUntil(queuePushLifecycle(() => syncPushSubscription(event)));",
            ),
            "subscription rotation is serialized with durable preference changes under waitUntil",
        )
        val preferenceHandler = body.substringAfter(
            "self.addEventListener(\"message\", (event) => {",
        ).substringBefore("\n});")
        assertTrue(
            preferenceHandler.contains("message.type !== PUSH_PREFERENCE_MESSAGE") &&
                preferenceHandler.contains("typeof message.enabled !== \"boolean\"") &&
                preferenceHandler.contains(
                    "const applied = queuePushLifecycle(() => applyPushPreference(message.enabled, endpoints))",
                ) &&
                preferenceHandler.contains("event.waitUntil(applied.then(") &&
                preferenceHandler.contains("() => answer(true)") &&
                preferenceHandler.contains("() => answer(false)"),
            "a page preference message keeps its durable write and OFF compensation alive after page closure",
        )
        val rotation = body.substringAfter("async function syncPushSubscription(event) {")
            .substringBefore("\n}\n\n/**")
        val createReplacementAt = rotation.indexOf(
            "await self.registration.pushManager.subscribe(oldSubscription.options)",
        )
        val intentBeforeCreateAt = rotation.indexOf("await pushIsStillWanted()")
        val intentBeforeRegisterAt = rotation.indexOf(
            "if (replacement && !(await pushIsStillWanted()))",
        )
        val saveReplacementAt = rotation.indexOf("await registerPushSubscription(replacement)")
        val intentAfterRegisterAt = rotation.indexOf(
            "if (!(await pushIsStillWanted()))",
            startIndex = saveReplacementAt.coerceAtLeast(0),
        )
        val compensateReplacementAt = rotation.indexOf(
            "await discardPushSubscription(replacement)",
            startIndex = intentAfterRegisterAt.coerceAtLeast(0),
        )
        val dropObsoleteAt = rotation.indexOf(
            "await unregisterPushSubscription(oldSubscription.endpoint)",
        )
        assertTrue(
            rotation.contains("event.newSubscription") &&
                rotation.contains("event.oldSubscription") &&
                intentBeforeCreateAt in 0 until createReplacementAt &&
                intentBeforeRegisterAt in (createReplacementAt + 1) until saveReplacementAt &&
                intentAfterRegisterAt in (saveReplacementAt + 1) until compensateReplacementAt &&
                compensateReplacementAt in (intentAfterRegisterAt + 1) until dropObsoleteAt &&
                dropObsoleteAt > saveReplacementAt &&
                rotation.contains("oldSubscription.endpoint !== replacement.endpoint"),
            "rotation rechecks live intent around renewal and compensates a crossed OFF before old cleanup",
        )
        val lifecycleQueue = body.substringAfter("function queuePushLifecycle(operation) {")
            .substringBefore("\n}\n\n/**")
        val storePreference = body.substringAfter("async function storePushPreference(enabled) {")
            .substringBefore("\n}\n\n/**")
        val currentIntent = body.substringAfter("async function pushIsStillWanted() {")
            .substringBefore("\n}\n\n/**")
        val applyPreference = body.substringAfter(
            "async function applyPushPreference(enabled, rememberedEndpoints) {",
        ).substringBefore("\n}\n\n/**")
        val discardReplacement = body.substringAfter(
            "async function discardPushSubscription(subscription) {",
        ).substringBefore("\n}\n\n/**")
        val cacheReadAt = currentIntent.indexOf(
            "await self.caches.match(",
        )
        val storedValueAt = currentIntent.indexOf(
            "(await response.text()) === \"1\"",
        )
        val permissionAfterCacheAt = currentIntent.indexOf(
            "Notification.permission === \"granted\"",
            startIndex = storedValueAt.coerceAtLeast(0),
        )
        assertTrue(
            body.contains("const PUSH_PREFERENCE_MESSAGE = \"push-notification-preference\"") &&
                body.contains("const PUSH_PREFERENCE_CACHE = \"kotgent-push-preference-v1\"") &&
                body.contains("const PUSH_PREFERENCE_URL = \"/.kotgent-push-preference\"") &&
                lifecycleQueue.contains("pushLifecycle.catch(() => {}).then(operation)") &&
                lifecycleQueue.contains("pushLifecycle = queued.catch(() => {})") &&
                storePreference.contains("await self.caches.open(PUSH_PREFERENCE_CACHE)") &&
                storePreference.contains(
                    "cache.put(PUSH_PREFERENCE_URL, new Response(enabled ? \"1\" : \"0\"))",
                ) &&
                currentIntent.contains("Notification.permission !== \"granted\"") &&
                currentIntent.contains(
                    "{ cacheName: PUSH_PREFERENCE_CACHE }",
                ) &&
                cacheReadAt >= 0 &&
                storedValueAt > cacheReadAt &&
                permissionAfterCacheAt > storedValueAt,
            "renewal requires a durable explicit ON and live permission even after every page disappears",
        )
        val persistChoiceAt = applyPreference.indexOf("await storePushPreference(enabled)")
        val enabledReturnAt = applyPreference.indexOf("if (enabled) return")
        val rememberedDropAt = applyPreference.indexOf("rememberedEndpoints.forEach(startDaemonDrop)")
        val browserLookupAt = applyPreference.indexOf(
            "await self.registration.pushManager.getSubscription()",
        )
        val discoveredDropAt = applyPreference.indexOf(
            "startDaemonDrop(subscription.endpoint)",
        )
        val browserDropAt = applyPreference.indexOf("subscription.unsubscribe()")
        assertTrue(
            persistChoiceAt in 0 until enabledReturnAt &&
                rememberedDropAt in (enabledReturnAt + 1) until browserLookupAt &&
                discoveredDropAt in (browserLookupAt + 1) until browserDropAt &&
                applyPreference.contains("await Promise.allSettled(["),
            "serialized OFF persists first, then deletes remembered and current endpoints after page closure",
        )
        assertTrue(
            discardReplacement.contains("Promise.allSettled([") &&
                discardReplacement.indexOf("unregisterPushSubscription(subscription.endpoint)") in
                0 until discardReplacement.indexOf("subscription.unsubscribe()"),
            "a replacement that crosses OFF drops daemon reachability before its browser subscription",
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
        val preferenceAckAt = subscribe.indexOf(
            "await syncWorkerPushPreference(registration, true)",
        )
        val subscribeKeyAt = subscribe.indexOf("await vapidKeyOrNull(context)")
        assertTrue(
            permissionAt >= 0 &&
                permissionAwaitAt > permissionAt &&
                registrationAt > permissionAwaitAt &&
                preferenceAckAt in (registrationAt + 1) until subscribeKeyAt,
            "permission starts from the click, then durable ON is acknowledged before browser/network mutation",
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
        val workerPreference = body.substringAfter(
            "export async function syncWorkerPushPreference(registration = null, waitForApply = false) {",
        ).substringBefore("\n}\n\nfunction signalPushRepair")
        val delayedRegistrationAt = workerPreference.indexOf(
            "await navigator.serviceWorker.getRegistration()",
        )
        val readCurrentPreferenceAt = workerPreference.indexOf("const enabled = notifyEnabled()")
        val postPreferenceAt = workerPreference.indexOf("worker.postMessage(message)")
        assertTrue(
            body.contains(
                "export const PUSH_PREFERENCE_MESSAGE = \"push-notification-preference\"",
            ) &&
                body.contains("const PUSH_PREFERENCE_ACK_TIMEOUT_MS = 2_000") &&
                body.contains(
                    "import { ensurePermission, isEnabled as notifyEnabled, setPushActive }",
                ) &&
                delayedRegistrationAt >= 0 &&
                readCurrentPreferenceAt > delayedRegistrationAt &&
                postPreferenceAt > readCurrentPreferenceAt &&
                workerPreference.contains(
                    "endpoints: enabled ? [] : Array.from(rememberedEndpoints())",
                ) &&
                workerPreference.contains("navigator.serviceWorker.controller") &&
                workerPreference.contains("activeRegistrationMemory.active") &&
                workerPreference.contains("if (!waitForApply)") &&
                workerPreference.contains("const channel = new MessageChannel()") &&
                workerPreference.contains(
                    "setTimeout(() => finish(false), PUSH_PREFERENCE_ACK_TIMEOUT_MS)",
                ) &&
                workerPreference.contains("worker.postMessage(message, [channel.port2])"),
            "messages reread intent after lookup, carry OFF endpoints, and can await serialized worker application",
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
        val publishOffAt = unsubscribe.indexOf("syncWorkerPushPreference()")
        val cachedDropAt = unsubscribe.indexOf("rememberedEndpoints().forEach(startDaemonDrop)")
        val browserLookupAt = unsubscribe.indexOf("await navigator.serviceWorker.getRegistration()")
        val discoveredDropAt = unsubscribe.indexOf("startDaemonDrop(endpoint)")
        val browserUnsubscribeAt = unsubscribe.indexOf(
            "settleMutation(subscription.unsubscribe(), context)",
        )
        val cleanupFinallyAt = unsubscribe.indexOf("} finally {")
        val waitForDaemonAt = unsubscribe.indexOf("await Promise.allSettled(", cleanupFinallyAt)
        assertTrue(
            publishOffAt in 0 until cachedDropAt &&
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
        val refreshRegistrationAt = refresh.indexOf(
            "await navigator.serviceWorker.getRegistration()",
        )
        val refreshPreferenceAt = refresh.indexOf(
            "await syncWorkerPushPreference(registration, true)",
        )
        val refreshLookupAt = refresh.indexOf(
            "await registration.pushManager.getSubscription()",
        )
        val refreshRememberAt = refresh.indexOf("rememberEndpoint(existing.endpoint)")
        val refreshKeyAt = refresh.indexOf("await vapidKeyOrNull(context)")
        val missingRegistrationAt = refresh.indexOf("if (!registration || !existing) {")
        val repairWithoutPromptAt = refresh.indexOf("subscribe(Promise.resolve(true), context)")
        assertTrue(
            refresh.indexOf("await registerSubscription(subscription, context)") in
                0 until refresh.indexOf("setPushActive(true)") &&
                refreshRegistrationAt >= 0 &&
                refreshPreferenceAt in (refreshRegistrationAt + 1) until refreshLookupAt &&
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
        val togglePermissionAt = toggle.indexOf(
            "const permission = next ? ensurePermission() : null",
        )
        val togglePreferenceAt = toggle.indexOf("syncWorkerPushPreference()")
        val toggleIntentAt = toggle.indexOf("pushPermissionRef.current =")
        val toggleRepairAt = toggle.indexOf("repairPushRef.current()")
        assertTrue(
            togglePermissionAt >= 0 &&
                togglePreferenceAt in (togglePermissionAt + 1) until toggleIntentAt &&
                toggleRepairAt > toggleIntentAt,
            "an enable click claims permission first, then publishes its intent before requesting reconciliation",
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
                toggle.contains("repairPushRef.current()") &&
                !toggle.contains("queuePushTransition(") &&
                toggle.contains(
                    "Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort())",
                ) &&
                repair.contains("const desired = notifyEnabled()") &&
                repair.contains("const repairGeneration = transition + \":\" + desired") &&
                repair.contains("pushRepairGenerationRef.current === repairGeneration") &&
                repair.contains("transition,\n      desired,") &&
                repair.contains("const permission = pushPermissionRef.current") &&
                repair.contains("permission.transition === transition && permission.request") &&
                repair.contains("pushSubscribe(permission.request, context)") &&
                repair.contains("refreshPush(context)") &&
                repair.contains("pushUnsubscribe(context)"),
            "one repair owner derives the subscription decision from current local and origin-wide intent",
        )
        val storageSync = sidebar.substringAfter(
            "const syncNotificationPreference = (event = null) => {",
        )
            .substringBefore("\n    };")
        val addStorageListenerAt = sidebar.indexOf(
            "window.addEventListener(\"storage\", syncNotificationPreference)",
        )
        val preservedPermissionAt = storageSync.indexOf(
            "request: next && !preferenceChanged ? permission.request : null",
        )
        val storageRepairAt = storageSync.indexOf("repairPushRef.current()")
        assertTrue(
            storageSync.contains("const next = notifyEnabled()") &&
                storageSync.indexOf("syncWorkerPushPreference()") in
                (storageSync.indexOf("const next = notifyEnabled()") + 1) until
                storageSync.indexOf("if (!preferenceChanged && !repairSignalled) return false") &&
                storageSync.contains("event.key === PUSH_REPAIR_SIGNAL_KEY") &&
                storageSync.contains("if (!preferenceChanged && !repairSignalled) return false") &&
                storageSync.contains("notifyOnRef.current = next") &&
                storageSync.contains("setNotifyOn(next)") &&
                storageSync.contains("++pushTransitionIdRef.current") &&
                storageSync.contains(
                    "Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort())",
                ) &&
                preservedPermissionAt >= 0 &&
                storageRepairAt > preservedPermissionAt &&
                !storageSync.contains("queuePushTransition(") &&
                addStorageListenerAt >= 0 &&
                sidebar.contains("window.removeEventListener(\"storage\", syncNotificationPreference)"),
            "cross-tab changes publish intent while the repair owner preserves an in-flight permission gesture",
        )
        val mountReconciliation = sidebar.substringAfter(
            "window.addEventListener(\"storage\", syncNotificationPreference);",
        ).substringBefore("\n    return () => {")
        val closeListenerGapAt = mountReconciliation.indexOf("if (!syncNotificationPreference())")
        val mountRepairAt = mountReconciliation.indexOf("repairPushRef.current()")
        assertTrue(
            closeListenerGapAt >= 0 &&
                mountRepairAt > closeListenerGapAt &&
                !mountReconciliation.contains("++pushTransitionIdRef.current") &&
                !mountReconciliation.contains("pushPermissionRef.current ="),
            "mount reconciliation joins the current transition without advancing its generation or discarding its permission request",
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
                workerMessage.contains("fetchSessionRowRef.current(msg.sessionId)"),
            "a notification target missing from a stale snapshot is retained and its ONE row fetched",
        )
        val knownTarget = workerMessage.substringAfter(
            "if (sessionsRef.current.some((session) => session.id === msg.sessionId)) {",
        ).substringBefore("\n      }")
        assertTrue(
            knownTarget.indexOf("deepLinkRef.current = null") in
                0 until knownTarget.indexOf("selectSession(msg.sessionId)"),
            "a known notification target drops any older retained one before it is selected immediately",
        )
        // Both carriers of a late-arriving deep-link target honour it: the snapshot applicator (bounded
        // by its markReadIfViewing tail) and the single-row applicator (the worker's fetch lands there).
        val reloadSelection = app.substringAfter("const wanted = deepLinkRef.current;")
            .substringBefore("markReadIfViewing(active.id")
        val targetGuard = reloadSelection.indexOf("if (target) {")
        assertTrue(
            targetGuard >= 0 &&
                reloadSelection.indexOf("deepLinkRef.current = null") > targetGuard &&
                reloadSelection.indexOf("showSession(target)") > targetGuard,
            "a snapshot that does not contain the notification target leaves it retained for a later frame",
        )
        val rowApplicator = app.substringAfter("const applySessionRow = useCallback((row) => {")
            .substringBefore("\n  }, [showSession]);")
        assertTrue(
            rowApplicator.contains("if (deepLinkRef.current === row.id) {") &&
                rowApplicator.contains("showSession(row)"),
            "a retained notification target arriving as a single row is honoured too",
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
        assertTrue(
            Regex(
                """(?s)#app\.installed-app\s*\{[^}]*height:\s*100vh""",
            ).containsMatchIn(css),
            "an installed PWA restores the physical viewport height WebKit subtracts the bottom inset from",
        )
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
        val installedClassAt = app.indexOf("appRoot.classList.toggle(\"installed-app\", installedApp)")
        val renderAt = app.indexOf("render(html`<\${App} />`, appRoot)")
        assertTrue(
            app.contains("window.matchMedia(\"(display-mode: standalone)\").matches") &&
                app.contains("window.matchMedia(\"(display-mode: fullscreen)\").matches") &&
                app.contains("window.navigator.standalone === true") &&
                installedClassAt >= 0 && renderAt > installedClassAt,
            "the shell recognizes standard, WebKit-fullscreen, and iOS-vendor installed signals before rendering",
        )
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
        val viewportSizing = pane.substringAfter("const sizeForVisualViewport = () => {")
            .substringBefore("\n    };")
        val keyboardStateAt = viewportSizing.indexOf(
            "app.classList.toggle(\"visual-viewport-shrunken\", viewportShrunken)",
        )
        val hiddenViewportReturnAt = viewportSizing.indexOf("if (!viewportShrunken) return")
        val applyCapAt = viewportSizing.indexOf("host.classList.add(\"visual-viewport-sized\")")
        assertTrue(
            keyboardStateAt >= 0 &&
                hiddenViewportReturnAt > keyboardStateAt &&
                applyCapAt > hiddenViewportReturnAt,
            "only a keyboard-shrunken visual viewport owns the terminal cap and shell inset state",
        )
        assertTrue(
            pane.contains("app.classList.remove(\"visual-viewport-shrunken\")"),
            "terminal teardown cannot leave a stale keyboard-safe-area state on the shell",
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
        // With the DOM renderer the cursor is a <span> rebuilt on every repaint of its row, which restarts
        // its CSS blink from the "on" phase — under a TUI repainting per keystroke the phase is reset at
        // irregular intervals and reads as stuttering. A steady cursor has no phase to lose, and claude
        // (measured: it sends `?12l`) asks for the same thing.
        assertTrue(
            pane.contains("cursorBlink: false,"),
            "the cursor is steady, so repaint-driven blink restarts cannot stutter it",
        )
    }

    /**
     * xterm 5.5 deliberately skips its native touch handlers while mouse tracking is active. Kotgent
     * keeps that mode active so wheel events reach tmux's pane history, therefore a phone swipe needs a
     * small browser-side bridge into xterm's existing wheel pipeline. Pin that bridge, its gesture
     * ownership rules, and its teardown here; real touch delivery remains a real-device check.
     */
    @Test
    fun theWebUiBridgesPhoneSwipesIntoXtermWheelEvents() = withServer { ctx ->
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        val css = ctx.get("/style.css").bodyAsText()
        // Slice on explicit indices rather than substringAfter/Before: those default to
        // `missingDelimiterValue = this`, so a renamed helper would silently widen `bridge` to the whole
        // file and let an assertion be satisfied by unrelated code elsewhere in the component.
        val bridgeStart = pane.indexOf("function installSwipeScroll(term) {")
        assertTrue(bridgeStart >= 0, "the bridge helper exists under its documented name")
        val bridgeEnd = pane.indexOf("\n}\n\nexport function TerminalPane", bridgeStart)
        assertTrue(bridgeEnd > bridgeStart, "the bridge helper ends where the component begins")
        val bridge = pane.substring(bridgeStart, bridgeEnd)
        assertTrue(bridge.length < pane.length, "the slice is a strict substring, not the whole file")

        assertTrue(
            bridge.contains("term.modes.mouseTrackingMode === \"none\""),
            "the bridge yields to xterm's native touch scrolling when mouse tracking is inactive",
        )
        // Pointer capture, not TouchEvents: a touch gesture stays bound to the node it began on, and the
        // rows under the finger are exactly what a scroll repaints. Measured on a real iPhone, a swipe
        // over glyphs then produced 1-2 reports for a whole gesture while the empty gutter beside the
        // text stayed smooth. Capturing retargets every later move to the terminal element.
        assertTrue(
            bridge.contains("element.setPointerCapture(event.pointerId)") &&
                bridge.contains("event.pointerType !== \"touch\"") &&
                bridge.contains("Math.abs(totalY) <= Math.abs(totalX)"),
            "a finger's pointer is captured, and only a predominantly vertical gesture is claimed",
        )
        assertFalse(
            bridge.contains("addEventListener(\"touch"),
            "the bridge does not listen for touch events, whose delivery a repaint can cut",
        )
        assertTrue(
            bridge.contains("element.dispatchEvent(new WheelEvent(\"wheel\"") &&
                bridge.contains("deltaMode: WheelEvent.DOM_DELTA_LINE"),
            "a claimed swipe becomes line-based wheel input handled by xterm's current mouse protocol",
        )
        // Ordering, not mere presence: cancelling an unqualified move suppresses the compatibility mouse
        // burst, and with it xterm's mousedown focus — i.e. hoisting this above the claim gate would make
        // the software keyboard unreachable on a phone while every "contains" still passed.
        val claimGateAt = bridge.indexOf("if (!gesture.claimed) {")
        val preventDefaultAt = bridge.indexOf("event.preventDefault()")
        assertTrue(
            claimGateAt >= 0 && preventDefaultAt > claimGateAt,
            "the gesture is cancelled only after it qualifies as a swipe (claim at $claimGateAt, " +
                "preventDefault at $preventDefaultAt)",
        )
        // One report per row. The rate is deliberately NOT tmux's five-lines-per-report — an agent pane
        // forwards the wheel to its full-screen TUI instead of entering copy-mode, and converting for
        // copy-mode made that common case five times too slow.
        assertTrue(
            bridge.contains("Math.trunc(pendingPx / rowHeight)") &&
                bridge.contains("pendingPx -= direction * count * rowHeight"),
            "travel is converted row-for-row and only what is emitted leaves the bank",
        )
        // The gesture banks; a frame loop emits. An agent pane repaints its whole alternate screen per
        // report, so emitting a whole move's worth at once arrives as a visible lurch — and a phone
        // gesture has no browser-synthesised momentum to carry it after the finger lifts.
        val moveStart = bridge.indexOf("const onPointerMove = (event) => {")
        val moveEnd = bridge.indexOf("const onPointerUp = ")
        assertTrue(moveStart in 0 until moveEnd, "the bridge has distinct move and up handlers")
        assertFalse(
            bridge.substring(moveStart, moveEnd).contains("dispatchEvent"),
            "a move banks travel instead of emitting reports itself",
        )
        assertTrue(
            bridge.contains("frameHandle = requestAnimationFrame(frame)") &&
                bridge.contains("cancelAnimationFrame(frameHandle)"),
            "reports are paced by a frame loop that can be stopped",
        )
        assertTrue(
            bridge.contains("velocity *= Math.pow(inertiaDecayPerMs, elapsed)") &&
                bridge.contains("coasting = true"),
            "a lifted finger keeps scrolling under a decaying velocity",
        )
        // A new contact must stop a coasting scroll before it captures, or the throw fights the finger.
        val downAt = bridge.indexOf("const onPointerDown = (event) => {")
        val stopInDown = bridge.indexOf("stopMotion();", downAt)
        val captureAt = bridge.indexOf("element.setPointerCapture(event.pointerId)", downAt)
        assertTrue(
            downAt >= 0 && stopInDown in (downAt + 1) until captureAt,
            "a new touch cancels inertia immediately, before the pointer is even captured",
        )
        assertTrue(
            pane.contains("swipeScroll.dispose()"),
            "the bridge is disposed with its terminal",
        )
        for (event in listOf("pointerdown", "pointermove", "pointerup", "pointercancel")) {
            assertTrue(
                bridge.contains("element.addEventListener(\"$event\"") &&
                    bridge.contains("element.removeEventListener(\"$event\""),
                "the $event listener has a matching teardown",
            )
        }

        // The reservation belongs to the bridge, which is installed unconditionally — under `pinch-zoom`
        // or `auto` a real iPhone stops scrolling the terminal entirely, which is what every viewport
        // wider than the phone breakpoint (landscape, iPad) used to get.
        assertTrue(
            Regex("""(?s)#terminal-host \.xterm\s*\{[^}]*height: 100%[^}]*touch-action:\s*none""")
                .containsMatchIn(css),
            "the unconditional terminal rule owns the touch gesture",
        )
        val breakpoint = css.indexOf("@media (max-width: 720px)")
        assertTrue(breakpoint > 0, "the mobile breakpoint exists")
        val nextMedia = css.indexOf("@media ", breakpoint + 1).let { if (it < 0) css.length else it }
        assertFalse(
            css.substring(breakpoint, nextMedia).contains("touch-action"),
            "the reservation is not scoped to the phone breakpoint, where the bridge is not",
        )
    }

    /**
     * FitAddon measures the terminal's parent box, then subtracts padding from the `.xterm` element
     * itself. Padding the parent instead makes the proposed grid one row/column too large whenever the
     * unaccounted pixels cross a cell boundary, leaving the final row clipped by `overflow: hidden`.
     */
    @Test
    fun xtermFitSubtractsThePaddingThatFramesTerminalContent() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        val addon = ctx.get("/vendor/addon-fit.js").bodyAsText()
        val hostRule = assertNotNull(
            Regex("""(?s)#terminal-host\s*\{([^}]*)}""").find(css)?.groupValues?.get(1),
            "the terminal host rule exists",
        )
        val xtermRule = assertNotNull(
            Regex("""(?s)#terminal-host \.xterm\s*\{([^}]*)}""").find(css)?.groupValues?.get(1),
            "the xterm rule exists",
        )

        assertTrue(
            addon.contains("""this._terminal.element.parentElement""") &&
                addon.contains("""getPropertyValue("padding-top")"""),
            "the vendored FitAddon measures the parent but subtracts padding from .xterm",
        )
        assertTrue(
            !hostRule.contains("padding:"),
            "the measured parent must not hide unaccounted terminal padding",
        )
        assertTrue(
            xtermRule.contains("height: 100%") && xtermRule.contains("padding: 6px 8px"),
            "desktop padding belongs to the element FitAddon subtracts from the available box",
        )

        val breakpoint = css.indexOf("@media (max-width: 720px)")
        assertTrue(breakpoint > 0, "the mobile breakpoint exists")
        val nextMedia = css.indexOf("@media ", breakpoint + 1).let { if (it < 0) css.length else it }
        val mobileCss = css.substring(breakpoint, nextMedia)
        assertTrue(
            Regex("""(?s)#terminal-host \.xterm\s*\{[^}]*padding:\s*4px 6px""")
                .containsMatchIn(mobileCss),
            "mobile padding stays on .xterm too, so its smaller grid is fitted to visible pixels",
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
        // xterm splits its output across TWO events: a mouse report whose active encoding is the legacy
        // X10 one goes to onBinary (`CoreMouseService` routes `DEFAULT` to `triggerBinaryEvent`) because
        // its coordinates are raw bytes above 127. Subscribing to onData alone drops those reports with no
        // error at all — the mouse just stops working — and the encoding degrades exactly this way when
        // tracking arrives without SGR, which is what TERMINAL_MODE_RESET's ordering rule guards against.
        assertTrue(
            pane.contains("const binarySubscription = term.onBinary(") &&
                pane.contains("bytes[i] = data.charCodeAt(i) & 0xff") &&
                pane.contains("binarySubscription.dispose()"),
            "legacy-encoded mouse reports reach the upstream byte-wise and unsubscribe with the terminal",
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
        assertTrue(
            Regex("""(?s)#app:has\(\.key-bar\)\s*\{[^}]*padding-bottom:\s*0""")
                .containsMatchIn(mobileCss) &&
                Regex(
                    """(?s)\.key-bar\s*\{[^}]*padding-bottom:\s*calc\(5px \+ var\(--active-safe-area-bottom\)\)""",
                ).containsMatchIn(mobileCss) &&
                cssRuleOf(css, "#app").contains("var(--active-safe-area-bottom)") &&
                Regex(
                    """(?s)#app\.visual-viewport-shrunken\s*\{[^}]*--active-safe-area-bottom:\s*0px""",
                ).containsMatchIn(css) &&
                cssRuleOf(css, ".key-bar-key").contains("min-height: 48px"),
            "the inset moves into an attached toolbar, stays on no-toolbar footers, and clears above the keyboard",
        )
    }

    /**
     * A suspended mobile page or a daemon restart can drop the terminal WebSocket. There is no browser
     * runner in this native suite, so pin the complete source-side lifecycle: the exact closed attachment
     * is remembered, fresh/foreground attachments get one bounded attempt, a failed liveness read retains
     * its candidate, and the self-healing events socket grants another attempt when the daemon is back.
     */
    @Test
    fun theWebUiReattachesAClosedAliveTerminalAfterBackgroundingOrDaemonRestart() = withServer { ctx ->
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
            "a retry asks the daemon for current liveness before restoring the attachment",
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
        val activeBranch = schedule.substringAfter("if (activeRef.current !== id) {").substringBefore("}")
        assertTrue(
            activeBranch.contains("reattachIdRef.current = null"),
            "a different active session is explicit intent, which discards the candidate",
        )
        assertTrue(
            schedule.contains("if (pendingRef.current) return;"),
            "a control action in flight is as transient as an unreachable daemon, so its branch returns " +
                "without discarding the candidate and a later grant retries once the action settled",
        )

        val failedRefresh = schedule.substringAfter("} catch (err) {")
            .substringBefore("\n      } finally {")
        assertTrue(
            failedRefresh.contains("reattachRequestRef.current !== controller") &&
                failedRefresh.contains("setHint(detachedHint(null))"),
            "only the owning failed refresh reports the detachment, without claiming stale liveness",
        )
        assertTrue(
            failedRefresh.contains("if (isDefiniteAnswer(err)) reattachIdRef.current = null"),
            "a 4xx is the daemon's own answer about this session, so it discards the candidate instead of " +
                "re-asking on every later grant; every other failure keeps it for daemon recovery",
        )
        val api = ctx.get("/lib/api.js").bodyAsText()
        assertTrue(
            api.contains("export function isDefiniteAnswer(error)") &&
                api.contains("error.status >= 400 && error.status < 500") &&
                api.contains("expired.status = resp.status") &&
                api.contains("failed.status = resp.status"),
            "apiRequest carries the HTTP status, so a caller can tell a definitive refusal from a daemon " +
                "it simply could not reach",
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

        val events = app.substringAfter("// Live updates. The daemon re-sends a full snapshot on connect")
            .substringBefore("// Coming back to the tab is the third trigger")
        val recoveredAt = events.indexOf("if (opened)")
        val recoveryGrantAt = events.indexOf("reattachAvailableRef.current = true")
        val recoveryScheduleAt = events.indexOf("scheduleReattachRef.current()")
        val openedAt = events.indexOf("opened = true")
        assertTrue(
            events.contains("let opened = false") &&
                events.contains("socket.onopen = () => {") &&
                recoveredAt >= 0 &&
                recoveryGrantAt > recoveredAt &&
                recoveryScheduleAt > recoveryGrantAt &&
                openedAt > recoveryScheduleAt,
            "only a recovered events socket grants and schedules another terminal attempt",
        )
        val scheduleAt = app.indexOf("const scheduleReattach = useCallback(() => {")
        val eventsAt = app.indexOf("// Live updates. The daemon re-sends a full snapshot on connect")
        assertTrue(
            scheduleAt >= 0 && eventsAt > scheduleAt,
            "the recovery callback is defined before the events effect consumes it",
        )
        assertTrue(
            app.contains("const scheduleReattachRef = useRef(scheduleReattach)") &&
                app.contains("scheduleReattachRef.current = scheduleReattach") &&
                events.contains("scheduleReattachRef.current()") &&
                !app.contains("}, [applyServerPreferences, say, scheduleReattach]);"),
            "the events effect reaches the recovery callback through a ref, like the loadRef beside it: a " +
                "dependency would rebuild the daemon socket and reset `opened`, disabling recovery silently",
        )

        val showSession = app.substringAfter("const showSession = useCallback((session) => {")
            .substringBefore("\n  }, [cancelReattach]);")
        val attach = app.substringAfter("const attach = useCallback(() => {")
            .substringBefore("\n  }, [cancelReattach]);")
        val resume = app.substringAfter("} else if (action === \"resume\") {")
            .substringBefore("\n      }")
        for ((source, name) in listOf(
            showSession to "selecting/starting a live session",
            attach to "explicitly attaching",
            resume to "resuming a session",
        )) {
            val grantAt = source.indexOf("reattachAvailableRef.current = true")
            assertTrue(
                grantAt >= 0 && source.indexOf("setAttachedId(") > grantAt,
                "$name grants one retry before opening its terminal",
            )
            // -1 where the site never cancels at all, which cannot revoke the grant either.
            assertTrue(
                source.indexOf("cancelReattach()") < grantAt,
                "$name grants AFTER its own cancel, which would otherwise revoke the grant it just made",
            )
        }
        assertTrue(
            showSession.indexOf("if (isAliveState(session.state)) {") in
                0 until showSession.indexOf("reattachAvailableRef.current = true"),
            "selecting a session that is not alive arms no retry at all",
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

    /** One top-level `selector { … }` rule body, so a declaration check cannot read a neighbouring rule. */
    private fun cssRuleOf(css: String, selector: String): String {
        val start = css.indexOf("\n$selector {")
        val end = css.indexOf("}", start.coerceAtLeast(0))
        assertTrue(start >= 0 && end > start, "the stylesheet declares `$selector`")
        return css.substring(start, end)
    }

    /** The new-session dialog's agent `<fieldset>`, so a picker assertion cannot read the rest of the form. */
    private fun agentPickerOf(dialogs: String): String {
        val start = dialogs.indexOf("<fieldset class=\"field agent-picker\"")
        val end = dialogs.indexOf("</fieldset>", start.coerceAtLeast(0))
        assertTrue(start >= 0 && end > start, "the agent picker is a bounded fieldset")
        return dialogs.substring(start, end)
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
