package io.kotgent.transport

import io.kotgent.core.AgentEvent
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.PreferencesStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StaleCursorException
import io.kotgent.store.StoredEvent
import io.kotgent.store.TaskStore
import io.kotgent.task.BacklogEntry
import io.kotgent.task.TaskUpdate
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `GET /events` WebSocket (plan Task 14) — the live feed that keeps the browser's session list and
 * "needs attention" queue current without polling.
 *
 * ## Two modes on one endpoint
 *  - **Global (default).** With no `session` query param, it streams [EventsFrame]s: on connect ONE
 *    [SessionsSnapshotDto] carrying every session as a full row, then per-session live traffic — a
 *    session this socket has not carried yet arrives as a full-row [SessionRowDto], every later change
 *    as a light [SessionUpdateDto] patch. The client builds its entire list from this socket (no
 *    `GET /sessions` on load), applying each frame only if its `rev` is newer than the row it holds —
 *    frames are idempotent, and an HTTP response racing a frame cannot roll a row back. There is no
 *    periodic re-delivery and no resumption cursor: a reconnect gets a fresh snapshot as its baseline,
 *    and a [DROP_OLDEST][EventStore.sessionUpdates] loss is prevented per-socket by the conflating
 *    sender below rather than healed after the fact.
 *
 *    The snapshot is taken inside [onSubscription] — i.e. *after* this collector is subscribed to the
 *    shared flow — so any change emitted after subscription is buffered and delivered right after the
 *    snapshot, closing the subscribe/snapshot race (no update is both missed and absent from the
 *    snapshot).
 *
 *    An update for a session with no `sessions` row (an append can outrun the row's creation) produces
 *    NO frame and does NOT mark the id as carried: the row arrives whole on the next emission after the
 *    row exists. Marking it carried would ship every later change as a patch the client must ignore
 *    (unknown id), leaving the session invisible on this socket until a reconnect.
 *
 *    **Tasks ride the same socket** when the daemon has a [TaskStore]: one `tasks_snapshot` baseline,
 *    then `task_row` / `task_update` / `task_removed` from a SECOND collector with its own conflating
 *    sender ([launchTaskStream]). The two streams share nothing but the socket — a task frame can
 *    therefore interleave with a session frame, which is fine because the client dispatches on `type`
 *    and applies every row newest-rev-wins.
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
fun Route.eventsWs(
    store: EventStore,
    preferencesStore: PreferencesStore,
    /**
     * The task layer, or `null` for a daemon without one — in which case the whole task branch of the
     * global stream is skipped and an old or task-less client sees exactly today's protocol (no
     * `tasks_snapshot`, so a board simply finds nothing rather than hanging on a baseline that never
     * comes).
     */
    taskStore: TaskStore? = null,
    json: Json = TRANSPORT_JSON,
) {
    webSocket("/events") {
        val sessionParam = call.request.queryParameters["session"]
        if (sessionParam != null) {
            streamOneSession(store, json, sessionParam)
        } else {
            streamGlobalUpdates(store, preferencesStore, taskStore, json)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.streamGlobalUpdates(
    store: EventStore,
    preferencesStore: PreferencesStore,
    taskStore: TaskStore?,
    json: Json,
) {
    val ws = this
    coroutineScope {
        // Preferences share the global socket because they are daemon-wide, not session events. StateFlow
        // delivers its current persisted value immediately to this new collector, then every accepted
        // save. The per-session mode below remains the canonical event log only.
        launch {
            preferencesStore.preferences.collect { preferences ->
                ws.sendEventsFrame(json, preferences.toUpdateDto())
            }
        }

        // Per-socket conflation state. The Mutex is for Kotlin/Native memory visibility across the three
        // writers (collector, sender, baseline), not for serializing sends — the single sender does that.
        val lock = Mutex()
        val pending = LinkedHashMap<SessionId, SessionUpdate>()
        val sent = HashSet<SessionId>()
        val wake = Channel<Unit>(Channel.CONFLATED)

        // The single sequential sender. It alone touches the socket for session frames, so per-id order
        // is (row | snapshot) first, patches after — and it sends OUTSIDE the lock, so a slow client can
        // never stall the collector into re-opening the DROP_OLDEST window conflation exists to close.
        // The one achievable inversion (getSession returns a row newer than the update that woke us, and
        // that update later goes out as a patch with an older rev) is harmless: the client applies frames
        // newest-rev-wins.
        launch {
            for (unit in wake) {
                while (true) {
                    val next = lock.withLock {
                        val iterator = pending.entries.iterator()
                        if (!iterator.hasNext()) {
                            null
                        } else {
                            val entry = iterator.next()
                            // Read out before remove(): a K/N map entry is invalidated by its removal.
                            val banked = entry.key to entry.value
                            iterator.remove()
                            banked
                        }
                    } ?: break
                    val (id, update) = next
                    if (lock.withLock { id in sent }) {
                        ws.sendEventsFrame(json, update.toDto())
                    } else {
                        // Order matters: fetch first, and only a DELIVERED row marks the id as carried.
                        // No row → no frame and NOT carried (see the endpoint KDoc).
                        val row = store.getSession(id) ?: continue
                        ws.sendEventsFrame(json, SessionRowDto(row.toDto()))
                        lock.withLock { sent.add(id) }
                    }
                }
            }
        }

        // The task branch, when this daemon has one. Its own lock, its own conflation state and its own
        // sender — sharing them with the sessions branch would make a slow task read hold up a session
        // patch for no reason, and the two flows have nothing in common but the socket.
        if (taskStore != null) launchTaskStream(ws, taskStore, json)

        // The collector never awaits a send: it banks the newest update per session and signals the
        // sender. A burst during one slow send conflates instead of backing up into the shared flow.
        store.sessionUpdates
            .onSubscription {
                // Baseline: ONE snapshot frame carrying every session as a full row; all of those ids
                // are now carried by this socket, so their later changes ship as patches.
                val metas = store.listSessions()
                lock.withLock { metas.forEach { sent.add(it.id) } }
                ws.sendEventsFrame(json, SessionsSnapshotDto(metas.map { it.toDto() }))
            }
            .collect { update ->
                lock.withLock { pending[update.sessionId] = update }
                wake.trySend(Unit)
            }
    }
}

/**
 * The task half of the global stream: ONE [TasksSnapshotDto] baseline, then a full [TaskRowDto] for a
 * ref this socket has not carried yet, a [TaskUpdateDto] for every later change and a [TaskRemovedDto]
 * for a delete — conflated per ref by the same collector/sender split the sessions branch uses, and
 * sending only through [sendEventsFrame].
 *
 * ## Why the baseline is QUEUED rather than sent from `.onSubscription { }`
 * The sessions baseline sends from inside its `.onSubscription { }`, which closes the subscribe/snapshot
 * race but leaves a second one: while that send is suspended the collector has not begun draining, and
 * [TaskStore.taskUpdates] is `DROP_OLDEST` past 1024 entries — a burst ONE renormalization of a large
 * project can produce by itself, since every rewritten row stamps a rev and emits. So here
 * `.onSubscription { }` only READS the snapshot and hands it to the sequential sender as its first item;
 * the collector starts draining immediately and never waits on a socket write.
 *
 * Nothing can overtake that first item: the collector cannot bank anything until `.onSubscription { }`
 * has returned, and the sender empties the baseline slot before it ever looks at the pending map.
 */
private fun CoroutineScope.launchTaskStream(
    ws: DefaultWebSocketServerSession,
    tasks: TaskStore,
    json: Json,
) {
    // As on the sessions branch, the Mutex is for Kotlin/Native memory visibility across the three
    // writers (collector, sender, baseline reader) — the single sender is what serializes the sends.
    val lock = Mutex()
    var baseline: QueuedTasksBaseline? = null
    val pending = LinkedHashMap<TaskRef, TaskUpdate>()
    val sent = HashSet<TaskRef>()
    val wake = Channel<Unit>(Channel.CONFLATED)

    // The single sequential sender for task frames: baseline first, then the banked refs, all outside
    // the lock so a slow client conflates instead of stalling the collector.
    launch {
        for (unit in wake) {
            while (true) {
                val queued = lock.withLock { baseline.also { baseline = null } }
                if (queued != null) {
                    // Marked before the send, like the sessions snapshot: this one frame IS the delivery
                    // of every row in it, and a send that fails takes the whole socket with it anyway.
                    lock.withLock { sent.addAll(queued.refs) }
                    ws.sendEventsFrame(json, TasksSnapshotDto(queued.rows))
                    continue
                }
                val next = lock.withLock {
                    val iterator = pending.entries.iterator()
                    if (!iterator.hasNext()) {
                        null
                    } else {
                        val entry = iterator.next()
                        // Read out before remove(): a K/N map entry is invalidated by its removal.
                        val banked = entry.key to entry.value
                        iterator.remove()
                        banked
                    }
                } ?: break
                val (ref, update) = next
                val entry = update.entry
                if (entry == null) {
                    // A delete goes out whether or not THIS socket carried the ref: the client can also
                    // have learned the row from `GET /api/v1/tasks`, and a `task_removed` for a row it
                    // does not hold is a no-op. Clearing the mark is what makes a ref that comes back
                    // (an id reused by a future tracker) arrive whole instead of as an ignored patch.
                    ws.sendEventsFrame(json, TaskRemovedDto(ref.value))
                    lock.withLock { sent.remove(ref) }
                    continue
                }
                // The payload is the entry the store COMMITTED and emitted, not a re-read: the update
                // already carries the row, so re-reading would cost a query per frame and reintroduce
                // the "the row moved on since this update" inversion for no gain. Only the tracker
                // fields and the edges — which the signal does not carry — are read here.
                val row = taskFrameRow(tasks, entry)
                if (lock.withLock { ref in sent }) {
                    ws.sendEventsFrame(json, TaskUpdateDto(row))
                } else {
                    // Same rule as a session row: only a DELIVERED row marks the ref as carried.
                    ws.sendEventsFrame(json, TaskRowDto(row))
                    lock.withLock { sent.add(ref) }
                }
            }
        }
    }

    launch {
        tasks.taskUpdates
            .onSubscription {
                val read = readTasksBaseline(tasks)
                lock.withLock { baseline = read }
                wake.trySend(Unit)
            }
            .collect { update ->
                lock.withLock { pending[update.ref] = update }
                wake.trySend(Unit)
            }
    }
}

/** The first item the task sender ships: the baseline rows plus the refs they mark as carried. */
private class QueuedTasksBaseline(val refs: Set<TaskRef>, val rows: List<BacklogEntryDto>)

/**
 * Every entry of every known project, as full rows.
 *
 * Three queries per PROJECT, never one per card: the tracker rows and the whole edge set are resolved in
 * one call each and handed to [BacklogEntry.toDto] per entry — the read shape [BacklogEntryDto] documents.
 */
private suspend fun readTasksBaseline(tasks: TaskStore): QueuedTasksBaseline {
    val refs = LinkedHashSet<TaskRef>()
    val rows = mutableListOf<BacklogEntryDto>()
    for (project in tasks.listProjects()) {
        val entries = tasks.listBacklog(project.id)
        if (entries.isEmpty()) continue
        val tracker = tasks.list(project.id).associateBy { it.ref }
        val edges = tasks.dependencyEdges(project.id)
        for (entry in entries) {
            refs += entry.ref
            rows += entry.toDto(tracker[entry.ref], edges[entry.ref].orEmpty())
        }
    }
    return QueuedTasksBaseline(refs, rows)
}

/** One committed entry joined with the two things [TaskUpdate] cannot carry: its tracker row and its edges. */
private suspend fun taskFrameRow(tasks: TaskStore, entry: BacklogEntry): BacklogEntryDto =
    entry.toDto(tasks.get(entry.ref), tasks.dependenciesOf(entry.ref))

private suspend fun DefaultWebSocketServerSession.streamOneSession(
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

/**
 * A frame of the GLOBAL `/events` mode. One sealed hierarchy so the wire discriminator (`type`, from
 * [TRANSPORT_JSON]'s `classDiscriminator`) is generated, never a hand-written field that would collide
 * with it at runtime.
 *
 * INVARIANT: every send of a global frame must encode through THIS base serializer
 * ([sendEventsFrame]) — kotlinx emits the discriminator only when encoding via the sealed base; a
 * concrete `X.serializer()` produces a frame without `type` that the client silently drops.
 * (Same rule as [io.kotgent.core.AgentEvent] in the store.)
 */
@Serializable
sealed class EventsFrame

/** The connect baseline: every session as a full row. The client replaces its list with this. */
@Serializable
@SerialName("sessions_snapshot")
data class SessionsSnapshotDto(
    val sessions: List<SessionDto>,
) : EventsFrame()

/** One full row for a session this socket has not carried yet. The client upserts it newest-rev-wins. */
@Serializable
@SerialName("session_row")
data class SessionRowDto(
    val session: SessionDto,
) : EventsFrame()

/**
 * A light patch for a session this socket already carries. Every field — [model] and its `null`
 * included — is read from the committed row, so the patch is authoritative; the client applies it
 * newest-[rev]-wins and silently ignores an unknown [sessionId] (the server does not produce those).
 */
@Serializable
@SerialName("session_update")
data class SessionUpdateDto(
    val sessionId: String,
    val state: String,
    val needsAttention: Boolean,
    val lastSeq: Long,
    val unread: Long,
    /** Whether the session is archived ("done"); the client hides/shows the row on this. */
    val archived: Boolean = false,
    /** The committed row's model, or null — authoritative either way (a rebind-correction clear rides here). */
    val model: String? = null,
    /** The row's global monotonic revision (see [SessionDto.rev]). */
    val rev: Long = 0,
    /**
     * The task this session is linked to, or null — authoritative either way, like [model]. The sidebar's
     * task badge is rendered from it, so a link made by `kotgent task claim` inside a pane moves the
     * badge on this frame instead of on the next reload.
     */
    val taskRef: String? = null,
    /** The session's resolved project, or null outside one. */
    val projectId: String? = null,
) : EventsFrame()

fun SessionUpdate.toDto(): SessionUpdateDto = SessionUpdateDto(
    sessionId = sessionId.value,
    state = state.name,
    needsAttention = state.needsAttention,
    lastSeq = lastSeq.value,
    unread = unread,
    archived = archived,
    model = model,
    rev = rev,
    taskRef = taskRef?.value,
    projectId = projectId?.value,
)

/*
 * --- task frames ---------------------------------------------------------------------------------
 *
 * The same protocol as the session frames, on the same socket: ONE `tasks_snapshot` baseline, then a
 * full `task_row` for a ref this socket has not carried yet, a `task_update` for every later change, and
 * a `task_removed` when the ref is deleted. Same conflating per-socket sender, same "only a DELIVERED
 * row marks the ref as carried" rule, same `EventsFrame.serializer()`-only send path. No second socket.
 *
 * `task_row` and `task_update` carry the SAME payload on purpose. A backlog entry is small, and the
 * source signal (`TaskUpdate`) carries no tracker fields, so a patch would have to re-read the joined
 * row anyway — a lighter subset would buy nothing and could silently omit a changed title. The
 * discriminator still matters to the client: a `task_row` may ADD a row, a `task_update` only updates a
 * ref it already knows.
 *
 * The tasks baseline must NOT be sent from inside `.onSubscription { }` the way the sessions baseline
 * is. That closes the subscribe/snapshot race but leaves a second one: while the send is suspended the
 * collector has not begun draining, and the source flow drops the oldest past 1024 buffered updates —
 * which ONE renormalization of a large project can produce by itself. So `.onSubscription { }` READS the
 * snapshot and queues it to the sequential sender as its first item; the collector starts draining
 * immediately and never waits on a socket write.
 */

/** The connect baseline for tasks: every entry of every known project as a full row. */
@Serializable
@SerialName("tasks_snapshot")
data class TasksSnapshotDto(
    val tasks: List<BacklogEntryDto>,
) : EventsFrame()

/** One full entry for a ref this socket has not carried yet. The client upserts it newest-rev-wins. */
@Serializable
@SerialName("task_row")
data class TaskRowDto(
    val task: BacklogEntryDto,
) : EventsFrame()

/** A change to a ref this socket already carries. Applied newest-rev-wins; an unknown ref is ignored. */
@Serializable
@SerialName("task_update")
data class TaskUpdateDto(
    val task: BacklogEntryDto,
) : EventsFrame()

/** The ref was deleted. The client drops the row and the sender forgets that it carried it. */
@Serializable
@SerialName("task_removed")
data class TaskRemovedDto(
    val ref: String,
) : EventsFrame()

/** The one send path for global frames — see the [EventsFrame] invariant. */
private suspend fun DefaultWebSocketServerSession.sendEventsFrame(json: Json, frame: EventsFrame) {
    send(Frame.Text(json.encodeToString(EventsFrame.serializer(), frame)))
}

/** A single canonical event pushed on the per-session `/events?session=…` stream (not an [EventsFrame]). */
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
