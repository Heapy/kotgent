package io.kotgent.transport

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.unread
import io.kotgent.daemon.AgentBinaryNotFoundException
import io.kotgent.daemon.DuplicateImportException
import io.kotgent.daemon.ImportCwdException
import io.kotgent.daemon.NoSuchSessionException
import io.kotgent.daemon.ResumeBlockedException
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.TranscriptNotFoundException
import io.kotgent.daemon.UnknownAgentKindException
import io.kotgent.daemon.UnsupportedAgentException
import io.kotgent.store.EventStore
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
 * [KotgentServer] (leave copy-mode, then write into the session's shared upstream), so [controlRoutes]
 * stays decoupled from the terminal fan-out plumbing (and unit tests can supply a recording sink).
 *
 * Returns whether a **full pty write completed without throwing** across the ways this path can fail.
 * This is a REST endpoint, so the caller gets one answer and may have no terminal to look at:
 *  - **no upstream** — the bridge is lazy, so with no terminal subscriber attached (no browser tab, no
 *    `kotgent attach`) there is no `tmux attach` pty to write into and the bytes go nowhere. This is
 *    the *common* drop, and [io.kotgent.pty.TerminalBridge.write] reports it;
 *  - **copy-mode** — with `mouse on` forced, one wheel scroll by ANY viewer puts the shared pane into
 *    copy-mode, where tmux routes every keystroke (including bytes written into the attached client's
 *    pty) to the copy-mode key table and drops them, the same silent swallow `Tmux.sendKeys`' cancel
 *    exists to prevent. The sink cancels copy-mode first and reports `false` when it provably could not.
 *
 *  - **pty write failure** — the real write loops over partial POSIX writes, so a later error may follow
 *    a successfully written prefix. `false` therefore means full pty write completion was not observed,
 *    not that zero bytes arrived or that retrying the whole body is safe. A normal return proves only
 *    that the pty accepted the full body, not that the pane's process consumed it.
 *
 * [controlRoutes] turns a `false` into a conservative `409` naming all three possibilities — the sink
 * answers one Boolean, so the endpoint does not pretend to know whether zero bytes or a prefix arrived.
 */
typealias TerminalInputSink = suspend (SessionId, ByteArray) -> Boolean

/**
 * The control-plane REST surface over [SessionManager] + the [EventStore] cache (plan Task 14). Read
 * endpoints serve the store's session cache (cheap, already-projected); write endpoints drive the
 * [SessionManager] lifecycle. All responses are hand-serialized with [json] via `respondText` (no
 * ContentNegotiation plugin needed — matches the Task-12 hook route style).
 *
 * Endpoints:
 *  - `GET  /version`                        — the running application's display version.
 *  - `GET  /sessions`                       — list all sessions (from the store cache).
 *  - `GET  /sessions/{id}`                  — one session, or `404`.
 *  - `POST /sessions`                       — start a new session (`{agent, cwd, name?, tags?}`) → `201`.
 *  - `POST /sessions/import`                — register a provider session started OUTSIDE kotgent
 *    (`{agent, providerSessionId, cwd?, name?, tags?}`) as a `resumable` row → `201`; `400` for an unknown
 *    kind / malformed id / cwd failures / a transcript the probe cannot see (distinguishable messages —
 *    by the route convention `404` means "no such session `{id}`", and import addresses no resource);
 *    `409` for a provider id an existing session already holds (the body names it, plus an archived note).
 *  - `POST /sessions/{id}/{stop|resume|interrupt|detach|done|undone}` — a lifecycle control op
 *    (`done` = kill + archive off the sidebar; `undone` = un-archive).
 *  - `POST /sessions/{id}/input`            — write raw terminal input (`TerminalInput` only in the slice).
 *  - `POST /sessions/{id}/read`             — advance the unread cursor (`{seq}`): "I have seen through
 *    this seq". Monotonic + clamped in SQL; the recomputed `unread` reaches every client via the ordinary
 *    `SessionUpdate`.
 *
 * `PATCH /sessions/{id}` is BACKLOG (omitted per the plan). Mounted inside [authenticated] by the server —
 * NOT [loopbackOnly] — so every endpoint takes either credential (the CLI's master-token `Bearer` or the
 * browser's session cookie) and is reachable through the tunnel. That is deliberate for `/read`, whose only
 * real caller is the cookie-authenticated Web UI, on the phone as much as on the desktop.
 */
fun Route.controlRoutes(
    sessionManager: SessionManager,
    store: EventStore,
    input: TerminalInputSink,
    currentVersion: String,
    json: Json = TRANSPORT_JSON,
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
        val meta = try {
            sessionManager.start(req.agent, req.cwd, req.name, req.tags)
        } catch (e: UnsupportedAgentException) {
            // The requested kind is not one this daemon supports — a clear client error, not a silent
            // substitution or a 500.
            call.respondText("cannot start session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (e: AgentBinaryNotFoundException) {
            // The kind is supported but its binary did not resolve on the daemon's PATH (launchd's minimal
            // env) — a client-fixable misconfiguration carrying a `kotgent install` hint, not a 500.
            call.respondText("cannot start session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (e: TmuxException) {
            // e.g. a non-existent cwd → tmux new-session fails: a bad request, not a server error.
            call.respondText("cannot start session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.respondText(
            json.encodeToString(SessionDto.serializer(), meta.toDto()),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }

    // Literal `import` outranks `{id}` in the sibling routes, so this can never shadow a session path.
    post("/sessions/import") {
        val req = try {
            json.decodeFromString(ImportSessionRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        // The DTO carries the id as a String (see ImportSessionRequest); constructing the value class
        // HERE keeps a malformed UUID a clean 400 instead of the serializer's IllegalArgumentException
        // sailing past the SerializationException catch above into a 500.
        val importProviderId = try {
            ProviderSessionId(req.providerSessionId)
        } catch (e: IllegalArgumentException) {
            call.respondText("cannot import session: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        }
        // The four import failures are deliberately standalone, hierarchy-free exceptions (see their
        // declarations in SessionManager.kt), so each catch is independent and their order carries no
        // meaning — no parent catch can swallow a sibling. Their bodies differ only in status, hence
        // the one shared answer helper.
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
            // 409: the message names the existing kotgent session id — the `kotgent session '<id>'`
            // phrase the CLI's runImportCommand parses back out (see DUPLICATE_IMPORT_ID_IN_BODY in
            // Commands.kt) — and, when it is archived, points at Restore.
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
        val bytes = call.receiveText().encodeToByteArray()
        // An empty body has nothing to deliver, so it must not reach the sink: the sink's copy-mode
        // cancel is a SHARED-pane side effect, and running it here would yank every viewer of that pane
        // out of their scrollback for a write that is a guaranteed no-op. `Tmux.sendKeys` guards the
        // same way; this is the REST seam's half of that rule.
        if (bytes.isEmpty()) {
            call.respondText("ok")
            return@post
        }
        // `false` = full pty write completion was NOT observed. No-terminal/copy-mode-preflight failures
        // write nothing, but a pty write can write a prefix and then throw. The sink collapses those
        // cases into one Boolean, so the wire response must warn about partial delivery and must not
        // prescribe a blind whole-body retry that could duplicate commands or paste content.
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

    // Literal `read` outranks the `{action}` param route below — the unread cursor is not a lifecycle op.
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
        // A negative seq is clamped rather than rejected — and the clamp is LOAD-BEARING, not cosmetic:
        // `Seq`'s init requires value >= 0 (core/Ids.kt), so `Seq(-5)` would throw inside the handler and
        // surface as a 500 long before SQL saw it. Clamping keeps a nonsense body a harmless no-op.
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
            // `resume` rebuilds the adapter from the STORED agent kind, so a legacy/foreign row (e.g. a
            // `codex` session persisted before the kind was gated) throws the same exception the start
            // route maps to 400. Map it here too — it is a client error, not a 500.
            call.respondText("action '$action' failed: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (e: AgentBinaryNotFoundException) {
            // `resume` rebuilds the adapter from the stored kind; if that binary is no longer on the
            // daemon's PATH the builder throws this — same 400 + `kotgent install` hint as start, not a 500.
            call.respondText("action '$action' failed: ${e.message}", status = HttpStatusCode.BadRequest)
            return@post
        } catch (_: TmuxCopyModeException) {
            // `interrupt`'s Ctrl-C went to the copy-mode key table instead of the process (any viewer's
            // wheel scroll parks the SHARED pane there). Caught BEFORE the TmuxException branch below —
            // it is a subtype, and the two must not share a status: ordinary operation failures keep the
            // generic 400 mapping, whereas this condition is transient and retryable, so it gets the same
            // 409 + operator hint as `/input`. `interrupt` throws before it persists any derived state,
            // so nothing was recorded for the keystroke tmux ate.
            call.respondText(
                "action '$action' was not delivered: session ${id.value}'s pane is in tmux copy-mode; " +
                    "scroll the pane back to the bottom (or press q), then retry",
                status = HttpStatusCode.Conflict,
            )
            return@post
        } catch (e: TmuxException) {
            // e.g. resume's tmux new-session fails on a stale cwd: a bad request, not a 500.
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

/** Parse a path id into a [SessionId], returning null on a blank/invalid value (→ `400`) instead of throwing. */
private fun sessionId(raw: String?): SessionId? =
    raw?.let { runCatching { SessionId(it) }.getOrNull() }

// --- wire DTOs (transport owns its contract; core types are not exposed directly) ----------------

/** Response body for `GET /version` — already formatted for direct display by thin clients. */
@Serializable
data class VersionDto(val version: String)

/** Request body for `POST /sessions`. `name`/`tags` are optional. */
@Serializable
data class StartSessionRequest(
    val agent: String,
    val cwd: String,
    val name: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * Request body for `POST /sessions/import`. [providerSessionId] is a plain `String` (like every
 * [StartSessionRequest] field) on purpose: [ProviderSessionId]'s value-class serializer calls the
 * primary constructor, whose `require` throws [IllegalArgumentException] — NOT `SerializationException`
 * — so decoding it directly would sail past the route's decode catch and surface a malformed UUID as a
 * 500. The handler constructs the value class itself and maps the failure to a 400. `cwd` is optional:
 * when absent, the daemon's [io.kotgent.daemon.VendorSessionLocator] discovers it from the provider's
 * on-disk store.
 */
@Serializable
data class ImportSessionRequest(
    val agent: String,
    val providerSessionId: String,
    val cwd: String? = null,
    val name: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * Request body for `POST /sessions/{id}/read` — the highest seq the client has actually displayed. It is
 * explicit (rather than "mark everything read") because the server may have moved ahead during the
 * round-trip, and an implicit form would silently clear events the client never showed.
 */
@Serializable
data class MarkReadRequest(val seq: Long)

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
    /** Best-effort discovered model (e.g. `"claude-opus-4-8"`), or null — shown in the sidebar. */
    val model: String? = null,
    /** Agent CLI version (e.g. `"2.1.218"`), or null until detected — shown in the sidebar. */
    val cliVersion: String? = null,
    /** Resolved agent CLI path, or null — a tooltip detail (the sidebar shows the version). */
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
    /** Whether the session is "done" — hidden from the default sidebar (the UI filters on this). */
    val archived: Boolean = false,
    /**
     * The row's global monotonic revision (see `Sessions.sq`). The client applies any observation of a
     * row — an HTTP DTO or a WS frame — only if its rev is newer than the row it holds, so responses
     * and frames merge safely in any arrival order.
     */
    val rev: Long = 0,
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
)
