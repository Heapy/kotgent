package io.kotgent.cli

import io.kotgent.core.PaneId
import io.kotgent.core.TaskRef
import io.kotgent.task.MoveTarget
import io.kotgent.transport.API_PREFIX
import io.kotgent.transport.ActivityEntryDto
import io.kotgent.transport.BacklogEntryDto
import io.kotgent.transport.CommentRequest
import io.kotgent.transport.CreateProjectRequest
import io.kotgent.transport.CreateTaskRequest
import io.kotgent.transport.DepsRequest
import io.kotgent.transport.LinkRequest
import io.kotgent.transport.LinkedSessionDto
import io.kotgent.transport.MoveTaskRequest
import io.kotgent.transport.NextTaskRequest
import io.kotgent.transport.NextTaskResponse
import io.kotgent.transport.PatchTaskRequest
import io.kotgent.transport.ProjectDto
import io.kotgent.transport.TASK_PANE_HEADER
import io.kotgent.transport.TRANSPORT_JSON
import io.kotgent.transport.TaskDetailDto
import io.kotgent.transport.WhoamiDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ApiClient]'s task/project surface (plan Task 20) against an embedded stub daemon.
 *
 * The stub is this file's own — deliberately not `CliTest`'s, which is a shared suite no wave-2 task may
 * touch — and it records the method, path, query, credentials and body of every request, because what
 * these methods must get right is exactly that: the route each one calls, the `/api/v1` prefix, the pane
 * header when (and only when) a pane was resolved, the session id in the body, and the shapes of the
 * request DTOs. Every server-touching body is bounded by [withTimeout] (anti-hang).
 */
class ApiClientTaskTest {

    // --- identity: the pane header and the session id ---------------------------------------------

    @Test
    fun aResolvedPaneRidesEveryTaskCallAsAHeader() = withStub(paneId = PaneId("%7")) { stub, api ->
        assertEquals(WHOAMI, api.whoami())
        val seen = stub.requests.receive()
        assertEquals("GET", seen.method)
        assertEquals("$API_PREFIX/whoami", seen.path, "whoami lives under the API prefix")
        assertEquals("Bearer secret", seen.auth)
        assertEquals("%7", seen.pane, "a resolved pane is how the daemon attributes the call")
    }

    @Test
    fun noPaneMeansNoPaneHeaderAtAll() = withStub(paneId = null) { stub, api ->
        api.whoami()
        assertNull(
            stub.requests.receive().pane,
            "a fabricated pane id would resolve against kotgent's tmux server and attribute the call " +
                "to an unrelated session, so absent is the honest wire",
        )
    }

    @Test
    fun thePaneHeaderReachesTheWholeTaskSurfaceNotJustWhoami() = withStub(paneId = PaneId("%3")) { stub, api ->
        api.listTasks(null)
        assertEquals("%3", stub.requests.receive().pane)
        api.createTask("t")
        assertEquals("%3", stub.requests.receive().pane)
        api.taskDetail(REF)
        assertEquals("%3", stub.requests.receive().pane)
        api.nextTask()
        assertEquals("%3", stub.requests.receive().pane)
        api.listProjects()
        assertEquals("%3", stub.requests.receive().pane)
    }

    @Test
    fun anExplicitSessionRidesTheBodyRatherThanTheHeader() = withStub(paneId = null) { stub, api ->
        api.linkTask(REF, sessionId = "sess1234")
        val body = TRANSPORT_JSON.decodeFromString(LinkRequest.serializer(), stub.requests.receive().body)
        assertEquals("sess1234", body.sessionId, "--session is the escape hatch for a caller outside a pane")
    }

    @Test
    fun aPaneCallCarriesNoSessionIdInTheBody() = withStub(paneId = PaneId("%1")) { stub, api ->
        api.unlinkTask(REF)
        val seen = stub.requests.receive()
        assertEquals("%1", seen.pane)
        assertNull(
            TRANSPORT_JSON.decodeFromString(LinkRequest.serializer(), seen.body).sessionId,
            "the daemon resolves the pane; inventing an id here would be a second way to be wrong",
        )
    }

    // --- reads --------------------------------------------------------------------------------------

    @Test
    fun listTasksWithoutAProjectSendsNoQuery() = withStub { stub, api ->
        assertEquals(listOf(ENTRY), api.listTasks(null))
        val seen = stub.requests.receive()
        assertEquals("$API_PREFIX/tasks", seen.path)
        assertEquals("", seen.query, "a null project must not become an empty project= filter")
    }

    @Test
    fun listTasksSendsTheProjectAsAQueryParameter() = withStub { stub, api ->
        api.listTasks(PROJECT)
        assertEquals("project=$PROJECT", stub.requests.receive().query)
    }

    @Test
    fun aBlankProjectIsTreatedAsNoProject() = withStub { stub, api ->
        api.listTasks("   ")
        assertEquals("", stub.requests.receive().query, "a blank filter would ask the daemon for project ''")
    }

    @Test
    fun taskDetailKeepsTheRefsColonInThePathSegment() = withStub { stub, api ->
        assertEquals(DETAIL, api.taskDetail(REF))
        val seen = stub.requests.receive()
        assertEquals("$API_PREFIX/tasks/$REF", seen.path, "':' is a legal pchar — the ref survives verbatim")
        assertEquals(REF, seen.ref)
    }

    @Test
    fun aRefThatIsNotWellFormedStillReachesTheDaemon() = withStub { stub, api ->
        api.taskDetail("no such ref")
        assertEquals(
            "no such ref",
            stub.requests.receive().ref,
            "the daemon answers 400 for a malformed ref; the client must not fail while building the URL",
        )
    }

    @Test
    fun listProjectsDecodesTheSelectorsSource() = withStub { stub, api ->
        assertEquals(listOf(PROJECT_DTO), api.listProjects())
        assertEquals("$API_PREFIX/projects", stub.requests.receive().path)
    }

    // --- writes -------------------------------------------------------------------------------------

    @Test
    fun createTaskPostsTitleBodyProjectAndSession() = withStub { stub, api ->
        assertEquals(ENTRY, api.createTask("wire the board", "with tests", PROJECT, "sess1234"))
        val seen = stub.requests.receive()
        assertEquals("POST", seen.method)
        assertEquals("$API_PREFIX/tasks", seen.path)
        val body = TRANSPORT_JSON.decodeFromString(CreateTaskRequest.serializer(), seen.body)
        assertEquals(CreateTaskRequest("wire the board", "with tests", PROJECT, "sess1234"), body)
    }

    @Test
    fun createTaskFromTheBoardSendsNeitherProjectNorSessionWhenItHasNone() = withStub { stub, api ->
        api.createTask("just a title")
        val body = TRANSPORT_JSON.decodeFromString(CreateTaskRequest.serializer(), stub.requests.receive().body)
        assertEquals(CreateTaskRequest("just a title", "", null, null), body)
    }

    @Test
    fun patchCarriesTheStateAndItsMessageAsOneRequest() = withStub { stub, api ->
        assertEquals(ENTRY, api.patchTask(REF, state = "review", message = "ready for a look"))
        val seen = stub.requests.receive()
        assertEquals("PATCH", seen.method)
        assertEquals("$API_PREFIX/tasks/$REF", seen.path)
        val body = TRANSPORT_JSON.decodeFromString(PatchTaskRequest.serializer(), seen.body)
        assertEquals(PatchTaskRequest(null, null, "review", "ready for a look", null), body)
    }

    @Test
    fun patchLeavesUnnamedFieldsNull() = withStub { stub, api ->
        api.patchTask(REF, title = "renamed")
        val body = TRANSPORT_JSON.decodeFromString(PatchTaskRequest.serializer(), stub.requests.receive().body)
        assertEquals("renamed", body.title)
        assertNull(body.body, "a null field means leave unchanged, never clear")
        assertNull(body.state)
    }

    @Test
    fun deleteIsTrueWhenTheDaemonRemovedTheTask() = withStub { stub, api ->
        assertTrue(api.deleteTask(REF))
        val seen = stub.requests.receive()
        assertEquals("DELETE", seen.method)
        assertEquals("$API_PREFIX/tasks/$REF", seen.path)
    }

    @Test
    fun deleteIsFalseWhenThereWasNoSuchTask() = withStub { stub, api ->
        stub.deleteStatus = HttpStatusCode.NotFound
        assertEquals(false, api.deleteTask(REF), "404 is the route's 'no such task', not a failure")
    }

    @Test
    fun deleteThrowsForEveryOtherFailure() = withStub { stub, api ->
        stub.deleteStatus = HttpStatusCode.BadRequest
        val failure = assertFailsWith<ApiException> { api.deleteTask("bad ref") }
        assertEquals(
            400,
            failure.status,
            "a malformed ref reported as a successful no-op would be worse than an error",
        )
    }

    @Test
    fun everyMoveTargetBecomesExactlyOneField() = withStub { stub, api ->
        assertEquals(ENTRY, api.moveTask(REF, MoveTarget.Top))
        val top = stub.requests.receive()
        assertEquals("$API_PREFIX/tasks/$REF/move", top.path)
        assertEquals(MoveTaskRequest(top = true), decodeMove(top.body))

        api.moveTask(REF, MoveTarget.Bottom)
        assertEquals(MoveTaskRequest(bottom = true), decodeMove(stub.requests.receive().body))

        api.moveTask(REF, MoveTarget.Before(TaskRef("local:7")))
        assertEquals(MoveTaskRequest(before = "local:7"), decodeMove(stub.requests.receive().body))

        api.moveTask(REF, MoveTarget.After(TaskRef("local:9")))
        assertEquals(MoveTaskRequest(after = "local:9"), decodeMove(stub.requests.receive().body))
    }

    @Test
    fun aDependencyEditSendsItsActionAndTarget() = withStub { stub, api ->
        api.editTaskDependency(REF, "add", "local:7")
        val seen = stub.requests.receive()
        assertEquals("$API_PREFIX/tasks/$REF/deps", seen.path)
        assertEquals(DepsRequest("add", "local:7"), TRANSPORT_JSON.decodeFromString(DepsRequest.serializer(), seen.body))
    }

    @Test
    fun aRefusedDependencyEditSurfacesItsStatus() = withStub { stub, api ->
        stub.depsStatus = HttpStatusCode.BadRequest
        val failure = assertFailsWith<ApiException> { api.editTaskDependency(REF, "add", REF) }
        assertEquals(400, failure.status)
        assertTrue(failure.message!!.contains("400"), "the CLI prints this straight at the operator")
    }

    @Test
    fun commentReturnsTheActivityRowItCreated() = withStub { stub, api ->
        assertEquals(ACTIVITY, api.commentOnTask(REF, "on it", sessionId = "sess1234"))
        val seen = stub.requests.receive()
        assertEquals("$API_PREFIX/tasks/$REF/comment", seen.path)
        val body = TRANSPORT_JSON.decodeFromString(CommentRequest.serializer(), seen.body)
        assertEquals(CommentRequest("on it", "sess1234"), body)
    }

    @Test
    fun linkAndUnlinkHitTheirOwnRoutes() = withStub { stub, api ->
        api.linkTask(REF)
        assertEquals("$API_PREFIX/tasks/$REF/link", stub.requests.receive().path)
        api.unlinkTask(REF)
        assertEquals("$API_PREFIX/tasks/$REF/unlink", stub.requests.receive().path)
    }

    @Test
    fun createProjectPostsThePathAndOptionalName() = withStub { stub, api ->
        assertEquals(PROJECT_DTO, api.createProject("/repo", "kotgent"))
        val seen = stub.requests.receive()
        assertEquals("POST", seen.method)
        assertEquals("$API_PREFIX/projects", seen.path)
        val body = TRANSPORT_JSON.decodeFromString(CreateProjectRequest.serializer(), seen.body)
        assertEquals(CreateProjectRequest("/repo", "kotgent"), body)
    }

    // --- next: "nothing eligible" is an answer, not a failure ----------------------------------------

    @Test
    fun nextReturnsTheTaskItLinked() = withStub { stub, api ->
        assertEquals(ENTRY, api.nextTask(PROJECT, "sess1234"))
        val seen = stub.requests.receive()
        assertEquals("POST", seen.method)
        assertEquals("$API_PREFIX/tasks/next", seen.path, "'next' can never be read as a ref — a ref needs a ':'")
        val body = TRANSPORT_JSON.decodeFromString(NextTaskRequest.serializer(), seen.body)
        assertEquals(NextTaskRequest(PROJECT, "sess1234"), body)
    }

    @Test
    fun nextReturnsNullRatherThanThrowingWhenNothingIsEligible() = withStub { stub, api ->
        stub.nextEntry = null
        assertNull(
            api.nextTask(),
            "a 200 with a null task is what lets `kotgent task next` exit 3 instead of reporting a failure",
        )
    }

    // --- credentials and timeouts --------------------------------------------------------------------

    @Test
    fun everyTaskCallFailsFastWithoutAToken() = withStub(token = null) { stub, api ->
        assertFailsWith<MissingTokenException> { api.whoami() }
        assertFailsWith<MissingTokenException> { api.listTasks(PROJECT) }
        assertFailsWith<MissingTokenException> { api.createTask("t") }
        assertFailsWith<MissingTokenException> { api.taskDetail(REF) }
        assertFailsWith<MissingTokenException> { api.patchTask(REF, state = "done") }
        assertFailsWith<MissingTokenException> { api.deleteTask(REF) }
        assertFailsWith<MissingTokenException> { api.moveTask(REF, MoveTarget.Top) }
        assertFailsWith<MissingTokenException> { api.editTaskDependency(REF, "add", "local:7") }
        assertFailsWith<MissingTokenException> { api.commentOnTask(REF, "hi") }
        assertFailsWith<MissingTokenException> { api.linkTask(REF) }
        assertFailsWith<MissingTokenException> { api.unlinkTask(REF) }
        assertFailsWith<MissingTokenException> { api.nextTask() }
        assertFailsWith<MissingTokenException> { api.listProjects() }
        assertFailsWith<MissingTokenException> { api.createProject("/repo") }
        assertNull(stub.requests.tryReceive().getOrNull(), "no network I/O happens without a token")
    }

    /**
     * The task methods issue their calls through the client they were GIVEN, so a daemon that accepts the
     * connection and then says nothing — an orphan holding the listening socket — is a reportable error
     * rather than a hang. A method that built its own client would keep waiting here.
     */
    @Test
    fun aSilentDaemonTimesOutInsteadOfHanging(): Unit = runBlocking {
        withTimeout(30_000) {
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    get("$API_PREFIX/projects") {
                        delay(20_000)
                        call.respondText("[]", ContentType.Application.Json)
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val impatient = HttpClient(ClientCIO) {
                // Only the end-to-end budget is short, so the failure is deterministically the request
                // timeout rather than a race between it and the socket one.
                install(HttpTimeout) {
                    connectTimeoutMillis = 3_000
                    requestTimeoutMillis = 300
                }
            }
            val api = ApiClient(baseUrl = "http://127.0.0.1:$port", token = "secret", client = impatient)
            try {
                assertFailsWith<HttpRequestTimeoutException> { api.listProjects() }
            } finally {
                api.close()
                server.stop(100, 500)
            }
        }
    }

    // --- harness ---------------------------------------------------------------------------------

    /** One recorded request the stub daemon saw. */
    private data class Recorded(
        val method: String,
        val path: String,
        val query: String,
        val auth: String?,
        val pane: String?,
        val ref: String?,
        val body: String,
    )

    /** A stub of the daemon's task surface that records what [ApiClient] sends and answers canned DTOs. */
    private class Stub {
        val requests = Channel<Recorded>(Channel.UNLIMITED)

        /** `DELETE /tasks/{ref}`'s answer — the one status [ApiClient.deleteTask] reads instead of throwing. */
        var deleteStatus: HttpStatusCode = HttpStatusCode.NoContent

        /** `POST /tasks/{ref}/deps`' answer, so a refusal can be driven from a test. */
        var depsStatus: HttpStatusCode = HttpStatusCode.OK

        /** What `POST /tasks/next` links; `null` is "nothing eligible", which is a `200`. */
        var nextEntry: BacklogEntryDto? = ENTRY

        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("$API_PREFIX/whoami") {
                    record("GET", "")
                    respondJson(WhoamiDto.serializer(), WHOAMI)
                }
                get("$API_PREFIX/tasks") {
                    record("GET", "")
                    respondJson(ListSerializer(BacklogEntryDto.serializer()), listOf(ENTRY))
                }
                post("$API_PREFIX/tasks") {
                    record("POST", call.receiveText())
                    respondJson(BacklogEntryDto.serializer(), ENTRY, HttpStatusCode.Created)
                }
                post("$API_PREFIX/tasks/next") {
                    record("POST", call.receiveText())
                    respondJson(NextTaskResponse.serializer(), NextTaskResponse(nextEntry))
                }
                get("$API_PREFIX/tasks/{ref}") {
                    record("GET", "")
                    respondJson(TaskDetailDto.serializer(), DETAIL)
                }
                patch("$API_PREFIX/tasks/{ref}") {
                    record("PATCH", call.receiveText())
                    respondJson(BacklogEntryDto.serializer(), ENTRY)
                }
                delete("$API_PREFIX/tasks/{ref}") {
                    record("DELETE", "")
                    call.respond(deleteStatus)
                }
                post("$API_PREFIX/tasks/{ref}/move") {
                    record("POST", call.receiveText())
                    respondJson(BacklogEntryDto.serializer(), ENTRY)
                }
                post("$API_PREFIX/tasks/{ref}/deps") {
                    record("POST", call.receiveText())
                    call.respondText("{}", ContentType.Application.Json, depsStatus)
                }
                post("$API_PREFIX/tasks/{ref}/comment") {
                    record("POST", call.receiveText())
                    respondJson(ActivityEntryDto.serializer(), ACTIVITY, HttpStatusCode.Created)
                }
                post("$API_PREFIX/tasks/{ref}/link") {
                    record("POST", call.receiveText())
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("$API_PREFIX/tasks/{ref}/unlink") {
                    record("POST", call.receiveText())
                    call.respondText("{}", ContentType.Application.Json)
                }
                get("$API_PREFIX/projects") {
                    record("GET", "")
                    respondJson(ListSerializer(ProjectDto.serializer()), listOf(PROJECT_DTO))
                }
                post("$API_PREFIX/projects") {
                    record("POST", call.receiveText())
                    respondJson(ProjectDto.serializer(), PROJECT_DTO, HttpStatusCode.Created)
                }
            }
        }

        private fun io.ktor.server.routing.RoutingContext.record(method: String, body: String) {
            requests.trySend(
                Recorded(
                    method = method,
                    path = call.request.path(),
                    query = call.request.uri.substringAfter('?', ""),
                    auth = call.request.headers[HttpHeaders.Authorization],
                    pane = call.request.headers[TASK_PANE_HEADER],
                    ref = call.parameters["ref"],
                    body = body,
                ),
            )
        }

        private suspend fun <T> io.ktor.server.routing.RoutingContext.respondJson(
            serializer: kotlinx.serialization.SerializationStrategy<T>,
            value: T,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = call.respondText(TRANSPORT_JSON.encodeToString(serializer, value), ContentType.Application.Json, status)
    }

    private fun withStub(
        token: String? = "secret",
        paneId: PaneId? = null,
        block: suspend (Stub, ApiClient) -> Unit,
    ) = runBlocking {
        withTimeout(30_000) {
            val stub = Stub()
            stub.server.start(wait = false)
            val port = stub.server.engine.resolvedConnectors().first().port
            val api = ApiClient(baseUrl = "http://127.0.0.1:$port", token = token, paneId = paneId)
            try {
                block(stub, api)
            } finally {
                api.close()
                stub.server.stop(100, 500)
            }
        }
    }

    private fun decodeMove(body: String): MoveTaskRequest =
        TRANSPORT_JSON.decodeFromString(MoveTaskRequest.serializer(), body)

    private companion object {
        const val REF: String = "local:42"
        const val PROJECT: String = "0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34"

        val ENTRY = BacklogEntryDto(
            ref = REF,
            project = PROJECT,
            title = "wire the board",
            body = "with tests",
            url = null,
            position = 1024.0,
            state = "todo",
            blocked = false,
            dependsOn = listOf("local:7"),
            createdAt = 1,
            updatedAt = 2,
            rev = 3,
        )

        val ACTIVITY = ActivityEntryDto(
            id = 5,
            ref = REF,
            ts = 9,
            kind = "comment",
            author = "sess1234",
            text = "on it",
        )

        val PROJECT_DTO = ProjectDto(id = PROJECT, name = "kotgent", path = "/repo", updatedAt = 7)

        val DETAIL = TaskDetailDto(
            task = ENTRY,
            projectName = "kotgent",
            projectPath = "/repo",
            dependsOn = listOf("local:7"),
            dependents = listOf("local:9"),
            sessions = listOf(
                LinkedSessionDto(
                    id = "sess1234",
                    name = "kt-sess1234",
                    agent = "claude",
                    state = "running",
                    needsAttention = false,
                    alive = true,
                ),
            ),
            activity = listOf(ACTIVITY),
        )

        val WHOAMI = WhoamiDto(sessionId = "sess1234", projectId = PROJECT, taskRef = REF)
    }
}
