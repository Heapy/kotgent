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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * The kanban board's serving/source contract (task-backlog plan, Task 24).
 *
 * The browser cannot run in the macosArm64 test binary, so what is automatable is the same thing
 * `WebUiServingTest` locks down for the session view: the real modules are served, and the invariants
 * that have no other guard are present in the source the daemon actually hands out.
 *
 * Two of those invariants are cross-file and have no compiler behind them at all:
 *
 *  - **The CSS vocabulary.** Task 28 writes every board rule in `style.css` while this file writes the
 *    markup, and neither agent can read the other's file. The frozen class list in the plan is the whole
 *    contract, so [theTwoComponentsEmitOnlyTheSharedBoardVocabulary] asserts this end of it — every
 *    `board-*` / `task-*` class these two modules emit is on the list. Task 28's own test asserts the
 *    other end (each of them appears in the stylesheet). A typo has nowhere else to surface.
 *  - **`touch-action: none` belongs to the card HANDLE alone.** Reserving it on the whole card would
 *    take a phone's column scroll away, so [touchActionIsScopedToTheCardHandleAlone] pins the scope from
 *    the markup side, where the handlers are.
 */
class WebUiBoardTest {

    private val token = "webui-board-token-abc123"

    /** Every class name in the plan's "Board CSS vocabulary" — the frozen contract with Task 28. */
    private val boardVocabulary = setOf(
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

    @Test
    fun theBoardAndItsCardAreServedAsModules() = withServer { ctx ->
        for (path in listOf("/components/Board.js", "/components/TaskCard.js")) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path is served")
            val ct = resp.headers[HttpHeaders.ContentType].orEmpty()
            assertTrue(ct.contains("javascript", ignoreCase = true), "$path is served as JavaScript")
        }
        val board = ctx.get("/components/Board.js").bodyAsText()
        val card = ctx.get("/components/TaskCard.js").bodyAsText()
        assertTrue(board.contains("export function Board("), "app.js imports { Board } by name")
        assertTrue(card.contains("export function TaskCard("), "Board.js imports { TaskCard } by name")
        // The stubs are gone: a component that still answered `return null` would serve, pass a
        // content-type check, and render an empty screen.
        assertFalse(board.contains("// Task 24."), "the Board stub body is replaced")
        assertFalse(card.contains("// Task 24."), "the TaskCard stub body is replaced")
    }

    @Test
    fun theBoardRendersTheFourColumnsOverOneSelectedProject() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        assertTrue(board.contains("export const BOARD_COLUMNS"), "the four columns are one declared list")
        for (state in listOf("todo", "in_progress", "review", "done")) {
            assertTrue(
                board.contains("state: \"$state\""),
                "the board has a '$state' column (the io.kotgent.task.TaskState names)",
            )
        }
        assertTrue(board.contains("class=\"board-columns\""), "the four columns share one track")
        assertTrue(
            board.contains("\"board-column\" + (over ? \" board-drop-target\" : \"\")") &&
                board.contains("data-state=\${column.state}"),
            "each column carries its state and gains board-drop-target while a drag is over it",
        )
        // Exactly one project at a time: the entries are filtered by the selected id, and there is no
        // "all projects" option — `position` is a project-wide rank, so a combined view could not be
        // reordered by any move the API can express.
        assertTrue(
            board.contains(".filter((task) => task.project === projectId)"),
            "the board renders only the selected project's entries",
        )
        // The selection itself is no longer the board's. The `<select>` in its header became rows in the
        // sidebar, so the list, the pick and the healing of a pick naming a project that is gone all
        // live in `app.js` — the one ancestor of both. What is left here is a title.
        assertFalse(
            board.contains("<select class=\"board-project\"") || board.contains("setProjectId("),
            "the board consumes the selection and cannot write it — one owner, in the shell",
        )
        assertTrue(
            board.contains("const project = projects.find((row) => row.id === projectId) || null;") &&
                board.contains("<span class=\"board-project\">"),
            "and it names the selected project in its head, the way the terminal head names a session",
        )
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("projects.some((project) => project.id === current) ? current : projects[0].id"),
            "the selection heals in the shell when the selected project is no longer listed",
        )
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertFalse(
            sidebar.contains("All projects") || sidebar.contains("Every project"),
            "the sidebar lists projects and nothing else — there is still no all-projects option",
        )
    }

    /**
     * The board's head is the terminal head's twin, because they are the same slot of one shell.
     *
     * Both sidebar controls have to be here: without the collapse toggle the board would be the one
     * screen that cannot bring a ⌘1-collapsed sidebar back, and without ☰ a phone could not open the
     * drawer at all — the opener it used to rely on lives in `TerminalPane.js`, which this screen
     * unmounts.
     *
     * They reuse the terminal header's own ids rather than minting `board-` ones. That is legal because
     * the two heads are the two arms of one branch and can never be in the document together, and it is
     * what makes every existing rule — including the breakpoint's neutralization of the collapse toggle —
     * reach this row with nothing restated. A `board-`-prefixed id would also have been scanned as part
     * of the class vocabulary by [theTwoComponentsEmitOnlyTheSharedBoardVocabulary].
     */
    @Test
    fun theBoardHeadCarriesTheSameShellControlsTheTerminalHeadDoes() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        for (control in listOf(
            "id=\"drawer-toggle\"" to "the phone's drawer opener",
            "id=\"sidebar-toggle\"" to "the desktop collapse toggle",
            "id=\"palette-button\"" to "the palette opener",
        )) {
            assertTrue(
                board.contains(control.first) && pane.contains(control.first),
                "${control.second} is on BOTH heads, under the one id every rule is keyed on",
            )
        }
        assertTrue(
            board.contains("onClick=\${onToggleDrawer}") && board.contains("onClick=\${onToggleSidebar}") &&
                board.contains("onOpenPalette(\"leader\")"),
            "and each is wired to the shell handler its twin uses",
        )
        // The branch is what keeps those ids unique: exactly one of the two heads is ever rendered.
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("\${onBoard ? html`") && app.contains("<\${TerminalPane}"),
            "the two heads are the two arms of one branch, so the shared ids can never collide",
        )
        val css = ctx.get("/style.css").bodyAsText()
        val mobile = css.substringAfter("@media (max-width: 720px)").substringBefore("@media (any-pointer")
        assertTrue(
            mobile.contains("#sidebar-toggle { display: none; }") &&
                css.contains(".drawer-toggle,\n.drawer-close,\n.drawer-scrim { display: none; }"),
            "so one collapse rule and one shared ☰ rule govern both screens, with no second copy",
        )
    }

    @Test
    fun theDoneColumnIsCappedAtItsTailWithAShowAllToggle() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        assertTrue(board.contains("export const DONE_VISIBLE_LIMIT"), "the cap is one named constant")
        assertTrue(
            board.contains("column.entries.slice(column.entries.length - DONE_VISIBLE_LIMIT)"),
            "the cap keeps the LAST N of the ordered column — what closed recently, not what closed first",
        )
        assertTrue(
            board.contains("column.state === \"done\" && !showAllDone") &&
                board.contains("column.state === \"done\" && column.entries.length > DONE_VISIBLE_LIMIT"),
            "only the done column is capped, and the toggle appears only when something is hidden",
        )
        assertTrue(
            board.contains("board-show-all-done") && board.contains("setShowAllDone((shown) => !shown)"),
            "the toggle reveals the whole column",
        )
        assertTrue(
            board.contains("<span>\${column.entries.length}</span>"),
            "the column head counts every entry, not the capped slice",
        )
    }

    @Test
    fun creatingATaskCarriesTheSelectedProjectId() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        // The browser has no session and no pane, so the daemon cannot resolve a project for it: the
        // board is the one client that must name one explicitly.
        assertTrue(
            board.contains("createTask(projectId, title, body)"),
            "the create posts the SELECTED project id",
        )
        assertTrue(
            board.contains("class=\"button board-new-task\" disabled=\${!projectId}"),
            "the new-task action is unavailable while no project is selected",
        )
        // "New project" is asked for elsewhere now — the sidebar's `+ New` and the palette both bump the
        // one-shot counter — but the FORM stays here, beside the create-task one it shares a
        // directory-completion field with. A second copy in the sidebar would be a second implementation.
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(
            sidebar.contains("id=\"sidebar-new-project\"") &&
                sidebar.contains("onClick=\${() => onNewProject()}"),
            "the sidebar, which owns the project list, is what offers a new project",
        )
        assertTrue(
            board.contains("newProjectRequest") && board.contains("setForm(\"project\")"),
            "and the board still opens the form, on the counter that asks it to",
        )
        assertTrue(
            board.contains("apiRequest(\"/directories/complete\"") &&
                board.contains("createProject(path, name)"),
            "the new-project action completes a directory on the DAEMON and posts that path",
        )
        assertTrue(
            board.contains("if (onProjectCreated) await onProjectCreated(created);"),
            "the created row goes back to the app, which owns the list and the selection",
        )
        // The board still never READS the list over HTTP — the socket's baseline is where it comes from.
        // Merging a write's own answer is a different thing and is required; see
        // [everyWriteMergesTheCommittedRowItsAnswerCarries].
        assertFalse(board.contains("fetchTasks("), "the board never fetches the task list")
    }

    /**
     * Every task write answers with the committed `BacklogEntryDto` and its `rev`, and the board merges
     * it — through `app.js`'s own rev-aware upsert, handed down, so a response and the frame that follows
     * it take the SAME path into the one list and the older of the two simply loses.
     *
     * Discarding the answer was a hole, not a purity: while `/events` is down or reconnecting REST still
     * works, so a create, a move or a delete changed nothing on screen and reported no error either.
     *
     * The test is written as the whole chain on purpose — `app.js` passing the applier, the applier being
     * the rev-aware one, and each call site publishing. Verified falsifiable: none of these strings was
     * in the pre-fix source, and the board's five writes all discarded their answers.
     */
    @Test
    fun everyWriteMergesTheCommittedRowItsAnswerCarries() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("onTaskRow=\${applyTaskRow}") && app.contains("onTaskRemoved=\${applyTaskRemoved}"),
            "the board is handed the SAME appliers the events frames go through",
        )
        assertTrue(
            app.contains("setTasks((current) => upsertTaskIfNewer(current, row));") &&
                app.contains("setTasks((current) => removeTask(current, ref));"),
            "and those really are the newest-rev-wins upsert and the removal, not a second merge",
        )
        assertTrue(
            board.contains("if (row && row.ref && onTaskRow) onTaskRow(row);"),
            "one publisher on this side, which ignores an answer that is not a row",
        )
        for (write in listOf(
            "if (plan.state) publishRow(await patchTask(ref, { state: plan.state }));",
            "if (plan.move) publishRow(await moveTask(ref, plan.move));",
            "publishRow(await patchTask(entry.ref, { state: state }));",
            "publishRow(await moveTask(entry.ref, target));",
            "publishRow(created);",
        )) {
            assertTrue(board.contains(write), "this write merges its own answer: '$write'")
        }
        // A delete answers `ok` and no row at all, so the removal itself is what gets applied.
        assertTrue(
            board.contains("if (onTaskRemoved) onTaskRemoved(entry.ref);"),
            "a deleted card leaves the list on the response, not only on the frame",
        )
    }

    /**
     * New project starts where New session does: on the Preferences base path.
     *
     * The form used to send `basePath: null` and refuse anything that did not already begin with `/`, so
     * the one directory the operator had configured once had to be retyped in full for every project —
     * and the completion list could not help until it was, because the endpoint refuses a relative input
     * with no absolute base (`DirectoryCompletion.kt`).
     *
     * Completion and submit are asserted together on purpose: they must apply the SAME join. A form that
     * completed relative input against the base while its submit passed the raw value through would list
     * a real directory and then post a path `POST /projects` rejects as relative.
     *
     * The `app.js` half is read out of the `<${'$'}{Board}` element rather than out of the whole file: the
     * New-session dialog has passed `basePath=${'$'}{prefs.basePath}` since long before this, so a bare
     * `contains` over the source passed on the parent commit and proved nothing at all.
     */
    @Test
    fun theNewProjectFormStartsOnTheBasePathAndResolvesRelativeInputAgainstIt() = withServer { ctx ->
        val app = ctx.get("/app.js").bodyAsText()
        val board = ctx.get("/components/Board.js").bodyAsText()
        val boardElement = sliceBetween(app, "<\${Board}", "/>", "the board element")
        assertTrue(
            boardElement.contains("basePath=\${prefs.basePath}"),
            "the BOARD is handed the same preference the New-session dialog gets",
        )
        assertTrue(
            board.contains("<\${NewProjectForm} basePath=\${basePath}"),
            "and passes it down to the form that names a directory",
        )
        assertTrue(
            board.contains("useState(base.charAt(0) === \"/\" ? base : \"\")"),
            "the directory field opens ON the base path instead of empty",
        )
        // Snapshot, not a live read: preferences are shared, so another tab committing a new base under
        // an OPEN form would otherwise split it against itself — an old path in the field, a new one in
        // the hint, a third answer from completion. `useState`'s initializer form is the snapshot.
        assertTrue(
            board.contains("const [base] = useState(() => normalizePath(basePath));"),
            "the form freezes one base for its whole life, so all four uses name the same tree",
        )
        assertTrue(
            board.contains("body: JSON.stringify({ basePath: base || null, input: typed })"),
            "completion resolves a relative name against that base rather than sending null",
        )
        assertTrue(
            board.contains("const typed = resolveProjectPath(path, base);"),
            "and the submit applies the same one, so the list and the create agree on the path",
        )
    }

    /**
     * The project-name field must accept every name `POST /projects` does.
     *
     * An `<input maxlength>` shorter than the API's cap is not a stricter client-side rule — it silently
     * refuses the keystroke, so the 81st character of a perfectly legal 100-character name could not be
     * typed at all, with no message anywhere. The number is imported from the daemon rather than repeated
     * here, so this fails if either side moves.
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

    @Test
    fun aCardShowsEveryLinkedSessionWithItsStateDot() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val card = ctx.get("/components/TaskCard.js").bodyAsText()
        // No exclusivity: a task may be linked from any number of sessions, so the card shows them all.
        assertTrue(
            card.contains("sessions.map((session)") && card.contains("class=\"task-sessions\""),
            "the card maps over EVERY linked session rather than showing the first",
        )
        assertTrue(
            card.contains("class=\"task-session-dot\" data-state=\${session.state}"),
            "each session's dot carries the state stateBadge names",
        )
        assertTrue(card.contains("stateBadge(session.state)"), "the dot's label reuses the shared badge")
        // Derived from the session list the app already holds, and walked ONCE for the whole board.
        assertTrue(
            board.contains("if (!session.taskRef) continue;") &&
                board.contains("sessionsByTask.get(entry.ref) || []"),
            "the links come from session.taskRef, indexed once per render rather than per card",
        )
    }

    @Test
    fun aCardCarriesTheBlockedMarkerTheDependencyCountAndItsMenu() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val card = ctx.get("/components/TaskCard.js").bodyAsText()
        assertTrue(
            card.contains("entry.blocked && html`") && card.contains("class=\"task-blocked\""),
            "blocked is rendered from the server-derived flag, never recomputed here",
        )
        assertTrue(
            card.contains("(entry.dependsOn || []).length") && card.contains("class=\"task-dep-count\""),
            "the dependency count comes off the DTO the board already has",
        )
        assertTrue(
            card.contains("<details class=\"task-card-menu\">") && card.contains("Delete task"),
            "the per-card menu carries delete",
        )
        assertTrue(
            board.contains("window.confirm(\"Delete \"") && board.contains("deleteTask(entry.ref)"),
            "a delete is confirmed first — it takes the task's dependencies and feed with it",
        )
        assertTrue(
            card.contains("href=\${taskPath(entry.ref)}") || card.contains("const href = taskPath(entry.ref)"),
            "the title is a real link to /tasks/{ref}",
        )
    }

    @Test
    fun theDragIsPointerEventsWithAnEightPixelSlopAndCapture() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val card = ctx.get("/components/TaskCard.js").bodyAsText()
        assertTrue(board.contains("const DRAG_SLOP_PX = 8;"), "the slop is 8 px")
        assertTrue(
            board.contains("Math.abs(event.clientX - gesture.startX) < DRAG_SLOP_PX") &&
                board.contains("Math.abs(event.clientY - gesture.startY) < DRAG_SLOP_PX"),
            "a press under the slop is still a press",
        )
        assertTrue(
            board.contains("gesture.element.setPointerCapture(event.pointerId)"),
            "the gesture is captured once it is claimed, so it survives its own re-render",
        )
        assertTrue(
            board.contains("releasePointerCapture(pointerId)"),
            "the capture is released when the gesture ends",
        )
        assertTrue(
            board.contains("event.pointerId !== gesture.pointerId"),
            "every later event is matched against the captured pointer id",
        )
        for (handler in listOf("onPointerDown", "onPointerMove", "onPointerUp", "onPointerCancel")) {
            assertTrue(card.contains("$handler=\${"), "the handle wires $handler")
        }
        // A gesture the platform took away is not a drop: cancel must not mutate the backlog.
        val cancel = sliceBetween(
            board,
            "const dragPointerCancel = useCallback(",
            "}, [endGesture]);",
            "the pointercancel handler",
        )
        assertFalse(cancel.contains("applyDrop"), "a cancelled drag applies nothing")
        // The release position is the drop: a browser need not send a pointermove before pointerup.
        assertTrue(
            board.contains("dropTargetAt(event.clientX, event.clientY, gesture.ref)"),
            "the drop is resolved from the release coordinates",
        )
    }

    @Test
    fun touchActionIsScopedToTheCardHandleAlone() = withServer { ctx ->
        val card = ctx.get("/components/TaskCard.js").bodyAsText()
        val handle = sliceBetween(
            card,
            "class=\"task-card-handle\"",
            "onPointerDown=",
            "the drag handle's attributes",
        )
        assertTrue(
            handle.contains("style=\"touch-action: none\""),
            "the handle reserves the gesture the browser would otherwise claim",
        )
        // Exactly one inline style in the whole component, and it is that one — reserving the card (or
        // the column) would cost a phone the scroll of the one column it can see.
        assertEquals(
            1,
            card.split("style=").size - 1,
            "touch-action is the component's ONLY inline style, and it is on the handle",
        )
    }

    @Test
    fun aCrossColumnDropPatchesTheStateThenMoves() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val apply = sliceBetween(
            board,
            "const applyDrop = useCallback(",
            "}, [publishRow, say]);",
            "the drop handler",
        )
        val patchAt = apply.indexOf("patchTask(ref, { state: plan.state })")
        val moveAt = apply.indexOf("moveTask(ref, plan.move)")
        assertTrue(patchAt >= 0 && moveAt > patchAt, "the PATCH is issued before the move")
        // Each request carries exactly its half: `/move` takes no state and PATCH takes no position.
        assertFalse(
            board.contains("{ state: plan.state, ") || board.contains("state: target.state,"),
            "the PATCH carries the state alone",
        )
        // A drop that changes only the column is ONE request: the plan compares against the position a
        // bare PATCH would leave the card at, instead of assuming a cross-column drop must also move.
        assertTrue(
            board.contains("const at = others.findIndex((row) => row.position > entry.position);") &&
                board.contains("if (!stateChanged && !needsMove) return null;"),
            "a drop that changes nothing issues nothing, and a pure column change issues only the PATCH",
        )
        assertTrue(
            board.contains("move = { before: target.beforeRef }") &&
                board.contains("move = { after: others[others.length - 1].ref }") &&
                board.contains("move = { bottom: true }"),
            "a move names a NEIGHBOUR whenever there is one; only an empty column falls back to the end",
        )
        assertTrue(
            board.contains("document.elementFromPoint") && board.contains("closest(\".board-column\")"),
            "the drop target is read from the DOM under the pointer",
        )
    }

    @Test
    fun thePhoneBranchIsOneColumnASwitcherAndMenuMoves() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        val card = ctx.get("/components/TaskCard.js").bodyAsText()
        assertTrue(
            board.contains("const PHONE_QUERY = \"(max-width: 720px)\";"),
            "the breakpoint is the one style.css already uses",
        )
        assertTrue(
            board.contains("columns.filter((column) => column.state === activeColumn)"),
            "below the breakpoint exactly one column is rendered",
        )
        assertTrue(
            board.contains("class=\"board-column-switch\"") &&
                board.contains("setActiveColumn(column.state)"),
            "the switcher is how the other three columns are reachable at all",
        )
        // Dragging between columns cannot exist when only one is on screen, so the menu carries the
        // moves there — and only there.
        assertTrue(
            board.contains("BOARD_COLUMNS.filter((column) => column.state !== activeColumn)"),
            "the phone's move targets are the other three columns",
        )
        assertTrue(
            card.contains("moveTargets.length > 0 && html`") && card.contains("Move to \${target.label}"),
            "the card's move actions render only when it was given targets (the phone branch)",
        )
        assertTrue(
            card.contains("onMoveUp(entry)") && card.contains("onMoveDown(entry)"),
            "reordering within the column is reachable without a pointer drag",
        )
    }

    @Test
    fun theNewTaskRequestCounterOpensTheFormWhenItChanges() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        // The palette navigates AND bumps in one event, so the board usually mounts with the counter
        // already at 1: starting the comparison from the mounted value would swallow that request.
        assertTrue(
            board.contains("const servedRequestRef = useRef(0);"),
            "the counter is compared against 0, not against whatever it held at mount",
        )
        assertTrue(
            board.contains("if (newTaskRequest === servedRequestRef.current) return;") &&
                board.contains("servedRequestRef.current = newTaskRequest;"),
            "the form opens when the counter CHANGES, so the same command can fire twice",
        )
        assertTrue(
            board.contains("}, [newTaskRequest]);"),
            "the effect is keyed on the counter alone",
        )
    }

    @Test
    fun rejectedMutationsGoThroughTheAnnouncementChannel() = withServer { ctx ->
        val board = ctx.get("/components/Board.js").bodyAsText()
        assertTrue(board.contains("if (onAnnounce) onAnnounce(text, error);"), "announcements go to the app")
        for (message in listOf(
            "Could not move ",
            "Could not delete ",
        )) {
            assertTrue(
                board.contains("say(\"$message\""),
                "a failed mutation says so: '$message'",
            )
        }
        // The project READ moved out with the project list: it is the shell's now, and so is its failure.
        // Same channel either way — `say` here is the app's own `status` writer, handed down.
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(
            app.contains("say(\"Could not load projects: \" + errorMessage(e), true);"),
            "and the one read that left this file still announces its failure where it now lives",
        )
        assertTrue(
            board.contains("errorMessage(e), true)"),
            "the daemon's own text is surfaced, flagged as an error",
        )
        // The two forms hold a draft and a request, so they keep the light-dismiss gestures off while
        // busy exactly like every other dialog that can lose typed input.
        assertEquals(
            2,
            board.split("lightDismiss=\${!busy}").size - 1,
            "both board forms opt out of a light dismiss while they are working",
        )
    }

    @Test
    fun theTwoComponentsEmitOnlyTheSharedBoardVocabulary() = withServer { ctx ->
        val sources = mapOf(
            "Board.js" to ctx.get("/components/Board.js").bodyAsText(),
            "TaskCard.js" to ctx.get("/components/TaskCard.js").bodyAsText(),
        )
        for ((name, source) in sources) {
            for (token in vocabularyTokensIn(source)) {
                assertTrue(
                    boardVocabulary.contains(token),
                    "$name emits '$token', which is not in the plan's Board CSS vocabulary — " +
                        "Task 28 writes style.css from that list and would never style it",
                )
            }
        }
        // The other end of the same contract: the classes these two files OWN are all really emitted.
        val combined = sources.values.joinToString("\n")
        for (owned in listOf(
            "board", "board-head", "board-identity", "board-project", "board-project-path",
            "board-new-task",
            "board-columns", "board-column", "board-column-head", "board-column-switch",
            "board-show-all-done", "board-drop-target",
            "task-card", "task-card-handle", "task-card-title", "task-card-meta",
            "task-blocked", "task-dep-count", "task-sessions", "task-session-dot", "task-card-menu",
        )) {
            assertTrue(
                combined.contains("\"$owned\"") || combined.contains("\"$owned ") ||
                    combined.contains(" $owned\"") || combined.contains(" $owned "),
                "the board emits the '$owned' class Task 28 is styling",
            )
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

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

    /** One bounded slice of a served source, which FAILS when either delimiter is missing. */
    private fun sliceBetween(source: String, start: String, end: String, what: String): String {
        val from = source.indexOf(start)
        assertTrue(from >= 0, "extraction of $what failed: no `$start` in the served source")
        val to = source.indexOf(end, startIndex = from + start.length)
        assertTrue(to > from, "extraction of $what failed: no `$end` after its start delimiter")
        return source.substring(from, to)
    }

    // --- harness -------------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String): HttpResponse = client.get("http://127.0.0.1:$port$path")
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
                terminalBridgeFactory = { _, _ -> error("no terminal bridge in a board serving test") },
                currentVersion = "9.8.7+deadbee",
                webUiDir = locateBoardWebUiDir(),
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

    /** A no-op [EventStore]: nothing on the static-serving path touches it. */
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
private fun boardTestCurrentDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())
    buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun boardTestFileExists(path: String): Boolean = access(path, F_OK) == 0

/** Locate `resources/webui` by walking up from the cwd, so the runner's start directory does not matter. */
private fun locateBoardWebUiDir(): String {
    var dir = boardTestCurrentDir()
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (boardTestFileExists("$candidate/index.html")) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}
