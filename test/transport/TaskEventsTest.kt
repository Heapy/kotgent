package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePreferencesStore
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.task.BacklogEntry
import io.kotgent.task.Task
import io.kotgent.task.TaskState
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
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
            tasks.seedTask(TaskRef("local:2"), alpha, "second", position = 2.0)
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
    fun theBaselineCarriesADeletedProjectsCardsInOneReadBecauseThePageHasNoOtherSourceForThem() = withTasksSocket(
        seed = { tasks ->
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedProject(beta, "beta", "/repo/beta", archived = true)
            tasks.seedTask(TaskRef("local:1"), alpha, "live", position = 1.0)
            tasks.seedTask(TaskRef("local:9"), beta, "deleted but kept", position = 1.0)
        },
    ) { ws ->
        val snapshot = ws.expectSnapshot()
        assertEquals(
            listOf("local:1", "local:9"),
            snapshot.tasks.map { it.ref },
            "`listProjects()` defaults to the LIVE projects — right for a selector, wrong here: this " +
                "snapshot is the only thing a page builds its task list from, so a deleted project's " +
                "deep-linked card would 404 in the browser and a restore would show an empty backlog " +
                "until a reload, which is not what 'reads stay open' means. The fake's selector throws, " +
                "so this also pins the read as ONE observation: asking it twice would ship a project " +
                "TWICE across a delete (a duplicate card no later patch can reach) and not at all " +
                "across a restore, and the baseline is one-shot per socket",
        )
        assertEquals(
            "deleted but kept",
            snapshot.tasks.single { it.ref == "local:9" }.title,
            "and it is a whole row, joined like any other",
        )
    }

    @Test
    fun aDaemonWithoutATaskStoreSendsNoTaskFramesAtAll() = runBlocking {
        withTimeout(30.seconds) {
            withServer(tasks = null) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") {
                    assertEquals(
                        "sessions_snapshot",
                        frameOfKind("sessions_snapshot").first,
                        "the socket itself works",
                    )
                    assertNull(
                        withTimeoutOrNull(1.seconds) { nextTaskFrame() },
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

        val _ = ws.tasks.addTask(TaskRef("local:1"), alpha, "fresh", position = 1.0)
        val row = ws.expectRow()
        assertEquals("local:1", row.task.ref, "a ref new to this socket arrives as a full row")
        assertEquals("fresh", row.task.title, "…carrying everything the client needs to render a card")

        val _ = ws.tasks.transition(TaskRef("local:1"), TaskState.review, author = "s-1", message = null)
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
        val _ = ws.expectSnapshot()

        assertTrue(ws.tasks.delete(TaskRef("local:1")), "the task went away")
        assertEquals("local:1", ws.expectRemoved().ref, "a null-entry update becomes task_removed")

        val _ = ws.tasks.addTask(TaskRef("local:1"), alpha, "reborn", position = 1.0)
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
            seen.all { [ref, patch] -> patch.task.rev > before.getValue(ref) },
            "…each with a fresh rev, or a connected board would hold stale positions",
        )
    }

    @Test
    fun aBurstEmittedWhileTheBaselineIsBeingReadIsDeliveredAfterIt() = runBlocking {
        withTimeout(30.seconds) {
            val tasks = tasksStore()
            val baseline = Baseline(tasks)
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:0"), alpha, "already there", position = 0.5)
            val burst = (1..20).map { TaskRef("local:$it") }

            withServer(tasks) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") {
                    baseline.entered.await()
                    burst.forEach {
                        val _ = tasks.addTask(it, alpha, "burst ${it.key}", position = it.key.toDouble())
                    }
                    val _ = baseline.gate.complete(Unit)

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
        withTimeout(30.seconds) {
            val tasks = tasksStore(updatesBuffer = 0)
            val baseline = Baseline(tasks)
            tasks.seedProject(alpha, "alpha", "/repo/alpha")
            tasks.seedTask(TaskRef("local:0"), alpha, "already there", position = 0.5)

            withServer(tasks) { port, client ->
                client.webSocket("ws://127.0.0.1:$port/events") {
                    baseline.entered.await()
                    withTimeout(5.seconds) {
                        val _ = tasks.addTask(TaskRef("local:1"), alpha, "banked", position = 1.0)
                    }
                    val _ = baseline.gate.complete(Unit)

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
        withTimeout(30.seconds) {
            val tasks = tasksStore()
            val _ = Baseline(tasks).gate.complete(Unit)
            seed(tasks)
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
            routing { eventsWs(FakeEventStore(), FakePreferencesStore(), tasks, TRANSPORT_JSON) }
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
        val [actual, text] = nextTaskFrame()
        assertEquals(type, actual, "expected a $type frame, got $actual: $text")
        return text
    }

    private suspend fun DefaultClientWebSocketSession.nextTaskFrame(): Pair<String, String> {
        while (true) {
            val [type, text] = nextFrame()
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


    /** Parks the baseline's edge read outside the lock so live updates can enter the gap. */
    private class Baseline(store: FakeTaskStore) {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        init {
            store.beforeDependencyEdges = {
                val _ = entered.complete(Unit)
                gate.await()
            }
        }
    }

    private fun tasksStore(updatesBuffer: Int = 1024): FakeTaskStore =
        FakeTaskStore(updatesBuffer = updatesBuffer).also {
            it.interceptor = ForbiddingInterceptor(setOf("listProjects")) { _, _ ->
                "the /events baseline owes TaskStore.listAllProjects' single-observation contract"
            }
        }
}
