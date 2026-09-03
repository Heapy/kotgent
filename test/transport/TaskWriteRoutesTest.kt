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
import io.kotgent.daemon.TaskService
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.task.ActivityKind
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.FakeProjectFs
import io.kotgent.task.MemoryProjectFileWriter
import io.kotgent.task.PROJECT_FILE_NAME
import io.kotgent.task.ProjectRecord
import io.kotgent.task.TaskState
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
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

class TaskWriteRoutesTest {

    private companion object {
        val UNREACHED_BY_WRITE_ROUTES = setOf(
            "upsertSession", "updateSessionState", "setModel", "setModelForProvider", "markRead",
            "listSessions", "append", "read", "projectionOf",
        )
    }

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
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = beta)
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
        env.fs.addDirectories(listOf("/repo", "/repo/sub"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null)
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
        env.fs.addDirectories(listOf("/repo", "/repo/sub", "/repo/.git"))
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null)
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
        env.fs.addDirectories(listOf("/scratch", "/scratch/notes"))
        env.seedSession(sessionOne, cwd = "/scratch/notes", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"loose"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(listOf("/scratch/notes" to "notes"), env.writer.calls)
    }

    @Test
    fun createFromAPaneWhoseProjectWasDeletedIsRefusedBeforeAnythingIsWritten() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/sub"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null)
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
            assertNotNull(env.sessions.snapshotSessions()[sessionOne]).projectId,
            "and the deleted project was not bound onto the calling session either",
        )
    }

    @Test
    fun createFromAPaneWhoseDeletedProjectWasRestoredFilesTheTaskAsBefore() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/sub"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        assertTrue(env.tasks.setProjectArchived(alpha, false), "the operator restores the project")

        val resp = env.post("/tasks", """{"title":"back in business"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status, "the guard keys on the mark, not on the file or the row")
        assertEquals(
            alpha.value,
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText()).project,
        )
        assertEquals(alpha, assertNotNull(env.sessions.snapshotSessions()[sessionOne]).projectId)
    }

    @Test
    fun createFromASessionStampedWithADeletedProjectIsRefusedToo() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = alpha)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"through the old stamp"}""", pane = paneOne)

        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "the stamp is a shortcut past resolution, not a licence: a session bound before the delete " +
                "would otherwise keep filing cards the board cannot show, and whether an agent hit the " +
                "refusal would depend on which session it was in rather than which project it is",
        )
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent") && body.contains(alpha.value), "the refusal names the project: $body")
        assertTrue(body.contains("kotgent project restore ${alpha.value}"), "and the way back: $body")
        assertTrue(
            body.contains(PROJECT_FILE_NAME),
            "and the file exit too: after a delete no NEW session in a directory is ever stamped, so a " +
                "session that IS stamped was stamped because a $PROJECT_FILE_NAME there named the " +
                "project — which the tombstone never touches. That is the 'created in the wrong folder' " +
                "case, and moving the file is the only exit that fixes it: $body",
        )
        assertTrue(
            body.contains("started after that"),
            "worded for THIS arrival, though — the stamp short-circuits the filesystem, so moving the " +
                "file frees the directory while this session keeps the project it carries: $body",
        )
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "nothing was created")
        assertTrue(env.fs.reads.isEmpty(), "and the stamp still short-circuits the filesystem")

        assertTrue(env.tasks.setProjectArchived(alpha, false), "the operator restores it")
        assertEquals(
            HttpStatusCode.Created,
            env.post("/tasks", """{"title":"back in business"}""", pane = paneOne).status,
            "and the same session files normally again — the mark is re-read, never latched",
        )
    }

    @Test
    fun aDeleteLandingBetweenResolutionAndTheInsertFilesNothing() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/sub"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        // Interleave deletion after resolution and before the atomic insert check.
        env.tasks.beforeCreate = {
            assertTrue(env.tasks.setProjectArchived(alpha, true))
            env.tasks.beforeCreate = null
        }

        val resp = env.post("/tasks", """{"title":"through the gap"}""", pane = paneOne)

        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "the insert reads the tombstone in its own transaction, so the delete wins the race it " +
                "would otherwise lose to a route that had already stopped looking (answered " +
                "${resp.bodyAsText()})",
        )
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent") && body.contains(alpha.value), "the refusal names it: $body")
        assertTrue(
            body.contains("kotgent project restore ${alpha.value}") && body.contains(PROJECT_FILE_NAME),
            "and it is the ARRIVAL's refusal, not a generic one — the store answers whether, the route " +
                "still answers how, so a card that resolved through a $PROJECT_FILE_NAME keeps the " +
                "three exits that arrival offers: $body",
        )
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "no card was filed into a deleted project")
        assertTrue(env.tasks.snapshotTasks().isEmpty(), "and no tracker row was left behind either")

        assertTrue(env.tasks.setProjectArchived(alpha, false), "the operator restores the project")
        assertEquals(
            HttpStatusCode.Created,
            env.post("/tasks", """{"title":"after the restore"}""", pane = paneOne).status,
            "and the very next request files normally — the guard is a re-read, never a latch",
        )
    }

    @Test
    fun aDeleteRacingACreateThatNamedTheProjectOutrightIs404LikeAnUnknownOne() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        env.tasks.beforeCreate = {
            assertTrue(env.tasks.setProjectArchived(alpha, true))
            env.tasks.beforeCreate = null
        }

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"through the gap"}""")

        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "a caller who typed the uuid stands on no project file, so there is no third exit to offer " +
                "and the store's refusal leaves through the same door the pre-check's does",
        )
        assertTrue(resp.bodyAsText().contains(alpha.value), "the body names the project it refused")
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "nothing was created")
    }

    @Test
    fun createFromAPaneWhoseCwdIsGoneCannotAdoptItsWayIntoADeletedProject() = withTaskServer { env ->
        // Force the fallback that adopts the checkout root's existing project file.
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "kotgent", "/repo")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.seedSession(sessionOne, cwd = "/repo/gone", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"through the fallback"}""", pane = paneOne)

        assertEquals(HttpStatusCode.BadRequest, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(resp.bodyAsText().contains(alpha.value), "the refusal names the project it adopted")
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "no card was filed into a deleted project")
        assertNull(
            assertNotNull(env.sessions.snapshotSessions()[sessionOne]).projectId,
            "and the session was not bound to it either",
        )
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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha)

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
        env.seedSession(sessionOne, cwd = "/repo", projectId = beta)

        val resp = env.post("/tasks", """{"title":"named","sessionId":"${sessionOne.value}"}""")

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(
            beta.value,
            TRANSPORT_JSON.decodeFromString(BacklogEntryDto.serializer(), resp.bodyAsText()).project,
        )
    }


    @Test
    fun createFromAPaneBindsTheProjectItResolvedOntoTheCallingSession() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/sub"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null, updatedAt = 4242L)
        env.panes[PaneId(paneOne)] = sessionOne

        assertEquals(HttpStatusCode.Created, env.post("/tasks", """{"title":"resolved"}""", pane = paneOne).status)

        val row = assertNotNull(env.sessions.snapshotSessions()[sessionOne])
        assertEquals(
            alpha,
            row.projectId,
            "the session that resolved a project keeps it, or its next ref-less task command has no project",
        )
    }

    @Test
    fun createFromAPaneInAProjectlessDirectoryBindsTheProjectItCreatedOntoTheCallingSession() =
        withTaskServer { env ->
            env.fs.addDirectories(listOf("/repo", "/repo/sub", "/repo/.git"))
            env.seedSession(sessionOne, cwd = "/repo/sub", projectId = null)
            env.panes[PaneId(paneOne)] = sessionOne

            assertEquals(HttpStatusCode.Created, env.post("/tasks", """{"title":"first ever"}""", pane = paneOne).status)

            assertEquals(minted, assertNotNull(env.sessions.snapshotSessions()[sessionOne]).projectId)
        }

    @Test
    fun createWithAnExplicitProjectDoesNotRePointTheCallingSession() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/other")
        env.seedSession(sessionOne, cwd = "/repo", projectId = beta)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"someone else's"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(beta, assertNotNull(env.sessions.snapshotSessions()[sessionOne]).projectId)
    }


    @Test
    fun aCreateFromAPaneIsAttributedToTheCallingSession() = withTaskServer { env ->
        env.tasks.seedProject(beta, "beta", "/repo")
        env.seedSession(sessionOne, cwd = "/repo", projectId = beta)
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
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))

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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha)
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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha, taskRef = ref)
        env.seedSession(sessionTwo, cwd = "/repo", projectId = alpha, taskRef = ref)

        assertEquals(HttpStatusCode.OK, env.patch("/tasks/${ref.value}", """{"state":"done"}""").status)

        assertNull(env.sessions.snapshotSessions()[sessionOne]?.taskRef)
        assertNull(env.sessions.snapshotSessions()[sessionTwo]?.taskRef)
        assertTrue(
            env.sessions.snapshotSessions().values.none { it.archived },
            "closing a task never archives a session",
        )
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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha, taskRef = ref)
        env.seedSession(sessionTwo, cwd = "/repo", projectId = alpha, taskRef = ref)

        val resp = env.request(HttpMethod.Delete, "/tasks/${ref.value}")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "the task is gone")
        assertNull(env.sessions.snapshotSessions()[sessionOne]?.taskRef, "no session is left holding a dangling badge")
        assertNull(env.sessions.snapshotSessions()[sessionTwo]?.taskRef)
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
        for ([ref, on, refusal] in refusals) {
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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha)
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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha)
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
        env.seedSession(sessionOne, cwd = "/repo", projectId = alpha)
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
        env.fs.addDirectory("/srv/new-repo")

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
        env.fs.addDirectories(listOf("/repo", "/repo/.git", "/repo/src"))

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
        env.fs.addDirectories(listOf("/repo", "/repo/.git", "/repo/.git/worktrees/feature", "/wt/feature"))
        env.fs.writeFile("/wt/feature/.git", "gitdir: /repo/.git/worktrees/feature\n")

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
        env.fs.addDirectories(listOf("/repo", "/repo/.git", "/repo/packages", "/repo/packages/api"))
        env.fs.writeFile("/repo/packages/api/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"api"}""")

        val resp = env.post("/projects", """{"path":"/repo/packages/api"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id, "the answer is the project that OWNS the path, not a fresh uuid")
        assertEquals("api", dto.name)
        assertEquals("/repo/packages/api", dto.path)
        assertTrue(env.writer.calls.isEmpty(), "an owned path is adopted, never written to")
        assertNull(
            env.fs.written["/repo/$PROJECT_FILE_NAME"],
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
        env.fs.addDirectories(listOf("/repo", "/repo/.git", "/repo/src"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")

        val resp = env.post("/projects", """{"path":"/repo/src"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id)
        assertEquals("kotgent", dto.name, "the committed name wins over a default derived from the directory")
        assertEquals("/repo", dto.path)
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun postProjectsRefusesToRestoreADeletedProjectFromADescendantPath() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git", "/repo/sub"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "deleted name", "/old/checkout")
        assertTrue(env.tasks.setProjectArchived(alpha, true))

        val resp = env.post("/projects", """{"path":"/repo/sub"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("was deleted"), resp.bodyAsText())
        assertEquals(
            ProjectRecord(alpha, "deleted name", "/old/checkout", 0L, archived = true),
            env.tasks.snapshotProjects()[alpha],
            "adopting a descendant must not clear the tombstone or rewrite the archived project's identity",
        )
        assertTrue(env.tasks.listProjects().isEmpty(), "the deleted ancestor must stay out of live selectors")
        assertEquals(listOf(alpha), env.tasks.listProjects(archived = true).map { it.id })
        assertTrue(env.writer.calls.isEmpty(), "a refused ancestor adoption must not write a competing project file")
    }

    @Test
    fun postProjectsRefusesToRestoreADeletedProjectFromOneOfItsLinkedWorktrees() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git", "/repo/.git/worktrees/feature", "/wt/feature"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.fs.writeFile("/wt/feature/.git", "gitdir: /repo/.git/worktrees/feature\n")
        env.tasks.seedProject(alpha, "deleted name", "/old/checkout")
        assertTrue(env.tasks.setProjectArchived(alpha, true))

        val resp = env.post("/projects", """{"path":"/wt/feature"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("was deleted"), resp.bodyAsText())
        assertEquals(
            ProjectRecord(alpha, "deleted name", "/old/checkout", 0L, archived = true),
            env.tasks.snapshotProjects()[alpha],
            "a worktree carries no project file of its own, so naming it is not the explicit re-adoption",
        )
        assertTrue(env.writer.calls.isEmpty(), "and nothing is written into the main checkout either")
    }

    @Test
    fun postProjectsAdoptingADeletedProjectsDirectoryBringsItBack() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "the name it carried when it was deleted", "/old/checkout")
        assertTrue(env.tasks.setProjectArchived(alpha, true))

        val resp = env.post("/projects", """{"path":"/repo"}""")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id, "adoption answers the project that owns the path, deleted or not")
        assertEquals(
            false,
            dto.archived,
            "and answers it as LIVE — a client merges this DTO into the list it just re-read, so a " +
                "stale `true` would hide the project the operator has only now asked for again",
        )
        assertEquals(
            ProjectRecord(alpha, "kotgent", "/repo", 0L, archived = false),
            env.tasks.snapshotProjects()[alpha],
            "the mark is cleared BEFORE registration, so the row also takes the file's name and this checkout",
        )
        assertEquals("kotgent", dto.name, "and the answer is read back after both writes, never between them")
        assertEquals("/repo", dto.path)
        assertEquals(
            listOf(alpha),
            env.tasks.listProjects().map { it.id },
            "a project the operator asked for again must be one the board lists",
        )
        assertTrue(env.tasks.listProjects(archived = true).isEmpty(), "and the tombstone is gone, not duplicated")
        assertTrue(env.writer.calls.isEmpty(), "an owned path is adopted, never written to — the file is still there")
    }

    @Test
    fun postProjectsRefusesWhenADeleteWinsWhileAnExistingProjectIsBeingAdopted() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.tasks.seedProject(alpha, "deleted name", "/old/checkout")
        assertTrue(env.tasks.setProjectArchived(alpha, true))
        env.tasks.beforeUpsertProject = { id ->
            assertEquals(alpha, id)
            assertTrue(env.tasks.setProjectArchived(id, true), "the racing delete wins after POST clears the mark")
        }

        val resp = env.post("/projects", """{"path":"/repo"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("was deleted"), resp.bodyAsText())
        assertTrue(resp.bodyAsText().contains("kotgent project restore ${alpha.value}"), resp.bodyAsText())
        assertEquals(
            ProjectRecord(alpha, "deleted name", "/old/checkout", 0L, archived = true),
            env.tasks.snapshotProjects()[alpha],
            "a refused registration must not overwrite the tombstoned row's name or last-seen path",
        )
        assertTrue(env.writer.calls.isEmpty(), "the race is in the adoption branch, which never invokes the writer")
    }

    @Test
    fun postProjectsRefusesWhenTheWriterAdoptsAFileThatAppearedAfterResolution() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.writer.beforeEnsure = { dir, _ ->
            env.fs.writeFile("$dir/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
            env.tasks.seedProject(alpha, "deleted name", "/old/checkout")
            assertTrue(env.tasks.setProjectArchived(alpha, true))
        }

        val resp = env.post("/projects", """{"path":"/repo"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("was deleted"), resp.bodyAsText())
        assertTrue(resp.bodyAsText().contains("kotgent project restore ${alpha.value}"), resp.bodyAsText())
        assertEquals(listOf("/repo" to "repo"), env.writer.calls)
        assertEquals(
            ProjectRecord(alpha, "deleted name", "/old/checkout", 0L, archived = true),
            env.tasks.snapshotProjects()[alpha],
            "adopting a racing file must not resurrect or rewrite the project it identifies",
        )
        assertNull(env.tasks.snapshotProjects()[minted], "the writer adopted the racing uuid instead of minting one")
    }

    @Test
    fun postProjectsAdoptingALiveProjectIsUnchangedAndStaysIdempotent() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")

        val first = env.post("/projects", """{"path":"/repo"}""")
        val second = env.post("/projects", """{"path":"/repo"}""")

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(
            first.bodyAsText(),
            second.bodyAsText(),
            "the clear is a no-op on a live project, so adopt is the idempotent operation it always was",
        )
        assertEquals(
            ProjectRecord(alpha, "kotgent", "/repo", 0L, archived = false),
            env.tasks.snapshotProjects()[alpha],
        )
        assertTrue(env.tasks.listProjects(archived = true).isEmpty())
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun postProjectsHonoursAGivenNameAndRefusesOneAFileCouldNotCarry() = withTaskServer { env ->
        env.fs.addDirectory("/srv/new-repo")

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
        env.fs.addDirectory("/srv/new-repo")

        val relative = env.post("/projects", """{"path":"new-repo"}""")
        assertEquals(HttpStatusCode.BadRequest, relative.status)
        assertTrue(relative.bodyAsText().contains("absolute"), "a relative path must never reach realpath")

        assertEquals(HttpStatusCode.BadRequest, env.post("/projects", """{"path":"/srv/nope"}""").status)
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun postProjectsRefusesAPathThatIsNotADirectory() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.fs.writeFile("/repo/README.md", "# repo\n")

        val resp = env.post("/projects", """{"path":"/repo/README.md"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("/repo/README.md"), resp.bodyAsText())
        assertTrue(env.writer.calls.isEmpty(), "nothing is written for a path that is not a directory")
        assertTrue(env.tasks.snapshotProjects().isEmpty(), "and no project is registered")
        assertNull(env.fs.written["/repo/$PROJECT_FILE_NAME"], "no project file appeared at the checkout root")
    }

    @Test
    fun postProjectsRefusesAFileEvenWhenAProjectIsCommittedAboveIt() = withTaskServer { env ->
        env.fs.addDirectories(listOf("/repo", "/repo/.git"))
        env.fs.writeFile("/repo/$PROJECT_FILE_NAME", """{"id":"${alpha.value}","name":"kotgent"}""")
        env.fs.writeFile("/repo/README.md", "# repo\n")

        val resp = env.post("/projects", """{"path":"/repo/README.md"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(env.tasks.snapshotProjects().isEmpty(), "adoption must not answer for a path that is a file")
        assertTrue(env.writer.calls.isEmpty())
    }

    @Test
    fun aWriterRefusalIsA400() = withTaskServer { env ->
        env.fs.addDirectory("/srv/readonly")
        env.writer.failOn += "/srv/readonly"

        val resp = env.post("/projects", """{"path":"/srv/readonly"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("/srv/readonly"))
    }


    @Test
    fun deletingAProjectMarksTheRowAndRemovesNothingItOwns() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "still here afterwards")

        val resp = env.request(HttpMethod.Delete, "/projects/${alpha.value}")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id)
        assertTrue(dto.archived, "the answer is the committed row, and the row now carries the tombstone")
        assertEquals(
            listOf(ref),
            env.tasks.snapshotEntries().keys.toList(),
            "nothing cascades — the backlog is exactly what a restore has to return",
        )
        assertTrue(env.tasks.listProjects().isEmpty(), "the board's list no longer carries it")
        assertEquals(listOf(alpha), env.tasks.listProjects(archived = true).map { it.id })
        assertTrue(env.writer.calls.isEmpty(), "and the .kotgent.json that named it is never touched")
        assertTrue(env.fs.reads.isEmpty(), "nor read: the uuid on the row is the whole address")
    }

    @Test
    fun deletingAnAlreadyDeletedProjectIs200BecauseTheIntentIsAlreadySatisfied() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")

        val first = env.request(HttpMethod.Delete, "/projects/${alpha.value}")
        val second = env.request(HttpMethod.Delete, "/projects/${alpha.value}")

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(
            HttpStatusCode.OK,
            second.status,
            "a repeat asks for a state the project is already in — an error would name nothing the " +
                "caller could fix",
        )
        assertEquals(first.bodyAsText(), second.bodyAsText(), "and answers the same row both times")
    }

    @Test
    fun restoringClearsTheMarkAndReturnsTheWholeBacklogWithIt() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "waited through the tombstone")
        assertEquals(HttpStatusCode.OK, env.request(HttpMethod.Delete, "/projects/${alpha.value}").status)

        val resp = env.request(HttpMethod.Post, "/projects/${alpha.value}/restore")

        assertEquals(HttpStatusCode.OK, resp.status)
        val dto = TRANSPORT_JSON.decodeFromString(ProjectDto.serializer(), resp.bodyAsText())
        assertEquals(alpha.value, dto.id)
        assertEquals(false, dto.archived, "restore answers the live row a client will merge into its list")
        assertEquals(listOf(alpha), env.tasks.listProjects().map { it.id }, "the board lists it again")
        assertTrue(env.tasks.listProjects(archived = true).isEmpty())
        assertEquals(
            listOf(ref),
            env.tasks.snapshotEntries().keys.toList(),
            "and its backlog was never anywhere else — restore is one column, not a recovery",
        )
    }

    @Test
    fun restoringALiveProjectIs200AndChangesNothing() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")

        val first = env.request(HttpMethod.Post, "/projects/${alpha.value}/restore")
        val second = env.request(HttpMethod.Post, "/projects/${alpha.value}/restore")

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status, "restore is idempotent from either starting state")
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(
            ProjectRecord(alpha, "alpha", "/repo", 0L, archived = false),
            env.tasks.snapshotProjects()[alpha],
        )
    }

    @Test
    fun deletingOrRestoringAUuidTheDaemonHasNeverSeenIs404() = withTaskServer { env ->
        val unseen = "99999999-8888-4777-8666-555555555555"

        val answers = listOf(
            env.request(HttpMethod.Delete, "/projects/$unseen"),
            env.request(HttpMethod.Post, "/projects/$unseen/restore"),
        )

        for (resp in answers) {
            assertEquals(
                HttpStatusCode.NotFound,
                resp.status,
                "404 is reserved for the one case a caller can act on: a uuid that names no row at all",
            )
            assertTrue(resp.bodyAsText().contains(unseen), "the message names it: ${resp.bodyAsText()}")
        }
        assertTrue(env.tasks.snapshotProjects().isEmpty(), "and nothing was created on the way")
    }

    @Test
    fun aMalformedProjectIdIs400OnBothRoutesAndMovesNothing() = withTaskServer { env ->
        env.tasks.seedProject(alpha, "alpha", "/repo")

        val answers = listOf(
            env.request(HttpMethod.Delete, "/projects/not-a-uuid"),
            env.request(HttpMethod.Post, "/projects/not-a-uuid/restore"),
        )

        for (resp in answers) {
            assertEquals(
                HttpStatusCode.BadRequest,
                resp.status,
                "a uuid that cannot parse addresses no resource at all: ${resp.bodyAsText()}",
            )
            assertTrue(
                resp.bodyAsText().contains("malformed project id 'not-a-uuid'"),
                resp.bodyAsText(),
            )
            assertTrue(
                resp.bodyAsText().contains("expected a canonical uuid"),
                "and it says what a well-formed one is: ${resp.bodyAsText()}",
            )
        }
        assertEquals(listOf(alpha), env.tasks.listProjects().map { it.id }, "and no row moved")
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
            HttpMethod.Delete to "/projects/${alpha.value}",
            HttpMethod.Post to "/projects/${alpha.value}/restore",
        )
        for ([method, path] in calls) {
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
        val writer: MemoryProjectFileWriter,
        val panes: MutableMap<PaneId, SessionId>,
    ) {
        private var seededSessions = 0L

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

        fun seedSession(
            id: SessionId,
            cwd: String,
            projectId: ProjectId?,
            taskRef: TaskRef? = null,
            updatedAt: Long = 0L,
        ) {
            sessions.seedSession(
                SessionMeta(
                    id = id,
                    name = id.value,
                    agent = "claude",
                    cwd = cwd,
                    tmuxSession = "kt-${id.value}",
                    state = SessionState.running,
                    stateSource = EventSource.system,
                    createdAt = seededSessions++,
                    updatedAt = updatedAt,
                    taskRef = taskRef,
                    projectId = projectId,
                ),
            )
        }
    }

    private fun withTaskServer(block: suspend (Env) -> Unit) = runBlocking {
        withTimeout(60.seconds) {
            val tokens = TokenHolder(token)
            val tasks = FakeTaskStore(now = { 0L })
            val sessions = FakeEventStore(now = { 0L }).also {
                it.interceptor = ForbiddingInterceptor(UNREACHED_BY_WRITE_ROUTES) { store, method ->
                    "the task write routes are not expected to call $store.$method"
                }
            }
            val fs = FakeProjectFs()
            val writer = MemoryProjectFileWriter(fs) { minted }
            val panes = mutableMapOf<PaneId, SessionId>()
            val service = TaskService(
                tasks = tasks,
                sessions = sessions,
                projectFs = fs,
                projectFiles = writer,
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
                    val _ = authenticated(tokens::current) {
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


    private fun HttpRequestBuilder.applyIfPresent(name: String, value: String?) {
        if (value != null) header(name, value)
    }
}
