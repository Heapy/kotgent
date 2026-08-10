package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
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
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertTrue

/**
 * The session→task badge (plan Task 26), asserted from the served sources.
 *
 * There is no browser in the macosArm64 test binary, so what is checkable is the same thing every other
 * Web UI test here checks: the daemon really serves these three modules, and their text carries the
 * contracts the feature rests on. Three of those contracts are cross-file and nothing else could catch
 * them breaking:
 *
 *  1. **One builder.** `taskBadge` lives in `lib/sessions.js` and BOTH components call it. Two local
 *     spellings of "what does this session's task ref say" would drift the moment one of them learns to
 *     resolve a title and the other does not.
 *  2. **The `tasks` prop reaches every row.** The sidebar renders `SessionRow` from four places
 *     (attention, flat, grouped, done); a call site that forgets the prop renders a badge that can only
 *     take the unknown arm — a bare `local:42` where every other row shows a title, with nothing failing.
 *  3. **`patchIfNewer` carries `taskRef`.** The daemon moves the badge by emitting a `session_update`;
 *     a patch applicator that drops the field freezes every badge until a reload.
 *
 * The class names come from the plan's frozen "Board CSS vocabulary" — Task 28 writes `style.css` in a
 * worktree that cannot read these files, so this is one end of a contract with no compiler between them.
 */
class WebUiTaskBadgeTest {

    private val token = "webui-task-badge-token-abc123"

    // --- lib/sessions.js -----------------------------------------------------------------------------

    @Test
    fun theBadgeTextBuilderIsExportedFromTheSessionHelpers() = withServer { ctx ->
        val helpers = ctx.get("/lib/sessions.js").bodyAsText()
        assertTrue(
            helpers.contains("export function taskBadge(session, tasks)"),
            "lib/sessions.js exports the one badge-text builder both components render",
        )
    }

    @Test
    fun anUnknownRefStillRendersAsTheBareRef() = withServer { ctx ->
        val builder = sliceBetween(
            ctx.get("/lib/sessions.js").bodyAsText(),
            "export function taskBadge(session, tasks) {",
            "\n}",
            "the taskBadge body",
        )
        // The ONLY thing that renders nothing is a session linked to no task at all. A ref whose task is
        // missing from `tasks` is a real anomaly (`sessions.task_ref` is a reference, not a foreign key,
        // so a delete can leave one) and showing the bare ref is how the UI names it.
        assertEquals(
            1,
            Regex("return null").findAll(builder).count(),
            "taskBadge returns nothing for exactly one reason — no ref — and never for an unresolved one",
        )
        assertTrue(
            builder.contains("if (!ref) return null;"),
            "that one early return is the ref-less session",
        )
        assertTrue(
            builder.contains("label: title || ref"),
            "an unresolved ref falls back to the bare ref as the label",
        )
        assertTrue(
            builder.contains("known: !!entry"),
            "`known` reports whether the backlog carried the ref — it is what picks the unknown class",
        )
    }

    @Test
    fun aSessionUpdatePatchCarriesTheTaskLinkOntoTheRow() = withServer { ctx ->
        val patch = sliceBetween(
            ctx.get("/lib/sessions.js").bodyAsText(),
            "export function patchIfNewer(list, msg) {",
            "\n}",
            "the patchIfNewer body",
        )
        assertTrue(
            patch.contains("taskRef: msg.taskRef"),
            "a session_update moves the task badge: the patch applies taskRef verbatim (null unlinks)",
        )
        assertTrue(
            patch.contains("projectId: msg.projectId"),
            "the patch applies the session's project the same way",
        )
    }

    // --- components ----------------------------------------------------------------------------------

    @Test
    fun bothComponentsRenderTheBadgeThroughTheSharedBuilder() = withServer { ctx ->
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        listOf("Sidebar.js" to sidebar, "TerminalPane.js" to pane).forEach { (name, source) ->
            assertTrue(
                source.contains("taskBadge } from \"../lib/sessions.js\";"),
                "$name imports the shared builder rather than spelling the badge text itself",
            )
            assertTrue(
                source.contains("taskBadge(session, tasks)"),
                "$name builds its badge from the session and the tasks prop",
            )
            assertTrue(
                source.contains("if (!task) return null;"),
                "$name renders nothing for a session that is linked to no task",
            )
        }
    }

    @Test
    fun bothComponentsSpellTheFrozenBadgeClasses() = withServer { ctx ->
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        listOf("Sidebar.js" to sidebar, "TerminalPane.js" to pane).forEach { (name, source) ->
            assertTrue(
                source.contains("\"task-badge\" + (task.known ? \"\" : \" task-badge-unknown\")"),
                "$name marks an unresolved ref with task-badge-unknown beside the base task-badge class",
            )
            assertTrue(
                source.contains("class=\"task-session-dot\" data-state=\${session.state}"),
                "$name carries the session's state on the badge's state dot",
            )
            // The vocabulary is frozen because Task 28 writes the stylesheet in a worktree that cannot
            // read this file. A class it never heard of is a rule that never gets written.
            val invented = Regex("\"(task|board)-[a-z-]+\"").findAll(source)
                .map { it.groupValues[0].trim('"') }
                .filterNot { it in setOf("task-badge", "task-badge-unknown", "task-session-dot") }
                .toList()
            assertTrue(invented.isEmpty(), "$name invents board vocabulary Task 28 will never style: $invented")
        }
    }

    @Test
    fun theBadgeLinksToTheTaskScreenAndYieldsAModifiedClick() = withServer { ctx ->
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        listOf("Sidebar.js" to sidebar, "TerminalPane.js" to pane).forEach { (name, source) ->
            // Matched as an import LIST rather than one literal line: the sidebar also imports the screen
            // constants and `routePath` for its two navigation links, so the statement wraps there. What
            // must hold is that both names come from the router, not that they arrive on one line.
            val routerImport = Regex("""import \{([\s\S]*?)\} from "\.\./lib/router\.js";""")
                .find(source)?.groupValues?.get(1).orEmpty()
            assertTrue(
                routerImport.contains("navigate") && routerImport.contains("taskPath"),
                "$name reaches /tasks/{ref} through the router rather than building the path by hand",
            )
            assertTrue(
                source.contains("href=\${taskPath(task.ref)}"),
                "$name renders a real href, so copy-link and middle-click behave",
            )
            assertTrue(
                source.contains("navigate(taskPath(task.ref));"),
                "$name hands a plain left click to the router instead of reloading the shell",
            )
            assertTrue(
                source.contains(
                    "if (event.button !== 0 || event.metaKey || event.ctrlKey || " +
                        "event.shiftKey || event.altKey) return;",
                ),
                "$name leaves a modified or non-primary click to the browser (open in a new tab)",
            )
            assertTrue(
                source.contains("event.stopPropagation();"),
                "$name stops the click before an enclosing row can also act on it",
            )
        }
    }

    @Test
    fun theSidebarThreadsTheTasksPropToEveryRow() = withServer { ctx ->
        val sidebar = ctx.get("/components/Sidebar.js").bodyAsText()
        assertTrue(
            sidebar.contains("export function Sidebar({\n  screen = SCREEN_SESSIONS,\n  sessions, tasks,"),
            "the sidebar takes the tasks prop app.js already passes it, beside the screen it draws for",
        )
        assertTrue(
            sidebar.contains("function SessionRow({ session, tasks,"),
            "a row takes the task list it resolves its ref against",
        )
        assertTrue(
            sidebar.contains("function SessionGroup({\n  group, tasks,"),
            "a directory group passes the list down to its own rows and subgroups",
        )
        // Four row sites (attention, grouped, flat, done) and two group sites (the list, and the group's
        // own recursion): EVERY one must carry the prop, or the rows below it silently degrade to the
        // bare-ref arm — a badge that renders, looks deliberate, and is missing its title.
        val rowSites = Regex("<\\\$\\{SessionRow\\}([\\s\\S]*?)/>").findAll(sidebar).toList()
        val groupSites = Regex("<\\\$\\{SessionGroup\\}([\\s\\S]*?)/>").findAll(sidebar).toList()
        assertEquals(4, rowSites.size, "the sidebar renders SessionRow from the four places it always has")
        assertEquals(2, groupSites.size, "SessionGroup is rendered from the list and from itself")
        (rowSites + groupSites).forEachIndexed { index, site ->
            assertTrue(
                site.groupValues[1].contains("tasks=\${tasks}"),
                "render site #$index passes the tasks prop down: ${site.value}",
            )
        }
    }

    @Test
    fun theTerminalHeaderCarriesTheBadgeBesideTheStateBadge() = withServer { ctx ->
        val pane = ctx.get("/components/TerminalPane.js").bodyAsText()
        assertTrue(
            pane.contains("  session, tasks, attachedId,"),
            "the pane takes the tasks prop app.js already passes it",
        )
        val head = sliceBetween(pane, "<div class=\"terminal-identity\">", "</div>", "the terminal identity block")
        assertTrue(
            head.contains("<\${HeaderTaskBadge} session=\${session} tasks=\${tasks} />"),
            "the badge is rendered inside the header identity, next to the title and the state",
        )
        // Unconditionally — the component decides. Nothing around this header may be wrapped in a
        // session guard (`thePaletteReplacesRedundantDesktopChromeWithoutRemovingEntryPoints` in
        // WebUiServingTest is the rule), so the null case has to live inside the component instead.
        assertTrue(
            pane.contains("const task = session ? taskBadge(session, tasks) : null;"),
            "the header badge answers null for a session-less header itself, rather than being guarded",
        )
        assertTrue(
            pane.indexOf("function HeaderTaskBadge(") > pane.indexOf("export function TerminalPane("),
            "the badge is declared below the pane, so the pane's markup stays the module's first",
        )
    }

    // --- harness -------------------------------------------------------------------------------------

    /**
     * One bounded `[start, end)` slice, which FAILS when either delimiter is missing — `substringAfter`
     * would silently widen to the whole file and turn every assertion below into a file-wide search.
     */
    private fun sliceBetween(source: String, start: String, end: String, what: String): String {
        val from = source.indexOf(start)
        assertTrue(from >= 0, "extraction of $what failed: no `$start` in the served source")
        val to = source.indexOf(end, startIndex = from + start.length)
        assertTrue(to > from, "extraction of $what failed: no `$end` after its start delimiter")
        return source.substring(from, to)
    }

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String) = client.get("http://127.0.0.1:$port$path")
    }

    private fun withServer(block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(40_000) {
            val store = BadgeNoopStore()
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
                terminalBridgeFactory = { _, _ -> error("no terminal bridge in a serving test") },
                currentVersion = "9.8.7+badge",
                webUiDir = badgeWebUiDir(),
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

    /** Enough of an [EventStore] to construct the server; static serving never reaches any of it. */
    private class BadgeNoopStore : EventStore, PreferencesStore {
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()
        private val preferenceState = MutableStateFlow(UiPreferences("", 1, 0))
        override val preferences: StateFlow<UiPreferences> get() = preferenceState
        override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
            UiPreferences(basePath, groupingLevel, preferenceState.value.revision + 1)
                .also { preferenceState.value = it }
        override suspend fun upsertSession(meta: SessionMeta) {}
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ) {}
        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {}
        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) {}
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: ProviderSessionId,
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
private fun badgeCurrentDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())
    buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun badgeFileExists(path: String): Boolean = access(path, F_OK) == 0

/** `resources/webui`, found by walking up from wherever the runner started. */
private fun badgeWebUiDir(): String {
    var dir = badgeCurrentDir()
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (badgeFileExists("$candidate/index.html")) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}
