package io.kotgent.transport

import io.kotgent.core.AgentEvent
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.unread
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StaleCursorException
import io.kotgent.store.StoredEvent
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `GET /events` WebSocket (plan Task 14) — the live feed that keeps the browser's session list and
 * "needs attention" queue current without polling.
 *
 * ## Two modes on one endpoint
 *  - **Global (default).** With no `session` query param, it streams cross-session state-change
 *    notifications: first a **snapshot** of the current sessions (one [SessionUpdateDto] each), then a
 *    live stream of every subsequent [EventStore.sessionUpdates] change. This is the plan's "snapshot
 *    current sessions then stream changes" model. There is no meaningful global cursor (seq is
 *    per-session, Task 7), so this mode is intentionally cursor-less; the snapshot is the baseline.
 *
 *    The snapshot is taken inside [onSubscription] — i.e. *after* this collector is subscribed to the
 *    shared flow — so any change emitted after subscription is buffered and delivered right after the
 *    snapshot, closing the subscribe/snapshot race (no update is both missed and absent from the
 *    snapshot).
 *
 *  - **Per-session (`?session=<id>&from=<seq>`).** Streams that one session's canonical
 *    [io.kotgent.core.AgentEvent] log from the restart-safe per-session cursor `from` (default 0),
 *    via [EventStore.subscribe]. This is where the per-session cursor lives; a cursor beyond the log
 *    fails with [StaleCursorException], which is surfaced to the client as a `VIOLATED_POLICY` close
 *    (a stale cursor is a hard error, per Task 7 — the client must resync, not silently skip).
 *
 * Mounted inside [authenticated]. The browser authenticates the handshake with its ambient session cookie
 * (no token in the URL); `kotgent attach` and other native clients send an `Authorization: Bearer` header.
 */
fun Route.eventsWs(store: EventStore, json: Json = TRANSPORT_JSON) {
    webSocket("/events") {
        val sessionParam = call.request.queryParameters["session"]
        if (sessionParam != null) {
            streamOneSession(store, json, sessionParam)
        } else {
            streamGlobalUpdates(store, json)
        }
    }
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.streamGlobalUpdates(
    store: EventStore,
    json: Json,
) {
    val ws = this
    coroutineScope {
        // Periodic full resync. `sessionUpdates` is a DROP_OLDEST buffer, so a consumer that falls far
        // behind could miss a session's LAST update and show a stale state until it reconnects. Re-sending
        // every session's current state on a slow tick makes any such drop self-heal (the newest state is
        // re-delivered), so the UI can never get stuck on a stale "needs attention". Cheap: a handful of
        // rows every few seconds. Cancelled when the collect below ends (socket closed).
        launch {
            while (isActive) {
                delay(GLOBAL_RESYNC_MILLIS)
                for (meta in store.listSessions()) {
                    ws.send(Frame.Text(json.encodeToString(SessionUpdateDto.serializer(), meta.toUpdateDto())))
                }
            }
        }
        store.sessionUpdates
            .onSubscription {
                // Baseline snapshot (after subscription so nothing between here and the first live emit is lost).
                for (meta in store.listSessions()) {
                    ws.send(Frame.Text(json.encodeToString(SessionUpdateDto.serializer(), meta.toUpdateDto())))
                }
            }
            .collect { update ->
                ws.send(Frame.Text(json.encodeToString(SessionUpdateDto.serializer(), update.toDto())))
            }
    }
}

/** How often the global events stream re-sends every session's current state as a drop-proof resync. */
private const val GLOBAL_RESYNC_MILLIS: Long = 15_000

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.streamOneSession(
    store: EventStore,
    json: Json,
    sessionParam: String,
) {
    val sessionId = runCatching { SessionId(sessionParam) }.getOrNull()
    if (sessionId == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "malformed session id"))
        return
    }
    // Reject an unknown session rather than subscribing to a never-emitting stream that hangs the socket.
    if (store.getSession(sessionId) == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
        return
    }
    val from = Seq((call.request.queryParameters["from"]?.toLongOrNull() ?: 0L).coerceAtLeast(0))
    try {
        store.subscribe(sessionId, from).collect { stored ->
            send(Frame.Text(json.encodeToString(StoredEventDto.serializer(), stored.toDto())))
        }
    } catch (e: StaleCursorException) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "stale cursor"))
    }
}

// --- wire DTOs -----------------------------------------------------------------------------------

/** A live session state-change notification pushed to the browser (global `/events` mode). */
@Serializable
data class SessionUpdateDto(
    val type: String = "session_update",
    val sessionId: String,
    val state: String,
    val needsAttention: Boolean,
    val lastSeq: Long,
    val unread: Long,
    /** Whether the session is archived ("done"); the client hides/shows the row on this. */
    val archived: Boolean = false,
)

fun SessionUpdate.toDto(): SessionUpdateDto = SessionUpdateDto(
    sessionId = sessionId.value,
    state = state.name,
    needsAttention = state.needsAttention,
    lastSeq = lastSeq.value,
    unread = unread,
    archived = archived,
)

/** Snapshot form of a [SessionMeta] as a [SessionUpdateDto] (the baseline the client gets on connect). */
fun SessionMeta.toUpdateDto(): SessionUpdateDto = SessionUpdateDto(
    sessionId = id.value,
    state = state.name,
    needsAttention = state.needsAttention,
    lastSeq = lastSeq.value,
    unread = unread(lastSeq.value, readCursor.value),
    archived = archived,
)

/** A single canonical event pushed on the per-session `/events?session=…` stream. */
@Serializable
data class StoredEventDto(
    val type: String = "session_event",
    val sessionId: String,
    val seq: Long,
    val ts: Long,
    val source: String,
    val event: AgentEvent,
)

fun StoredEvent.toDto(): StoredEventDto = StoredEventDto(
    sessionId = sessionId.value,
    seq = seq.value,
    ts = ts,
    source = source.name,
    event = event,
)
