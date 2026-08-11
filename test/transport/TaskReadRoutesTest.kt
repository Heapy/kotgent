package io.kotgent.transport

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProjectId
import io.kotgent.core.Projection
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.TaskStore
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
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

class TaskReadRoutesTest {

    private val token = "task-read-routes-master-token-0123456789"
    private val fixedNow = 1_754_000_000_000L

    private val project = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val otherProject = ProjectId.of("11111111-2222-4333-8444-555555555555")

    private val first = TaskRef("local:1")
    private val second = TaskRef("local:2")
    private val third = TaskRef("local:3")

    private val paneSession = SessionId("2f6dd2f6-2b3f-4c53-9d3f-1f0f5d2a77aa")
    private val liveHolder = SessionId("3a7ee3a7-3c4f-4d64-8e40-2a1a6e3b88bb")
    private val deadHolder = SessionId("4b8ff4b8-4d50-4e75-9f51-3b2b7f4c99cc")
    private val pane = PaneId("%7")


    @Test
    fun whoamiAnswersTheCallingPanesSessionProjectAndTask() = withReadServer { env ->
        val resp = env.get("/whoami", bearer = token, pane = pane.value)
        assertEquals(HttpStatusCode.OK, resp.status)
        val who = TRANSPORT_JSON.decodeFromString(WhoamiDto.serializer(), resp.bodyAsText())
        assertEquals(paneSession.value, who.sessionId, "the pane resolves through the registry")
        assertEquals(project.value, who.projectId, "and carries the session's resolved project")
        assertEquals(
            second.value,
            who.taskRef,
            "and the task THIS session is linked to — this is how a ref-less `task comment` finds its " +
                "subject, and it is not the project's first task",
        )
    }

    @Test
    fun whoamiWithoutAPaneHeaderIs400AndNamesTheSessionFlag() = withReadServer { env ->
        val resp = env.get("/whoami", bearer = token)
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "no pane means no caller identity; 404 would claim a resource is missing",
        )
        assertTrue(resp.bodyAsText().contains("--session"), "the message names the fix: ${resp.bodyAsText()}")
    }

    @Test
    fun whoamiFromAPaneTheRegistryDoesNotKnowIs400() = withReadServer { env ->
        val resp = env.get("/whoami", bearer = token, pane = "%999")
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "the registry is narrowed to the live-pane set, so an unknown pane fails closed rather " +
                "than resolving to some other session",
        )
        assertTrue(resp.bodyAsText().contains("--session"))
    }

    @Test
    fun whoamiFromAMalformedPaneHeaderIs400() = withReadServer { env ->
        val resp = env.get("/whoami", bearer = token, pane = "not-a-pane")
        assertEquals(HttpStatusCode.BadRequest, resp.status, "a pane id that cannot parse resolves to nobody")
    }

    @Test
    fun whoamiAnswersAPaneWhoseSessionRowIsGoneWithBareIdentity() = withReadServer(
        sessions = emptyMap(),
    ) { env ->
        val resp = env.get("/whoami", bearer = token, pane = pane.value)
        assertEquals(
            HttpStatusCode.OK,
            resp.status,
            "the registry is the authority on WHO is calling; a missing row is a fact about the store",
        )
        val who = TRANSPORT_JSON.decodeFromString(WhoamiDto.serializer(), resp.bodyAsText())
        assertEquals(paneSession.value, who.sessionId)
        assertNull(who.projectId)
        assertNull(who.taskRef)
    }


    @Test
    fun theListIsOrderedByPositionAndCarriesBlockedAndTrackerFields() = withReadServer { env ->
        val resp = env.get("/tasks?project=${project.value}", bearer = token)
        assertEquals(HttpStatusCode.OK, resp.status)
        val rows = TRANSPORT_JSON.decodeFromString(
            ListSerializer(BacklogEntryDto.serializer()),
            resp.bodyAsText(),
        )
        assertEquals(
            listOf(first.value, second.value, third.value),
            rows.map { it.ref },
            "rank order comes from the store's `position` ordering, not from insertion order",
        )
        assertEquals(listOf(1.0, 2.0, 3.0), rows.map { it.position })
        assertEquals(
            listOf("write the parser", "wire the routes", "ship it"),
            rows.map { it.title },
            "the tracker fields are joined in",
        )
        assertEquals(
            listOf(false, false, true),
            rows.map { it.blocked },
            "`blocked` is derived server-side so the board does not recompute it per card",
        )
        assertEquals(
            listOf(emptyList(), emptyList(), listOf(first.value, second.value)),
            rows.map { it.dependsOn },
            "each entry gets its slice of the ONE edge-set query",
        )
        assertEquals(listOf(7L, 8L, 9L), rows.map { it.rev }, "every row carries its rev for newest-wins")
    }

    @Test
    fun theListReadsTheTrackerAndTheEdgeSetOncePerRequestNotOncePerCard() = withReadServer { env ->
        env.get("/tasks?project=${project.value}", bearer = token)
        assertEquals(
            listOf("project", "listBacklog", "list", "dependencyEdges"),
            env.journal(),
            "three reads for a whole project (plus the existence check) — a mapper that fetched per " +
                "card would undo exactly the reason `toDto` takes `dependsOn` as a parameter",
        )
    }

    @Test
    fun theListIsScopedToItsProject() = withReadServer { env ->
        val rows = TRANSPORT_JSON.decodeFromString(
            ListSerializer(BacklogEntryDto.serializer()),
            env.get("/tasks?project=${otherProject.value}", bearer = token).bodyAsText(),
        )
        assertEquals(listOf("local:9"), rows.map { it.ref }, "the other project's backlog is its own")
    }

    @Test
    fun theListFallsBackToTheCallingPanesProject() = withReadServer { env ->
        val resp = env.get("/tasks", bearer = token, pane = pane.value)
        assertEquals(HttpStatusCode.OK, resp.status)
        val rows = TRANSPORT_JSON.decodeFromString(
            ListSerializer(BacklogEntryDto.serializer()),
            resp.bodyAsText(),
        )
        assertEquals(
            listOf(first.value, second.value, third.value),
            rows.map { it.ref },
            "an agent inside a kotgent pane needs no argument at all",
        )
    }

    @Test
    fun theListWithNeitherAProjectNorASessionIs400AndNamesTheProjectFlag() = withReadServer { env ->
        val resp = env.get("/tasks", bearer = token)
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "the board with nothing selected, and the CLI from outside a session",
        )
        assertTrue(resp.bodyAsText().contains("--project"), "the message names the fix: ${resp.bodyAsText()}")
    }

    @Test
    fun theListWithAMalformedProjectIs400() = withReadServer { env ->
        val resp = env.get("/tasks?project=not-a-uuid", bearer = token)
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("--project"))
        assertEquals(
            emptyList(),
            env.journal(),
            "a malformed argument is refused before any store read",
        )
    }

    @Test
    fun theListOfAProjectTheDaemonHasNeverSeenIs404() = withReadServer { env ->
        val unknown = "99999999-8888-4777-8666-555555555555"
        val resp = env.get("/tasks?project=$unknown", bearer = token)
        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "every path that reads or creates a .kotgent.json upserts the row, so an unseen uuid is a " +
                "stale argument — an empty array would hide that",
        )
        assertTrue(resp.bodyAsText().contains(unknown), "the message names the project: ${resp.bodyAsText()}")
    }

    @Test
    fun aProjectIdIsMatchedCaseInsensitivelyBecauseProjectIdLowerCasesIt() = withReadServer { env ->
        val resp = env.get("/tasks?project=${project.value.uppercase()}", bearer = token)
        assertEquals(
            HttpStatusCode.OK,
            resp.status,
            "ProjectId.parseOrNull lower-cases, so two spellings of one uuid cannot key two backlogs",
        )
    }


    @Test
    fun theDetailCarriesDepsBothWaysSessionsActivityAndTheProjectPath() = withReadServer { env ->
        val resp = env.get("/tasks/${first.value}", bearer = token)
        assertEquals(HttpStatusCode.OK, resp.status)
        val detail = TRANSPORT_JSON.decodeFromString(TaskDetailDto.serializer(), resp.bodyAsText())

        assertEquals(first.value, detail.task.ref)
        assertEquals("write the parser", detail.task.title, "the tracker row is joined in")
        assertEquals("kotgent", detail.projectName)
        assertEquals("/Users/dev/kotgent", detail.projectPath, "the project's last-seen checkout")
        assertEquals(emptyList(), detail.dependsOn, "this one depends on nothing")
        assertEquals(
            listOf(third.value),
            detail.dependents,
            "and the reverse direction is carried too — the detail view edits both",
        )
        assertEquals(
            listOf(liveHolder.value, deadHolder.value),
            detail.sessions.map { it.id },
            "EVERY holder appears: linking is many-sessions-to-one-task and there is no exclusivity",
        )
        assertEquals(listOf("needs_approval", "resumable"), detail.sessions.map { it.state })
        assertEquals(
            listOf(true, false),
            detail.sessions.map { it.alive },
            "a dead holder still appears — the board shows who is on a task, not who is running",
        )
        assertEquals(listOf(true, false), detail.sessions.map { it.needsAttention })
        assertEquals(listOf(false, true), detail.sessions.map { it.archived })
        assertEquals(
            listOf("created", "comment"),
            detail.activity.map { it.kind },
            "the feed rides this response, oldest first, and deliberately not the events socket",
        )
        assertEquals("looks good", detail.activity.last().text)
    }

    @Test
    fun theDetailOfATaskWithNoTrackerRowRendersTheBareRef() = withReadServer { env ->
        val detail = TRANSPORT_JSON.decodeFromString(
            TaskDetailDto.serializer(),
            env.get("/tasks/local:9", bearer = token).bodyAsText(),
        )
        assertEquals("local:9", detail.task.title, "a null tracker row degrades to the ref, honestly")
        assertEquals("", detail.task.body)
    }

    @Test
    fun anUnknownRefIs404() = withReadServer { env ->
        val resp = env.get("/tasks/local:404", bearer = token)
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains("local:404"), "the message names the ref: ${resp.bodyAsText()}")
    }

    @Test
    fun aMalformedRefIs400AndNoBareWordCanEverParseAsOne() = withReadServer { env ->
        for (bad in listOf("next", "claim", "local", "local:", ":42", "local:4:2", "local:-42", "_x:1")) {
            val resp = env.get("/tasks/$bad", bearer = token)
            assertEquals(
                HttpStatusCode.BadRequest,
                resp.status,
                "'$bad' is not a '<tracker>:<key>' ref, so it is a bad request: ${resp.bodyAsText()}",
            )
        }
        assertEquals(emptyList(), env.journal(), "no malformed ref reached the store")
    }


    @Test
    fun projectsListsEveryKnownProject() = withReadServer { env ->
        val rows = TRANSPORT_JSON.decodeFromString(
            ListSerializer(ProjectDto.serializer()),
            env.get("/projects", bearer = token).bodyAsText(),
        )
        assertEquals(listOf(project.value, otherProject.value), rows.map { it.id })
        assertEquals(listOf("kotgent", "sidecar"), rows.map { it.name })
        assertEquals(
            listOf("/Users/dev/kotgent", null),
            rows.map { it.path },
            "a project the daemon has only ever seen through an import carries no path",
        )
    }


    @Test
    fun everyReadRouteRequiresACredential() = withReadServer { env ->
        for (path in listOf("/whoami", "/tasks?project=${project.value}", "/tasks/${first.value}", "/projects")) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                env.get(path, pane = pane.value).status,
                "$path is mounted inside the authenticated gate — a pane header is not a credential",
            )
        }
        assertEquals(emptyList(), env.journal(), "an unauthenticated request never reaches the store")
    }

    @Test
    fun aBrowserSessionCookieReachesEveryReadRoute() = withReadServer { env ->
        val cookie = env.signIn()
        val origin = "http://127.0.0.1:${env.port}"
        for (path in listOf("/tasks?project=${project.value}", "/tasks/${first.value}", "/projects")) {
            assertEquals(
                HttpStatusCode.OK,
                env.get(path, cookie = cookie, origin = origin).status,
                "$path answers the board, which has no Bearer — only the cookie the login flow set",
            )
        }
    }


    private fun backlog(): Map<TaskRef, BacklogEntry> = mapOf(
        first to entry(first, project, 1.0, TaskState.in_progress, blocked = false, rev = 7),
        second to entry(second, project, 2.0, TaskState.review, blocked = false, rev = 8),
        third to entry(third, project, 3.0, TaskState.todo, blocked = true, rev = 9),
        TaskRef("local:9") to entry(TaskRef("local:9"), otherProject, 1.0, TaskState.todo, false, rev = 10),
    )

    private fun entry(
        ref: TaskRef,
        project: ProjectId,
        position: Double,
        state: TaskState,
        blocked: Boolean,
        rev: Long,
    ) = BacklogEntry(
        ref = ref,
        project = project,
        position = position,
        state = state,
        blocked = blocked,
        createdAt = fixedNow,
        updatedAt = fixedNow,
        rev = rev,
    )

    private fun tracked(): Map<TaskRef, Task> = mapOf(
        first to Task(first, "write the parser", "the ref grammar", null, fixedNow),
        second to Task(second, "wire the routes", "", null, fixedNow),
        third to Task(third, "ship it", "", null, fixedNow),
    )

    private fun sessionRows(): Map<SessionId, SessionMeta> = mapOf(
        paneSession to session(paneSession, "the caller", SessionState.ready, second, createdAt = 1),
        liveHolder to session(liveHolder, "worker", SessionState.needs_approval, first, createdAt = 2),
        deadHolder to session(
            deadHolder, "an earlier attempt", SessionState.resumable, first, createdAt = 3, archived = true,
        ),
    )

    private fun session(
        id: SessionId,
        name: String,
        state: SessionState,
        taskRef: TaskRef?,
        createdAt: Long,
        archived: Boolean = false,
    ) = SessionMeta(
        id = id,
        name = name,
        agent = "claude",
        cwd = "/Users/dev/kotgent",
        tmuxSession = "kt-${id.value.take(8)}",
        state = state,
        createdAt = createdAt,
        updatedAt = createdAt,
        archived = archived,
        taskRef = taskRef,
        projectId = project,
    )

    private inner class Env(
        val port: Int,
        val client: HttpClient,
        private val tasks: FakeTaskStore,
    ) {
        suspend fun journal(): List<String> = tasks.journal()

        suspend fun get(
            path: String,
            bearer: String? = null,
            cookie: String? = null,
            origin: String? = null,
            pane: String? = null,
        ): HttpResponse = client.request("http://127.0.0.1:$port$API_PREFIX$path") {
            method = HttpMethod.Get
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (cookie != null) header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
            if (origin != null) header(HttpHeaders.Origin, origin)
            if (pane != null) header(TASK_PANE_HEADER, pane)
        }

        suspend fun signIn(): String {
            val ticket = TRANSPORT_JSON.decodeFromString(
                TicketResponse.serializer(),
                client.request("http://127.0.0.1:$port$AUTH_TICKET_PATH") {
                    method = HttpMethod.Post
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.bodyAsText(),
            ).ticket
            val exchanged = client.request("http://127.0.0.1:$port$AUTH_EXCHANGE_PATH") {
                method = HttpMethod.Post
                header(HttpHeaders.Origin, "http://127.0.0.1:$port")
                contentType(ContentType.Application.Json)
                setBody("""{"ticket":"$ticket"}""")
            }
            return parseServerSetCookieHeader(exchanged.headers[HttpHeaders.SetCookie]!!).value
        }
    }

    private fun withReadServer(
        sessions: Map<SessionId, SessionMeta> = sessionRows(),
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        withTimeout(30_000) {
            val tokens = TokenHolder(token)
            val tasks = FakeTaskStore(
                entries = backlog(),
                tracked = tracked(),
                edges = mapOf(third to listOf(first, second)),
                activity = mapOf(
                    first to listOf(
                        activityRow(1, first, ActivityKind.created, TaskService.BOARD_AUTHOR, null),
                        activityRow(2, first, ActivityKind.comment, paneSession.value, "looks good"),
                    ),
                ),
                projects = mapOf(
                    project to ProjectRecord(project, "kotgent", "/Users/dev/kotgent", fixedNow),
                    otherProject to ProjectRecord(otherProject, "sidecar", null, fixedNow),
                ),
            )
            val store = FakeEventStore(sessions)
            val routing = TaskRouting(
                tasks = tasks,
                service = TaskService(tasks, store, UnusedProjectFs, UnusedProjectFileWriter) { fixedNow },
                sessions = store,
                paneLookup = { if (it == pane) paneSession else null },
                json = TRANSPORT_JSON,
            )
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    authRoutes(tokens, TicketStore(now = { fixedNow }), null, TRANSPORT_JSON, now = { fixedNow })
                    authenticated(tokens::current, null) {
                        route(API_PREFIX) { taskReadRoutes(routing) }
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(Env(port, client, tasks))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    }

    private fun activityRow(id: Long, ref: TaskRef, kind: ActivityKind, author: String, text: String?) =
        TaskActivityEntry(id, ref, fixedNow, kind, author, text, null, null)

    private class FakeTaskStore(
        private val entries: Map<TaskRef, BacklogEntry>,
        private val tracked: Map<TaskRef, Task>,
        private val edges: Map<TaskRef, List<TaskRef>>,
        private val activity: Map<TaskRef, List<TaskActivityEntry>>,
        private val projects: Map<ProjectId, ProjectRecord>,
    ) : TaskStore {
        private val mutex = Mutex()
        private val calls = mutableListOf<String>()

        suspend fun journal(): List<String> = mutex.withLock { calls.toList() }

        private suspend fun <T> record(name: String, answer: T): T = mutex.withLock { calls += name; answer }

        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        override suspend fun entry(ref: TaskRef): BacklogEntry? = record("entry", entries[ref])

        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = record(
            "listBacklog",
            entries.values.filter { it.project == project }.sortedBy { it.position },
        )

        override suspend fun list(project: ProjectId): List<Task> = record(
            "list",
            tracked.values.filter { entries[it.ref]?.project == project },
        )

        override suspend fun get(ref: TaskRef): Task? = record("get", tracked[ref])

        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
            record("dependenciesOf", edges[ref].orEmpty())

        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = record(
            "dependentsOf",
            edges.filterValues { ref in it }.keys.sortedBy { it.value },
        )

        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> = record(
            "dependencyEdges",
            edges.filterKeys { entries[it]?.project == project },
        )

        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> =
            record("activity", activity[ref].orEmpty())

        override suspend fun listProjects(archived: Boolean): List<ProjectRecord> =
            record("listProjects", projects.values.filter { it.archived == archived }.sortedBy { it.name })

        override suspend fun project(id: ProjectId): ProjectRecord? = record("project", projects[id])

        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = readOnly("nextCandidate")
        override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
            readOnly("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = readOnly("update")
        override suspend fun delete(ref: TaskRef): Boolean = readOnly("delete")
        override suspend fun startIfTodo(ref: TaskRef): Boolean = readOnly("startIfTodo")
        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = readOnly("transition")

        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = readOnly("move")
        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) = readOnly("addDependency")
        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) = readOnly("removeDependency")
        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
            readOnly("comment")

        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = readOnly("appendActivity")

        override suspend fun upsertProject(id: ProjectId, name: String, path: String?) =
            readOnly("upsertProject")

        override suspend fun setProjectArchived(id: ProjectId, archived: Boolean) =
            readOnly("setProjectArchived")

        private fun readOnly(name: String): Nothing =
            error("the read routes must not call TaskStore.$name")
    }

    private class FakeEventStore(private val rows: Map<SessionId, SessionMeta>) : EventStore {
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = rows[sessionId]

        override suspend fun listSessions(): List<SessionMeta> = rows.values.sortedBy { it.createdAt }

        override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> =
            rows.values.filter { it.taskRef == taskRef }.sortedBy { it.createdAt }

        override suspend fun upsertSession(meta: SessionMeta) = unused("upsertSession")
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ) = unused("updateSessionState")

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) =
            unused("setArchived")

        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) = unused("setModel")
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: io.kotgent.core.ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = unused("setModelForProvider")

        override suspend fun markRead(sessionId: SessionId, seq: Seq) = unused("markRead")
        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) =
            unused("setTaskRef")

        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) =
            unused("setProjectId")

        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
            unused("append")

        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = unused("read")
        override suspend fun projectionOf(sessionId: SessionId): Projection = unused("projectionOf")
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = unused("subscribe")
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()

        private fun unused(name: String): Nothing =
            error("the read routes must not call EventStore.$name")
    }

    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("the read routes must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String? =
            error("the read routes must not touch the filesystem")

        override fun canonicalize(path: String): String? =
            error("the read routes must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile =
            error("the read routes must not write a project file")
    }
}
