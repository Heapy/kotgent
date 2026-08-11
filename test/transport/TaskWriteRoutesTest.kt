package io.kotgent.transport

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProjectId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.MoveTarget
import io.kotgent.task.PROJECT_FILE_NAME
import io.kotgent.task.ProjectFile
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.ProjectPathException
import io.kotgent.task.ProjectRecord
import io.kotgent.task.ProjectRegistration
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import io.kotgent.task.parseProjectFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

class TaskWriteRoutesTest {

    private val token = "task-write-routes-master-token-0123456789"
    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("11111111-2222-4333-8444-555555555555")
    private val minted = ProjectId.of("9a9a9a9a-1b1b-4c4c-8d8d-0e0e0e0e0e0e")
    private val paneOne = "%1"
    private val sessionOne = SessionId("s-one")
    private val sessionTwo = SessionId("s-two")


    @Test
    fun createWithAnExplicitProjectAndNoPaneNeedsNoSession() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"write it","body":"and test it"}""")

        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.project)
        assertEquals("write it", dto.title)
        assertEquals("and test it", dto.body)
        assertEquals(TaskState.todo.name, dto.state)
        assertEquals(listOf(TaskRef(dto.ref)), env.tasks.snapshotEntries().keys.toList())
        assertTrue(env.fs.reads.isEmpty(), "an explicit project reads no .kotgent.json")
        assertTrue(env.writer.calls.isEmpty(), "and writes none either")
    }

    @Test
    fun createWithAnUnknownExplicitProjectIs404() = withTaskServer { env ->
        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"x"}""")

        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains(alpha.value), "the body names the project it could not find")
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "nothing was created")
    }

    @Test
    fun createNamingADeletedProjectIs404LikeAnUnknownOne() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"x"}""")

        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "a tombstoned project is not addressable — a caller naming it gets what an unknown uuid gets",
        )
        assertTrue(resp.bodyAsText().contains(alpha.value), "the body names the project it refused")
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "nothing was created")
    }

    @Test
    fun createWithAMalformedExplicitProjectIs400() = withTaskServer { env ->
        val resp = env.post("/tasks", """{"project":"not-a-uuid","title":"x"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("uuid"))
    }

    @Test
    fun createFromAPaneWhoseSessionHasAProjectUsesItWithoutTouchingTheFilesystem() = withTaskServer { env ->
        env.tasks.seedProject(beta, "beta", "/repo")
        env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = beta)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"from the pane"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
        assertEquals(beta.value, dto.project)
        assertTrue(env.fs.reads.isEmpty(), "the session's stored project_id short-circuits resolution")
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun createFromAPaneResolvesTheCommittedFileAboveTheCwdAndRegistersIt() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/sub")
        env.fs.files["/repo/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"kotgent"}"""
        env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"resolved"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.project)
        assertEquals(
            ProjectRecord(alpha, "kotgent", "/repo", 0L),
            env.tasks.snapshotProjects()[alpha],
            "reading a project file registers the project, at the checkout the daemon just saw",
        )
        assertTrue(env.writer.calls.isEmpty(), "an existing file is adopted, never rewritten")
    }

    @Test
    fun createFromAPaneInAProjectlessDirectoryWritesTheFileAndRegistersTheProject() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/sub", "/repo/.git")
        env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"first ever"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
        assertEquals(minted.value, dto.project)
        assertEquals(
            listOf("/repo" to "repo"),
            env.writer.calls,
            "the file goes to the main checkout root, named after it — not into the session's subdirectory",
        )
        assertEquals(
            ProjectRecord(minted, "repo", "/repo", 0L),
            env.tasks.snapshotProjects()[minted],
            "a created project must appear in GET /projects or its backlog is unreachable",
        )
    }

    @Test
    fun createFromAPaneOutsideAnyRepositoryCreatesTheFileInTheSessionsOwnDirectory() = withTaskServer { env ->
        env.fs.dirs += setOf("/scratch", "/scratch/notes")
        env.sessions.seed(sessionOne, cwd = "/scratch/notes", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"loose"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(listOf("/scratch/notes" to "notes"), env.writer.calls)
    }

    @Test
    fun createFromAPaneWhoseProjectWasDeletedIsRefusedBeforeAnythingIsWritten() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/sub")
        env.fs.files["/repo/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"kotgent"}"""
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"into a deleted project"}""", pane = paneOne)

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent") && body.contains(alpha.value), "the refusal names the project: $body")
        assertTrue(body.contains("kotgent project restore ${alpha.value}"), "and the way back: $body")
        assertTrue(body.contains("--project"), "and the way past it: $body")
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "no card was filed into a deleted project")
        assertTrue(
            env.writer.calls.isEmpty(),
            "the refusal came before the fallback — that file is still on disk and would mint the same uuid",
        )
        assertNull(
            assertNotNull(env.sessions.snapshot()[sessionOne]).projectId,
            "and the deleted project was not bound onto the calling session either",
        )
    }

    @Test
    fun createFromAPaneWhoseDeletedProjectWasRestoredFilesTheTaskAsBefore() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/sub")
        env.fs.files["/repo/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"kotgent"}"""
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        assertTrue(env.tasks.setProjectArchived(alpha, false), "the operator restores the project")

        val resp = env.post("/tasks", """{"title":"back in business"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status, "the guard keys on the mark, not on the file or the row")
        assertEquals(
            alpha.value,
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText()).project,
        )
        assertEquals(alpha, assertNotNull(env.sessions.snapshot()[sessionOne]).projectId)
    }

    @Test
    fun createWithNeitherAProjectNorASessionIs400NamingProject() = withTaskServer { env ->
        val resp = env.post("/tasks", """{"title":"orphan"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("--project"), "the message names the fix")
        assertTrue(env.tasks.snapshotEntries().isEmpty())
        assertTrue(env.writer.calls.isEmpty(), "nothing is created on disk for a request that answers 400")
    }

    @Test
    fun createFromAnUnknownPaneIs400() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha)

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"x"}""", pane = "%99")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("%99"), "the refusal names the pane it could not resolve")
        assertTrue(env.tasks.snapshotEntries().isEmpty())
        assertTrue(env.tasks.snapshotActivity().isEmpty(), "and nothing was filed on the board's behalf")
    }

    @Test
    fun anIdentityThatNamesNobodyIsRefusedRatherThanAttributedToTheBoard() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")

        val garbagePane = env.post("/tasks", """{"project":"${alpha.value}","title":"x"}""", pane = "not-a-pane")
        assertEquals(HttpStatusCode.BadRequest, garbagePane.status)
        assertTrue(garbagePane.bodyAsText().contains("not-a-pane"), garbagePane.bodyAsText())

        val blank = env.post("/tasks", """{"project":"${alpha.value}","title":"x","sessionId":"   "}""")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertTrue(blank.bodyAsText().contains("--session"), blank.bodyAsText())

        assertTrue(env.tasks.snapshotEntries().isEmpty(), "neither request filed a card")
        assertTrue(env.tasks.snapshotActivity().isEmpty(), "and neither was recorded as the board's")
    }

    @Test
    fun createWithAnExplicitSessionIdResolvesItsProject() = withTaskServer { env ->
        env.tasks.seedProject(beta, "beta", "/repo")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = beta)

        val resp = env.post("/tasks", """{"title":"named","sessionId":"${sessionOne.value}"}""")

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(
            beta.value,
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText()).project,
        )
    }


    @Test
    fun createFromAPaneBindsTheProjectItResolvedOntoTheCallingSession() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/sub")
        env.fs.files["/repo/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"kotgent"}"""
        env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = null, updatedAt = 4242L)
        env.panes[PaneId(paneOne)] = sessionOne

        assertEquals(HttpStatusCode.Created, env.post("/tasks", """{"title":"resolved"}""", pane = paneOne).status)

        val row = assertNotNull(env.sessions.snapshot()[sessionOne])
        assertEquals(
            alpha,
            row.projectId,
            "the session that resolved a project keeps it, or its next ref-less task command has no project",
        )
        assertEquals(4242L, row.updatedAt, "a derived backfill is not activity and must not restamp the sort key")
    }

    @Test
    fun createFromAPaneInAProjectlessDirectoryBindsTheProjectItCreatedOntoTheCallingSession() =
        withTaskServer { env ->
            env.fs.dirs += setOf("/repo", "/repo/sub", "/repo/.git")
            env.sessions.seed(sessionOne, cwd = "/repo/sub", projectId = null)
            env.panes[PaneId(paneOne)] = sessionOne

            assertEquals(HttpStatusCode.Created, env.post("/tasks", """{"title":"first ever"}""", pane = paneOne).status)

            assertEquals(minted, assertNotNull(env.sessions.snapshot()[sessionOne]).projectId)
        }

    @Test
    fun createWithAnExplicitProjectDoesNotRePointTheCallingSession() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/other")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = beta)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"someone else's"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(beta, assertNotNull(env.sessions.snapshot()[sessionOne]).projectId)
    }


    @Test
    fun aCreateFromAPaneIsAttributedToTheCallingSession() = withTaskServer { env ->
        env.tasks.seedProject(beta, "beta", "/repo")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = beta)
        env.panes[PaneId(paneOne)] = sessionOne

        assertEquals(HttpStatusCode.Created, env.post("/tasks", """{"title":"mine"}""", pane = paneOne).status)

        val created = env.tasks.snapshotActivity().single { it.kind == ActivityKind.created }
        assertEquals(sessionOne.value, created.author)
    }

    @Test
    fun aCreateFromTheBoardIsAttributedToTheBoard() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")

        assertEquals(
            HttpStatusCode.Created,
            env.post("/tasks", """{"project":"${alpha.value}","title":"from the browser"}""").status,
        )

        assertEquals(
            TaskService.BOARD_AUTHOR,
            env.tasks.snapshotActivity().single { it.kind == ActivityKind.created }.author,
        )
    }

    @Test
    fun aCreateNamingASessionThatDoesNotExistIs400AndWritesNothing() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")
        env.fs.dirs += setOf("/repo", "/repo/.git")

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"x","sessionId":"s-ghost"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("s-ghost"), resp.bodyAsText())
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "no task")
        assertTrue(env.tasks.snapshotActivity().isEmpty(), "and no activity attributed to anyone")
        assertTrue(env.writer.calls.isEmpty(), "the author is resolved before anything can be written")
    }

    @Test
    fun createWithoutATitleIs400() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")
        assertEquals(
            HttpStatusCode.BadRequest,
            env.post("/tasks", """{"project":"${alpha.value}","title":"   "}""").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            env.post("/tasks", """not json""").status,
        )
        assertTrue(env.tasks.snapshotEntries().isEmpty())
    }


    @Test
    fun aStateChangeWithAMessageWritesExactlyOneActivityRow() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "ship it")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha)
        env.panes[PaneId(paneOne)] = sessionOne
        env.tasks.clearActivity()

        val resp = env.patch("/tasks/${ref.value}", """{"state":"review","message":"please look"}""", pane = paneOne)

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(
            TaskState.review.name,
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText()).state,
        )
        val feed = env.tasks.snapshotActivity().filter { it.ref == ref }
        assertEquals(1, feed.size, "the transition and its explanation are one row, not two")
        assertEquals(ActivityKind.transition, feed.single().kind)
        assertEquals("please look", feed.single().text)
        assertEquals(sessionOne.value, feed.single().author, "attributed to the calling pane's session")
    }

    @Test
    fun aStateChangeFromTheBoardIsAttributedToTheBoard() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "drag me")
        env.tasks.clearActivity()

        assertEquals(HttpStatusCode.OK, env.patch("/tasks/${ref.value}", """{"state":"in_progress"}""").status)

        assertEquals(
            listOf(TaskService.BOARD_AUTHOR),
            env.tasks.snapshotActivity().filter { it.ref == ref }.map { it.author },
        )
    }

    @Test
    fun aStateChangeNamingASessionThatDoesNotExistIs400AndWritesNothing() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "old title")
        env.tasks.clearActivity()

        val resp = env.patch(
            "/tasks/${ref.value}",
            """{"title":"new title","state":"review","sessionId":"s-ghost"}""",
        )

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("s-ghost"), "the refusal names the session it could not find")
        assertEquals(TaskState.todo, env.tasks.snapshotEntries().getValue(ref).state, "no transition landed")
        assertEquals(
            "old title",
            env.tasks.snapshotTasks().getValue(ref).title,
            "and the tracker edit did not land either — the author is resolved before the first write",
        )
        assertTrue(
            env.tasks.snapshotActivity().isEmpty(),
            "nothing was attributed to the board on the caller's behalf",
        )
    }

    @Test
    fun aStateChangeFromAPaneTheRegistryDoesNotKnowIs400AndWritesNothing() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "drag me")
        env.tasks.clearActivity()

        val resp = env.patch("/tasks/${ref.value}", """{"state":"in_progress"}""", pane = "%99")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("%99"), "the refusal names the pane it could not resolve")
        assertEquals(TaskState.todo, env.tasks.snapshotEntries().getValue(ref).state, "no transition landed")
        assertTrue(
            env.tasks.snapshotActivity().isEmpty(),
            "and nothing was attributed to the board on the caller's behalf",
        )
    }

    @Test
    fun aPatchCanCarryTrackerFieldsAndAStateAtOnce() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "old title")

        val resp = env.patch("/tasks/${ref.value}", """{"title":"new title","body":"why","state":"done"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText())
        assertEquals("new title", dto.title)
        assertEquals("why", dto.body)
        assertEquals(TaskState.done.name, dto.state)
    }

    @Test
    fun patchingATaskToDoneUnlinksEveryHolder() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "close me")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha, taskRef = ref)
        env.sessions.seed(sessionTwo, cwd = "/repo", projectId = alpha, taskRef = ref)

        assertEquals(HttpStatusCode.OK, env.patch("/tasks/${ref.value}", """{"state":"done"}""").status)

        assertNull(env.sessions.snapshot()[sessionOne]?.taskRef)
        assertNull(env.sessions.snapshot()[sessionTwo]?.taskRef)
        assertTrue(env.sessions.archived.isEmpty(), "closing a task never archives a session")
    }

    @Test
    fun aPatchWithNothingToChangeOrAnImpossibleStateIs400() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "steady")

        assertEquals(HttpStatusCode.BadRequest, env.patch("/tasks/${ref.value}", "{}").status)
        val unknown = env.patch("/tasks/${ref.value}", """{"state":"archived"}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.bodyAsText().contains("in_progress"), "the message lists the states that exist")
        assertEquals(TaskState.todo, env.tasks.snapshotEntries().getValue(ref).state)
    }

    @Test
    fun aMessageWithoutAStateChangeIs400AndPointsAtComment() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "steady")

        val resp = env.patch("/tasks/${ref.value}", """{"body":"edited","message":"a note"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("comment"), "the message names where a standalone note goes")
        assertEquals("", env.tasks.snapshotTasks().getValue(ref).body, "the refused patch wrote nothing")
    }


    @Test
    fun aDeleteUnlinksEveryHolderAndRemovesTheTask() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "obsolete")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha, taskRef = ref)
        env.sessions.seed(sessionTwo, cwd = "/repo", projectId = alpha, taskRef = ref)

        val resp = env.request(HttpMethod.Delete, "/tasks/${ref.value}")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "the task is gone")
        assertNull(env.sessions.snapshot()[sessionOne]?.taskRef, "no session is left holding a dangling badge")
        assertNull(env.sessions.snapshot()[sessionTwo]?.taskRef)
    }

    @Test
    fun deletingAnUnknownTaskIs404() = withTaskServer { env ->
        val resp = env.request(HttpMethod.Delete, "/tasks/local:404")
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains("local:404"))
    }


    @Test
    fun aMoveReRanksTheEntryAndRequiresExactlyOneTarget() = withTaskServer { env ->
        val first = env.seedTask(alpha, "first")
        val second = env.seedTask(alpha, "second")

        val moved = env.post("/tasks/${second.value}/move", """{"before":"${first.value}"}""")
        assertEquals(HttpStatusCode.OK, moved.status)
        assertTrue(
            env.tasks.snapshotEntries().getValue(second).position <
                env.tasks.snapshotEntries().getValue(first).position,
            "the moved entry really is ranked above its neighbour now",
        )

        for (body in listOf("{}", """{"top":true,"bottom":true}""", """{"before":"nope"}""")) {
            val bad = env.post("/tasks/${second.value}/move", body)
            assertEquals(HttpStatusCode.BadRequest, bad.status, "refused: $body")
            assertTrue(bad.bodyAsText().contains("exactly one"))
        }
    }

    @Test
    fun aMoveNamingSomethingThatIsNotThereIs404() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "only")

        assertEquals(HttpStatusCode.NotFound, env.post("/tasks/local:404/move", """{"top":true}""").status)
        val neighbour = env.post("/tasks/${ref.value}/move", """{"after":"local:404"}""")
        assertEquals(HttpStatusCode.NotFound, neighbour.status)
        assertTrue(neighbour.bodyAsText().contains("neighbour"))
    }


    @Test
    fun theFourDependencyRefusalsAreEach400NamingWhich() = withTaskServer { env ->
        val a = env.seedTask(alpha, "a")
        val b = env.seedTask(alpha, "b")
        val elsewhere = env.seedTask(beta, "elsewhere")
        assertEquals(HttpStatusCode.OK, env.post("/tasks/${b.value}/deps", """{"action":"add","on":"${a.value}"}""").status)

        val refusals = listOf(
            Triple(a, a, DependencyRefusal.self),
            Triple(a, TaskRef("local:404"), DependencyRefusal.unknownRef),
            Triple(TaskRef("local:404"), a, DependencyRefusal.unknownRef),
            Triple(a, elsewhere, DependencyRefusal.crossProject),
            Triple(a, b, DependencyRefusal.cycle),
        )
        for ((ref, on, refusal) in refusals) {
            val resp = env.post("/tasks/${ref.value}/deps", """{"action":"add","on":"${on.value}"}""")
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$refusal must be a 400")
            assertTrue(
                resp.bodyAsText().contains(refusal.name),
                "the body says which refusal it was; got '${resp.bodyAsText()}'",
            )
        }
        assertEquals(
            listOf(a),
            env.tasks.snapshotDeps()[b],
            "no refused edge landed, and the legitimate one is still there",
        )
    }

    @Test
    fun aDependencyCanBeAddedAndRemovedAndTheAnswerCarriesTheEdges() = withTaskServer { env ->
        val a = env.seedTask(alpha, "a")
        val b = env.seedTask(alpha, "b")

        val added = env.post("/tasks/${b.value}/deps", """{"action":"add","on":"${a.value}"}""")
        assertEquals(HttpStatusCode.OK, added.status)
        assertEquals(
            listOf(a.value),
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), added.bodyAsText()).dependsOn,
        )

        val removed = env.post("/tasks/${b.value}/deps", """{"action":"remove","on":"${a.value}"}""")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertEquals(
            emptyList(),
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), removed.bodyAsText()).dependsOn,
        )
    }

    @Test
    fun aDepsRequestWithAnUnknownActionOrMalformedTargetIs400() = withTaskServer { env ->
        val a = env.seedTask(alpha, "a")

        assertEquals(
            HttpStatusCode.BadRequest,
            env.post("/tasks/${a.value}/deps", """{"action":"toggle","on":"${a.value}"}""").status,
        )
        val malformed = env.post("/tasks/${a.value}/deps", """{"action":"add","on":"no-colon"}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertTrue(malformed.bodyAsText().contains("local:42"), "the message shows the shape it wanted")
    }

    @Test
    fun removingADependencyOfAnUnknownTaskIs404() = withTaskServer { env ->
        val a = env.seedTask(alpha, "a")
        assertEquals(
            HttpStatusCode.NotFound,
            env.post("/tasks/local:404/deps", """{"action":"remove","on":"${a.value}"}""").status,
        )
    }


    @Test
    fun aCommentRequiresASessionAndIsAttributedToIt() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "discuss")
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha)
        env.panes[PaneId(paneOne)] = sessionOne
        env.tasks.clearActivity()

        val anonymous = env.post("/tasks/${ref.value}/comment", """{"text":"who said that"}""")
        assertEquals(HttpStatusCode.BadRequest, anonymous.status)
        assertTrue(anonymous.bodyAsText().contains("--session"), "the message names the fix")
        assertTrue(env.tasks.snapshotActivity().isEmpty(), "an unattributable comment writes nothing")

        val resp = env.post("/tasks/${ref.value}/comment", """{"text":"looked at it"}""", pane = paneOne)
        assertEquals(HttpStatusCode.Created, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ActivityEntryDto.serializer(), resp.bodyAsText())
        assertEquals(ActivityKind.comment.name, dto.kind)
        assertEquals(sessionOne.value, dto.author)
        assertEquals("looked at it", dto.text)
        assertEquals(1, env.tasks.snapshotActivity().size)
    }

    @Test
    fun aCommentFromASessionThatDoesNotExistIs400() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "discuss")
        env.tasks.clearActivity()

        val resp = env.post("/tasks/${ref.value}/comment", """{"text":"hi","sessionId":"s-ghost"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(env.tasks.snapshotActivity().isEmpty())
    }

    @Test
    fun aBlankCommentIs400AndAnUnknownTaskIs404() = withTaskServer { env ->
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha)
        env.panes[PaneId(paneOne)] = sessionOne
        val ref = env.seedTask(alpha, "discuss")

        assertEquals(
            HttpStatusCode.BadRequest,
            env.post("/tasks/${ref.value}/comment", """{"text":"  "}""", pane = paneOne).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            env.post("/tasks/local:404/comment", """{"text":"hello"}""", pane = paneOne).status,
        )
    }


    @Test
    fun aMalformedRefIs400OnEveryRouteThatTakesOne() = withTaskServer { env ->
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha)
        env.panes[PaneId(paneOne)] = sessionOne
        val bad = "no-colon"
        val calls = listOf(
            suspend { env.patch("/tasks/$bad", """{"title":"x"}""") },
            suspend { env.request(HttpMethod.Delete, "/tasks/$bad") },
            suspend { env.post("/tasks/$bad/move", """{"top":true}""") },
            suspend { env.post("/tasks/$bad/deps", """{"action":"add","on":"local:1"}""") },
            suspend { env.post("/tasks/$bad/comment", """{"text":"x"}""", pane = paneOne) },
        )
        for (call in calls) {
            val resp = call()
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("malformed task ref"), resp.bodyAsText())
        }
    }


    @Test
    fun postProjectsWritesTheFileAtAnAbsolutePathAndRegistersIt() = withTaskServer { env ->
        env.fs.dirs += "/srv/new-repo"

        val resp = env.post("/projects", """{"path":"/srv/new-repo"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(minted.value, dto.id)
        assertEquals("new-repo", dto.name, "the directory name is the default display name")
        assertEquals("/srv/new-repo", dto.path)
        assertEquals(listOf("/srv/new-repo" to "new-repo"), env.writer.calls)
        assertNotNull(env.tasks.snapshotProjects()[minted])
    }

    @Test
    fun postProjectsAnchorsASubdirectoryAtTheMainCheckoutRoot() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/.git", "/repo/src")

        val resp = env.post("/projects", """{"path":"/repo/src"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals("/repo", dto.path, "the registered path is the checkout, not the subdirectory")
        assertEquals("repo", dto.name, "and the default name is the root's, not 'src'")
        assertEquals(
            listOf("/repo" to "repo"),
            env.writer.calls,
            "the file goes to the main checkout root — the same anchor POST /tasks' step 4 uses",
        )
        assertEquals(
            ProjectRecord(minted, "repo", "/repo", 0L),
            env.tasks.snapshotProjects()[minted],
        )
    }

    @Test
    fun postProjectsPointedAtALinkedWorktreeWritesIntoTheMainCheckout() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/.git", "/repo/.git/worktrees/feature", "/wt/feature")
        env.fs.files["/wt/feature/.git"] = "gitdir: /repo/.git/worktrees/feature\n"

        val resp = env.post("/projects", """{"path":"/wt/feature"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(listOf("/repo" to "repo"), env.writer.calls)
        assertEquals(
            "/repo",
            TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText()).path,
        )
    }

    @Test
    fun postProjectsAdoptsTheProjectAlreadyCommittedAtThePathInsteadOfMintingOneAbove() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/.git", "/repo/packages", "/repo/packages/api")
        env.fs.files["/repo/packages/api/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"api"}"""

        val resp = env.post("/projects", """{"path":"/repo/packages/api"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id, "the answer is the project that OWNS the path, not a fresh uuid")
        assertEquals("api", dto.name)
        assertEquals("/repo/packages/api", dto.path)
        assertTrue(env.writer.calls.isEmpty(), "an owned path is adopted, never written to")
        assertNull(
            env.fs.files["/repo/$PROJECT_FILE_NAME"],
            "and no competing project file appears at the checkout root",
        )
        assertEquals(
            ProjectRecord(alpha, "api", "/repo/packages/api", 0L),
            env.tasks.snapshotProjects()[alpha],
            "reading a project file registers it, so the board's selector can reach its backlog",
        )
    }

    @Test
    fun postProjectsAdoptsTheProjectCommittedAboveTheNamedDirectory() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/.git", "/repo/src")
        env.fs.files["/repo/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"kotgent"}"""

        val resp = env.post("/projects", """{"path":"/repo/src"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id)
        assertEquals("kotgent", dto.name, "the committed name wins over a default derived from the directory")
        assertEquals("/repo", dto.path)
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun postProjectsHonoursAGivenNameAndRefusesOneAFileCouldNotCarry() = withTaskServer { env ->
        env.fs.dirs += "/srv/new-repo"

        assertEquals(
            "Backlog",
            TRANSPORT_JSON.decodeFromString(
                ProjectDto.serializer(),
                env.post("/projects", """{"path":"/srv/new-repo","name":" Backlog "}""").bodyAsText(),
            ).name,
        )

        val tooLong = "n".repeat(101)
        val refused = env.post("/projects", """{"path":"/srv/new-repo","name":"$tooLong"}""")
        assertEquals(
            HttpStatusCode.BadRequest,
            refused.status,
            "a name the resolver would refuse to read back must never reach the file",
        )
        assertEquals(1, env.writer.calls.size, "the refused request wrote nothing")
    }

    @Test
    fun postProjectsRefusesARelativeOrMissingPath() = withTaskServer { env ->
        env.fs.dirs += "/srv/new-repo"

        val relative = env.post("/projects", """{"path":"new-repo"}""")
        assertEquals(HttpStatusCode.BadRequest, relative.status)
        assertTrue(relative.bodyAsText().contains("absolute"), "a relative path must never reach realpath")

        assertEquals(HttpStatusCode.BadRequest, env.post("/projects", """{"path":"/srv/nope"}""").status)
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun postProjectsRefusesAPathThatIsNotADirectory() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/.git")
        env.fs.files["/repo/README.md"] = "# repo\n"

        val resp = env.post("/projects", """{"path":"/repo/README.md"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("/repo/README.md"), resp.bodyAsText())
        assertTrue(env.writer.calls.isEmpty(), "nothing is written for a path that is not a directory")
        assertTrue(env.tasks.snapshotProjects().isEmpty(), "and no project is registered")
        assertNull(env.fs.files["/repo/$PROJECT_FILE_NAME"], "no project file appeared at the checkout root")
    }

    @Test
    fun postProjectsRefusesAFileEvenWhenAProjectIsCommittedAboveIt() = withTaskServer { env ->
        env.fs.dirs += setOf("/repo", "/repo/.git")
        env.fs.files["/repo/$PROJECT_FILE_NAME"] = """{"id":"${alpha.value}","name":"kotgent"}"""
        env.fs.files["/repo/README.md"] = "# repo\n"

        val resp = env.post("/projects", """{"path":"/repo/README.md"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(env.tasks.snapshotProjects().isEmpty(), "adoption must not answer for a path that is a file")
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun aWriterRefusalIsA400() = withTaskServer { env ->
        env.fs.dirs += "/srv/readonly"
        env.writer.failOn += "/srv/readonly"

        val resp = env.post("/projects", """{"path":"/srv/readonly"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("/srv/readonly"))
    }


    @Test
    fun everyWriteRouteRequiresACredential() = withTaskServer { env ->
        val calls = listOf(
            HttpMethod.Post to "/tasks",
            HttpMethod.Patch to "/tasks/local:1",
            HttpMethod.Delete to "/tasks/local:1",
            HttpMethod.Post to "/tasks/local:1/move",
            HttpMethod.Post to "/tasks/local:1/deps",
            HttpMethod.Post to "/tasks/local:1/comment",
            HttpMethod.Post to "/projects",
        )
        for ((method, path) in calls) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                env.request(method, path, body = "{}", bearer = null).status,
                "$method $path is not reachable without a credential",
            )
        }
        assertTrue(env.tasks.snapshotEntries().isEmpty())
    }


    private inner class Env(
        val port: Int,
        val client: HttpClient,
        val tasks: FakeTaskStore,
        val sessions: FakeEventStore,
        val fs: FakeProjectFs,
        val writer: FakeProjectFileWriter,
        val panes: MutableMap<PaneId, SessionId>,
    ) {
        suspend fun request(
            method: HttpMethod,
            path: String,
            body: String? = null,
            pane: String? = null,
            bearer: String? = token,
        ): HttpResponse = client.request("http://127.0.0.1:$port$API_PREFIX$path") {
            this.method = method
            applyIfPresent(HttpHeaders.Authorization, bearer?.let { "Bearer $it" })
            applyIfPresent(TASK_PANE_HEADER, pane)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        suspend fun post(path: String, body: String, pane: String? = null): HttpResponse =
            request(HttpMethod.Post, path, body, pane)

        suspend fun patch(path: String, body: String, pane: String? = null): HttpResponse =
            request(HttpMethod.Patch, path, body, pane)

        suspend fun seedTask(project: ProjectId, title: String): TaskRef {
            tasks.seedProject(project, project.value.take(8), "/repo")
            return tasks.create(project, title, "").ref
        }
    }

    private fun withTaskServer(block: suspend (Env) -> Unit) = runBlocking {
        withTimeout(60_000) {
            val tokens = TokenHolder(token)
            val tasks = FakeTaskStore()
            val sessions = FakeEventStore()
            val fs = FakeProjectFs()
            val writer = FakeProjectFileWriter(fs, minted)
            val panes = mutableMapOf<PaneId, SessionId>()
            val service = TaskService(
                tasks = tasks,
                sessions = sessions,
                projectFs = fs,
                projectFiles = writer,
                now = { 0L },
            )
            val routing = TaskRouting(
                tasks = tasks,
                service = service,
                sessions = sessions,
                paneLookup = { pane -> panes[pane] },
                json = TRANSPORT_JSON,
            )
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    authRoutes(tokens, TicketStore(now = { 0L }), null, TRANSPORT_JSON, now = { 0L })
                    authenticated(tokens::current) {
                        route(API_PREFIX) { taskWriteRoutes(routing) }
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(Env(port, client, tasks, sessions, fs, writer, panes))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    }


    private class FakeTaskStore : TaskStore {
        private val mutex = Mutex()
        private val tasks = mutableMapOf<TaskRef, Task>()
        private val entries = mutableMapOf<TaskRef, BacklogEntry>()
        private val activity = mutableListOf<TaskActivityEntry>()
        private val deps = mutableMapOf<TaskRef, MutableList<TaskRef>>()
        private val projects = mutableMapOf<ProjectId, ProjectRecord>()
        private var nextKey = 0
        private var rev = 0L
        private var activityId = 0L

        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        fun seedProject(project: ProjectId, name: String, path: String?) {
            projects[project] = ProjectRecord(project, name, path, 0L)
        }

        fun clearActivity() {
            activity.clear()
        }

        suspend fun snapshotEntries(): Map<TaskRef, BacklogEntry> = mutex.withLock { entries.toMap() }
        suspend fun snapshotTasks(): Map<TaskRef, Task> = mutex.withLock { tasks.toMap() }
        suspend fun snapshotActivity(): List<TaskActivityEntry> = mutex.withLock { activity.toList() }
        suspend fun snapshotDeps(): Map<TaskRef, List<TaskRef>> =
            mutex.withLock { deps.mapValues { it.value.toList() } }
        suspend fun snapshotProjects(): Map<ProjectId, ProjectRecord> = mutex.withLock { projects.toMap() }

        override suspend fun list(project: ProjectId): List<Task> = mutex.withLock {
            entries.values.filter { it.project == project }.mapNotNull { tasks[it.ref] }
        }

        override suspend fun get(ref: TaskRef): Task? = mutex.withLock { tasks[ref] }

        override suspend fun create(
            project: ProjectId,
            title: String,
            body: String,
            author: String,
        ): Task = mutex.withLock {
            val ref = TaskRef("${TaskRef.LOCAL_TRACKER}:${++nextKey}")
            val task = Task(ref, title, body, url = null, updatedAt = 0L)
            tasks[ref] = task
            val end = entries.values.filter { it.project == project }.maxOfOrNull { it.position } ?: 0.0
            entries[ref] = BacklogEntry(ref, project, end + 1.0, TaskState.todo, false, 0L, 0L, ++rev)
            activity += TaskActivityEntry(++activityId, ref, 0L, ActivityKind.created, author, null, null, null)
            task
        }

        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = mutex.withLock {
            val existing = tasks[ref] ?: return@withLock null
            val updated = existing.copy(title = title ?: existing.title, body = body ?: existing.body)
            tasks[ref] = updated
            entries[ref]?.let { entries[ref] = it.copy(rev = ++rev) }
            updated
        }

        override suspend fun delete(ref: TaskRef): Boolean = mutex.withLock {
            activity.removeAll { it.ref == ref }
            deps.remove(ref)
            deps.values.forEach { it.remove(ref) }
            entries.remove(ref)
            tasks.remove(ref) != null
        }

        override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { entries[ref] }

        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = mutex.withLock {
            entries.values.filter { it.project == project }.sortedBy { it.position }
        }

        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = mutex.withLock {
            entries.values
                .filter { it.project == project && it.state == TaskState.todo }
                .minByOrNull { it.position }
        }

        override suspend fun startIfTodo(ref: TaskRef): Boolean = mutex.withLock {
            val existing = entries[ref] ?: return@withLock false
            if (existing.state != TaskState.todo) return@withLock false
            entries[ref] = existing.copy(state = TaskState.in_progress, rev = ++rev)
            true
        }

        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = mutex.withLock {
            val existing = entries[ref] ?: return@withLock null
            val moved = existing.copy(state = to, rev = ++rev)
            entries[ref] = moved
            activity += TaskActivityEntry(
                ++activityId, ref, 0L, ActivityKind.transition, author, message, existing.state, to,
            )
            moved
        }

        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = mutex.withLock {
            val existing = entries[ref] ?: return@withLock null
            val siblings = entries.values.filter { it.project == existing.project && it.ref != ref }
            val position = when (target) {
                MoveTarget.Top -> (siblings.minOfOrNull { it.position } ?: 1.0) - 1.0
                MoveTarget.Bottom -> (siblings.maxOfOrNull { it.position } ?: 0.0) + 1.0
                is MoveTarget.Before -> (entries[target.ref] ?: return@withLock null).position - 0.5
                is MoveTarget.After -> (entries[target.ref] ?: return@withLock null).position + 0.5
            }
            val moved = existing.copy(position = position, rev = ++rev)
            entries[ref] = moved
            moved
        }

        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
            mutex.withLock { deps[ref].orEmpty().toList() }

        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> =
            mutex.withLock { deps.filterValues { ref in it }.keys.toList() }

        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> = mutex.withLock {
            deps.filterKeys { entries[it]?.project == project }.mapValues { it.value.toList() }
        }

        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
            fun refuse(refusal: DependencyRefusal, why: String): Nothing = throw DependencyRefusedException(
                refusal, ref, dependsOn,
                "cannot add '${ref.value}' depends on '${dependsOn.value}': $why (${refusal.name})",
            )
            if (ref == dependsOn) refuse(DependencyRefusal.self, "a task cannot depend on itself")
            val from = entries[ref] ?: refuse(DependencyRefusal.unknownRef, "no such task '${ref.value}'")
            val to = entries[dependsOn]
                ?: refuse(DependencyRefusal.unknownRef, "no such task '${dependsOn.value}'")
            if (from.project != to.project) {
                refuse(DependencyRefusal.crossProject, "they belong to different projects")
            }
            if (reaches(dependsOn, ref)) refuse(DependencyRefusal.cycle, "it would close a ring")
            val edges = deps.getOrPut(ref) { mutableListOf() }
            if (dependsOn !in edges) edges += dependsOn
            entries[ref] = from.copy(rev = ++rev)
        }

        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
            deps[ref]?.remove(dependsOn)
            entries[ref]?.let { entries[ref] = it.copy(rev = ++rev) }
        }

        private fun reaches(start: TaskRef, target: TaskRef): Boolean {
            val seen = mutableSetOf<TaskRef>()
            val stack = ArrayDeque(listOf(start))
            while (stack.isNotEmpty()) {
                val here = stack.removeLast()
                if (here == target) return true
                if (!seen.add(here)) continue
                stack += deps[here].orEmpty()
            }
            return false
        }

        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
            mutex.withLock {
                if (ref !in entries) return@withLock null
                val row = TaskActivityEntry(++activityId, ref, 0L, ActivityKind.comment, author, text, null, null)
                activity += row
                row
            }

        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = mutex.withLock {
            if (ref !in entries) return@withLock null
            val row = TaskActivityEntry(++activityId, ref, 0L, kind, author, text, fromState, toState)
            activity += row
            row
        }

        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> =
            mutex.withLock { activity.filter { it.ref == ref } }

        override suspend fun upsertProject(id: ProjectId, name: String, path: String?): ProjectRegistration =
            mutex.withLock {
                val existing = projects[id]
                if (existing != null && existing.archived) {
                    return@withLock ProjectRegistration.refusedArchived
                }
                projects[id] = ProjectRecord(id, name, path ?: existing?.path, 0L)
                ProjectRegistration.registered
            }

        override suspend fun setProjectArchived(id: ProjectId, archived: Boolean): Boolean = mutex.withLock {
            val existing = projects[id] ?: return@withLock false
            projects[id] = existing.copy(archived = archived)
            true
        }

        override suspend fun listProjects(archived: Boolean): List<ProjectRecord> =
            mutex.withLock { projects.values.filter { it.archived == archived }.sortedBy { it.name } }

        override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock { projects[id] }
    }

    private class FakeEventStore : EventStore {
        private val mutex = Mutex()
        private val rows = mutableMapOf<SessionId, SessionMeta>()

        val archived: MutableSet<SessionId> = mutableSetOf()

        fun seed(
            id: SessionId,
            cwd: String,
            projectId: ProjectId?,
            taskRef: TaskRef? = null,
            updatedAt: Long = 0L,
        ) {
            rows[id] = SessionMeta(
                id = id,
                name = id.value,
                agent = "claude",
                cwd = cwd,
                tmuxSession = "kt-${id.value}",
                state = SessionState.running,
                stateSource = EventSource.system,
                createdAt = rows.size.toLong(),
                updatedAt = updatedAt,
                taskRef = taskRef,
                projectId = projectId,
            )
        }

        suspend fun snapshot(): Map<SessionId, SessionMeta> = mutex.withLock { rows.toMap() }

        override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock { rows[sessionId] }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) {
            mutex.withLock {
                rows[sessionId]?.let { rows[sessionId] = it.copy(taskRef = taskRef, updatedAt = updatedAt) }
            }
        }

        override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> = mutex.withLock {
            rows.values.filter { it.taskRef == taskRef }.sortedBy { it.createdAt }
        }

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {
            if (archived) this.archived += sessionId else this.archived -= sessionId
        }

        override suspend fun upsertSession(meta: SessionMeta) = unused("upsertSession")
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ) = unused("updateSessionState")
        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) = unused("setModel")
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = unused("setModelForProvider")
        override suspend fun markRead(sessionId: SessionId, seq: Seq) = unused("markRead")

        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) {
            mutex.withLock {
                rows[sessionId]?.let { rows[sessionId] = it.copy(projectId = projectId, updatedAt = updatedAt) }
            }
        }
        override suspend fun listSessions(): List<SessionMeta> = unused("listSessions")
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
            unused("append")
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = unused("read")
        override suspend fun projectionOf(sessionId: SessionId): Projection = unused("projectionOf")
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = unused("subscribe")
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()

        private fun unused(name: String): Nothing =
            error("the task write routes are not expected to call EventStore.$name")
    }

    private class FakeProjectFs : ProjectFs {
        val dirs: MutableSet<String> = mutableSetOf()
        val files: MutableMap<String, String> = mutableMapOf()

        val reads: MutableList<String> = mutableListOf()

        override fun isDirectory(path: String): Boolean = path.trimEnd('/') in dirs

        override fun readFile(path: String, maxBytes: Int): String? {
            reads += path
            return files[path]?.take(maxBytes)
        }

        override fun canonicalize(path: String): String? {
            val trimmed = path.trimEnd('/').ifEmpty { "/" }
            return if (trimmed in dirs || trimmed in files) trimmed else null
        }
    }

    private class FakeProjectFileWriter(
        private val fs: FakeProjectFs,
        private val mint: ProjectId,
    ) : ProjectFileWriter {
        val calls: MutableList<Pair<String, String>> = mutableListOf()

        val failOn: MutableSet<String> = mutableSetOf()

        override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile {
            calls += dir to name
            if (dir in failOn) throw ProjectPathException(dir, "cannot write $PROJECT_FILE_NAME in '$dir'")
            val path = "$dir/$PROJECT_FILE_NAME"
            fs.files[path]?.let { existing -> return parseProjectFile(existing) ?: ProjectFile(mint, name) }
            fs.files[path] = """{"id":"${mint.value}","name":"$name"}"""
            return ProjectFile(mint, name)
        }
    }

    private fun HttpRequestBuilder.applyIfPresent(name: String, value: String?) {
        if (value != null) header(name, value)
    }
}
