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
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.task.ActivityKind
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.TaskState
import io.kotgent.task.UnknownProjectException
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

class TaskLinkRoutesTest {

    private companion object {
        val UNREACHED_BY_LINK_ROUTES = setOf(
            "list", "create", "update", "delete", "listBacklog", "transition", "move", "dependentsOf",
            "dependencyEdges", "addDependency", "removeDependency", "comment", "activity",
            "upsertProject", "setProjectArchived", "listProjects", "listAllProjects",
        )
    }

    private val token = "task-link-routes-master-token-0123456789"
    private val alpha = ProjectId.of("0F2C7A4E-1C3D-4F7A-9B21-6F0A2D9C1E34")
    private val beta = ProjectId.of("11111111-2222-4333-8444-555555555555")

    private val t1 = TaskRef("local:1")
    private val t2 = TaskRef("local:2")
    private val t3 = TaskRef("local:3")

    private val s1 = SessionId("sess-one")
    private val s2 = SessionId("sess-two")

    private val pane1 = PaneId("%11")
    private val pane2 = PaneId("%12")
    private val unknownPane = PaneId("%99")

    private val fixedNow = 1_770_000_000_000L


    @Test
    fun twoSessionsLinkOneTaskAndBothStillHoldIt() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.seedSession(s2, pane2, alpha)

        assertEquals(HttpStatusCode.OK, env.link(t1, pane = pane1).status)
        assertEquals(
            TaskState.in_progress,
            env.stateOf(t1),
            "the first link advanced the conditional todo → in_progress",
        )

        assertEquals(
            HttpStatusCode.OK,
            env.link(t1, pane = pane2).status,
            "a task already in progress simply gains a second session — kotgent enforces no exclusivity",
        )
        assertEquals(
            listOf(s1, s2),
            env.sessions.sessionsHoldingTask(t1).map { it.id },
            "both sessions hold the task, which is what the detail view renders",
        )
        assertEquals(
            TaskState.in_progress,
            env.stateOf(t1),
            "the second link left the state alone — startIfTodo answering false is normal, not a failure",
        )
        assertEquals(
            listOf(ActivityKind.linked, ActivityKind.linked),
            env.activityKinds(t1),
            "each link is attributed in the feed",
        )
    }

    @Test
    fun aLinkFromAnUnknownPaneIsRefusedRatherThanSilentlyAttributed() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)

        val fromNowhere = env.link(t1, pane = unknownPane)
        assertEquals(
            HttpStatusCode.BadRequest,
            fromNowhere.status,
            "a pane the registry does not know must be refused, never resolved to some other session",
        )
        assertTrue("--session" in fromNowhere.bodyAsText(), "the refusal names the fix")

        assertEquals(
            HttpStatusCode.BadRequest,
            env.link(t1).status,
            "no pane header and no body id is the same refusal",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            env.link(t1, body = """{"sessionId":"sess-missing"}""").status,
            "an explicit --session naming no row is refused too: resolveCallerSession does not check, so " +
                "the route must",
        )

        assertEquals(emptyList(), env.sessions.sessionsHoldingTask(t1), "no refusal wrote a link")
        assertEquals(TaskState.todo, env.stateOf(t1), "and none of them started the task")
    }

    @Test
    fun linkingAnUnknownTaskIs404AndWritesNothing() = withLinkServer { env ->
        env.seedSession(s1, pane1, alpha)

        val resp = env.link(TaskRef("local:404"), pane = pane1)
        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "deliberately creating a dangling sessions.task_ref is not the same as tolerating a racing one",
        )
        assertTrue("local:404" in resp.bodyAsText(), "the refusal names the ref")
        assertNull(env.linkOf(s1), "the session was left unlinked")
    }

    @Test
    fun aMalformedRefIsRefusedBeforeAnythingIsRead() = withLinkServer { env ->
        env.seedSession(s1, pane1, alpha)
        for (bad in listOf("notaref", "local:", ":42", "local:4..2")) {
            val resp = env.post("/tasks/$bad/link", pane = pane1)
            assertEquals(HttpStatusCode.BadRequest, resp.status, "'$bad' is not a ref")
            assertTrue("<tracker>:<key>" in resp.bodyAsText(), "the refusal says what a ref looks like")
        }
        assertNull(env.linkOf(s1))
    }


    @Test
    fun unlinkDropsOnlyTheCallersLinkAndLeavesTheTaskAlone() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.seedSession(s2, pane2, alpha)
        val _ = env.link(t1, pane = pane1)
        val _ = env.link(t1, pane = pane2)

        assertEquals(HttpStatusCode.OK, env.unlink(t1, pane = pane1).status)
        assertNull(env.linkOf(s1), "the caller's link is gone")
        assertEquals(t1, env.linkOf(s2), "the other session's link is untouched")
        assertEquals(
            TaskState.in_progress,
            env.stateOf(t1),
            "a session detaching says nothing about whether the work is finished",
        )
    }

    @Test
    fun unlinkingATaskTheSessionDoesNotHoldIsRefusedAndWritesNothing() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedTask(t2, alpha)
        env.seedSession(s1, pane1, alpha)
        val _ = env.link(t2, pane = pane1)

        val resp = env.unlink(t1, pane = pane1)
        assertEquals(
            HttpStatusCode.Conflict,
            resp.status,
            "TaskService.unlink clears whatever the session holds, so a mismatched ref must be refused " +
                "here or the path segment is decorative",
        )
        assertTrue("local:2" in resp.bodyAsText(), "the refusal names what the session actually holds")
        assertEquals(t2, env.linkOf(s1), "and the real link survived")
    }

    @Test
    fun unlinkingWithNothingLinkedIsIdempotent() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)

        assertEquals(
            HttpStatusCode.OK,
            env.unlink(t1, pane = pane1).status,
            "the caller asked for 'not linked to this', which is already true",
        )
        assertNull(env.linkOf(s1))
    }

    @Test
    fun aReleaseThatRacedANewerClaimIsRefusedRatherThanReportedAsAnUnlink() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedTask(t2, alpha)
        env.seedSession(s1, pane1, alpha)
        val _ = env.link(t1, pane = pane1)

        env.sessions.beforeConditionalClear = { env.sessions.setTaskRef(s1, t2) }

        val resp = env.unlink(t1, pane = pane1)
        assertEquals(
            HttpStatusCode.Conflict,
            resp.status,
            "a clear that wrote nothing must not be reported as an unlink",
        )
        assertTrue("local:1" in resp.bodyAsText(), "the refusal names the ref that was not cleared")
        assertEquals(t2, env.linkOf(s1), "the newer link survives the release keyed by the older ref")
        assertEquals(
            listOf(ActivityKind.linked),
            env.activityKinds(t1),
            "and no `unlinked` row is written for a release that wrote nothing",
        )
    }

    @Test
    fun unlinkStillClearsALinkWhoseTaskIsGone() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        val _ = env.link(t1, pane = pane1)
        env.tasks.forgetTask(t1)

        assertEquals(
            HttpStatusCode.OK,
            env.unlink(t1, pane = pane1).status,
            "a session left holding a deleted task's ref is exactly who needs to clear it",
        )
        assertNull(env.linkOf(s1))
    }


    @Test
    fun nextTakesTheFirstEligibleTaskInTheSessionsOwnProject() = withLinkServer { env ->
        env.seedTask(t2, alpha, position = 2.0)
        env.seedTask(t1, alpha, position = 1.0)
        env.seedTask(t3, beta, position = 1.0)
        env.seedSession(s1, pane1, alpha)

        val taken = env.nextTask(pane = pane1)
        assertEquals(t1.value, taken?.ref, "rank order decides, and the project comes from the session")
        assertEquals(TaskState.in_progress.name, taken?.state, "the answer is re-read after the transition")
        assertEquals(t1, env.linkOf(s1))
    }

    @Test
    fun nextWithNothingEligibleIsNotAnErrorStatus() = withLinkServer { env ->
        env.seedTask(t1, alpha, state = TaskState.done)
        env.seedSession(s1, pane1, alpha)

        val resp = env.post("/tasks/next", pane = pane1)
        assertEquals(
            HttpStatusCode.OK,
            resp.status,
            "a null task is the ONLY 'nothing eligible' signal; an error status could not be told apart " +
                "from a real failure, and the CLI maps this to exit 3",
        )
        assertNull(
            TRANSPORT_JSON.decodeFromString(NextTaskResponse.serializer(), resp.bodyAsText()).task,
            "and the body says so",
        )
        assertNull(env.linkOf(s1), "nothing eligible means nothing linked")
    }

    @Test
    fun nextUnderContentionHandsTwoSessionsTwoDifferentTasks() = withLinkServer { env ->
        env.seedTask(t1, alpha, position = 1.0)
        env.seedTask(t2, alpha, position = 2.0)
        env.seedSession(s1, pane1, alpha)
        env.seedSession(s2, pane2, alpha)

        val taken = listOf(pane1, pane2)
            .map { pane -> env.scope.async { env.nextTask(pane = pane) } }
            .awaitAll()
        val first = assertNotNull(taken[0], "the first caller got a task").ref
        val second = assertNotNull(taken[1], "so did the second").ref

        assertTrue(
            first != second,
            "the conditional todo → in_progress is what stops two agents taking one task; got " +
                "$first and $second",
        )
        assertEquals(
            setOf(t1, t2),
            setOfNotNull(env.linkOf(s1), env.linkOf(s2)),
            "both sessions ended up linked, one task each",
        )
    }

    @Test
    fun nextAcceptsAnExplicitProjectAndRefusesWhenThereIsNoneAtAll() = withLinkServer { env ->
        env.seedTask(t3, beta)
        env.seedSession(s1, pane1, project = null)

        val noProject = env.post("/tasks/next", pane = pane1)
        assertEquals(
            HttpStatusCode.BadRequest,
            noProject.status,
            "a session outside any project cannot default one",
        )
        assertTrue("--project" in noProject.bodyAsText(), "the refusal names the fix")

        assertEquals(
            HttpStatusCode.BadRequest,
            env.post("/tasks/next", pane = pane1, body = """{"project":"not-a-uuid"}""").status,
            "a malformed project uuid is a refusal, not an empty backlog",
        )

        val taken = env.nextTask(pane = pane1, body = """{"project":"${beta.value}"}""")
        assertEquals(t3.value, taken?.ref, "an explicit project overrides the session's")
        assertEquals(t3, env.linkOf(s1))
    }

    @Test
    fun nextForAProjectTheDaemonHasNeverSeenIs404RatherThanNothingEligible() = withLinkServer { env ->
        env.seedSession(s1, pane1, alpha)
        env.seedProject(alpha)

        val resp = env.post("/tasks/next", pane = pane1, body = """{"project":"${beta.value}"}""")

        assertEquals(HttpStatusCode.NotFound, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(beta.value in resp.bodyAsText(), "the body names the project it could not find")
        assertNull(env.linkOf(s1), "a refused pickup links nothing")
    }

    @Test
    fun nextIs404WhenTheSessionsOwnProjectHasNoRow() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.tasks.forgetProject(alpha)

        val resp = env.post("/tasks/next", pane = pane1)

        assertEquals(HttpStatusCode.NotFound, resp.status, "answered ${resp.bodyAsText()}")
        assertNull(env.linkOf(s1), "and nothing was taken")
        assertEquals(
            TaskState.todo,
            env.stateOf(t1),
            "the refusal precedes linkNext, so no candidate was started either",
        )
    }

    @Test
    fun nextRefusesADeletedProjectBecauseItStartsTheCardItHandsOut() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.tasks.seedArchived(alpha, archived = true)

        val fromTheSession = env.post("/tasks/next", pane = pane1)
        assertEquals(
            HttpStatusCode.NotFound,
            fromTheSession.status,
            "a deleted project is not a work source: linkNext would start a card on a board that no " +
                "longer lists it (answered ${fromTheSession.bodyAsText()})",
        )
        assertEquals(
            HttpStatusCode.NotFound,
            env.post("/tasks/next", pane = pane1, body = """{"project":"${alpha.value}"}""").status,
            "naming it explicitly is the same answer an unknown uuid gets",
        )
        assertNull(env.linkOf(s1), "nothing was linked")
        assertEquals(
            TaskState.todo,
            env.stateOf(t1),
            "and the refusal precedes linkNext, so no card moved to in_progress",
        )
        assertTrue(
            env.activityKinds(t1).isEmpty(),
            "…and no activity row records work that never started",
        )
    }

    @Test
    fun aDeleteLandingBetweenNextsCheckAndItsSelectionIsRefused() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)

        // Interleave deletion after the route check and before candidate selection.
        env.tasks.beforeNextCandidate = {
            env.tasks.seedArchived(alpha, archived = true)
            env.tasks.beforeNextCandidate = null
        }

        val resp = env.post("/tasks/next", pane = pane1)
        val body = resp.bodyAsText()

        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "a delete after the route's check is a refused project, not an empty live backlog " +
                "(answered $body)",
        )
        assertEquals(
            UnknownProjectException(alpha).message,
            body,
            "the race uses the same refusal as a project deleted before the request",
        )
        assertNull(env.linkOf(s1), "nothing was linked")
        assertEquals(
            TaskState.todo,
            env.stateOf(t1),
            "and no card moved to in_progress on a board that no longer lists its project",
        )
        assertTrue(env.activityKinds(t1).isEmpty(), "…and nothing claims work that never started")
    }

    @Test
    fun aDeleteLandingBetweenNextsSelectionAndItsStartIsRefused() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)

        // Interleave deletion after candidate selection and before its transition.
        env.tasks.beforeStartIfTodo = {
            env.tasks.seedArchived(alpha, archived = true)
            env.tasks.beforeStartIfTodo = null
        }

        val resp = env.post("/tasks/next", pane = pane1)
        val body = resp.bodyAsText()

        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "a delete after selection is a refused project, not an empty live backlog (answered $body)",
        )
        assertEquals(
            UnknownProjectException(alpha).message,
            body,
            "the race uses the same refusal as a project deleted before the request",
        )
        assertNull(env.linkOf(s1), "nothing was linked")
        assertEquals(
            TaskState.todo,
            env.stateOf(t1),
            "and no card moved to in_progress on a board that no longer lists its project",
        )
        assertTrue(env.activityKinds(t1).isEmpty(), "…and nothing claims work that never started")
    }

    @Test
    fun linkStaysOpenForADeletedProjectsCardBecauseItNamesOneThatAlreadyExists() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.tasks.seedArchived(alpha, archived = true)

        assertEquals(
            HttpStatusCode.OK,
            env.link(t1, pane = pane1).status,
            "the tombstone closes the project as a SOURCE of work — a card the caller names by ref is " +
                "reachable exactly as `task show` and `task done` still are",
        )
        assertEquals(t1, env.linkOf(s1))
        assertEquals(
            TaskState.in_progress,
            env.stateOf(t1),
            "claiming a `todo` card starts it here as it would in a live project",
        )
        assertEquals(listOf(ActivityKind.linked), env.activityKinds(t1), "…and the feed records it")
    }

    @Test
    fun theNextLiteralIsNeverShadowedByTheRefPattern() = withLinkServer { env ->
        env.seedSession(s1, pane1, alpha)
        env.seedProject(alpha)
        assertNull(
            TaskRef.parseOrNull("next"),
            "the mandatory ':' is what makes a bare literal unshadowable — this is the whole guarantee",
        )
        val resp = env.post("/tasks/next", pane = pane1)
        assertEquals(
            HttpStatusCode.OK,
            resp.status,
            "/tasks/next reaches the next handler with /tasks/{ref}/link mounted beside it",
        )
        assertEquals(ContentType.Application.Json, resp.contentType()?.withoutParameters())
    }


    @Test
    fun startingASessionWithATaskRefAnswersARowAlreadyCarryingIt() = withLinkServer { env ->
        env.seedTask(t1, alpha)

        val resp = env.post(
            "/sessions",
            body = """{"agent":"claude","cwd":"/tmp/work","taskRef":"${t1.value}"}""",
        )
        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertEquals(
            t1.value,
            dto.taskRef,
            "start --task is ONE request: the answer already carries the link, so a client merging this " +
                "DTO newest-rev-wins does not need a second round trip to see it",
        )
        assertEquals(t1, env.linkOf(SessionId(dto.id)), "and the row really holds it")
        assertEquals(TaskState.in_progress, env.stateOf(t1), "the launch also started the task")
    }

    @Test
    fun startingASessionWithATaskRefAgainstATaskLessServerIs400AndStartsNothing() =
        withLinkServer(withTaskLayer = false) { env ->
            val resp = env.post(
                "/sessions",
                body = """{"agent":"claude","cwd":"/tmp/work","taskRef":"${t1.value}"}""",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                resp.status,
                "a daemon with no task layer refuses the link rather than starting the session and " +
                    "dropping it silently",
            )
            assertEquals(emptyList(), env.allSessions(), "and no session was started")
            assertEquals(emptyList(), env.tmux.newSessionCommands, "not even a tmux side effect")
        }

    @Test
    fun startingASessionWithAMalformedTaskRefIs400BeforeTheLaunch() = withLinkServer { env ->
        val resp = env.post("/sessions", body = """{"agent":"claude","cwd":"/tmp/work","taskRef":"nope"}""")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue("<tracker>:<key>" in resp.bodyAsText(), "the refusal says what a ref looks like")
        assertEquals(emptyList(), env.tmux.newSessionCommands, "refused before any tmux side effect")
    }

    @Test
    fun startingASessionWithATaskRefNamingNoTaskIs400BeforeTheLaunch() = withLinkServer { env ->
        env.seedTask(t1, alpha)

        val resp = env.post("/sessions", body = """{"agent":"claude","cwd":"/tmp/work","taskRef":"local:404"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue("local:404" in resp.bodyAsText(), "the refusal names the ref it could not find")
        assertEquals(emptyList(), env.allSessions(), "no session row was written")
        assertEquals(emptyList(), env.tmux.newSessionCommands, "and no tmux side effect happened")
    }

    @Test
    fun startingASessionWithoutATaskRefIsUnchanged() = withLinkServer { env ->
        val resp = env.post("/sessions", body = """{"agent":"claude","cwd":"/tmp/work"}""")
        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertNull(dto.taskRef, "the ordinary start path did not grow a link")
        assertEquals(1, env.tmux.newSessionCommands.size)
    }


    @Test
    fun everyLinkRouteIsInsideTheAuthenticatedGate() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        for (path in listOf("/tasks/${t1.value}/link", "/tasks/${t1.value}/unlink", "/tasks/next")) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                env.post(path, pane = pane1, bearer = null).status,
                "$path is mounted inside authenticated { }",
            )
        }
        assertNull(env.linkOf(s1), "an unauthenticated request wrote nothing")
    }


    private inner class Env(
        val port: Int,
        val client: HttpClient,
        val tasks: FakeTaskStore,
        val sessions: FakeEventStore,
        val registry: PaneRegistry,
        val tmux: FakeTmux,
        val scope: CoroutineScope,
    ) {
        suspend fun seedTask(
            ref: TaskRef,
            project: ProjectId,
            state: TaskState = TaskState.todo,
            position: Double = 1.0,
        ) {
            tasks.seedProject(project, project.value.take(8), "/repo")
            tasks.seedTask(ref, project, "title of ${ref.value}", state = state, position = position)
        }

        fun seedProject(project: ProjectId) = tasks.seedProject(project, project.value.take(8), "/repo")

        suspend fun stateOf(ref: TaskRef): TaskState? = tasks.snapshotEntries()[ref]?.state

        suspend fun activityKinds(ref: TaskRef): List<ActivityKind> =
            tasks.snapshotActivity().filter { it.ref == ref }.map { it.kind }

        suspend fun linkOf(id: SessionId): TaskRef? = sessions.snapshotSessions()[id]?.taskRef

        suspend fun allSessions(): List<SessionMeta> = sessions.snapshotSessions().values.toList()

        suspend fun seedSession(id: SessionId, pane: PaneId, project: ProjectId?) {
            sessions.upsertSession(
                SessionMeta(
                    id = id,
                    name = id.value,
                    agent = "claude",
                    cwd = "/tmp/work",
                    tmuxSession = "kt-${id.value}",
                    paneId = pane,
                    state = SessionState.running,
                    stateSource = EventSource.system,
                    createdAt = fixedNow + sessions.snapshotSessions().size,
                    updatedAt = fixedNow,
                    projectId = project,
                ),
            )
            registry.register(pane, id)
        }

        suspend fun link(ref: TaskRef, pane: PaneId? = null, body: String? = null) =
            post("/tasks/${ref.value}/link", pane, body)

        suspend fun unlink(ref: TaskRef, pane: PaneId? = null, body: String? = null) =
            post("/tasks/${ref.value}/unlink", pane, body)

        suspend fun nextTask(pane: PaneId? = null, body: String? = null): BacklogEntryDto? {
            val resp = post("/tasks/next", pane, body)
            assertEquals(HttpStatusCode.OK, resp.status, "next answered ${resp.bodyAsText()}")
            return TRANSPORT_JSON.decodeFromString(NextTaskResponse.serializer(), resp.bodyAsText()).task
        }

        suspend fun post(
            path: String,
            pane: PaneId? = null,
            body: String? = null,
            bearer: String? = token,
        ): HttpResponse = client.request("http://127.0.0.1:$port$API_PREFIX$path") {
            method = HttpMethod.Post
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (pane != null) header(TASK_PANE_HEADER, pane.value)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    private fun withLinkServer(
        withTaskLayer: Boolean = true,
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        withTimeout(60.seconds) {
            val tasks = FakeTaskStore(now = { fixedNow }).also {
                it.interceptor = ForbiddingInterceptor(UNREACHED_BY_LINK_ROUTES) { store, method ->
                    "the link routes must not call $store.$method"
                }
            }
            val store = FakeEventStore(now = { fixedNow })
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                tmux,
                store,
                registry,
                agentFactoryOf(mapOf("claude" to { cwd: String -> CannedAdapter(cwd) })),
                ProviderIdCapture(store, scope),
                { _, _, _ -> false },
                { _, _ -> null },
                setOf("claude"),
                now = { fixedNow },
            )
            val service = TaskService(
                tasks = tasks,
                sessions = store,
                projectFs = UnusedProjectFs,
                projectFiles = UnusedProjectFileWriter,
            )
            val routing = TaskRouting(
                tasks = tasks,
                service = service,
                sessions = store,
                paneLookup = registry::lookup,
            )
            val tokens = TokenHolder(token)
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    val _ = authenticated(tokens::current) {
                        route(API_PREFIX) {
                            controlRoutes(
                                manager,
                                store,
                                { _, _ -> true },
                                "test-version",
                                if (withTaskLayer) service else null,
                                TRANSPORT_JSON,
                                if (withTaskLayer) tasks else null,
                            )
                            if (withTaskLayer) taskLinkRoutes(routing)
                        }
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(Env(port, client, tasks, store, registry, tmux, scope))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
                scope.cancel()
            }
        }
    }

    private class CannedAdapter(private val cwd: String) : AgentAdapter {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
            is LaunchMode.New -> LaunchSpec(
                listOf("claude"),
                emptyMap(),
                cwd,
                ProviderSessionId("00000000-0000-4000-8000-000000000000"),
            )
            is LaunchMode.Resume -> LaunchSpec(listOf("claude", "--resume"), emptyMap(), cwd, null)
        }
    }

    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("the link routes must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String =
            error("the link routes must not touch the filesystem")
        override fun canonicalize(path: String): String =
            error("the link routes must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String) =
            error("the link routes must not write a project file")
    }
}
