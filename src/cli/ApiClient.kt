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

/** Thrown when a control call is attempted but no token is available (the daemon owns token creation). */
class MissingTokenException(val tokenPath: String) : RuntimeException(
    "no kotgent token found at $tokenPath — is the daemon running? start it with: kotgent daemon",
)

/** Thrown when the daemon answers a control call with a non-2xx status (e.g. 409 resume-blocked). */
class ApiException(val status: Int, val body: String) :
    RuntimeException("kotgent daemon returned HTTP $status: ${body.trim().ifEmpty { "(no body)" }}")

/**
 * Where [path] actually lives on the daemon: under [API_PREFIX], **except** for the `/auth*` bootstrap
 * surface, which deliberately did not move (see [API_PREFIX]'s KDoc).
 *
 * The exemption is not cosmetic. This one client mixes both kinds — `"/sessions"` moved,
 * [AUTH_TICKET_PATH] and [AUTH_ROTATE_PATH] did not — so a blanket `"$API_PREFIX$path"` helper would
 * silently break `kotgent web` (which mints a login ticket) and `kotgent token rotate`. It is the exact
 * counterpart of the `/auth` exemption inside `resources/webui/lib/api.js`'s `apiRequest`/`wsUrl`; both
 * sides need it, and they must agree.
 */
fun daemonPath(path: String): String = if (path.startsWith(AUTH_PAGE_PATH)) path else "$API_PREFIX$path"

/**
 * A thin Ktor CIO **client** for the kotgent daemon's control REST (plan Task 15). It talks to the same
 * surface [io.kotgent.transport.controlRoutes] serves, reusing the transport's wire types
 * ([SessionDto] / [StartSessionRequest] / [TRANSPORT_JSON]) so the CLI and the server can never drift.
 *
 * Auth: the shared token (read from `~/.kotgent/token` by default via [readTokenOrNull]) is sent as
 * `Authorization: Bearer <token>` on every call. A `null` token makes each call fail fast with
 * [MissingTokenException] before any network I/O, so the CLI can print a clear setup hint.
 *
 * Testability: point [baseUrl] at an embedded stub Ktor server on an ephemeral port and pass an explicit
 * [token] — every method issues exactly one REST call against it (see `CliTest`).
 */
class ApiClient(
    private val baseUrl: String = defaultBaseUrl(),
    private val token: String? = readTokenOrNull(),
    private val client: HttpClient = defaultHttpClient(),
    private val json: Json = TRANSPORT_JSON,
    private val tokenPath: String = defaultTokenPath(),
    /**
     * The kotgent pane this process is running in, sent as [TASK_PANE_HEADER] on every task call — how a
     * ref-less `kotgent task show` finds its own session. Deliberately a constructor parameter with a
     * `null` default rather than a call to [TmuxSelf.currentPane]: resolving it is a socket-path check
     * with an injected environment (Task 18), the caller does it once, and a client built for a stub
     * server must be able to say "no pane" without one.
     */
    private val paneId: PaneId? = null,
) : AutoCloseable {

    /** `GET /api/v1/sessions` — all sessions from the daemon's cache. */
    suspend fun listSessions(): List<SessionDto> {
        val resp = client.get(url("/sessions")) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(ListSerializer(SessionDto.serializer()), resp.bodyAsText())
    }

    /**
     * `POST /api/v1/sessions` — start a new [agent] session in [cwd]; returns the created session (with
     * its id).
     *
     * [taskRef] backs `kotgent start --task <ref>`: the session row and its task link are written by this
     * one request, so a failed launch leaves no link behind and there is nothing to roll back.
     */
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

    /**
     * `POST /api/v1/sessions/import` — register a provider session started OUTSIDE kotgent as a `resumable`
     * row (no launch, no tmux side effect); returns the created session. A null [cwd] lets the daemon
     * discover the project directory from the provider's on-disk store. Import failures surface as
     * [ApiException]: 409 = the provider id is already held by an existing kotgent session (the body
     * names it), 400 = unknown agent / malformed id / cwd or transcript problems.
     */
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

    /** `POST /api/v1/sessions/{id}/stop`. Returns the updated session if the daemon echoed one. */
    suspend fun stop(id: String): SessionDto? = control(id, "stop")

    /** `POST /api/v1/sessions/{id}/resume`. Throws [ApiException] (409) if the provider id is still pending. */
    suspend fun resume(id: String): SessionDto? = control(id, "resume")

    /** `POST /api/v1/sessions/{id}/interrupt`. */
    suspend fun interrupt(id: String): SessionDto? = control(id, "interrupt")

    private suspend fun control(id: String, action: String): SessionDto? {
        val resp = client.post(url("/sessions/$id/$action")) { bearer() }
        ensureSuccess(resp)
        val text = resp.bodyAsText()
        // The daemon returns the updated SessionDto for a live session, or a plain "ok" otherwise.
        return runCatching { json.decodeFromString(SessionDto.serializer(), text) }.getOrNull()
    }

    /**
     * `POST /auth/ticket` — mint a one-shot login ticket plus the URLs (local + optional public) that carry
     * it in their fragment. `Bearer` + loopback only on the daemon side, so this is the CLI's job, not a
     * browser's. Backs `kotgent web` and the Task-11 QR dialog.
     */
    suspend fun issueTicket(): TicketResponse {
        val resp = client.post(url(AUTH_TICKET_PATH)) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(TicketResponse.serializer(), resp.bodyAsText())
    }

    /**
     * `POST /auth/rotate` — re-mint the master token, returning the new value for `kotgent token rotate` to
     * print. The daemon persists it (token file + hook headers) and publishes it before answering, so the
     * old key stops authenticating new requests the moment this returns.
     */
    suspend fun rotateToken(): String {
        val resp = client.post(url(AUTH_ROTATE_PATH)) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(RotateResponse.serializer(), resp.bodyAsText()).token
    }

    // --- the task / project surface (Tasks 13-15's routes) -----------------------------------------

    /*
     * These fourteen signatures were declared in the contracts wave and filled in later, because
     * `src/cli/TaskCommands.kt` calls every one of them and may not touch this file. A signature that
     * only appeared with its body would have left that file unable to compile beside this one. The
     * signatures are therefore fixed: a caller depends on each parameter list as written.
     *
     * Two identity rules every method below shares, and they are the reason the parameters look the way
     * they do:
     *  - [paneHeader] goes out whenever a pane was resolved, which is how the daemon attributes a call
     *    made from inside a kotgent pane to that pane's session.
     *  - an explicit `sessionId` in the BODY wins and is not re-resolved: `--session <id>` is the escape
     *    hatch for a caller outside any pane, and it means the CLI must skip `GET /whoami` entirely
     *    rather than asking a question it already knows the answer to.
     * Failures surface as [ApiException] carrying the HTTP status, like every other method here.
     */

    /**
     * `GET /api/v1/whoami` — what the calling PANE resolves to. Pane resolution, not a session lookup:
     * a caller that was given `--session <id>` must never come here.
     */
    suspend fun whoami(): WhoamiDto =
        json.decodeFromString(WhoamiDto.serializer(), taskGet("/whoami"))

    /** `GET /api/v1/tasks?project=` — one project's backlog in `position` order, each entry with `blocked`. */
    suspend fun listTasks(project: String?): List<BacklogEntryDto> {
        val query = project?.takeIf { it.isNotBlank() }?.let { "?project=${it.encodeURLParameter()}" } ?: ""
        return json.decodeFromString(ListSerializer(BacklogEntryDto.serializer()), taskGet("/tasks$query"))
    }

    /** `POST /api/v1/tasks` — create. A null [project] lets the daemon resolve one (see [CreateTaskRequest]). */
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

    /** `GET /api/v1/tasks/{ref}` — entry, project path, both dependency directions, sessions, activity. */
    suspend fun taskDetail(ref: String): TaskDetailDto =
        json.decodeFromString(TaskDetailDto.serializer(), taskGet("/tasks/${refSegment(ref)}"))

    /**
     * `PATCH /api/v1/tasks/{ref}` — title / body / state. A null field means "leave unchanged", never
     * "clear"; [message] is meaningful only alongside [state] and is what makes `task review -m` one
     * operation.
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

    /**
     * `DELETE /api/v1/tasks/{ref}` — unlinks every holder, then removes the task, its deps and its feed.
     *
     * `false` is the daemon's `404`, i.e. "there was no such task", which is the only reading a `Boolean`
     * return can have next to [ApiException]: the route convention says `404` means "no such task `{ref}`"
     * (`TaskReadRoutes`), and `TaskService.delete` answers the same `Boolean`. Every other non-2xx —
     * including the `400` of a ref that cannot be parsed — is a failure and throws, because a caller that
     * cannot tell "gone already" from "you asked wrong" would report a malformed ref as a successful
     * no-op.
     */
    suspend fun deleteTask(ref: String): Boolean {
        val resp = client.delete(url("/tasks/${refSegment(ref)}")) {
            bearer()
            paneHeader()
        }
        if (resp.status.value == HTTP_NOT_FOUND) return false
        ensureSuccess(resp)
        return true
    }

    /** `POST /api/v1/tasks/{ref}/move` — never carries a state; a column change is a separate [patchTask]. */
    suspend fun moveTask(ref: String, target: MoveTarget): BacklogEntryDto {
        val request = json.encodeToString(MoveTaskRequest.serializer(), target.toRequest())
        return json.decodeFromString(
            BacklogEntryDto.serializer(),
            taskPost("/tasks/${refSegment(ref)}/move", request),
        )
    }

    /**
     * `POST /api/v1/tasks/{ref}/deps` — [action] is `"add"` or `"remove"`; the four refusals are `400`s.
     *
     * The route answers the UPDATED entry (`TaskWriteRoutes` re-reads it after the edit) and this returns
     * it, because `blocked` — the one field a dependency edit changes — is derived server-side and cannot
     * be inferred from the request. Discarding the body left the CLI, the one consumer with no events
     * socket, unable to tell whether the task it just made dependent is still workable.
     */
    suspend fun editTaskDependency(ref: String, action: String, on: String): BacklogEntryDto {
        val request = json.encodeToString(DepsRequest.serializer(), DepsRequest(action = action, on = on))
        return json.decodeFromString(
            BacklogEntryDto.serializer(),
            taskPost("/tasks/${refSegment(ref)}/deps", request),
        )
    }

    /** `POST /api/v1/tasks/{ref}/comment` — requires session identity, so an activity row is attributable. */
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

    /** `POST /api/v1/tasks/{ref}/link` — unconditional; a task already `in_progress` simply gains a session. */
    suspend fun linkTask(ref: String, sessionId: String? = null) {
        val request = json.encodeToString(LinkRequest.serializer(), LinkRequest(sessionId = sessionId))
        taskPost("/tasks/${refSegment(ref)}/link", request)
    }

    /** `POST /api/v1/tasks/{ref}/unlink` — drops this session's link and leaves the task's state alone. */
    suspend fun unlinkTask(ref: String, sessionId: String? = null) {
        val request = json.encodeToString(LinkRequest.serializer(), LinkRequest(sessionId = sessionId))
        taskPost("/tasks/${refSegment(ref)}/unlink", request)
    }

    /**
     * `POST /api/v1/tasks/next` — link the next eligible task to the calling session.
     *
     * A `null` return is **"nothing eligible"**, not a failure: the route answers `200` with a null task
     * precisely so this can be told apart from an error, and it is what `kotgent task next` maps to
     * exit `3`.
     */
    suspend fun nextTask(project: String? = null, sessionId: String? = null): BacklogEntryDto? {
        val request = json.encodeToString(
            NextTaskRequest.serializer(),
            NextTaskRequest(project = project, sessionId = sessionId),
        )
        return json.decodeFromString(NextTaskResponse.serializer(), taskPost("/tasks/next", request)).task
    }

    /** `GET /api/v1/projects` — every known project (the board selector's source, and `project list`'s). */
    suspend fun listProjects(): List<ProjectDto> =
        json.decodeFromString(ListSerializer(ProjectDto.serializer()), taskGet("/projects"))

    /** `POST /api/v1/projects` — write `.kotgent.json` at an absolute path; an existing file always wins. */
    suspend fun createProject(path: String, name: String? = null): ProjectDto {
        val request = json.encodeToString(
            CreateProjectRequest.serializer(),
            CreateProjectRequest(path = path, name = name),
        )
        return json.decodeFromString(ProjectDto.serializer(), taskPost("/projects", request))
    }

    override fun close(): Unit = client.close()

    // --- internals -------------------------------------------------------------------------------

    /** [baseUrl] plus [daemonPath] of [path] — the one place a CLI call learns where a route lives. */
    private fun url(path: String): String = "$baseUrl${daemonPath(path)}"

    /**
     * A `GET` on the task surface: the two credentials ([bearer], [paneHeader]) and the status check, so
     * each method above is its route plus its decode and nothing else.
     */
    private suspend fun taskGet(path: String): String {
        val resp = client.get(url(path)) {
            bearer()
            paneHeader()
        }
        ensureSuccess(resp)
        return resp.bodyAsText()
    }

    /** The `POST` counterpart of [taskGet]. Every task write carries a JSON body, even `link`'s. */
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
     * A [io.kotgent.core.TaskRef] as one path segment.
     *
     * A well-formed ref needs no escaping at all — its charset is `[A-Za-z0-9_-]` either side of the one
     * mandatory `:`, and `:` is a legal `pchar` that [encodeURLPathPart] leaves alone, so `local:42`
     * survives verbatim and the daemon's `{ref}` sees exactly what the operator typed. The encode is for
     * the ref that is *not* well formed: `kotgent task show "no such ref"` must reach the daemon and come
     * back a `400`, not fail while the client is still assembling a URL.
     */
    private fun refSegment(ref: String): String = ref.encodeURLPathPart()

    private fun HttpRequestBuilder.bearer() {
        val t = token ?: throw MissingTokenException(tokenPath)
        header(HttpHeaders.Authorization, "Bearer $t")
    }

    /**
     * Send [TASK_PANE_HEADER] when — and only when — a pane was resolved. Absent, the daemon has no pane
     * to resolve and answers `400` naming `--session`, which is the honest outcome: a fabricated or
     * foreign pane id would resolve against kotgent's tmux server and attribute the call to an unrelated
     * session (see [TmuxSelf]).
     */
    private fun HttpRequestBuilder.paneHeader() {
        paneId?.let { header(TASK_PANE_HEADER, it.value) }
    }

    private suspend fun ensureSuccess(resp: HttpResponse) {
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, resp.bodyAsText())
    }
}

/**
 * The CLI's HTTP client: plain CIO plus finite timeouts. Every control call is a short request/response,
 * so an answer that never arrives is always a failure, never patience — and it *can* never arrive: if
 * the daemon died while an orphaned process still holds its listening socket (see
 * [io.kotgent.sys.markOpenFdsCloexec]), the kernel completes the TCP handshake against a socket nobody
 * accepts, and an untimed client waits forever. Timeouts turn that into a reportable error.
 */
fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

/**
 * The one place the CLI turns a [MoveTarget] into `POST /tasks/{ref}/move`'s wire body.
 *
 * The domain type is a sealed hierarchy and the wire body is four optional fields of which exactly one is
 * set, so this `when` is the whole translation — and being exhaustive, a fifth target would fail to
 * compile here rather than silently posting an empty move the daemon would reject at runtime.
 */
private fun MoveTarget.toRequest(): MoveTaskRequest = when (this) {
    is MoveTarget.Top -> MoveTaskRequest(top = true)
    is MoveTarget.Bottom -> MoveTaskRequest(bottom = true)
    is MoveTarget.Before -> MoveTaskRequest(before = ref.value)
    is MoveTarget.After -> MoveTaskRequest(after = ref.value)
}

/** The daemon's "no such task" for `DELETE /tasks/{ref}` — the one non-2xx [ApiClient] reads as an answer. */
private const val HTTP_NOT_FOUND: Int = 404

/** TCP connect budget — loopback, so anything slower than this is not a live daemon. */
private const val CONNECT_TIMEOUT_MS: Long = 3_000

/** End-to-end budget for one control call (`start` shells out to tmux + claude, so not too tight). */
private const val REQUEST_TIMEOUT_MS: Long = 20_000
