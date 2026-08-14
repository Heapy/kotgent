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

fun Route.eventsWs(
    store: EventStore,
    preferencesStore: PreferencesStore,
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
        launch {
            preferencesStore.preferences.collect { preferences ->
                ws.sendEventsFrame(json, preferences.toUpdateDto())
            }
        }

        val lock = Mutex()
        val pending = LinkedHashMap<SessionId, SessionUpdate>()
        val sent = HashSet<SessionId>()
        val wake = Channel<Unit>(Channel.CONFLATED)

        // Collectors only bank newest-per-id state; this sender performs every socket write outside
        // the mutex so a slow client cannot reopen the shared flow's DROP_OLDEST loss window.
        launch {
            for (unit in wake) {
                while (true) {
                    val next = lock.withLock {
                        val iterator = pending.entries.iterator()
                        if (!iterator.hasNext()) {
                            null
                        } else {
                            val entry = iterator.next()
                            val banked = entry.key to entry.value
                            iterator.remove()
                            banked
                        }
                    } ?: break
                    val (id, update) = next
                    if (lock.withLock { id in sent }) {
                        ws.sendEventsFrame(json, update.toDto())
                    } else {
                        val row = store.getSession(id) ?: continue
                        ws.sendEventsFrame(json, SessionRowDto(row.toDto()))
                        // Absence above must not mark an id carried: its next update still owes a full row.
                        lock.withLock { sent.add(id) }
                    }
                }
            }
        }

        if (taskStore != null) launchTaskStream(ws, taskStore, json)

        store.sessionUpdates
            .onSubscription {
                // Subscription precedes the snapshot, so changes absent from it are already buffered.
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

private fun CoroutineScope.launchTaskStream(
    ws: DefaultWebSocketServerSession,
    tasks: TaskStore,
    json: Json,
) {
    val lock = Mutex()
    var baselineDue = true
    val pending = LinkedHashMap<TaskRef, TaskUpdate>()
    val sent = HashSet<TaskRef>()
    val wake = Channel<Unit>(Channel.CONFLATED)

    // The sender reads and writes the baseline. The collector must begin draining immediately because
    // this read can suspend across store mutexes and a renormalization can exceed the flow buffer.
    launch {
        for (unit in wake) {
            while (true) {
                if (lock.withLock { baselineDue.also { baselineDue = false } }) {
                    val queued = readTasksBaseline(tasks)
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
                        val banked = entry.key to entry.value
                        iterator.remove()
                        banked
                    }
                } ?: break
                val (ref, update) = next
                val entry = update.entry
                if (entry == null) {
                    // The row may have arrived over HTTP; deletion is harmless even if this socket never carried it.
                    ws.sendEventsFrame(json, TaskRemovedDto(ref.value))
                    lock.withLock { sent.remove(ref) }
                    continue
                }
                val row = taskFrameRow(tasks, entry)
                if (lock.withLock { ref in sent }) {
                    ws.sendEventsFrame(json, TaskUpdateDto(row))
                } else {
                    ws.sendEventsFrame(json, TaskRowDto(row))
                    lock.withLock { sent.add(ref) }
                }
            }
        }
    }

    launch {
        tasks.taskUpdates
            .onSubscription { wake.trySend(Unit) }
            .collect { update ->
                lock.withLock { pending[update.ref] = update }
                wake.trySend(Unit)
            }
    }
}

private class QueuedTasksBaseline(val refs: Set<TaskRef>, val rows: List<BacklogEntryDto>)

private suspend fun readTasksBaseline(tasks: TaskStore): QueuedTasksBaseline {
    val refs = LinkedHashSet<TaskRef>()
    val rows = mutableListOf<BacklogEntryDto>()
    // Archived backlogs remain in the one-shot baseline so deep links and later restores have their rows.
    for (project in tasks.listAllProjects()) {
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
        // A cursor beyond the log is a resync-required protocol error, never an empty stream.
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message ?: "stale cursor"))
    }
}


@Serializable
// One sealed serializer owns the `type` discriminator; hand-written type fields would collide at runtime.
sealed class EventsFrame

@Serializable
@SerialName("sessions_snapshot")
data class SessionsSnapshotDto(
    val sessions: List<SessionDto>,
) : EventsFrame()

@Serializable
@SerialName("session_row")
data class SessionRowDto(
    val session: SessionDto,
) : EventsFrame()

@Serializable
@SerialName("session_update")
data class SessionUpdateDto(
    val sessionId: String,
    val state: String,
    val needsAttention: Boolean,
    val lastSeq: Long,
    val unread: Long,
    val archived: Boolean = false,
    val model: String? = null,
    val rev: Long = 0,
    val updatedAt: Long = 0,
    val taskRef: String? = null,
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
    updatedAt = updatedAt,
    taskRef = taskRef?.value,
    projectId = projectId?.value,
)


@Serializable
@SerialName("tasks_snapshot")
data class TasksSnapshotDto(
    val tasks: List<BacklogEntryDto>,
) : EventsFrame()

@Serializable
@SerialName("task_row")
data class TaskRowDto(
    val task: BacklogEntryDto,
) : EventsFrame()

@Serializable
@SerialName("task_update")
data class TaskUpdateDto(
    val task: BacklogEntryDto,
) : EventsFrame()

@Serializable
@SerialName("task_removed")
data class TaskRemovedDto(
    val ref: String,
) : EventsFrame()

private suspend fun DefaultWebSocketServerSession.sendEventsFrame(json: Json, frame: EventsFrame) {
    send(Frame.Text(json.encodeToString(EventsFrame.serializer(), frame)))
}

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
