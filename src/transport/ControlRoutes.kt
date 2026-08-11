package io.kotgent.transport

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.core.unread
import io.kotgent.daemon.AgentBinaryNotFoundException
import io.kotgent.daemon.DuplicateImportException
import io.kotgent.daemon.ImportCwdException
import io.kotgent.daemon.NoSuchSessionException
import io.kotgent.daemon.ResumeBlockedException
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TaskService
import io.kotgent.daemon.TranscriptNotFoundException
import io.kotgent.daemon.UnknownAgentKindException
import io.kotgent.daemon.UnsupportedAgentException
import io.kotgent.store.EventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.UnknownTaskException
import io.kotgent.tmux.TmuxCopyModeException
import io.kotgent.tmux.TmuxException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val TRANSPORT_JSON: Json = Json {
    // Shared with sealed WebSocket frames and AgentEvent's serialized type names.
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

typealias TerminalInputSink = suspend (SessionId, ByteArray) -> Boolean

fun Route.controlRoutes(
    sessionManager: SessionManager,
    store: EventStore,
    input: TerminalInputSink,
    currentVersion: String,
    taskService: TaskService? = null,
    json: Json = TRANSPORT_JSON,
    taskStore: TaskStore? = null,
) {
    get("/version") {
        call.respondText(
            json.encodeToString(VersionDto.serializer(), VersionDto(currentVersion)),
            ContentType.Application.Json,
        )
    }

    get("/sessions") {
        val dtos = store.listSessions().map { it.toDto() }
        call.respondText(
            json.encodeToString(ListSerializer(SessionDto.serializer()), dtos),
            ContentType.Application.Json,
        )
    }

    post("/sessions") {
        val req = try {
            json.decodeFromString(StartSessionRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        val requestedTaskRef = req.taskRef?.takeIf { it.isNotBlank() }
        // Refuse every link error before launch so one bad body cannot leave an unlinked live agent.
        var linkTo: TaskRef? = null
        if (requestedTaskRef != null) {
            linkTo = TaskRef.parseOrNull(requestedTaskRef)
            if (linkTo == null) {
                call.respondText(
                    "cannot start session: ${MalformedTaskRefException(requestedTaskRef).message}",
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
            if (taskService == null || taskStore == null) {
                call.respondText(
                    "cannot start session: this daemon has no task layer, so taskRef " +
                        "'$requestedTaskRef' cannot be linked",
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
            if (taskStore.entry(linkTo) == null) {
                call.respondText(
                    "cannot start session: ${UnknownTaskException(linkTo).message}",
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
        }
        val meta = try {
            sessionManager.start(req.agent, req.cwd, req.name, req.tags)
        } catch (e: UnsupportedAgentException) {
            call.respondText("cannot start session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (e: AgentBinaryNotFoundException) {
            call.respondText("cannot start session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (e: TmuxException) {
            call.respondText("cannot start session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        }
        val started = if (linkTo != null && taskService != null) {
            taskService.link(meta.id, linkTo)
            store.getSession(meta.id) ?: meta.copy(taskRef = linkTo)
        } else {
            meta
        }
        call.respondText(
            json.encodeToString(SessionDto.serializer(), started.toDto()),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }

    post("/sessions/import") {
        val req = try {
            json.decodeFromString(ImportSessionRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        val importProviderId = try {
            // Keep the DTO a String: value-class construction throws IllegalArgumentException, not a
            // SerializationException the body decoder would map to 400.
            ProviderSessionId(req.providerSessionId)
        } catch (e: IllegalArgumentException) {
            call.respondText("cannot import session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        }
        suspend fun importFailure(e: RuntimeException, status: HttpStatusCode) =
            call.respondText("cannot import session: ${e.message}", status = status)
        val meta = try {
            sessionManager.importSession(req.agent, importProviderId, req.cwd, req.name, req.tags)
        } catch (e: UnknownAgentKindException) {
            importFailure(e, HttpStatusCode.BadRequest)
            return@post
        } catch (e: ImportCwdException) {
            importFailure(e, HttpStatusCode.BadRequest)
            return@post
        } catch (e: TranscriptNotFoundException) {
            importFailure(e, HttpStatusCode.BadRequest)
            return@post
        } catch (e: DuplicateImportException) {
            importFailure(e, HttpStatusCode.Conflict)
            return@post
        }
        call.respondText(
            json.encodeToString(SessionDto.serializer(), meta.toDto()),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }

    get("/sessions/{id}") {
        val id = sessionId(call.parameters["id"]) ?: run {
            call.respondText("malformed session id", status = HttpStatusCode.BadRequest)
            return@get
        }
        val meta = store.getSession(id)
        if (meta == null) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(json.encodeToString(SessionDto.serializer(), meta.toDto()), ContentType.Application.Json)
    }

    post("/sessions/{id}/input") {
        val id = sessionId(call.parameters["id"]) ?: run {
            call.respondText("malformed session id", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (store.getSession(id) == null) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
            return@post
        }
        val bytes = call.receiveText().encodeToByteArray()
        if (bytes.isEmpty()) {
            // Avoid copy-mode clearance, a shared-pane side effect, for a guaranteed no-op write.
            call.respondText("ok")
            return@post
        }
        if (!input(id, bytes)) {
            call.respondText(
                "input delivery for session ${id.value} could not be confirmed: no terminal may be " +
                    "attached, tmux copy-mode clearance may have failed, or the pty write may have " +
                    "stopped after delivering a prefix; if tmux copy-mode is the cause, scroll the pane " +
                    "back to the bottom (or press q), then retry; otherwise inspect the session before " +
                    "resending because retrying the whole body can duplicate input",
                status = HttpStatusCode.Conflict,
            )
            return@post
        }
        call.respondText("ok")
    }

    post("/sessions/{id}/read") {
        val id = sessionId(call.parameters["id"]) ?: run {
            call.respondText("malformed session id", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (store.getSession(id) == null) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
            return@post
        }
        val req = try {
            json.decodeFromString(MarkReadRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        store.markRead(id, Seq(req.seq.coerceAtLeast(0)))
        call.respondText("ok")
    }

    post("/sessions/{id}/{action}") {
        val id = sessionId(call.parameters["id"]) ?: run {
            call.respondText("malformed session id", status = HttpStatusCode.BadRequest)
            return@post
        }
        val action = call.parameters["action"].orEmpty()
        if (store.getSession(id) == null) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
            return@post
        }
        try {
            when (action) {
                "stop" -> sessionManager.stop(id)
                "resume" -> sessionManager.resume(id)
                "interrupt" -> sessionManager.interrupt(id)
                "detach" -> sessionManager.detach(id)
                "done" -> sessionManager.markDone(id)
                "undone" -> sessionManager.undone(id)
                else -> {
                    call.respondText("unknown action '$action'", status = HttpStatusCode.BadRequest)
                    return@post
                }
            }
        } catch (_: ResumeBlockedException) {
            call.respondText("resume blocked: provider id pending", status = HttpStatusCode.Conflict)
            return@post
        } catch (_: NoSuchSessionException) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
            return@post
        } catch (e: UnsupportedAgentException) {
            call.respondText("action '$action' failed: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (e: AgentBinaryNotFoundException) {
            call.respondText("action '$action' failed: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (_: TmuxCopyModeException) {
            // This is a retryable TmuxException subtype and must keep its distinct 409 mapping.
            call.respondText(
                "action '$action' was not delivered: session ${id.value}'s pane is in tmux copy-mode; " +
                    "scroll the pane back to the bottom (or press q), then retry",
                status = HttpStatusCode.Conflict,
            )
            return@post
        } catch (e: TmuxException) {
            call.respondText("action '$action' failed: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        }
        val updated = store.getSession(id)
        if (updated != null) {
            call.respondText(json.encodeToString(SessionDto.serializer(), updated.toDto()), ContentType.Application.Json)
        } else {
            call.respondText("ok")
        }
    }
}

private fun sessionId(raw: String?): SessionId? =
    raw?.let { runCatching { SessionId(it) }.getOrNull() }


@Serializable
data class VersionDto(val version: String)

@Serializable
data class StartSessionRequest(
    val agent: String,
    val cwd: String,
    val name: String? = null,
    val tags: List<String> = emptyList(),
    val taskRef: String? = null,
)

@Serializable
data class ImportSessionRequest(
    val agent: String,
    val providerSessionId: String,
    val cwd: String? = null,
    val name: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class MarkReadRequest(val seq: Long)

@Serializable
data class SessionDto(
    val id: String,
    val name: String,
    val tags: List<String>,
    val agent: String,
    val model: String? = null,
    val cliVersion: String? = null,
    val cliPath: String? = null,
    val providerSessionId: String?,
    val state: String,
    val needsAttention: Boolean,
    val alive: Boolean,
    val cwd: String,
    val tmuxSession: String,
    val paneId: String?,
    val lastSeq: Long,
    val readCursor: Long,
    val unread: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
    // Clients merge HTTP and WebSocket observations newest-revision-wins.
    val rev: Long = 0,
    val taskRef: String? = null,
    val projectId: String? = null,
)

fun SessionMeta.toDto(): SessionDto = SessionDto(
    id = id.value,
    name = name,
    tags = tags,
    agent = agent,
    model = model,
    cliVersion = cliVersion,
    cliPath = cliPath,
    providerSessionId = providerSessionId?.value,
    state = state.name,
    needsAttention = state.needsAttention,
    alive = state.isAlive,
    cwd = cwd,
    tmuxSession = tmuxSession,
    paneId = paneId?.value,
    lastSeq = lastSeq.value,
    readCursor = readCursor.value,
    unread = unread(lastSeq.value, readCursor.value),
    createdAt = createdAt,
    updatedAt = updatedAt,
    archived = archived,
    rev = rev,
    taskRef = taskRef?.value,
    projectId = projectId?.value,
)
