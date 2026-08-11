package io.kotgent.transport

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProjectId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.PreferencesStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.TaskStore
import io.kotgent.store.UiPreferences
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class TaskEventsTest {

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")


    @Test
    fun everyTaskFrameKindCarriesTheTypeDiscriminator() {
        fun typeOf(frame: EventsFrame): String? {
            val encoded = TRANSPORT_JSON.encodeToString(EventsFrame.serializer(), frame)
            return TRANSPORT_JSON.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content
        }
        val row = entryOf(TaskRef("local:1"), alpha).toDto(Task(TaskRef("local:1"), "t", "b", null, 5L))
        assertEquals("tasks_snapshot", typeOf(TasksSnapshotDto(listOf(row))))
        assertEquals("task_row", typeOf(TaskRowDto(row)))
        assertEquals("task_update", typeOf(TaskUpdateDto(row)))
        assertEquals("task_removed", typeOf(TaskRemovedDto("local:1")))
    }


    @Test
    fun theBaselineIsOneSnapshotOfEveryProjectsRowsJoinedWithTheirTrackerFields() = withTasksSocket(
        seed = { tasks ->
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedProject(beta, "beta", "/repo/beta")
            tasks.seedTask(TaskRef("local:1"), alpha, "first", position = 1.0)
            tasks.seedTask(TaskRef("local:2"), alpha, "second", position = 2.0, blocked = true)
            tasks.seedDependency(TaskRef("local:2"), TaskRef("local:1"))
            tasks.seedTask(TaskRef("local:9"), beta, "elsewhere", position = 1.0)
        },
    ) { ws ->
        val snapshot = ws.expectSnapshot()
        assertEquals(
            listOf("local:1", "local:2", "local:9"),
            snapshot.tasks.map { it.ref },
            "the baseline carries every entry of every known project",
        )
        val second = snapshot.tasks.single { it.ref == "local:2" }
        assertEquals("second", second.title, "a snapshot row is joined with its tracker fields")
        assertEquals(listOf("local:1"), second.dependsOn, "…and with its edges, resolved per project")
        assertTrue(second.blocked, "…and carries the derived blocked the board renders")
        assertTrue(second.rev > 0, "a stored row carries a positive rev")
    }

    @Test
    fun aDaemonWithoutATaskStoreSendsNoTaskFramesAtAll() = runBlocking {
        withTimeout(30_000) {
            withServer(tasks = null) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") {
                    assertEquals(
                        "sessions_snapshot",
                        frameOfKind("sessions_snapshot").first,
                        "the socket itself works",
                    )
                    assertNull(
                        withTimeoutOrNull(1_000) { nextTaskFrame() },
                        "no task frame is produced without a task store",
                    )
                }
            }
        }
    }


    @Test
    fun aLinkArrivesAsAPatchForARefTheSnapshotAlreadyCarried() = withTasksSocket(
        seed = { tasks ->
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:1"), alpha, "first", position = 1.0)
        },
    ) { ws ->
        val baselineRev = ws.expectSnapshot().tasks.single().rev

        assertTrue(ws.tasks.startIfTodo(TaskRef("local:1")), "the todo → in_progress transition applied")

        val patch = ws.expectUpdate()
        assertEquals("local:1", patch.task.ref)
        assertEquals("in_progress", patch.task.state, "the link's state change rides the patch")
        assertTrue(patch.task.rev > baselineRev, "…with a rev newer than the row it follows")
        assertEquals("first", patch.task.title, "a patch is a whole row, tracker fields included")
    }

    @Test
    fun aTaskCreatedAfterConnectArrivesAsAFullRowAndThenAsPatches() = withTasksSocket(
        seed = { tasks -> tasks.seedProject(alpha, "alpha", "/repo/alpha") },
    ) { ws ->
        assertTrue(ws.expectSnapshot().tasks.isEmpty(), "the baseline is empty before any task")

        ws.tasks.addTask(TaskRef("local:1"), alpha, "fresh", position = 1.0)
        val row = ws.expectRow()
        assertEquals("local:1", row.task.ref, "a ref new to this socket arrives as a full row")
        assertEquals("fresh", row.task.title, "…carrying everything the client needs to render a card")

        ws.tasks.transition(TaskRef("local:1"), TaskState.review, author = "s-1", message = null)
        val patch = ws.expectUpdate()
        assertEquals("review", patch.task.state)
        assertTrue(patch.task.rev > row.task.rev, "the patch's rev is newer than the row it follows")
    }

    @Test
    fun aDeleteArrivesAsTaskRemovedAndClearsTheCarriedMark() = withTasksSocket(
        seed = { tasks ->
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:1"), alpha, "doomed", position = 1.0)
        },
    ) { ws ->
        ws.expectSnapshot()

        assertTrue(ws.tasks.delete(TaskRef("local:1")), "the task went away")
        assertEquals("local:1", ws.expectRemoved().ref, "a null-entry update becomes task_removed")

        ws.tasks.addTask(TaskRef("local:1"), alpha, "reborn", position = 1.0)
        assertEquals("reborn", ws.expectRow().task.title, "the ref is uncarried again, so it arrives whole")
    }

    @Test
    fun aRenormalizationReachesTheSocketRowByRow() = withTasksSocket(
        seed = { tasks ->
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:1"), alpha, "one", position = 1.0)
            tasks.seedTask(TaskRef("local:2"), alpha, "two", position = 1.000000000_1)
            tasks.seedTask(TaskRef("local:3"), alpha, "three", position = 1.000000000_2)
        },
    ) { ws ->
        val before = ws.expectSnapshot().tasks.associate { it.ref to it.rev }

        ws.tasks.renormalize(alpha)

        val seen = mutableMapOf<String, TaskUpdateDto>()
        repeat(3) { val patch = ws.expectUpdate(); seen[patch.task.ref] = patch }
        assertEquals(setOf("local:1", "local:2", "local:3"), seen.keys, "every rewritten row reaches the socket")
        assertEquals(
            listOf(1.0, 2.0, 3.0),
            listOf("local:1", "local:2", "local:3").map { seen.getValue(it).task.position },
            "…carrying the renormalized ranks",
        )
        assertTrue(
            seen.all { (ref, patch) -> patch.task.rev > before.getValue(ref) },
            "…each with a fresh rev, or a connected board would hold stale positions",
        )
    }

    @Test
    fun aBurstEmittedWhileTheBaselineIsBeingReadIsDeliveredAfterIt() = runBlocking {
        withTimeout(30_000) {
            val tasks = FakeTaskStore()
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:0"), alpha, "already there", position = 0.5)
            val burst = (1..20).map { TaskRef("local:$it") }

            withServer(tasks) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") {
                    tasks.baselineEntered.await()
                    burst.forEach { tasks.addTask(it, alpha, "burst ${it.key}", position = it.key.toDouble()) }
                    tasks.baselineGate.complete(Unit)

                    assertEquals(
                        listOf("local:0"),
                        expectSnapshot().tasks.map { it.ref },
                        "the queued baseline is still the FIRST task frame, ahead of everything banked",
                    )
                    val delivered = burst.map { expectRow().task.ref }
                    assertEquals(burst.map { it.value }, delivered, "every banked update reached the socket")
                }
            }
        }
    }

    @Test
    fun theCollectorIsAlreadyDrainingWhileTheBaselineIsBeingRead() = runBlocking {
        withTimeout(30_000) {
            val tasks = FakeTaskStore(updatesBuffer = 0)
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:0"), alpha, "already there", position = 0.5)

            withServer(tasks) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") {
                    tasks.baselineEntered.await()
                    withTimeout(5_000) {
                        tasks.addTask(TaskRef("local:1"), alpha, "banked", position = 1.0)
                    }
                    tasks.baselineGate.complete(Unit)

                    assertEquals(
                        listOf("local:0"),
                        expectSnapshot().tasks.map { it.ref },
                        "the snapshot is still the FIRST task frame",
                    )
                    assertEquals(
                        "local:1",
                        expectRow().task.ref,
                        "…and what was banked during the read follows it, whole",
                    )
                }
            }
        }
    }


    private class Env(
        val socket: DefaultClientWebSocketSession,
        val tasks: FakeTaskStore,
    )

    private fun withTasksSocket(
        seed: (FakeTaskStore) -> Unit = {},
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        withTimeout(30_000) {
            val tasks = FakeTaskStore()
            seed(tasks)
            tasks.baselineGate.complete(Unit)
            withServer(tasks) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") { block(Env(this, tasks)) }
            }
        }
    }

    private suspend fun withServer(
        tasks: FakeTaskStore?,
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing { eventsWs(EmptyEventStore(), FixedPreferencesStore(), tasks, TRANSPORT_JSON) }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        try {
            block(port, client)
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }


    private suspend fun Env.expectSnapshot(): TasksSnapshotDto = socket.expectSnapshot()

    private suspend fun Env.expectRow(): TaskRowDto = socket.expectRow()

    private suspend fun Env.expectUpdate(): TaskUpdateDto = socket.expectUpdate()

    private suspend fun Env.expectRemoved(): TaskRemovedDto = socket.expectRemoved()

    private suspend fun DefaultClientWebSocketSession.expectSnapshot(): TasksSnapshotDto =
        TRANSPORT_JSON.decodeFromString(TasksSnapshotDto.serializer(), expectTaskFrame("tasks_snapshot"))

    private suspend fun DefaultClientWebSocketSession.expectRow(): TaskRowDto =
        TRANSPORT_JSON.decodeFromString(TaskRowDto.serializer(), expectTaskFrame("task_row"))

    private suspend fun DefaultClientWebSocketSession.expectUpdate(): TaskUpdateDto =
        TRANSPORT_JSON.decodeFromString(TaskUpdateDto.serializer(), expectTaskFrame("task_update"))

    private suspend fun DefaultClientWebSocketSession.expectRemoved(): TaskRemovedDto =
        TRANSPORT_JSON.decodeFromString(TaskRemovedDto.serializer(), expectTaskFrame("task_removed"))

    private suspend fun DefaultClientWebSocketSession.expectTaskFrame(type: String): String {
        val (actual, text) = nextTaskFrame()
        assertEquals(type, actual, "expected a $type frame, got $actual: $text")
        return text
    }

    private suspend fun DefaultClientWebSocketSession.nextTaskFrame(): Pair<String, String> {
        while (true) {
            val (type, text) = nextFrame()
            if (type.startsWith("task")) return type to text
        }
    }

    private suspend fun DefaultClientWebSocketSession.frameOfKind(type: String): Pair<String, String> {
        while (true) {
            val frame = nextFrame()
            if (frame.first == type) return frame
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextFrame(): Pair<String, String> {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val type = runCatching {
                TRANSPORT_JSON.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
            }.getOrNull() ?: continue
            return type to text
        }
    }

    private fun entryOf(
        ref: TaskRef,
        project: ProjectId,
        position: Double = 1.0,
        state: TaskState = TaskState.todo,
        blocked: Boolean = false,
        rev: Long = 1L,
    ) = BacklogEntry(
        ref = ref,
        project = project,
        position = position,
        state = state,
        blocked = blocked,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        rev = rev,
    )


    private class FakeTaskStore(
        updatesBuffer: Int = 1024,
    ) : TaskStore {
        private val lock = Mutex()
        private val projects = LinkedHashMap<ProjectId, ProjectRecord>()
        private val entries = LinkedHashMap<TaskRef, BacklogEntry>()
        private val trackerRows = LinkedHashMap<TaskRef, Task>()
        private val edges = LinkedHashMap<TaskRef, MutableList<TaskRef>>()
        private var rev = 0L

        // Parks the baseline's final read outside the lock so live updates can enter the gap.
        val baselineEntered = CompletableDeferred<Unit>()
        val baselineGate = CompletableDeferred<Unit>()

        override val id: String = TaskRef.LOCAL_TRACKER

        private val updates = MutableSharedFlow<TaskUpdate>(
            extraBufferCapacity = updatesBuffer,
            // Zero capacity makes collector readiness a deterministic rendezvous rather than a burst race.
            onBufferOverflow = if (updatesBuffer == 0) BufferOverflow.SUSPEND else BufferOverflow.DROP_OLDEST,
        )
        override val taskUpdates: SharedFlow<TaskUpdate> = updates


        fun seedProject(id: ProjectId, name: String, path: String) {
            projects[id] = ProjectRecord(id, name, path, updatedAt = 1_000L)
        }

        fun seedTask(
            ref: TaskRef,
            project: ProjectId,
            title: String,
            position: Double,
            state: TaskState = TaskState.todo,
            blocked: Boolean = false,
        ) {
            entries[ref] = BacklogEntry(ref, project, position, state, blocked, 1_000L, 1_000L, ++rev)
            trackerRows[ref] = Task(ref, title, "body of $title", null, 1_000L)
        }

        fun seedDependency(ref: TaskRef, dependsOn: TaskRef) {
            edges.getOrPut(ref) { mutableListOf() } += dependsOn
        }


        suspend fun addTask(ref: TaskRef, project: ProjectId, title: String, position: Double) {
            val entry = lock.withLock {
                val created = BacklogEntry(ref, project, position, TaskState.todo, false, 2_000L, 2_000L, ++rev)
                entries[ref] = created
                trackerRows[ref] = Task(ref, title, "body of $title", null, 2_000L)
                created
            }
            updates.emit(TaskUpdate(ref, entry, entry.rev))
        }

        suspend fun renormalize(project: ProjectId) {
            val rewritten = lock.withLock {
                entries.values
                    .filter { it.project == project }
                    .sortedBy { it.position }
                    .mapIndexed { index, entry ->
                        val moved = entry.copy(position = index + 1.0, rev = ++rev)
                        entries[entry.ref] = moved
                        moved
                    }
            }
            rewritten.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
        }


        override suspend fun listProjects(archived: Boolean): List<ProjectRecord> =
            lock.withLock { projects.values.filter { it.archived == archived } }

        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = lock.withLock {
            entries.values.filter { it.project == project }.sortedBy { it.position }
        }

        override suspend fun list(project: ProjectId): List<Task> = lock.withLock {
            entries.values.filter { it.project == project }.mapNotNull { trackerRows[it.ref] }
        }

        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> {
            baselineEntered.complete(Unit)
            baselineGate.await()
            return lock.withLock {
                edges.filterKeys { entries[it]?.project == project }.mapValues { it.value.toList() }
            }
        }

        override suspend fun get(ref: TaskRef): Task? = lock.withLock { trackerRows[ref] }

        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
            lock.withLock { edges[ref]?.toList().orEmpty() }


        override suspend fun startIfTodo(ref: TaskRef): Boolean {
            val started = lock.withLock {
                val existing = entries[ref]
                if (existing == null || existing.state != TaskState.todo) {
                    null
                } else {
                    existing.copy(state = TaskState.in_progress, rev = ++rev).also { entries[ref] = it }
                }
            } ?: return false
            updates.emit(TaskUpdate(ref, started, started.rev))
            return true
        }

        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? {
            val moved = lock.withLock {
                entries[ref]?.copy(state = to, rev = ++rev)?.also { entries[ref] = it }
            } ?: return null
            updates.emit(TaskUpdate(ref, moved, moved.rev))
            return moved
        }

        override suspend fun delete(ref: TaskRef): Boolean {
            val removedRev = lock.withLock {
                if (entries.remove(ref) == null) return@withLock null
                trackerRows.remove(ref)
                edges.remove(ref)
                ++rev
            } ?: return false
            updates.emit(TaskUpdate(ref, null, removedRev))
            return true
        }


        override suspend fun entry(ref: TaskRef): BacklogEntry? = unused("entry")
        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = unused("nextCandidate")
        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = unused("move")
        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = unused("dependentsOf")
        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) = unused("addDependency")
        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) = unused("removeDependency")
        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
            unused("comment")
        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = unused("appendActivity")
        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = unused("activity")
        override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
            unused("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")
        override suspend fun upsertProject(id: ProjectId, name: String, path: String?) = unused("upsertProject")
        override suspend fun setProjectArchived(id: ProjectId, archived: Boolean) = unused("setProjectArchived")
        override suspend fun project(id: ProjectId): ProjectRecord? = unused("project")

        private fun unused(name: String): Nothing =
            error("the events socket is not expected to call TaskStore.$name")
    }

    private class EmptyEventStore : EventStore {
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()

        override suspend fun listSessions(): List<SessionMeta> = emptyList()
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()

        override suspend fun upsertSession(meta: SessionMeta) = unused("upsertSession")
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
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
            unused("append")
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = unused("read")
        override suspend fun projectionOf(sessionId: SessionId): Projection = unused("projectionOf")

        private fun unused(name: String): Nothing =
            error("the events socket is not expected to call EventStore.$name")
    }

    private class FixedPreferencesStore : PreferencesStore {
        override val preferences: StateFlow<UiPreferences> =
            MutableStateFlow(UiPreferences("/tmp", 1, 1))

        override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
            error("the events socket is not expected to save preferences")
    }
}
