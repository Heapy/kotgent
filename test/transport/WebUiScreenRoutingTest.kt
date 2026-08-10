package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
 * The seams between the router, the two task screens and the shell that hosts them.
 *
 * `lib/router.js`, `components/Board.js`, `components/TaskDetail.js`, `components/Sidebar.js` and
 * `style.css` were each written and tested alone, and every defect this file pins was invisible from
 * inside any one of them: the route grammar was complete and the daemon served it, but nothing consumed
 * `/s/{id}`; the board announced its refusals through a channel whose only renderer the board itself
 * unmounts; the stylesheet sized two screens that turned out to render together. None of it is reachable
 * from a component's own serving test, because each of those reads one file.
 *
 * As everywhere else in this suite, the macosArm64 test binary cannot run the JS: what is automatable is
 * the served SOURCE, and what each assertion below states is a coupling that spans at least two files.
 * Behaviour in a real browser stays on the manual checklist.
 */
class WebUiScreenRoutingTest {

    /**
     * `/s/{id}` is parsed by the router, linked by two components and served by the daemon. Before this,
     * nothing read it: `app.js` imported neither `SCREEN_SESSIONS` nor `sessionPath`, and the only use of
     * `route.id` was the task detail's ref. Clicking a session dot on a card left the board and landed on
     * the session view with nothing selected.
     */
    @Test
    fun aRouteNamingASessionSelectsIt() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        assertTrue(
            app.contains("  SCREEN_SESSIONS,") && app.contains("  sessionPath,"),
            "app.js imports the session arm of the route grammar it has to consume",
        )
        assertTrue(
            app.contains("const routeSessionId = route.screen === SCREEN_SESSIONS ? route.id : null;"),
            "the session id the route names is read out of the route",
        )
        val effect = sliceBetween(
            app,
            "const routeSessionId = route.screen === SCREEN_SESSIONS",
            "\n  }, [routeSessionId, sessions, activeId, showSession]);",
            "the route-to-selection effect",
        )
        assertTrue(
            effect.contains("const target = sessions.find((s) => s.id === routeSessionId);") &&
                effect.contains("showSession(target)"),
            "a route id whose row this client holds is selected",
        )
        assertTrue(
            effect.contains("if (!target) return;"),
            "an id the first snapshot has not carried yet is HELD, not discarded — a deep-linked reload " +
                "arrives before the list does, and the effect re-runs on every list change",
        )
        assertTrue(
            effect.contains("if (!routeSessionId || routeSessionId === activeId) return;"),
            "guarded on the active id, which is what makes showSession's own navigate idempotent here " +
                "instead of re-entrant",
        )
        // Both carriers of the notification deep link must agree on one retained target.
        assertTrue(
            effect.contains("if (deepLinkRef.current === routeSessionId) deepLinkRef.current = null;"),
            "honouring the route retires the retained deep-link target, so a later row for the same id " +
                "cannot re-select a session the operator has left",
        )
    }

    /**
     * The mirror half, and the one with more victims: a selection that did not move the URL was invisible
     * while the board owned the screen, because `onBoard` is computed from the route alone. The palette's
     * session rows, a push notification tapped into an open tab, and the task detail's own "Start
     * session" all ran to completion behind a kanban board.
     */
    @Test
    fun everySelectionMovesTheRouteAndThatIsWhatLeavesTheBoard() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        val showSession = sliceBetween(
            app,
            "const showSession = useCallback((session) => {",
            "\n  }, [cancelReattach]);",
            "the showSession handler",
        )
        assertTrue(
            showSession.contains("navigate(sessionPath(session.id));"),
            "the ONE selection funnel navigates, so every entry point into it (a sidebar tap, a palette " +
                "row, a notification, a freshly started or imported session) leaves the board",
        )
        // The screen decision is still made in exactly one place, from the route — which is precisely why
        // the selection has to reach the route rather than only `activeId`.
        assertTrue(
            app.contains("const onBoard = route.screen === SCREEN_TASKS || route.screen === SCREEN_TASK;"),
            "which screen is on is still derived from the route alone",
        )
        assertEquals(
            1,
            app.split("const onBoard =").size - 1,
            "there is one owner of that question",
        )
    }

    /**
     * The board is otherwise a one-way door in the surface this app is built for. An installed PWA draws
     * no browser chrome, and both shell controls that could navigate — the drawer opener and the palette
     * opener — live in `TerminalPane.js`, which the board's branch unmounts.
     */
    @Test
    fun theBoardCarriesOneControlBackToTheSessionView() = withStaticWebUi { ctx ->
        val board = ctx.text("/components/Board.js")
        assertTrue(
            board.contains("""const SESSIONS_PATH = routePath({ screen: SCREEN_SESSIONS, id: null });"""),
            "the target is the router's own spelling of the session view, not a hand-written \"/\"",
        )
        assertTrue(
            board.contains("""<a id="go-to-sessions" class="button button-quiet" href=${'$'}{SESSIONS_PATH}"""),
            "and it is a real link, so a modified click still opens a tab",
        )
        assertTrue(
            board.contains("if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;") &&
                board.contains("navigate(SESSIONS_PATH);"),
            "the plain click alone is handed to the router",
        )
        val css = ctx.text("/style.css")
        assertTrue(
            cssRuleOf(css, "#go-to-sessions").contains("text-decoration: none"),
            "an anchor wearing `.button` still has to lose the UA's underline — `.button` never had to " +
                "say so for a <button>",
        )
    }

    /**
     * `app.js` renders `Board` and `TaskDetail` as siblings at `/tasks/{ref}` — deliberately, because the
     * card keeps its `aria-current` highlight while its detail is read. `#app` is a flex row and both
     * roots claimed `flex: 1 1 auto`, so the viewport split in half: four crushed columns on a desktop
     * and two unusable ~195px screens on a phone.
     */
    @Test
    fun theBoardAndItsDetailShareTheScreenOnADesktopAndNotOnAPhone() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        assertTrue(
            app.contains("<\${Board}") && app.contains("<\${TaskDetail} taskRef=\${route.id}"),
            "both screens really are mounted together at /tasks/{ref}",
        )
        val css = ctx.text("/style.css")
        // The base rules are untouched: they are what the phone and the bare board still use.
        for (selector in listOf(".board", ".task-detail")) {
            assertTrue(
                cssRuleOf(css, selector).contains("flex: 1 1 auto"),
                "`$selector` alone on the screen still takes it",
            )
        }
        val desktopAt = css.indexOf("@media (min-width: 721px)")
        val mobileAt = css.indexOf("@media (max-width: 720px)")
        assertTrue(desktopAt in 1 until mobileAt, "the desktop rule is declared before the phone one")
        val desktop = css.substring(desktopAt, mobileAt)
        assertTrue(
            Regex("""(?s)#app:has\(\.task-detail\)\s+\.task-detail\s*\{[^}]*flex: 0 1 clamp\(""")
                .containsMatchIn(desktop),
            "on a desktop the detail is a BOUNDED right-hand panel, so the board keeps the width its " +
                "four tracks need",
        )
        assertTrue(
            Regex("""(?s)#app:has\(\.task-detail\)\s+\.board\s*\{[^}]*display: none""")
                .containsMatchIn(css.substring(mobileAt)),
            "on a phone there is no room for a master AND a detail: the detail replaces the board",
        )
        assertFalse(
            Regex("""(?s)#app:has\(\.task-detail\)\s+\.board\s*\{[^}]*display: none""")
                .containsMatchIn(css.substring(0, mobileAt)),
            "and that replacement is phone-only — hiding the board on a desktop would throw the card " +
                "highlight and the project selector away with it",
        )
    }

    /**
     * Task 24 was told to "surface rejected mutations through the existing announcement channel rather
     * than failing silently", and both screens do — into `status`, whose only renderer was `#status-line`
     * in the sidebar footer, i.e. inside the very branch that unmounts when the board mounts.
     */
    @Test
    fun theBoardsAnnouncementsHaveARendererOfTheirOwn() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        val board = ctx.text("/components/Board.js")
        val detail = ctx.text("/components/TaskDetail.js")
        val sidebar = ctx.text("/components/Sidebar.js")
        // The other end of the coupling: these two really do report only through the channel.
        assertTrue(board.contains("if (onAnnounce) onAnnounce(text, error);"), "the board announces")
        assertTrue(detail.contains("onAnnounce(\"A task needs a title.\", true);"), "so does the detail")
        assertTrue(
            sidebar.contains("<p id=\"status-line\""),
            "the session view's renderer is still the sidebar's, and it goes with the sidebar",
        )
        assertTrue(
            app.contains(
                "<p id=\"board-status\" class=\${\"status-line board-status\" + " +
                    "(status.error ? \" error\" : \"\")}",
            ),
            "the board branch renders the same live region under its own id, wearing the same class so " +
                "an error still reads as one",
        )
        assertTrue(
            app.contains("role=\"status\" aria-live=\"polite\">\${status.text}</p>"),
            "and it is a real live region, not a silent div",
        )
        val css = ctx.text("/style.css")
        assertTrue(css.contains(".board-status:empty { display: none; }"), "a quiet board draws nothing")
        assertTrue(
            cssRuleOf(css, ".board-status").contains("position: fixed"),
            "it floats over screens that have no chrome row to give it",
        )
    }

    /**
     * `newTaskRequest` is a one-shot counter in `App` compared against a ref `Board` recreates on every
     * MOUNT. That is right for a board that mounts once; the route unmounts it, so a counter that only
     * ever grew re-opened the create form on every later visit — a task badge tapped to READ a task
     * popped a New-task modal over its detail.
     */
    @Test
    fun theOneShotNewTaskRequestIsRetiredWithTheBoard() = withStaticWebUi { ctx ->
        val board = ctx.text("/components/Board.js")
        assertTrue(
            board.contains("const servedRequestRef = useRef(0);") &&
                board.contains("if (newTaskRequest === servedRequestRef.current) return;"),
            "the board still compares against a per-MOUNT ref that starts at 0",
        )
        val app = ctx.text("/app.js")
        // Both one-shot counters retire together: "New project" is the same mechanism for the board's
        // other form, and a counter left standing re-opens its modal on a later visit made to READ.
        val wiring = sliceBetween(
            app,
            "const [newTaskRequest, setNewTaskRequest] = useState(0);",
            "const openSessionTask",
            "the new-task request wiring",
        )
        assertTrue(
            wiring.contains("if (!onBoard) {") &&
                wiring.contains("setNewTaskRequest(0);") &&
                wiring.contains("setNewProjectRequest(0);"),
            "so the app puts its side back to 0 when the board goes away — both ends then read " +
                "\"never asked\", and the next visit opens nothing",
        )
    }

    /**
     * The scrim is rendered OUTSIDE the screen branch while the drawer itself is inside it, so leaving
     * the session view with the drawer open (`Sidebar`'s task badge navigates and closes nothing) left a
     * phone looking at a full-screen 58%-opacity overlay with no drawer in front of it.
     */
    @Test
    fun leavingTheSessionViewCannotStrandItsDrawerScrim() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        val sidebar = ctx.text("/components/Sidebar.js")
        assertTrue(
            sidebar.contains("navigate(taskPath(task.ref));"),
            "the badge that triggers it still navigates without touching the drawer",
        )
        assertTrue(
            app.contains("class=\"drawer-scrim\""),
            "the scrim is still rendered by the shell, outside the branch that owns the drawer",
        )
        assertTrue(
            app.contains(
                "useEffect(() => {\n    if (onBoard) setDrawerOpen(false);\n  }, [onBoard]);",
            ),
            "so the shell closes the drawer whenever the board takes the screen — every navigation off " +
                "the session view, not just the one the badge makes",
        )
    }

    /**
     * CLAUDE.md: "`app.js` POSTs `/sessions/{id}/read` for the session it DISPLAYS". Before the router
     * "visible" and "displayed" were the same statement; now the board can own the screen for as long as
     * the operator grooms a backlog while `activeId` still points at a session nobody can see.
     */
    @Test
    fun theReadCursorAdvancesOnlyWhileTheSessionViewIsOnScreen() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        val guard = sliceBetween(
            app,
            "function markReadIfViewing(id, unread, lastSeq) {",
            "\n  postRead(id, lastSeq);",
            "the mark-read guard",
        )
        assertTrue(
            guard.contains("""if (document.visibilityState !== "visible") return;"""),
            "the tab must be in front of the operator",
        )
        assertTrue(
            guard.contains("if (!sessionViewOnScreen) return;"),
            "and the session view must be the screen that tab is showing",
        )
        assertTrue(
            app.contains("sessionViewOnScreen = !onBoard;"),
            "which the render body answers, from the same route the branch below reads",
        )
        // The gate would otherwise strand a badge: nothing else fires on the way back. The three
        // documented triggers are a selection, an /events frame and `visibilitychange`, and a return from
        // the board is none of them — the tab never stopped being visible.
        assertTrue(
            sliceBetween(
                app,
                "useEffect(() => {\n    if (onBoard) return;",
                "}, [onBoard]);",
                "the return-to-session-view mark-read trigger",
            ).contains("markReadIfViewing(s.id, s.unread, s.lastSeq)"),
            "coming back to the session view is the fourth trigger",
        )
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** One bounded slice of a served source, which FAILS when either delimiter is missing. */
    private fun sliceBetween(source: String, start: String, end: String, what: String): String {
        val from = source.indexOf(start)
        assertTrue(from >= 0, "extraction of $what failed: no `$start` in the served source")
        val to = source.indexOf(end, startIndex = from + start.length)
        assertTrue(to > from, "extraction of $what failed: no `$end` after its start delimiter")
        return source.substring(from, to)
    }

    /** One top-level `selector { … }` rule body, so a declaration check cannot read a neighbouring rule. */
    private fun cssRuleOf(css: String, selector: String): String {
        val start = css.indexOf("\n$selector {")
        assertTrue(start >= 0, "the stylesheet declares `$selector`")
        val end = css.indexOf("}", start)
        assertTrue(end > start, "`$selector`'s rule closes")
        return css.substring(start, end)
    }

    // --- harness -------------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun text(path: String): String {
            val resp: HttpResponse = client.get("http://127.0.0.1:$port$path")
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path is served")
            return resp.bodyAsText()
        }
    }

    /** A server whose only route is the real static handler over the real `resources/webui` tree. */
    private fun withStaticWebUi(block: suspend (Ctx) -> Unit) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing { staticWebUi(screenRoutingWebUiDir()) }
        }
        try {
            withTimeout(40_000) {
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
}

/** Locate `resources/webui` by walking up from the cwd, so the runner's start directory does not matter. */
private fun screenRoutingWebUiDir(): String {
    var dir = screenRoutingCwd()
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (screenRoutingExists("$candidate/index.html")) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}

@OptIn(ExperimentalForeignApi::class)
private fun screenRoutingCwd(): String = memScoped {
    val size = 4096
    val buffer = allocArray<ByteVar>(size)
    getcwd(buffer, size.convert())
    buffer.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun screenRoutingExists(path: String): Boolean = access(path, F_OK) == 0
