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
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.Seq
import io.kotgent.core.TaskRef
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
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
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The three link endpoints ([taskLinkRoutes]) and `POST /sessions`' optional `taskRef` — plan Task 15.
 *
 * ## What these tests are built around
 * The design's headline property is that **there is no exclusivity**, so most of the interesting
 * assertions are about what does NOT happen: a second session linking the same task is not refused, a
 * link to a task already `in_progress` does not change its state, an `unlink` does not close the task,
 * and "nothing eligible" is not an error. A test that only checked "the link was made" would pass
 * against an implementation that also enforced a claim, which is why every link case asserts the OTHER
 * session's link is still there afterwards.
 *
 * The one conditional write in the whole design is [TaskStore.startIfTodo], and
 * [nextUnderContentionHandsTwoSessionsTwoDifferentTasks] is what makes it meaningful:
 * [TaskRouting.service] is the concrete [TaskService], not an interface, so the service under test is
 * the real one and the contention it resolves is its own loop, not a stub's.
 *
 * ## Why the fakes have a [Mutex]
 * The CIO server runs handlers on its own engine threads while the test thread reads the state they
 * wrote, so every fake store is guarded by a coroutine lock — the happens-before, not the exclusion, is
 * the point. Every body is bounded by [withTimeout] as an anti-hang tripwire; the contention case needs
 * it most, because a lost race there is a hang rather than a wrong answer.
 */
class TaskLinkRoutesTest {

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

    // --- link ---------------------------------------------------------------------------------------

    @Test
    fun twoSessionsLinkOneTaskAndBothStillHoldIt() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.seedSession(s2, pane2, alpha)

        assertEquals(HttpStatusCode.OK, env.link(t1, pane = pane1).status)
        assertEquals(
            TaskState.in_progress,
            env.tasks.stateOf(t1),
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
            env.tasks.stateOf(t1),
            "the second link left the state alone — startIfTodo answering false is normal, not a failure",
        )
        assertEquals(
            listOf(ActivityKind.linked, ActivityKind.linked),
            env.tasks.activityKinds(t1),
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
        assertEquals(TaskState.todo, env.tasks.stateOf(t1), "and none of them started the task")
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
        assertNull(env.sessions.linkOf(s1), "the session was left unlinked")
    }

    @Test
    fun aMalformedRefIsRefusedBeforeAnythingIsRead() = withLinkServer { env ->
        env.seedSession(s1, pane1, alpha)
        for (bad in listOf("notaref", "local:", ":42", "local:4..2")) {
            val resp = env.post("/tasks/$bad/link", pane = pane1)
            assertEquals(HttpStatusCode.BadRequest, resp.status, "'$bad' is not a ref")
            assertTrue("<tracker>:<key>" in resp.bodyAsText(), "the refusal says what a ref looks like")
        }
        assertNull(env.sessions.linkOf(s1))
    }

    // --- unlink -------------------------------------------------------------------------------------

    @Test
    fun unlinkDropsOnlyTheCallersLinkAndLeavesTheTaskAlone() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.seedSession(s2, pane2, alpha)
        env.link(t1, pane = pane1)
        env.link(t1, pane = pane2)

        assertEquals(HttpStatusCode.OK, env.unlink(t1, pane = pane1).status)
        assertNull(env.sessions.linkOf(s1), "the caller's link is gone")
        assertEquals(t1, env.sessions.linkOf(s2), "the other session's link is untouched")
        assertEquals(
            TaskState.in_progress,
            env.tasks.stateOf(t1),
            "a session detaching says nothing about whether the work is finished",
        )
    }

    @Test
    fun unlinkingATaskTheSessionDoesNotHoldIsRefusedAndWritesNothing() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedTask(t2, alpha)
        env.seedSession(s1, pane1, alpha)
        env.link(t2, pane = pane1)

        val resp = env.unlink(t1, pane = pane1)
        assertEquals(
            HttpStatusCode.Conflict,
            resp.status,
            "TaskService.unlink clears whatever the session holds, so a mismatched ref must be refused " +
                "here or the path segment is decorative",
        )
        assertTrue("local:2" in resp.bodyAsText(), "the refusal names what the session actually holds")
        assertEquals(t2, env.sessions.linkOf(s1), "and the real link survived")
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
        assertNull(env.sessions.linkOf(s1))
    }

    @Test
    fun unlinkStillClearsALinkWhoseTaskIsGone() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.link(t1, pane = pane1)
        env.tasks.forget(t1)

        assertEquals(
            HttpStatusCode.OK,
            env.unlink(t1, pane = pane1).status,
            "a session left holding a deleted task's ref is exactly who needs to clear it",
        )
        assertNull(env.sessions.linkOf(s1))
    }

    // --- next ---------------------------------------------------------------------------------------

    @Test
    fun nextTakesTheFirstEligibleTaskInTheSessionsOwnProject() = withLinkServer { env ->
        env.seedTask(t2, alpha, position = 2.0)
        env.seedTask(t1, alpha, position = 1.0)
        env.seedTask(t3, beta, position = 1.0)
        env.seedSession(s1, pane1, alpha)

        val taken = env.nextTask(pane = pane1)
        assertEquals(t1.value, taken?.ref, "rank order decides, and the project comes from the session")
        assertEquals(TaskState.in_progress.name, taken?.state, "the answer is re-read after the transition")
        assertEquals(t1, env.sessions.linkOf(s1))
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
        assertNull(env.sessions.linkOf(s1), "nothing eligible means nothing linked")
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
            setOfNotNull(env.sessions.linkOf(s1), env.sessions.linkOf(s2)),
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
        assertEquals(t3, env.sessions.linkOf(s1))
    }

    /**
     * A project the daemon has never seen is a `404`, NOT "nothing eligible".
     *
     * This is the one answer `next` must never get wrong: a null task is the single value the CLI maps to
     * exit `3`, and the agent loop stops on that code. Reporting an empty backlog for a mistyped or stale
     * `--project` would retire an agent silently and forever, while `kotgent task list` on the same uuid
     * prints a clean `404`. The assertion is the STATUS, because a body nobody parses cannot stop a loop.
     */
    @Test
    fun nextForAProjectTheDaemonHasNeverSeenIs404RatherThanNothingEligible() = withLinkServer { env ->
        env.seedSession(s1, pane1, alpha)
        env.seedProject(alpha)

        val resp = env.post("/tasks/next", pane = pane1, body = """{"project":"${beta.value}"}""")

        assertEquals(HttpStatusCode.NotFound, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(beta.value in resp.bodyAsText(), "the body names the project it could not find")
        assertNull(env.sessions.linkOf(s1), "a refused pickup links nothing")
    }

    /**
     * The same check on the session-derived project, which is the shape an agent inside a pane actually
     * sends (`kotgent task next` with no argument). `GET /tasks` validates both spellings of the question;
     * validating only the explicit one would leave the ref-less loop — the common case — unguarded.
     */
    @Test
    fun nextIs404WhenTheSessionsOwnProjectHasNoRow() = withLinkServer { env ->
        env.seedTask(t1, alpha)
        env.seedSession(s1, pane1, alpha)
        env.tasks.forgetProject(alpha)

        val resp = env.post("/tasks/next", pane = pane1)

        assertEquals(HttpStatusCode.NotFound, resp.status, "answered ${resp.bodyAsText()}")
        assertNull(env.sessions.linkOf(s1), "and nothing was taken")
        assertEquals(
            TaskState.todo,
            env.tasks.stateOf(t1),
            "the refusal precedes linkNext, so no candidate was started either",
        )
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

    // --- POST /sessions with a taskRef ---------------------------------------------------------------

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
        assertEquals(t1, env.sessions.linkOf(SessionId(dto.id)), "and the row really holds it")
        assertEquals(TaskState.in_progress, env.tasks.stateOf(t1), "the launch also started the task")
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
            assertEquals(emptyList(), env.sessions.all(), "and no session was started")
            assertEquals(emptyList(), env.tmux.newSessionCommands, "not even a tmux side effect")
        }

    @Test
    fun startingASessionWithAMalformedTaskRefIs400BeforeTheLaunch() = withLinkServer { env ->
        val resp = env.post("/sessions", body = """{"agent":"claude","cwd":"/tmp/work","taskRef":"nope"}""")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue("<tracker>:<key>" in resp.bodyAsText(), "the refusal says what a ref looks like")
        assertEquals(emptyList(), env.tmux.newSessionCommands, "refused before any tmux side effect")
    }

    /**
     * A `taskRef` naming no task is refused BEFORE the launch, exactly as `POST /tasks/{ref}/link` refuses
     * it — the two halves of one feature used to disagree.
     *
     * `sessions.task_ref` being "a reference, not a foreign key" tolerates a delete that races an
     * in-flight link, a window microseconds wide; it does not license manufacturing one from a request
     * that could have looked. Without the check `kotgent start --task local:99` (a typo, or a task closed a
     * second earlier) really launches an agent and pins an unknown-task badge on it until the next daemon
     * restart. The tmux assertion is the load-bearing half: a refusal AFTER `start()` would be the very
     * outcome "one request" exists to prevent.
     */
    @Test
    fun startingASessionWithATaskRefNamingNoTaskIs400BeforeTheLaunch() = withLinkServer { env ->
        env.seedTask(t1, alpha)

        val resp = env.post("/sessions", body = """{"agent":"claude","cwd":"/tmp/work","taskRef":"local:404"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue("local:404" in resp.bodyAsText(), "the refusal names the ref it could not find")
        assertEquals(emptyList(), env.sessions.all(), "no session row was written")
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

    // --- the gate -----------------------------------------------------------------------------------

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
        assertNull(env.sessions.linkOf(s1), "an unauthenticated request wrote nothing")
    }

    // --- harness ------------------------------------------------------------------------------------

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
        ) = tasks.seed(ref, project, state, position)

        suspend fun seedProject(project: ProjectId) = tasks.seedProject(project)

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
                    createdAt = fixedNow + sessions.count(),
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
        withTimeout(60_000) {
            val tasks = FakeTaskStore()
            val store = FakeEventStore()
            val registry = PaneRegistry()
            val tmux = FakeTmux()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                tmux,
                store,
                registry,
                agentFactoryOf(mapOf("claude" to { cwd: String -> CannedAdapter(cwd) })),
                ProviderIdCapture(store, scope),
                VendorStoreProbe { _, _, _ -> false },
                VendorSessionLocator { _, _ -> null },
                setOf("claude"),
                now = { fixedNow },
            )
            // The concrete TaskService over the two fake stores: TaskRouting.service is not an interface,
            // so the contention this test drives is resolved by the real loop, not by a stub.
            val service = TaskService(
                tasks = tasks,
                sessions = store,
                projectFs = UnusedProjectFs,
                projectFiles = UnusedProjectFileWriter,
                now = { fixedNow },
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
                    authenticated(tokens::current) {
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

    /** A launch spec with a preallocated provider id — the claude shape, so no background capture runs. */
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

    /**
     * An in-memory [TaskStore] covering the members these routes and [TaskService] reach; every other
     * one throws, so a route that grew a call this test never modelled fails loudly instead of reading
     * an empty answer as a result.
     */
    private class FakeTaskStore : TaskStore {
        private val mutex = Mutex()
        private val entries = LinkedHashMap<TaskRef, BacklogEntry>()
        private val projects = LinkedHashMap<ProjectId, ProjectRecord>()
        private val feed = mutableListOf<TaskActivityEntry>()
        private var rev = 0L
        private var activityId = 0L

        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        suspend fun seed(ref: TaskRef, project: ProjectId, state: TaskState, position: Double) =
            mutex.withLock {
                entries[ref] = BacklogEntry(ref, project, position, state, false, 1_000L, 1_000L, ++rev)
                // Every path that reads or creates a `.kotgent.json` upserts the row, so a project with a
                // backlog always has one; a test that wants the opposite seeds the task and forgets the
                // project deliberately.
                projects.getOrPut(project) { ProjectRecord(project, project.value.take(8), "/repo", 0L) }
                Unit
            }

        /** Register a project that has no backlog yet — what `GET /projects` would list. */
        suspend fun seedProject(project: ProjectId) = mutex.withLock {
            projects[project] = ProjectRecord(project, project.value.take(8), "/repo", 0L)
            Unit
        }

        /** Drop the `projects` row while leaving the backlog — the stale/mistyped uuid case. */
        suspend fun forgetProject(project: ProjectId) = mutex.withLock { projects.remove(project); Unit }

        /** Delete a task behind the routes' back — the dangling-ref case `unlink` must still clear. */
        suspend fun forget(ref: TaskRef) = mutex.withLock { entries.remove(ref); Unit }

        suspend fun stateOf(ref: TaskRef): TaskState? = mutex.withLock { entries[ref]?.state }

        suspend fun activityKinds(ref: TaskRef): List<ActivityKind> =
            mutex.withLock { feed.filter { it.ref == ref }.map { it.kind } }

        override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { entries[ref] }

        override suspend fun get(ref: TaskRef): Task? = mutex.withLock {
            entries[ref]?.let { Task(ref, "title of ${ref.value}", "", null, it.updatedAt) }
        }

        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = mutex.withLock {
            entries.values
                .filter { it.project == project && it.state == TaskState.todo && !it.blocked }
                .minByOrNull { it.position }
        }

        override suspend fun startIfTodo(ref: TaskRef): Boolean = mutex.withLock {
            val existing = entries[ref]
            if (existing == null || existing.state != TaskState.todo) {
                false
            } else {
                entries[ref] = existing.copy(state = TaskState.in_progress, rev = ++rev)
                true
            }
        }

        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = mutex.withLock {
            if (ref !in entries) {
                null
            } else {
                TaskActivityEntry(++activityId, ref, 0L, kind, author, text, fromState, toState)
                    .also { feed += it }
            }
        }

        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> = emptyList()

        override suspend fun list(project: ProjectId): List<Task> = unused("list")
        override suspend fun create(project: ProjectId, title: String, body: String): Task = unused("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")
        override suspend fun delete(ref: TaskRef): Boolean = unused("delete")
        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = unused("listBacklog")
        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = unused("transition")
        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = unused("move")
        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = unused("dependentsOf")
        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
            unused("dependencyEdges")
        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) = unused("addDependency")
        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) = unused("removeDependency")
        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
            unused("comment")
        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = unused("activity")
        override suspend fun upsertProject(id: ProjectId, name: String, path: String?) = unused("upsertProject")
        override suspend fun listProjects(): List<ProjectRecord> = unused("listProjects")
        override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock { projects[id] }

        private fun unused(name: String): Nothing = error("the link routes must not call TaskStore.$name")
    }

    /**
     * An in-memory [EventStore] modelling the session row, its task link and just enough of the log for
     * [SessionManager.start] to run. It overrides both task-link members the routes reach — the
     * interface's defaults throw precisely so a fake that forgot one cannot make a green test out of a
     * link that persisted nothing.
     */
    private class FakeEventStore : EventStore {
        private val mutex = Mutex()
        private val rows = LinkedHashMap<SessionId, SessionMeta>()
        private var rev = 0L
        private var seq = 0L

        suspend fun all(): List<SessionMeta> = mutex.withLock { rows.values.toList() }

        suspend fun count(): Int = mutex.withLock { rows.size }

        suspend fun linkOf(id: SessionId): TaskRef? = mutex.withLock { rows[id]?.taskRef }

        override suspend fun upsertSession(meta: SessionMeta) = mutex.withLock {
            val existing = rows[meta.id]
            rows[meta.id] = meta.copy(
                // The real store's ON CONFLICT COALESCE: a snapshot read before a link must not clear it.
                taskRef = meta.taskRef ?: existing?.taskRef,
                projectId = meta.projectId ?: existing?.projectId,
                rev = ++rev,
            )
        }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) =
            mutex.withLock {
                val row = rows[sessionId] ?: return@withLock
                rows[sessionId] = row.copy(taskRef = taskRef, updatedAt = updatedAt, rev = ++rev)
            }

        override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> = mutex.withLock {
            rows.values.filter { it.taskRef == taskRef }.sortedBy { it.createdAt }
        }

        override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock { rows[sessionId] }

        override suspend fun listSessions(): List<SessionMeta> = mutex.withLock {
            rows.values.sortedBy { it.createdAt }
        }

        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
            mutex.withLock {
                val next = Seq(++seq)
                val row = rows[sessionId]
                if (row != null) {
                    rows[sessionId] = row.copy(
                        providerSessionId = (event as? AgentEvent.SessionBound)?.providerSessionId
                            ?: row.providerSessionId,
                        lastSeq = next,
                        rev = ++rev,
                    )
                }
                next
            }

        override suspend fun projectionOf(sessionId: SessionId): Projection = mutex.withLock {
            val row = rows[sessionId]
            Projection(
                state = row?.state ?: SessionState.running,
                pendingApprovals = 0,
                lastSeq = row?.lastSeq ?: Seq(0),
                providerSessionId = row?.providerSessionId,
                stopRequested = false,
            )
        }

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
            providerSessionId: ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = unused("setModelForProvider")
        override suspend fun markRead(sessionId: SessionId, seq: Seq) = unused("markRead")
        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) =
            unused("setProjectId")
        /** [SessionManager]'s id allocator asks whether a candidate id already owns a log. None does. */
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()

        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = unused("subscribe")
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()

        private fun unused(name: String): Nothing = error("this test must not call EventStore.$name")
    }

    /** [TaskService] carries these for the WRITE routes and never calls either itself. */
    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("the link routes must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String? =
            error("the link routes must not touch the filesystem")
        override fun canonicalize(path: String): String? =
            error("the link routes must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String) =
            error("the link routes must not write a project file")
    }
}
