package io.kotgent.transport

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.daemon.NoSuchSessionException
import io.kotgent.daemon.ResumeBlockedException
import io.kotgent.daemon.SessionManager
import io.kotgent.store.EventStore
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

/**
 * Shared JSON for the whole transport wire (control REST + both WebSockets). `classDiscriminator =
 * "type"` matches [io.kotgent.core.AgentEvent]'s `@SerialName`s, so a nested [io.kotgent.core.AgentEvent]
 * (in the per-session event stream) serializes as `{"type":"tool_call", …}` exactly as the store does.
 */
val TRANSPORT_JSON: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * How the transport delivers `POST /sessions/{id}/input` bytes to a session's terminal. Injected by
 * [KotgentServer] as `{ id, bytes -> terminalRegistry.getOrCreate(id).write(bytes) }`, so [controlRoutes]
 * stays decoupled from the terminal fan-out plumbing (and unit tests can supply a recording sink).
 */
typealias TerminalInputSink = suspend (SessionId, ByteArray) -> Unit

/**
 * The control-plane REST surface over [SessionManager] + the [EventStore] cache (plan Task 14). Read
 * endpoints serve the store's session cache (cheap, already-projected); write endpoints drive the
 * [SessionManager] lifecycle. All responses are hand-serialized with [json] via `respondText` (no
 * ContentNegotiation plugin needed — matches the Task-12 hook route style).
 *
 * Endpoints:
 *  - `GET  /sessions`                       — list all sessions (from the store cache).
 *  - `GET  /sessions/{id}`                  — one session, or `404`.
 *  - `POST /sessions`                       — start a new session (`{agent, cwd, name?, tags?}`) → `201`.
 *  - `POST /sessions/{id}/{stop|resume|interrupt|detach}` — a lifecycle control op.
 *  - `POST /sessions/{id}/input`            — write raw terminal input (`TerminalInput` only in the slice).
 *
 * `PATCH /sessions/{id}` is BACKLOG (omitted per the plan). Mounted inside [authenticated] by the server,
 * so every endpoint requires the shared token.
 */
fun Route.controlRoutes(
    sessionManager: SessionManager,
    store: EventStore,
    input: TerminalInputSink,
    json: Json = TRANSPORT_JSON,
) {
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
        val meta = sessionManager.start(req.agent, req.cwd, req.name, req.tags)
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

    // Literal `input` outranks the `{action}` param route below, so this handles /input specifically.
    post("/sessions/{id}/input") {
        val id = sessionId(call.parameters["id"]) ?: run {
            call.respondText("malformed session id", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (store.getSession(id) == null) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
            return@post
        }
        // Raw terminal input. Read as text (UTF-8) — the primary binary input path is the terminal WS.
        input(id, call.receiveText().encodeToByteArray())
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
                else -> {
                    call.respondText("unknown action '$action'", status = HttpStatusCode.BadRequest)
                    return@post
                }
            }
        } catch (e: ResumeBlockedException) {
            call.respondText("resume blocked: provider id pending", status = HttpStatusCode.Conflict)
            return@post
        } catch (e: NoSuchSessionException) {
            call.respondText("no such session ${id.value}", status = HttpStatusCode.NotFound)
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

/** Parse a path id into a [SessionId], returning null on a blank/invalid value (→ `400`) instead of throwing. */
private fun sessionId(raw: String?): SessionId? =
    raw?.let { runCatching { SessionId(it) }.getOrNull() }

// --- wire DTOs (transport owns its contract; core types are not exposed directly) ----------------

/** Request body for `POST /sessions`. `name`/`tags` are optional. */
@Serializable
data class StartSessionRequest(
    val agent: String,
    val cwd: String,
    val name: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * The wire shape of a session — a transport-owned projection of [SessionMeta] (kept separate so the
 * public API does not track internal domain-type churn). [state] is the enum name; [needsAttention] /
 * [alive] are pre-derived so a thin UI does not re-implement the state grouping.
 */
@Serializable
data class SessionDto(
    val id: String,
    val name: String,
    val tags: List<String>,
    val agent: String,
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
)

fun SessionMeta.toDto(): SessionDto = SessionDto(
    id = id.value,
    name = name,
    tags = tags,
    agent = agent,
    providerSessionId = providerSessionId?.value,
    state = state.name,
    needsAttention = state.needsAttention,
    alive = state.isAlive,
    cwd = cwd,
    tmuxSession = tmuxSession,
    paneId = paneId?.value,
    lastSeq = lastSeq.value,
    readCursor = readCursor.value,
    unread = (lastSeq.value - readCursor.value).coerceAtLeast(0),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
