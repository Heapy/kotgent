package io.kotgent.cli

import io.kotgent.core.PaneId
import io.kotgent.task.MoveTarget
import io.kotgent.transport.API_PREFIX
import io.kotgent.transport.AUTH_PAGE_PATH
import io.kotgent.transport.AUTH_ROTATE_PATH
import io.kotgent.transport.AUTH_TICKET_PATH
import io.kotgent.transport.ActivityEntryDto
import io.kotgent.transport.BacklogEntryDto
import io.kotgent.transport.CommentRequest
import io.kotgent.transport.CreateProjectRequest
import io.kotgent.transport.CreateTaskRequest
import io.kotgent.transport.DepsRequest
import io.kotgent.transport.ImportSessionRequest
import io.kotgent.transport.LinkRequest
import io.kotgent.transport.MoveTaskRequest
import io.kotgent.transport.NextTaskRequest
import io.kotgent.transport.NextTaskResponse
import io.kotgent.transport.PatchTaskRequest
import io.kotgent.transport.ProjectDto
import io.kotgent.transport.RotateResponse
import io.kotgent.transport.SessionDto
import io.kotgent.transport.StartSessionRequest
import io.kotgent.transport.TASK_PANE_HEADER
import io.kotgent.transport.TRANSPORT_JSON
import io.kotgent.transport.TaskDetailDto
import io.kotgent.transport.TicketResponse
import io.kotgent.transport.WhoamiDto
import io.kotgent.transport.defaultTokenPath
import io.kotgent.transport.readTokenOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MissingTokenException(val tokenPath: String) : RuntimeException(
    "no kotgent token found at $tokenPath — is the daemon running? start it with: kotgent daemon",
)

class ApiException(val status: Int, val body: String) :
    RuntimeException("kotgent daemon returned HTTP $status: ${body.trim().ifEmpty { "(no body)" }}")

/** Programmatic daemon routes live under [API_PREFIX]; only the HTML login page stays at the root. */
fun daemonPath(path: String): String = when {
    path == AUTH_PAGE_PATH -> path
    path == API_PREFIX || path.startsWith("$API_PREFIX/") -> path
    else -> "$API_PREFIX$path"
}

/**
 * Client for the daemon's control API. Every call authenticates with [token] and fails before network
 * I/O with [MissingTokenException] when it is absent.
 */
class ApiClient(
    private val baseUrl: String = defaultBaseUrl(),
    private val token: String? = readTokenOrNull(),
    private val client: HttpClient = defaultHttpClient(),
    private val json: Json = TRANSPORT_JSON,
    private val tokenPath: String = defaultTokenPath(),
    /** The current kotgent pane, sent as [TASK_PANE_HEADER] so task calls can resolve their session. */
    private val paneId: PaneId? = null,
) : AutoCloseable {

    suspend fun listSessions(): List<SessionDto> {
        val resp = client.get(url("/sessions")) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(ListSerializer(SessionDto.serializer()), resp.bodyAsText())
    }

    /** [taskRef], when present, is linked atomically with session creation. */
    suspend fun startSession(
        agent: String,
        cwd: String,
        name: String? = null,
        tags: List<String> = emptyList(),
        taskRef: String? = null,
    ): SessionDto {
        val body = json.encodeToString(
            StartSessionRequest.serializer(),
            StartSessionRequest(agent, cwd, name, tags, taskRef),
        )
        val resp = client.post(url("/sessions")) {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(resp)
        return json.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
    }

    /** Imports an external provider session without launching it; a null [cwd] enables provider discovery. */
    suspend fun importSession(
        agent: String,
        providerSessionId: String,
        cwd: String? = null,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionDto {
        val body = json.encodeToString(
            ImportSessionRequest.serializer(),
            ImportSessionRequest(agent, providerSessionId, cwd, name, tags),
        )
        val resp = client.post(url("/sessions/import")) {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(resp)
        return json.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
    }

    suspend fun stop(id: String): SessionDto? = control(id, "stop")

    /** Throws [ApiException] with status 409 while the provider id is still pending. */
    suspend fun resume(id: String): SessionDto? = control(id, "resume")

    suspend fun interrupt(id: String): SessionDto? = control(id, "interrupt")

    private suspend fun control(id: String, action: String): SessionDto? {
        val resp = client.post(url("/sessions/$id/$action")) { bearer() }
        ensureSuccess(resp)
        val text = resp.bodyAsText()
        // The daemon returns the updated SessionDto for a live session, or a plain "ok" otherwise.
        return runCatching { json.decodeFromString(SessionDto.serializer(), text) }.getOrNull()
    }

    /** Mints a one-shot browser login ticket; this bearer-authenticated endpoint is loopback-only. */
    suspend fun issueTicket(): TicketResponse {
        val resp = client.post(url(AUTH_TICKET_PATH)) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(TicketResponse.serializer(), resp.bodyAsText())
    }

    /** Returns only after the new token is persisted and the old token no longer authenticates requests. */
    suspend fun rotateToken(): String {
        val resp = client.post(url(AUTH_ROTATE_PATH)) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(RotateResponse.serializer(), resp.bodyAsText()).token
    }

    /**
     * Resolves the calling pane. Callers with an explicit session id must not replace it with this lookup.
     */
    suspend fun whoami(): WhoamiDto =
        json.decodeFromString(WhoamiDto.serializer(), taskGet("/whoami"))

    suspend fun listTasks(project: String?): List<BacklogEntryDto> {
        val query = project?.takeIf { it.isNotBlank() }?.let { "?project=${it.encodeURLParameter()}" } ?: ""
        return json.decodeFromString(ListSerializer(BacklogEntryDto.serializer()), taskGet("/tasks$query"))
    }

    /** A null [project] lets the daemon resolve it from session identity or cwd. */
    suspend fun createTask(
        title: String,
        body: String = "",
        project: String? = null,
        sessionId: String? = null,
    ): BacklogEntryDto {
        val request = json.encodeToString(
            CreateTaskRequest.serializer(),
            CreateTaskRequest(title = title, body = body, project = project, sessionId = sessionId),
        )
        return json.decodeFromString(BacklogEntryDto.serializer(), taskPost("/tasks", request))
    }

    suspend fun taskDetail(ref: String): TaskDetailDto =
        json.decodeFromString(TaskDetailDto.serializer(), taskGet("/tasks/${refSegment(ref)}"))

    /**
     * Null fields are unchanged, never cleared; [message] is meaningful only with [state].
     */
    suspend fun patchTask(
        ref: String,
        title: String? = null,
        body: String? = null,
        state: String? = null,
        message: String? = null,
        sessionId: String? = null,
    ): BacklogEntryDto {
        val request = json.encodeToString(
            PatchTaskRequest.serializer(),
            PatchTaskRequest(title = title, body = body, state = state, message = message, sessionId = sessionId),
        )
        val resp = client.patch(url("/tasks/${refSegment(ref)}")) {
            bearer()
            paneHeader()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        ensureSuccess(resp)
        return json.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
    }

    /** Returns false only for 404; malformed refs and every other non-2xx response throw [ApiException]. */
    suspend fun deleteTask(ref: String): Boolean {
        val resp = client.delete(url("/tasks/${refSegment(ref)}")) {
            bearer()
            paneHeader()
        }
        if (resp.status.value == HTTP_NOT_FOUND) return false
        ensureSuccess(resp)
        return true
    }

    suspend fun moveTask(ref: String, target: MoveTarget): BacklogEntryDto {
        val request = json.encodeToString(MoveTaskRequest.serializer(), target.toRequest())
        return json.decodeFromString(
            BacklogEntryDto.serializer(),
            taskPost("/tasks/${refSegment(ref)}/move", request),
        )
    }

    /** Returns the updated entry because `blocked` is derived by the daemon from all dependencies. */
    suspend fun editTaskDependency(ref: String, action: String, on: String): BacklogEntryDto {
        val request = json.encodeToString(DepsRequest.serializer(), DepsRequest(action = action, on = on))
        return json.decodeFromString(
            BacklogEntryDto.serializer(),
            taskPost("/tasks/${refSegment(ref)}/deps", request),
        )
    }

    suspend fun commentOnTask(ref: String, text: String, sessionId: String? = null): ActivityEntryDto {
        val request = json.encodeToString(
            CommentRequest.serializer(),
            CommentRequest(text = text, sessionId = sessionId),
        )
        return json.decodeFromString(
            ActivityEntryDto.serializer(),
            taskPost("/tasks/${refSegment(ref)}/comment", request),
        )
    }

    suspend fun linkTask(ref: String, sessionId: String? = null) {
        val request = json.encodeToString(LinkRequest.serializer(), LinkRequest(sessionId = sessionId))
        taskPost("/tasks/${refSegment(ref)}/link", request)
    }

    suspend fun unlinkTask(ref: String, sessionId: String? = null) {
        val request = json.encodeToString(LinkRequest.serializer(), LinkRequest(sessionId = sessionId))
        taskPost("/tasks/${refSegment(ref)}/unlink", request)
    }

    /** Returns null, rather than failing, when no task is eligible. */
    suspend fun nextTask(project: String? = null, sessionId: String? = null): BacklogEntryDto? {
        val request = json.encodeToString(
            NextTaskRequest.serializer(),
            NextTaskRequest(project = project, sessionId = sessionId),
        )
        return json.decodeFromString(NextTaskResponse.serializer(), taskPost("/tasks/next", request)).task
    }

    suspend fun listProjects(archived: Boolean = false): List<ProjectDto> =
        json.decodeFromString(
            ListSerializer(ProjectDto.serializer()),
            taskGet(if (archived) "/projects?archived=true" else "/projects"),
        )

    suspend fun createProject(path: String, name: String? = null): ProjectDto {
        val request = json.encodeToString(
            CreateProjectRequest.serializer(),
            CreateProjectRequest(path = path, name = name),
        )
        return json.decodeFromString(ProjectDto.serializer(), taskPost("/projects", request))
    }

    suspend fun deleteProject(id: String): ProjectDto = json.decodeFromString(
        ProjectDto.serializer(),
        taskDelete("/projects/${id.encodeURLPathPart()}"),
    )

    suspend fun restoreProject(id: String): ProjectDto = json.decodeFromString(
        ProjectDto.serializer(),
        taskPost("/projects/${id.encodeURLPathPart()}/restore", "{}"),
    )

    override fun close(): Unit = client.close()

    private fun url(path: String): String = "$baseUrl${daemonPath(path)}"

    private suspend fun taskGet(path: String): String {
        val resp = client.get(url(path)) {
            bearer()
            paneHeader()
        }
        ensureSuccess(resp)
        return resp.bodyAsText()
    }

    private suspend fun taskDelete(path: String): String {
        val resp = client.delete(url(path)) {
            bearer()
            paneHeader()
        }
        ensureSuccess(resp)
        return resp.bodyAsText()
    }

    private suspend fun taskPost(path: String, body: String): String {
        val resp = client.post(url(path)) {
            bearer()
            paneHeader()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(resp)
        return resp.bodyAsText()
    }

    /**
     * Escaping malformed task refs lets the daemon return its canonical 400 instead of failing during URL
     * construction; valid refs retain their literal colon.
     */
    private fun refSegment(ref: String): String = ref.encodeURLPathPart()

    private fun HttpRequestBuilder.bearer() {
        val t = token ?: throw MissingTokenException(tokenPath)
        header(HttpHeaders.Authorization, "Bearer $t")
    }

    /** Never synthesize a pane id: a foreign id could attribute the call to an unrelated session. */
    private fun HttpRequestBuilder.paneHeader() {
        paneId?.let { header(TASK_PANE_HEADER, it.value) }
    }

    private suspend fun ensureSuccess(resp: HttpResponse) {
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, resp.bodyAsText())
    }
}

/**
 * Finite timeouts also cover an orphan that inherited the daemon's listening socket: TCP can connect even
 * though no process will accept the request.
 */
fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

private fun MoveTarget.toRequest(): MoveTaskRequest = when (this) {
    is MoveTarget.Top -> MoveTaskRequest(top = true)
    is MoveTarget.Bottom -> MoveTaskRequest(bottom = true)
    is MoveTarget.Before -> MoveTaskRequest(before = ref.value)
    is MoveTarget.After -> MoveTaskRequest(after = ref.value)
}

private const val HTTP_NOT_FOUND: Int = 404

private const val CONNECT_TIMEOUT_MS: Long = 3_000

private const val REQUEST_TIMEOUT_MS: Long = 20_000
