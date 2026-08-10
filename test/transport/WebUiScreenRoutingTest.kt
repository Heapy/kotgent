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
     * Navigation is the sidebar's two links, and the sidebar is on every screen.
     *
     * The board used to be a one-way door in the surface this app is built for — an installed PWA draws
     * no browser chrome, and both shell controls that could navigate lived in `TerminalPane.js`, which
     * the board's branch unmounted — so it carried a "Sessions" link of its own. The sidebar is shell
     * furniture now: the link pair sits in its head, points BOTH ways, and no screen owns an exit.
     */
    @Test
    fun theSidebarCarriesTheAppsNavigationOnBothScreens() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        val sidebar = ctx.text("/components/Sidebar.js")
        val board = ctx.text("/components/Board.js")
        assertTrue(
            app.contains("screen=\${onBoard ? SCREEN_TASKS : SCREEN_SESSIONS}"),
            "the shell renders ONE sidebar and tells it which screen is on",
        )
        assertEquals(
            1,
            app.split("<\${Sidebar}").size - 1,
            "there is exactly one Sidebar in the tree — a per-screen copy would fork its state",
        )
        assertTrue(
            sidebar.contains("""const TASKS_PATH = routePath({ screen: SCREEN_TASKS, id: null });""") &&
                sidebar.contains("""routePath({ screen: SCREEN_SESSIONS, id: activeId || null })"""),
            "both targets are the router's own spelling, and the session one names the selection so the " +
                "address bar describes the terminal that is actually on screen",
        )
        assertTrue(
            sidebar.contains("""<nav class="nav-switch" aria-label="Screen">""") &&
                sidebar.contains("""href=${'$'}{link.path}""") &&
                sidebar.contains("""aria-current=${'$'}{screen === link.screen ? "page" : null}"""),
            "they are real links carrying the current screen, not two buttons",
        )
        assertTrue(
            sidebar.contains("if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;") &&
                sidebar.contains("navigate(path);"),
            "the plain click alone is handed to the router, so ⌘-click still opens a tab",
        )
        assertFalse(
            board.contains("go-to-sessions"),
            "and the board no longer carries an exit of its own — that link WAS the workaround",
        )
        val css = ctx.text("/style.css")
        assertTrue(
            cssRuleOf(css, ".nav-link").contains("text-decoration: none"),
            "an anchor styled as a control still has to lose the UA's underline — a <button> never had to",
        )
    }

    /**
     * The sidebar's head is fixed and its BODY is the screen's — the session list on one, the project
     * list on the other.
     *
     * Branched, not filtered: every session-only control reads the session list or the selection, and on
     * the board that selection is a leftover the operator cannot see. It is the same reason
     * `lib/commands.js` builds the whole `session` command group away there rather than disabling it.
     * The `status` renderer is branched for the opposite reason — the board keeps its own toast, because
     * on a phone this footer sits inside a CLOSED drawer, and rendering both would double every message.
     */
    @Test
    fun theSidebarSwapsItsBodyForTheScreenItIsOn() = withStaticWebUi { ctx ->
        val sidebar = ctx.text("/components/Sidebar.js")
        assertTrue(
            sidebar.contains("const onTasks = screen === SCREEN_TASKS;"),
            "one answer to which body to draw",
        )
        for (sessionOnly in listOf(
            "\${!onTasks && attention.length > 0 && html`",
            "\${!onTasks && doneSessions.length > 0 && html`",
            "\${!onTasks && html`\n      <section id=\"all-section\">",
        )) {
            assertTrue(
                sidebar.contains(sessionOnly),
                "the session view's own section `$sessionOnly` is built away on the board",
            )
        }
        assertTrue(
            sidebar.contains("\${onTasks && html`\n        <section id=\"projects-section\">") &&
                sidebar.contains("<ul id=\"project-list\" class=\"project-list\">") &&
                sidebar.contains("<\${ProjectRow}"),
            "and the project list is what takes its place",
        )
        assertTrue(
            sidebar.contains("\${!onTasks && html`\n          <p id=\"status-line\""),
            "the footer's live region is the SESSION view's renderer — the board keeps its own toast, " +
                "so neither screen announces twice",
        )
        // The count is open tasks, from the live list, so a project row that is stale (the list is
        // re-read on entry to the board, never polled) still carries a fresh number.
        assertTrue(
            sidebar.contains("if (!task || !task.project || task.state === \"done\") continue;"),
            "the per-project count is OPEN tasks, walked once for the whole list",
        )
        val app = ctx.text("/app.js")
        assertTrue(
            app.contains("if (onBoard) reloadProjects();"),
            "the one fetched list is re-read on arrival at the board, which is where it is shown",
        )
        assertTrue(
            app.contains("const appliedTaskProjectRef = useRef(null);") &&
                app.contains("setProjectId(entry.project);"),
            "opening /tasks/{ref} selects that task's project, once per ref, in the shell that owns it",
        )
        val css = ctx.text("/style.css")
        // The project row must be the session row's shape: the two lists are the same furniture, and a
        // sidebar whose rows changed size between screens would read as two different panels.
        val project = cssRuleOf(css, ".project-row")
        val session = cssRuleOf(css, ".session-row")
        for (declaration in listOf(
            "padding: 9px 10px",
            "border-radius: 12px",
            "margin: 2px var(--list-inset)",
        )) {
            assertTrue(
                project.contains(declaration) && session.contains(declaration),
                "`.project-row` and `.session-row` agree on `$declaration`",
            )
        }
        assertTrue(
            cssRuleOf(css, ".project-row.active").contains("var(--pill-active)") &&
                cssRuleOf(css, ".session-row.active").contains("var(--pill-active)"),
            "and on what a selected row looks like",
        )
        for (selector in listOf(".nav-switch", ".nav-link.active", ".project-main", ".project-name",
            ".project-sub", ".project-count", ".project-list")) {
            cssRuleOf(css, selector)   // asserts the rule exists; an unstyled row renders as a bare <li>
        }
    }

    /**
     * `app.js` renders `Board` and `TaskDetail` as siblings at `/tasks/{ref}` — deliberately, because the
     * card keeps its `aria-current` highlight while its detail is read. `#app` is a flex row and both
     * roots claimed `flex: 1 1 auto`, so the viewport split in half: four crushed columns on a desktop
     * and two unusable ~195px screens on a phone.
     *
     * Bounding the detail's flex basis fixed only the half of that a number could fix — the four tracks
     * still divided what was left, ~150px each on a 1440px window. A detail that shares the ROW always
     * bills the board, so on a desktop it now leaves the flow and floats over it at the same bounded
     * width; the board's tracks are then exactly as wide as they are with no task open, and the cost
     * moves to occlusion (the panel covers `done`) which closing the panel undoes.
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
        val desktopDetail = Regex("""(?s)#app:has\(\.task-detail\)\s+\.task-detail\s*\{([^}]*)}""")
            .find(desktop)?.groupValues?.get(1).orEmpty()
        assertTrue(
            desktopDetail.contains("position: absolute") && desktopDetail.contains("width: clamp("),
            "on a desktop the detail leaves the flow and floats over the board at a bounded width, so " +
                "the board keeps every track at the width it has with no task open",
        )
        assertTrue(
            cssRuleOf(css, "#app").contains("position: relative"),
            "and it floats against the SHELL, whose safe-area padding it therefore respects — without " +
                "this containing block the panel would position against the viewport and reach under a notch",
        )
        // A repaint fix, not a layout one, and the reason it is easy to delete by eye: nothing about the
        // panel's geometry depends on it. Measured on the installed app once the sidebar (which carries
        // `backdrop-filter` above this breakpoint) joined this screen — a band of the board's previous
        // frame stayed painted over the panel at its sticky head's layer seam.
        assertTrue(
            desktopDetail.contains("will-change: transform"),
            "the floating panel keeps its own compositing layer, or a stale tile of the board survives " +
                "on the seam between the sticky head and the scrolled content beneath it",
        )
        assertTrue(
            Regex("""(?s)@media \(min-width: 721px\)[^@]*#sidebar\s*\{[^}]*backdrop-filter""")
                .containsMatchIn(css),
            "and the blur that provoked it is still desktop-only — if the band ever returns, THAT is the " +
                "next thing to take away, which is only checkable while this pairing is written down",
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
     * The scrim is rendered OUTSIDE the screen branch. While the drawer itself was INSIDE it, leaving the
     * session view with the drawer open (`Sidebar`'s task badge navigates and closes nothing) left a
     * phone looking at a full-screen 58%-opacity overlay with no drawer in front of it, and the shell
     * needed an effect that closed the drawer on the way to the board.
     *
     * Both are now on the same side of the branch: the sidebar is rendered by the shell too, so the pair
     * cannot come apart and the effect is GONE rather than kept as a belt. That is what this asserts —
     * a re-introduced effect would silently take the project list away from every phone that opened the
     * drawer, tapped Tasks and expected to pick a project.
     */
    @Test
    fun theDrawerScrimCannotBeStrandedBecauseTheSidebarNeverUnmounts() = withStaticWebUi { ctx ->
        val app = ctx.text("/app.js")
        val sidebar = ctx.text("/components/Sidebar.js")
        assertTrue(
            sidebar.contains("navigate(taskPath(task.ref));"),
            "the badge that used to trigger it still navigates without touching the drawer",
        )
        assertTrue(
            app.contains("class=\"drawer-scrim\""),
            "the scrim is still rendered by the shell",
        )
        val branch = app.substringAfter("\${onBoard ? html`")
        assertFalse(
            branch.contains("<\${Sidebar}"),
            "and the sidebar is no longer inside the screen branch, which is what could strand it",
        )
        assertFalse(
            app.contains("if (onBoard) setDrawerOpen(false);"),
            "so the compensating effect is gone: closing the drawer on the way to the board would now " +
                "hide the project list the operator opened it for",
        )
        assertTrue(
            app.contains("const selectProject = useCallback((id) => {") &&
                app.substringAfter("const selectProject = useCallback((id) => {")
                    .substringBefore("}, []);")
                    .contains("setDrawerOpen(false);"),
            "picking a project closes the drawer, exactly as picking a session does — that is the rule " +
                "that replaced it",
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
