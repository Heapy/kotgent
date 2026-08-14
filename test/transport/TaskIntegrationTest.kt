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

class TaskIntegrationTest {

    private val token = "task-integration-token"

    private val projectId = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val projectName = "kotgent"

    private val projectRoot = "/repo"
    private val sessionCwd = "/repo/sub"

    private val provider = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


    @Test
    fun aTaskGoesFromCreationThroughALinkToDeletionAcrossBothRealStores() = withStack { ctx ->
        val session = ctx.startSession()

        ctx.events { frames ->
            val sessions = frames.await("sessions_snapshot", SessionsSnapshotDto.serializer())
            assertTrue(sessions.sessions.any { it.id == session.id }, "the session baseline carries the started row")
            assertEquals(
                emptyList<String>(),
                frames.await("tasks_snapshot", TasksSnapshotDto.serializer()).tasks.map { it.ref },
                "the task baseline is empty before anything is created",
            )

            val created = ctx.createTask(title = "wire the board", sessionId = session.id)
            assertEquals("local:1", created.ref, "the built-in tracker mints local:<n>")
            assertEquals("todo", created.state)
            val row = frames.await("task_row", TaskRowDto.serializer()) { it.task.ref == created.ref }
            assertEquals("wire the board", row.task.title, "the frame joins the tracker fields onto the entry")
            assertEquals("todo", row.task.state)

            assertEquals(HttpStatusCode.OK, ctx.link(created.ref, session.id).status)
            val advanced = frames.await("task_update", TaskUpdateDto.serializer()) { it.task.ref == created.ref }
            assertEquals("in_progress", advanced.task.state, "linking advanced the task out of todo")
            val linked = frames.await("session_update", SessionUpdateDto.serializer()) {
                it.sessionId == session.id && it.taskRef != null
            }
            assertEquals(created.ref, linked.taskRef, "the session's badge moved on the same socket")

            val detail = ctx.taskDetail(created.ref)
            assertEquals(created.ref, detail.task.ref)
            assertEquals(projectName, detail.projectName, "the detail carries the registered project")
            assertEquals(projectRoot, detail.projectPath, "…and the checkout the daemon saw most recently")
            assertEquals(listOf(session.id), detail.sessions.map { it.id }, "the linked session is joined in")
            assertTrue(
                detail.activity.map { it.kind }.containsAll(listOf("created", "linked")),
                "the feed recorded both writes: ${detail.activity.map { it.kind }}",
            )

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

        assertEquals(HttpStatusCode.NotFound, ctx.get("$API_PREFIX/tasks/local:1").status)
        assertNull(ctx.session(session.id).taskRef)
    }


    @Test
    fun aStartCarryingATaskRefProducesALinkedSessionThroughTheRealSessionManager() = withStack { ctx ->
        val bootstrap = ctx.startSession()
        val task = ctx.createTask(title = "adopt me", sessionId = bootstrap.id)

        val worker = ctx.startSession(taskRef = task.ref)
        assertEquals(task.ref, worker.taskRef, "the answered row already carries the link")
        assertEquals(task.ref, ctx.session(worker.id).taskRef, "and so does the committed row")
        assertEquals(
            "in_progress",
            ctx.taskDetail(task.ref).task.state,
            "the same request ran the conditional todo → in_progress",
        )

        assertEquals(HttpStatusCode.OK, ctx.link(task.ref, bootstrap.id).status)
        assertEquals(
            setOf(bootstrap.id, worker.id),
            ctx.taskDetail(task.ref).sessions.map { it.id }.toSet(),
            "a task may be linked from any number of sessions, and the board shows every one",
        )
    }


    @Test
    fun theApiAnswersUnderTheApiPrefixWhileTheSpaOwnsTheBarePath() = withStack { ctx ->
        val session = ctx.startSession()
        val task = ctx.createTask(title = "one word, two spaces", sessionId = session.id)
        val shell = ctx.getPublic("/")
        assertEquals(HttpStatusCode.OK, shell.status)

        val list = ctx.get("$API_PREFIX/tasks?project=${projectId.value}")
        assertEquals(HttpStatusCode.OK, list.status)
        assertContentTypeContains(list, "json")
        val rows = TRANSPORT_JSON.decodeFromString(ListSerializer(BacklogEntryDto.serializer()), list.bodyAsText())
        assertEquals(listOf(task.ref), rows.map { it.ref }, "the API answered from the real backlog")

        val board = ctx.getPublic("/tasks")
        assertEquals(HttpStatusCode.OK, board.status)
        assertContentTypeContains(board, "html")
        assertEquals(shell.bodyAsText(), board.bodyAsText(), "/tasks is a History-API route and serves the shell")

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


    @Test
    fun startingASessionRegistersItsProjectSoTheSelectorCanReachTheBacklog() = withStack { ctx ->
        assertEquals(
            emptyList(),
            ctx.projects().map { it.id },
            "no project is known before anything resolves one",
        )

        val session = ctx.startSession()
        assertEquals(projectId.value, session.projectId, "the start resolved the project from its cwd")

        val known = ctx.projects().single()
        assertEquals(projectId.value, known.id)
        assertEquals(projectName, known.name)
        assertEquals(projectRoot, known.path, "path is the checkout this daemon saw most recently")
        assertEquals(HttpStatusCode.OK, ctx.get("$API_PREFIX/tasks?project=${projectId.value}").status)
    }


    private inner class Ctx(val port: Int, val client: HttpClient) {

        suspend fun get(path: String): HttpResponse = client.get(url(path)) { credential() }

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

    private fun withStack(block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(60_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val events = SqliteEventStore.using(driver, now = { 1L })
            val tasks = SqliteTaskStore.using(driver, now = { 1L })
            val fs = FakeProjectFs(
                dirs = setOf(projectRoot, sessionCwd),
                files = mapOf(
                    "$projectRoot/$PROJECT_FILE_NAME" to """{"id":"${projectId.value}","name":"$projectName"}""",
                ),
            )
            val service = TaskService(tasks, events, fs, RefusingProjectFileWriter())
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
                idScope.cancel()
                // Do not close the driver: cancellation does not join the background provider-id capture.
            }
        }
    }

    private fun assertContentTypeContains(resp: HttpResponse, needle: String) {
        val actual = resp.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(actual.contains(needle, ignoreCase = true), "content-type '$actual' should mention '$needle'")
    }

    private class Frames(private val ws: DefaultClientWebSocketSession) {

        // Preserve unmatched interleaved kinds; discarding one can leave a later waiter hung forever.
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

        private fun typeOf(text: String): String? = runCatching {
            TRANSPORT_JSON.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()
    }

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
