package io.kotgent.transport

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.ProjectId
import io.kotgent.core.ProviderSessionId
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.SqliteTaskStore
import io.kotgent.task.PROJECT_FILE_NAME
import io.kotgent.task.ProjectFile
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The task layer end to end (plan Task 30): a real [SqliteTaskStore] and a real [SqliteEventStore] over
 * ONE SQLite driver, behind the real routes, behind a real [KotgentServer] on an ephemeral port, driven
 * by a real HTTP/WebSocket client.
 *
 * ## Why this file exists at all
 * Every wave-2 task tested against fakes — that is what made them independent, and it is the fan-out's
 * one deliberate compromise. So the first time the real store meets the real routes meets the real
 * socket is here, and these tests assert only what that combination can show:
 *
 *  - **Two stores, two mutexes, one driver.** `sessions` has exactly one writer and `backlog_entries`
 *    another; [TaskService] calls them sequentially and never nests their locks. A create → link →
 *    delete round trip driven entirely over HTTP is what proves the pair actually commits and does not
 *    deadlock a `Dispatchers.Default` thread apiece — no fake can fail that way.
 *  - **The cross-store join.** `GET /tasks/{ref}` reads its entry from the task store and its linked
 *    sessions from the event store; `DELETE` clears `sessions.task_ref` on every holder through the
 *    event store and then deletes through the tracker. Both are joins across the seam the two-store
 *    split created.
 *  - **The live protocol over a real socket.** `tasks_snapshot` / `task_row` / `task_update` /
 *    `task_removed` interleaved with the session frames, produced by real store emissions rather than
 *    by a fake's `tryEmit`.
 *  - **The two URL spaces.** `/api/v1/tasks` answers as the API and `/tasks` answers with the SPA shell,
 *    from ONE server, with real route bodies. `SpaRoutingTest` can only prove the negative half (its
 *    handlers were empty in wave 2, so the prefixed path merely fell through without being swallowed);
 *    the positive half moved here.
 *
 * What it deliberately does NOT re-prove: ordering arithmetic, the four dependency refusals, every
 * status code of every route, the frame discriminators. Those have unit tests, and repeating them here
 * would buy nothing but runtime.
 *
 * ## Fakes that remain, and why they are not the thing under test
 * `tmux` and the agent binary (a real launch is forbidden in automation), and the project FILESYSTEM —
 * a fake tree holding one `.kotgent.json`, so project resolution runs its real rules without writing
 * into anybody's checkout. The two STORES, the routes, the server, the socket and the client are real.
 *
 * Every body is bounded by [withTimeout] as an anti-hang tripwire: a frame that never arrives is a hang,
 * not a wrong answer.
 */
class TaskIntegrationTest {

    private val token = "task-integration-token"

    /** The uuid inside the fake tree's `.kotgent.json`; every project assertion below names it. */
    private val projectId = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val projectName = "kotgent"

    /** The checkout root holding the project file, and the session cwd one level below it. */
    private val projectRoot = "/repo"
    private val sessionCwd = "/repo/sub"

    private val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    // --- the whole round trip, over the real stack -------------------------------------------------

    @Test
    fun aTaskGoesFromCreationThroughALinkToDeletionAcrossBothRealStores() = withStack { ctx ->
        val session = ctx.startSession()

        ctx.events { frames ->
            // Both baselines first, so nothing below is racing the subscribe/snapshot handoff. They
            // arrive in EITHER order (see [Frames]); the task one is empty because the project exists —
            // the start registered it — but has no backlog yet.
            val sessions = frames.await("sessions_snapshot", SessionsSnapshotDto.serializer())
            assertTrue(sessions.sessions.any { it.id == session.id }, "the session baseline carries the started row")
            assertEquals(
                emptyList<String>(),
                frames.await("tasks_snapshot", TasksSnapshotDto.serializer()).tasks.map { it.ref },
                "the task baseline is empty before anything is created",
            )

            // 1. Create. A ref this socket has not carried yet arrives as a FULL row, not a patch.
            val created = ctx.createTask(title = "wire the board", sessionId = session.id)
            assertEquals("local:1", created.ref, "the built-in tracker mints local:<n>")
            assertEquals("todo", created.state)
            val row = frames.await("task_row", TaskRowDto.serializer()) { it.task.ref == created.ref }
            assertEquals("wire the board", row.task.title, "the frame joins the tracker fields onto the entry")
            assertEquals("todo", row.task.state)

            // 2. Link. Two writes in two stores, in order: the conditional todo → in_progress, then the
            // unconditional sessions.task_ref. Both are observable here, on one socket, as two frames.
            assertEquals(HttpStatusCode.OK, ctx.link(created.ref, session.id).status)
            val advanced = frames.await("task_update", TaskUpdateDto.serializer()) { it.task.ref == created.ref }
            assertEquals("in_progress", advanced.task.state, "linking advanced the task out of todo")
            val linked = frames.await("session_update", SessionUpdateDto.serializer()) {
                it.sessionId == session.id && it.taskRef != null
            }
            assertEquals(created.ref, linked.taskRef, "the session's badge moved on the same socket")

            // 3. The cross-store join: the entry comes from the task store, the holders from the event
            // store, in one response.
            val detail = ctx.taskDetail(created.ref)
            assertEquals(created.ref, detail.task.ref)
            assertEquals(projectName, detail.projectName, "the detail carries the registered project")
            assertEquals(projectRoot, detail.projectPath, "…and the checkout the daemon saw most recently")
            assertEquals(listOf(session.id), detail.sessions.map { it.id }, "the linked session is joined in")
            assertTrue(
                detail.activity.map { it.kind }.containsAll(listOf("created", "linked")),
                "the feed recorded both writes: ${detail.activity.map { it.kind }}",
            )

            // 4. Delete. Every holder is unlinked BEFORE the task goes, so no session is left carrying a
            // badge that points at nothing — the half that needs both stores to be real.
            assertEquals(HttpStatusCode.OK, ctx.deleteTask(created.ref).status)
            val cleared = frames.await("session_update", SessionUpdateDto.serializer()) {
                it.sessionId == session.id && it.taskRef == null
            }
            assertNull(cleared.taskRef, "deleting the task unlinked the session that held it")
            assertEquals(
                created.ref,
                frames.await("task_removed", TaskRemovedDto.serializer()).ref,
                "the delete goes out as task_removed, not as a patch",
            )
        }

        // The committed rows agree with the frames: the task is gone and the session is honestly unlinked.
        assertEquals(HttpStatusCode.NotFound, ctx.get("$API_PREFIX/tasks/local:1").status)
        assertNull(ctx.session(session.id).taskRef)
    }

    // --- POST /sessions carrying a taskRef ---------------------------------------------------------

    @Test
    fun aStartCarryingATaskRefProducesALinkedSessionThroughTheRealSessionManager() = withStack { ctx ->
        val bootstrap = ctx.startSession()
        val task = ctx.createTask(title = "adopt me", sessionId = bootstrap.id)

        // ONE request: the session row and its link are written together, so a failed launch could leave
        // no link behind. This is `kotgent start --task` and the board's "Start session".
        val worker = ctx.startSession(taskRef = task.ref)
        assertEquals(task.ref, worker.taskRef, "the answered row already carries the link")
        assertEquals(task.ref, ctx.session(worker.id).taskRef, "and so does the committed row")
        assertEquals(
            "in_progress",
            ctx.taskDetail(task.ref).task.state,
            "the same request ran the conditional todo → in_progress",
        )

        // No exclusivity, asserted where it is actually visible: two sessions, one card, one SQL read of
        // the real `sessions` table joined into the task detail.
        assertEquals(HttpStatusCode.OK, ctx.link(task.ref, bootstrap.id).status)
        assertEquals(
            setOf(bootstrap.id, worker.id),
            ctx.taskDetail(task.ref).sessions.map { it.id }.toSet(),
            "a task may be linked from any number of sessions, and the board shows every one",
        )
    }

    // --- the two URL spaces, with real route bodies on both sides ----------------------------------

    @Test
    fun theApiAnswersUnderTheApiPrefixWhileTheSpaOwnsTheBarePath() = withStack { ctx ->
        val session = ctx.startSession()
        val task = ctx.createTask(title = "one word, two spaces", sessionId = session.id)
        // Unauthenticated on purpose: the static surface is the bootstrap the browser fetches before it
        // has any credential, which is the other half of "two URL spaces".
        val shell = ctx.getPublic("/")
        assertEquals(HttpStatusCode.OK, shell.status)

        // API ground. Not "not the shell" (all SpaRoutingTest could assert while these bodies were
        // empty) but the actual rows, decoded — the positive half of the collision test.
        val list = ctx.get("$API_PREFIX/tasks?project=${projectId.value}")
        assertEquals(HttpStatusCode.OK, list.status)
        assertContentTypeContains(list, "json")
        val rows = TRANSPORT_JSON.decodeFromString(ListSerializer(BacklogEntryDto.serializer()), list.bodyAsText())
        assertEquals(listOf(task.ref), rows.map { it.ref }, "the API answered from the real backlog")

        // UI ground, same first segment, same server.
        val board = ctx.getPublic("/tasks")
        assertEquals(HttpStatusCode.OK, board.status)
        assertContentTypeContains(board, "html")
        assertEquals(shell.bodyAsText(), board.bodyAsText(), "/tasks is a History-API route and serves the shell")

        // One level deeper, where the ref segment is byte-identical on both sides.
        val detail = ctx.get("$API_PREFIX/tasks/${task.ref}")
        assertEquals(HttpStatusCode.OK, detail.status)
        assertContentTypeContains(detail, "json")
        assertEquals(
            task.ref,
            TRANSPORT_JSON.decodeFromString(TaskDetailDto.serializer(), detail.bodyAsText()).task.ref,
        )
        val deepLink = ctx.getPublic("/tasks/${task.ref}")
        assertEquals(HttpStatusCode.OK, deepLink.status)
        assertEquals(shell.bodyAsText(), deepLink.bodyAsText(), "the deep link serves the same shell")
    }

    // --- the registration that makes a backlog reachable at all ------------------------------------

    @Test
    fun startingASessionRegistersItsProjectSoTheSelectorCanReachTheBacklog() = withStack { ctx ->
        // Nothing has read a `.kotgent.json` yet, so the daemon knows no project and the board's
        // selector is empty.
        assertEquals(
            emptyList(),
            ctx.projects().map { it.id },
            "no project is known before anything resolves one",
        )

        val session = ctx.startSession()
        assertEquals(projectId.value, session.projectId, "the start resolved the project from its cwd")

        // The registration is the load-bearing half: without the `projects` row the backlog exists but is
        // unreachable, because `GET /tasks?project=` answers 404 for a uuid the daemon has never seen.
        val known = ctx.projects().single()
        assertEquals(projectId.value, known.id)
        assertEquals(projectName, known.name)
        assertEquals(projectRoot, known.path, "path is the checkout this daemon saw most recently")
        assertEquals(HttpStatusCode.OK, ctx.get("$API_PREFIX/tasks?project=${projectId.value}").status)
    }

    // --- harness -----------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {

        suspend fun get(path: String): HttpResponse = client.get(url(path)) { credential() }

        /** The static surface is deliberately UNauthenticated — the browser fetches it before it has a cookie. */
        suspend fun getPublic(path: String): HttpResponse = client.get(url(path))

        suspend fun post(path: String, body: String): HttpResponse = client.post(url(path)) {
            credential()
            setBody(body)
        }

        suspend fun startSession(taskRef: String? = null): SessionDto {
            val body = if (taskRef == null) {
                """{"agent":"claude","cwd":"$sessionCwd"}"""
            } else {
                """{"agent":"claude","cwd":"$sessionCwd","taskRef":"$taskRef"}"""
            }
            val resp = post("$API_PREFIX/sessions", body)
            assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
            return TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        }

        suspend fun session(id: String): SessionDto {
            val resp = get("$API_PREFIX/sessions/$id")
            assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
            return TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        }

        suspend fun createTask(title: String, sessionId: String): BacklogEntryDto {
            val resp = post("$API_PREFIX/tasks", """{"title":"$title","sessionId":"$sessionId"}""")
            assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
            return TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
        }

        suspend fun link(ref: String, sessionId: String): HttpResponse =
            post("$API_PREFIX/tasks/$ref/link", """{"sessionId":"$sessionId"}""")

        suspend fun deleteTask(ref: String): HttpResponse = client.delete(url("$API_PREFIX/tasks/$ref")) {
            credential()
        }

        suspend fun taskDetail(ref: String): TaskDetailDto {
            val resp = get("$API_PREFIX/tasks/$ref")
            assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
            return TRANSPORT_JSON.decodeFromString(TaskDetailDto.serializer(), resp.bodyAsText())
        }

        suspend fun projects(): List<ProjectDto> {
            val resp = get("$API_PREFIX/projects")
            assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
            return TRANSPORT_JSON.decodeFromString(ListSerializer(ProjectDto.serializer()), resp.bodyAsText())
        }

        /** Open the real global `/events` socket, credentialed with the master token like `kotgent attach`. */
        suspend fun events(block: suspend (Frames) -> Unit) {
            client.webSocket(
                "ws://127.0.0.1:$port$API_PREFIX/events",
                request = { header(HttpHeaders.Authorization, "Bearer $token") },
            ) { block(Frames(this)) }
        }

        private fun url(path: String): String = "http://127.0.0.1:$port$path"

        private fun io.ktor.client.request.HttpRequestBuilder.credential() {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    /**
     * The whole daemon-side stack: ONE in-memory SQLite driver under both real stores, a real
     * [SessionManager] over a fake tmux and a canned agent, the real [TaskService], and a real
     * [KotgentServer] serving the real `resources/webui` on an ephemeral port.
     *
     * The web UI directory is served for real because the two-URL-spaces assertion needs a shell to
     * come back; every other test simply does not ask for one.
     */
    private fun withStack(block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(60_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            // Constructed in the daemon's order (Commands.daemon): the event store first, then the task
            // store over the SAME driver — two writers, two mutexes, one connection.
            val events = SqliteEventStore.using(driver, now = { 1L })
            val tasks = SqliteTaskStore.using(driver, now = { 1L })
            val fs = FakeProjectFs(
                dirs = setOf(projectRoot, sessionCwd),
                files = mapOf(
                    "$projectRoot/$PROJECT_FILE_NAME" to """{"id":"${projectId.value}","name":"$projectName"}""",
                ),
            )
            val service = TaskService(tasks, events, fs, RefusingProjectFileWriter(), now = { 1L })
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                FakeTmux(),
                events,
                PaneRegistry(),
                AgentFactory { _, cwd ->
                    object : AgentAdapter {
                        override val events: Flow<AgentEvent> = emptyFlow()
                        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
                            is LaunchMode.New -> LaunchSpec(listOf("cat"), emptyMap(), cwd, provider)
                            is LaunchMode.Resume -> LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                        }
                    }
                },
                ProviderIdCapture(events, idScope),
                VendorStoreProbe { _, _, _ -> false },
                VendorSessionLocator { _, _ -> null },
                setOf("claude"),
                now = { 1L },
                taskStore = tasks,
                projectFs = fs,
            )
            val server = KotgentServer(
                sessionManager = manager,
                store = events,
                preferencesStore = events,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("the task integration test never attaches a terminal") },
                webUiDir = locateTaskIntegrationWebUiDir(),
                taskStore = tasks,
                taskService = service,
                port = 0,
            ).start()
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                block(Ctx(server.port(), client))
            } finally {
                client.close()
                server.stop()
                // The driver is deliberately NOT closed: `cancel()` does not JOIN the background id
                // capture, so a closed connection here would be a use-after-close race rather than a
                // cleanup. An in-memory database dies with the test binary, as everywhere else in the
                // suite.
                idScope.cancel()
            }
        }
    }

    private fun assertContentTypeContains(resp: HttpResponse, needle: String) {
        val actual = resp.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(actual.contains(needle, ignoreCase = true), "content-type '$actual' should mention '$needle'")
    }

    /**
     * Everything the socket has said so far, so a waiter can be asked for one kind without throwing away
     * another. **This buffering is not a convenience — a discarding waiter cannot be written correctly
     * against this socket at all**, and finding that out is one of the things only an integration test
     * could have found.
     *
     * The two streams are independent by construction ([launchTaskStream] is launched before the session
     * collector subscribes, and each has its own conflating sender), so `tasks_snapshot` and
     * `sessions_snapshot` arrive in **either order** — measured, not theorised: a waiter that skipped
     * frames of the wrong kind dropped the task baseline while waiting for the session one and then hung
     * forever, on roughly a third of runs. Preference frames interleave with both.
     *
     * [await] therefore scans what has already arrived before reading more, and CONSUMES the frame it
     * answers with. That keeps the ordering the assertions rely on — "the first matching frame not yet
     * claimed, in arrival order" — so a later `session_update` waiter cannot re-answer with an earlier
     * one's frame.
     */
    private class Frames(private val ws: DefaultClientWebSocketSession) {

        /** Every text frame in arrival order; an entry is nulled once a waiter has claimed it. */
        private val seen = mutableListOf<String?>()

        suspend fun <T> await(
            type: String,
            serializer: DeserializationStrategy<T>,
            predicate: (T) -> Boolean = { true },
        ): T {
            var at = 0
            while (true) {
                if (at == seen.size) {
                    seen += receiveText()
                    continue
                }
                val text = seen[at]
                if (text != null && typeOf(text) == type) {
                    val decoded = TRANSPORT_JSON.decodeFromString(serializer, text)
                    if (predicate(decoded)) {
                        seen[at] = null
                        return decoded
                    }
                }
                at++
            }
        }

        private suspend fun receiveText(): String {
            while (true) {
                val frame = ws.incoming.receive()
                if (frame is Frame.Text) return frame.readText()
            }
        }

        /** The `type` discriminator [TRANSPORT_JSON] writes for every [EventsFrame], or null. */
        private fun typeOf(text: String): String? = runCatching {
            TRANSPORT_JSON.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * A fixed fake tree: one checkout root holding a `.kotgent.json`, one directory below it, `realpath`
     * as identity over both. Immutable, because the CIO server reads it from its own engine threads.
     *
     * The filesystem is the one edge left faked here — the rules above it (`resolveProject`, the
     * `projects` upsert, the session row's `project_id`) all run for real, and writing a project file
     * into somebody's checkout during a test run is not something a test may do.
     */
    private class FakeProjectFs(
        private val dirs: Set<String>,
        private val files: Map<String, String>,
    ) : ProjectFs {
        override fun isDirectory(path: String): Boolean = path.trimEnd('/') in dirs
        override fun readFile(path: String, maxBytes: Int): String? = files[path]?.take(maxBytes)
        override fun canonicalize(path: String): String? {
            val trimmed = path.trimEnd('/').ifEmpty { "/" }
            return if (trimmed in dirs || trimmed in files) trimmed else null
        }
    }

    /**
     * The project file already exists in the fake tree, so every resolution path answers before a write
     * is ever needed. This refuses rather than writing anywhere: a test that reached it would be
     * creating files on the developer's disk, and failing loudly is how that stays true.
     */
    private class RefusingProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile =
            error("the task integration test never writes a $PROJECT_FILE_NAME (asked for '$name' in $dir)")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun taskIntegrationCurrentDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())
    buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun taskIntegrationFileExists(path: String): Boolean = access(path, F_OK) == 0

/** Locate `resources/webui` by walking up from the cwd, so the runner's start directory does not matter. */
private fun locateTaskIntegrationWebUiDir(): String {
    var dir = taskIntegrationCurrentDir()
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (taskIntegrationFileExists("$candidate/index.html")) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}
