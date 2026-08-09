package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProjectId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.store.EventStore
import io.kotgent.store.PreferencesStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.TaskStore
import io.kotgent.store.UiPreferences
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectFile
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
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
 * The SPA route grammar (`isSpaRoute`) and its one consequence for static serving: a History-API deep
 * link names no file on disk, so it must be answered with the shell instead of `404` — and nothing else
 * may be.
 *
 * ## Why this suite mounts the task routes
 * The whole point of the grammar is that two URL spaces now share one origin: `/tasks` belongs to the
 * browser and `/api/v1/tasks` to the daemon. `withServer` in `WebUiServingTest.kt` builds a
 * [KotgentServer] with `taskStore`/`taskService` left null, which leaves [taskRoutes] unmounted — and a
 * collision test against a surface that does not exist passes for the wrong reason. So this harness
 * passes both, and the fakes below exist only to satisfy the two constructor parameters; no request in
 * this file reaches a single one of their methods.
 *
 * ## Why the API assertion is "not the shell" rather than "JSON"
 * In wave 2 the three task route files are still empty bodies, so `/api/v1/tasks` genuinely has no
 * handler and falls through to the static catch-all, which answers `404`. That `404` is exactly the
 * property under test: had the grammar been written as a prefix match — or matched against the
 * rev-STRIPPED path, or been consulted before the traversal guard — the prefixed path could have been
 * swallowed into a `200` shell. Task 30, which has real route bodies, owns the positive half
 * ("`/api/v1/tasks` returns JSON while `/tasks` returns the shell").
 */
class SpaRoutingTest {

    private val token = "spa-routing-token"

    // --- the pure grammar ------------------------------------------------------------------------

    @Test
    fun theGrammarAcceptsExactlyTheThreeHistoryApiRoutes() {
        assertTrue(isSpaRoute("tasks"), "the board")
        assertTrue(isSpaRoute("tasks/local:42"), "one task's detail view")
        assertTrue(isSpaRoute("s/2f1c9b7e-0000-4000-8000-000000000001"), "one session's terminal")
        // The ref/id segment is not inspected — see the KDoc. A grammar that parsed them would answer an
        // unknown task with a bare 404 from the file server instead of letting the SPA render one.
        assertTrue(isSpaRoute("tasks/anything"), "the {ref} segment is opaque to the grammar")
        assertTrue(isSpaRoute("s/anything"), "the {id} segment is opaque to the grammar")
    }

    @Test
    fun theGrammarIsExactSegmentsAndNotAPrefixMatch() {
        // The whole reason it is not `rel.startsWith("s/")`: a mistyped asset path under a route prefix
        // must stay a 404, or "a wrong asset path 404s" is a false promise and every typo becomes a page
        // that loads and then does nothing.
        assertFalse(isSpaRoute("s/id/extra"), "a third segment is not a session route")
        assertFalse(isSpaRoute("tasks/id/missing.js"), "a mistyped asset under /tasks/{ref} still 404s")
        assertFalse(isSpaRoute("tasksy"), "a longer first segment is a different word, not a prefix")
        assertFalse(isSpaRoute("sessions"), "`s` is one segment, not one letter of another")
        assertFalse(isSpaRoute("lib/nope.js"), "an ordinary asset path")
        assertFalse(isSpaRoute("nope"), "an unknown single segment")
    }

    @Test
    fun theGrammarHasNoArmForTheEmptyPathOrAnEmptySegment() {
        // `staticWebUi` substitutes `index.html` for a blank path before this is ever consulted, so an
        // arm here would be dead code that also accepted `//`.
        assertFalse(isSpaRoute(""), "the empty path is staticWebUi's, not the grammar's")
        assertFalse(isSpaRoute("tasks/"), "a trailing slash leaves an empty {ref}")
        assertFalse(isSpaRoute("s/"), "a trailing slash leaves an empty {id}")
        assertFalse(isSpaRoute("/tasks"), "a leading slash leaves an empty first segment")
        assertFalse(isSpaRoute("s//x"), "an empty middle segment is malformed, not a route")
    }

    @Test
    fun theGrammarNeverMatchesARevisionedAssetPath() {
        // It is matched against the ORIGINAL `rel`, before `stripRevPrefix`. A revisioned path therefore
        // has at least three segments and cannot match — which is what keeps a stripped path that
        // happens to look like a route (`/_v/<rev>/tasks`) out of the shell branch.
        assertFalse(isSpaRoute("_v/7c41f9ab30d2/tasks"), "a revisioned path is never a UI route")
        assertFalse(isSpaRoute("_v/7c41f9ab30d2/app.js"), "the ordinary revisioned asset")
    }

    // --- what that means over HTTP, with both URL spaces mounted ---------------------------------

    @Test
    fun aDeepLinkServesTheShellWithASubstitutedRevision() = withServer { ctx ->
        val root = ctx.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        val shell = root.bodyAsText()
        val rev = revisionOf(shell)

        for (path in listOf("/tasks", "/tasks/local:42", "/s/2f1c9b7e-0000-4000-8000-000000000001")) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.OK, resp.status, "$path is a History-API route and serves the shell")
            val body = resp.bodyAsText()
            assertEquals(shell, body, "$path serves the very same shell as /")
            // The route falls through to the `path == "index.html"` branch rather than short-circuiting,
            // because that branch is what substitutes the revision. A shell whose every asset URL read
            // `/_v/__REV__/…` would load nothing at all.
            assertFalse(body.contains(WEBUI_REV_PLACEHOLDER), "$path substituted the revision placeholder")
            assertTrue(body.contains("/_v/$rev/app.js"), "$path carries the same content revision as /")
            assertEquals(
                "no-cache",
                resp.headers[HttpHeaders.CacheControl],
                "$path is the shell, which must always revalidate",
            )
            assertContentTypeContains(resp, "html")
        }
    }

    @Test
    fun theSpaGrammarDoesNotSwallowTheApiNamespace() = withServer { ctx ->
        // The task routes ARE mounted here (see the class KDoc); their bodies are empty in this wave, so
        // the prefixed path has no handler and reaches the static catch-all. 404 — NOT a 200 shell — is
        // the property: the grammar must never claim anything under `/api/v1`.
        val credentialed = ctx.get("$API_PREFIX/tasks") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(
            HttpStatusCode.NotFound,
            credentialed.status,
            "the API namespace is not a UI route; the SPA grammar must not answer it with the shell",
        )
        assertEquals("not found", credentialed.bodyAsText().trim(), "the static catch-all answered, with no shell")

        for (path in listOf("$API_PREFIX/tasks/local:42", "$API_PREFIX/whoami", "$API_PREFIX/projects")) {
            val resp = ctx.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.NotFound, resp.status, "$path is API ground, never the shell")
        }
    }

    @Test
    fun everythingOutsideTheGrammarStillGetsACleanNotFound() = withServer { ctx ->
        for (path in listOf("/s/id/extra", "/tasks/id/missing.js", "/lib/nope.js", "/nope", "/tasks/")) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.NotFound, resp.status, "$path is not a route and names no file")
            assertEquals("not found", resp.bodyAsText().trim(), "$path answered from the static catch-all")
        }
    }

    @Test
    fun anExistingFileStillWinsOverTheGrammar() = withServer { ctx ->
        // `isSpaRoute` is consulted only when no file was read, so a real asset is never shadowed by a
        // route that happens to share its shape. `s/…` and `tasks/…` name nothing on disk today, but the
        // ordering is the invariant — this asserts the branch, using the paths that do exist.
        val css = ctx.get("/style.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertContentTypeContains(css, "css")
        assertFalse(css.bodyAsText().contains("<!DOCTYPE html>"), "a real file is served, not the shell")
    }

    @Test
    fun theRoutesChangeNothingAboutRevisionedCachingOrTheStableUrls() = withServer { ctx ->
        val rev = revisionOf(ctx.get("/").bodyAsText())

        val asset = ctx.get("/_v/$rev/app.js")
        assertEquals(HttpStatusCode.OK, asset.status)
        assertEquals(
            IMMUTABLE_CACHE_CONTROL,
            asset.headers[HttpHeaders.CacheControl],
            "a revisioned asset is still immutable",
        )

        // The two files a deep link must not disturb: the worker's root scope and the installed PWA's
        // fixed manifest address are what keep push working from `/tasks/local:42`.
        val worker = ctx.get("/sw.js")
        assertEquals(HttpStatusCode.OK, worker.status)
        assertEquals("no-cache", worker.headers[HttpHeaders.CacheControl], "the worker still revalidates")
        assertContentTypeContains(worker, "javascript")

        val manifest = ctx.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertContentTypeContains(manifest, "manifest+json")

        val icon = ctx.get("/icons/logo.svg")
        assertEquals(HttpStatusCode.OK, icon.status, "the icons stay reachable at their stable address")
    }

    @Test
    fun theTraversalGuardStillOutranksTheGrammar() = withServer { ctx ->
        for (path in listOf("/tasks/../../etc/passwd", "/_v/abc/../../etc/passwd", "/s/../style.css")) {
            val resp = ctx.get(path)
            assertEquals(HttpStatusCode.Forbidden, resp.status, "$path is refused before anything reads a file")
            assertEquals("bad path", resp.bodyAsText().trim())
        }
    }

    // --- harness ----------------------------------------------------------------------------------

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
            val tasks = UnusedTaskStore()
            val server = KotgentServer(
                sessionManager = manager,
                store = store,
                preferencesStore = store,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in the SPA routing test") },
                webUiDir = locateSpaWebUiDir(),
                // Both halves, or `taskRoutes` is not mounted and the API/UI collision test is vacuous.
                taskStore = tasks,
                taskService = TaskService(tasks, store, UnusedProjectFs(), UnusedProjectFileWriter(), now = { 1L }),
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

    /** The revision the served shell carries, read out of a `src="…"` attribute rather than a comment. */
    private fun revisionOf(index: String): String {
        val marker = "src=\"/_v/"
        val at = index.indexOf(marker)
        assertTrue(at >= 0, "index.html fetches its assets through the revision prefix")
        val rest = index.substring(at + marker.length)
        val end = rest.indexOf('/')
        assertTrue(end > 0, "the revision prefix names a path underneath it")
        val rev = rest.substring(0, end)
        assertTrue(isRevToken(rev), "the served shell carries a real revision, not the placeholder")
        return rev
    }

    /**
     * A [TaskStore] that exists only so [KotgentServer] mounts [taskRoutes]. Every method throws: no
     * request in this file reaches one, and a silent empty answer would let a future edit start depending
     * on this fake instead of on a real store.
     */
    private class UnusedTaskStore : TaskStore {
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()
        override val id: String get() = unused()
        override suspend fun list(project: ProjectId): List<Task> = unused()
        override suspend fun get(ref: TaskRef): Task? = unused()
        override suspend fun create(project: ProjectId, title: String, body: String): Task = unused()
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused()
        override suspend fun delete(ref: TaskRef): Boolean = unused()
        override suspend fun entry(ref: TaskRef): BacklogEntry? = unused()
        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = unused()
        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = unused()
        override suspend fun startIfTodo(ref: TaskRef): Boolean = unused()
        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = unused()
        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = unused()
        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> = unused()
        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = unused()
        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> = unused()
        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef): Unit = unused()
        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef): Unit = unused()
        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? = unused()
        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = unused()
        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = unused()
        override suspend fun upsertProject(id: ProjectId, name: String, path: String?): Unit = unused()
        override suspend fun listProjects(): List<ProjectRecord> = unused()
        override suspend fun project(id: ProjectId): ProjectRecord? = unused()
    }

    /** Same rule as [UnusedTaskStore]: present for the constructor, never consulted. */
    private class UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = unused()
        override fun readFile(path: String, maxBytes: Int): String? = unused()
        override fun canonicalize(path: String): String? = unused()
    }

    private class UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile = unused()
    }

    /**
     * A no-op [EventStore] good enough to construct the server: nothing in this file touches a session.
     * The three task-related writes keep their throwing defaults on purpose.
     */
    private class NoopEventStore : EventStore, PreferencesStore {
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()
        private val preferenceState = MutableStateFlow(UiPreferences("", 1, 0))
        override val preferences: StateFlow<UiPreferences> get() = preferenceState
        override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
            UiPreferences(basePath, groupingLevel, preferenceState.value.revision + 1).also {
                preferenceState.value = it
            }
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

/** What every member of this file's constructor-only fakes does when something unexpectedly calls it. */
private fun unused(): Nothing = error("the SPA routing test never calls the task subsystem")

/** The current working directory (via `getcwd`), for locating `resources/webui` at test time. */
@OptIn(ExperimentalForeignApi::class)
private fun spaTestCurrentDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())
    buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun spaTestFileExists(path: String): Boolean = access(path, F_OK) == 0

/**
 * Locate `resources/webui`: `./kotlin test` runs from the module root, but walking up from the cwd keeps
 * the suite independent of where the runner happens to start.
 */
private fun locateSpaWebUiDir(): String {
    var dir = spaTestCurrentDir()
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (spaTestFileExists("$candidate/index.html")) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}
