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
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
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

/**
 * The task layer's non-link write surface (plan Task 14): `POST /tasks`, `PATCH`/`DELETE /tasks/{ref}`,
 * `POST /tasks/{ref}/{move,deps,comment}` and `POST /projects`.
 *
 * ## What these tests are built around
 * Three things no single collaborator can show on its own:
 *
 *  1. **`POST /tasks`'s four-step project resolution, in order.** Each step is exercised with the earlier
 *     ones deliberately unable to answer, because a route that consulted them in the wrong order still
 *     passes any test that only sets up one of them. The fake filesystem and the fake writer are separate
 *     objects precisely so "the file was READ" and "the file was WRITTEN" cannot be confused.
 *  2. **`PATCH` with a message is ONE operation.** The assertion is the activity feed's LENGTH, not its
 *     contents: a route that transitioned and then posted the message as a comment would produce the same
 *     text and a different count.
 *  3. **A delete releases every holder.** Two sessions hold one task, and both must come back clear —
 *     which is a `sessions` write the task store never makes, so only the route-over-service path shows it.
 *
 * [TaskRouting.service] is the CONCRETE [TaskService], so the fixture builds the real one over fake stores
 * (the plan's instruction): `transition` and `delete` here are the production bodies, and the
 * transition-then-unlink ordering they own is what the delete case actually observes.
 * [ProjectFileWriter] *is* an interface and is faked — `PosixProjectFileWriter`'s body belongs to Task 4.
 *
 * The stores are `Mutex`-guarded because the CIO server runs handlers on its own engine threads and the
 * test thread reads what a handler wrote. Every body is bounded by [withTimeout] (anti-hang).
 */
class TaskWriteRoutesTest {

    private val token = "task-write-routes-master-token-0123456789"
    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("11111111-2222-4333-8444-555555555555")
    private val minted = ProjectId.of("9a9a9a9a-1b1b-4c4c-8d8d-0e0e0e0e0e0e")
    private val paneOne = "%1"
    private val sessionOne = SessionId("s-one")
    private val sessionTwo = SessionId("s-two")

    // --- POST /tasks: the project resolution order ---------------------------------------------------

    /**
     * Step 1, and the board's whole path: an explicit project with **no pane header at all**. The board
     * has neither a pane nor a session, so a route that required one would make creating a card from the
     * browser impossible — which is the product.
     */
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

    /** An explicit project the daemon has never registered is a `404`, not a row invented from nothing. */
    @Test
    fun createWithAnUnknownExplicitProjectIs404() = withTaskServer { env ->
        val resp = env.post("/tasks", """{"project":"${alpha.value}","title":"x"}""")

        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains(alpha.value), "the body names the project it could not find")
        assertTrue(env.tasks.snapshotEntries().isEmpty(), "nothing was created")
    }

    /** A `project` that is not a uuid is a bad REQUEST — `404` would claim the daemon looked for it. */
    @Test
    fun createWithAMalformedExplicitProjectIs400() = withTaskServer { env ->
        val resp = env.post("/tasks", """{"project":"not-a-uuid","title":"x"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("uuid"))
    }

    /**
     * Step 2: the calling session already knows its project, so nothing on disk is consulted at all. The
     * filesystem assertions are the point — a route that ran `resolveProject` anyway would still put the
     * task in the right project here and be wrong about which step answered.
     */
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

    /**
     * Step 3: no stored project, but a committed `.kotgent.json` above the session's cwd. The row is
     * upserted from what was READ — without that, a project reachable on disk would have a backlog the
     * board's selector could never list.
     */
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

    /**
     * Step 4, and the branch that reads as contradictory until the order is written out: a projectless
     * directory does NOT `400` — it gets a `.kotgent.json` at the checkout root (so every worktree of that
     * repository shares the uuid) and a `projects` row.
     */
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

    /** No repository anywhere: the session's own directory is the root, the same degradation the resolver makes. */
    @Test
    fun createFromAPaneOutsideAnyRepositoryCreatesTheFileInTheSessionsOwnDirectory() = withTaskServer { env ->
        env.fs.dirs += setOf("/scratch", "/scratch/notes")
        env.sessions.seed(sessionOne, cwd = "/scratch/notes", projectId = null)
        env.panes[PaneId(paneOne)] = sessionOne

        val resp = env.post("/tasks", """{"title":"loose"}""", pane = paneOne)

        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(listOf("/scratch/notes" to "notes"), env.writer.calls)
    }

    /** The one case the plan makes a `400`: no project named and no session to resolve one from. */
    @Test
    fun createWithNeitherAProjectNorASessionIs400NamingProject() = withTaskServer { env ->
        val resp = env.post("/tasks", """{"title":"orphan"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("--project"), "the message names the fix")
        assertTrue(env.tasks.snapshotEntries().isEmpty())
        assertTrue(env.writer.calls.isEmpty(), "nothing is created on disk for a request that answers 400")
    }

    /** A pane the registry does not know fails closed — it must not fall back to some other session. */
    @Test
    fun createFromAnUnknownPaneIs400() = withTaskServer { env ->
        env.sessions.seed(sessionOne, cwd = "/repo", projectId = alpha)

        val resp = env.post("/tasks", """{"title":"x"}""", pane = "%99")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(env.tasks.snapshotEntries().isEmpty())
    }

    /** An explicit `--session` is the escape hatch for a caller outside any kotgent pane. */
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

    // --- PATCH -------------------------------------------------------------------------------------

    /**
     * `kotgent task review -m "…"` is ONE operation, and the feed LENGTH is what proves it: a route that
     * transitioned and then posted the message separately would write the same words in two rows.
     */
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

    /** With no session behind it — the board dragging a card — the row is attributed to the board. */
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

    /**
     * A `sessionId` naming no row is refused, rather than silently re-attributed to the board.
     *
     * `comment` already refuses exactly this input; `PATCH` was the one attributed write in the package
     * that did not, so `kotgent task review --session <typo> -m "…"` committed the transition and recorded
     * a human action for an agent's write — in the one feed the no-exclusivity design tells operators to
     * read to see who is doing what. The title assertion is the second half: the refusal is settled BEFORE
     * the tracker edit, so a rejected patch leaves nothing half-written.
     */
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

    /**
     * The boundary of that refusal, pinned so it is not widened by accident: only a session that was
     * NAMED and is missing is refused. An unresolvable pane header stays "no session at all", which is
     * the board's own legitimate shape — and is why `PATCH` cannot simply reuse `comment`'s
     * `requireCallerSession`. (Recorded residual: the CLI does send that header from inside a kotgent
     * pane, so a pane the registry has lost is attributed to `board` rather than refused. Narrowing that
     * means distinguishing "no header" from "header the registry rejected", which is a separate decision.)
     */
    @Test
    fun aStateChangeFromAPaneTheRegistryDoesNotKnowIsAttributedToTheBoard() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "drag me")
        env.tasks.clearActivity()

        assertEquals(
            HttpStatusCode.OK,
            env.patch("/tasks/${ref.value}", """{"state":"in_progress"}""", pane = "%99").status,
            "an unresolvable pane is 'no session', which the board path legitimately is",
        )
        assertEquals(
            listOf(TaskService.BOARD_AUTHOR),
            env.tasks.snapshotActivity().filter { it.ref == ref }.map { it.author },
        )
    }

    /** Tracker fields and the state travel together, and the answer is re-read rather than half of it. */
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

    /** Closing a task from the board releases every worker session and leaves them alive. */
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

    /** A message with no state change has no activity row to ride, and dropping it silently is worse. */
    @Test
    fun aMessageWithoutAStateChangeIs400AndPointsAtComment() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "steady")

        val resp = env.patch("/tasks/${ref.value}", """{"body":"edited","message":"a note"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("comment"), "the message names where a standalone note goes")
        assertEquals("", env.tasks.snapshotTasks().getValue(ref).body, "the refused patch wrote nothing")
    }

    // --- DELETE ------------------------------------------------------------------------------------

    /**
     * A delete releases every holder first — a `sessions` write the task store never makes, so this is
     * the only place the route-over-service path can be seen doing it.
     */
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

    // --- move --------------------------------------------------------------------------------------

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

    /** `move` cannot say whether the ref or the neighbour was missing, so the one `404` says both. */
    @Test
    fun aMoveNamingSomethingThatIsNotThereIs404() = withTaskServer { env ->
        val ref = env.seedTask(alpha, "only")

        assertEquals(HttpStatusCode.NotFound, env.post("/tasks/local:404/move", """{"top":true}""").status)
        val neighbour = env.post("/tasks/${ref.value}/move", """{"after":"local:404"}""")
        assertEquals(HttpStatusCode.NotFound, neighbour.status)
        assertTrue(neighbour.bodyAsText().contains("neighbour"))
    }

    // --- deps --------------------------------------------------------------------------------------

    /**
     * All four refusals answer `400` and each says which rule rejected the edge. `unknownRef` is
     * deliberately reachable from the PATH ref too: pre-checking that ref for existence would answer
     * `404` there and quietly make one of the four unreachable from one side.
     */
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

    /** A `remove` naming a task that is not there reaches the read-back's `404`. */
    @Test
    fun removingADependencyOfAnUnknownTaskIs404() = withTaskServer { env ->
        val a = env.seedTask(alpha, "a")
        assertEquals(
            HttpStatusCode.NotFound,
            env.post("/tasks/local:404/deps", """{"action":"remove","on":"${a.value}"}""").status,
        )
    }

    // --- comment -----------------------------------------------------------------------------------

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

    /** A `sessionId` naming a row that is not there is refused rather than attributed to a ghost. */
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

    // --- refs --------------------------------------------------------------------------------------

    /**
     * A ref that cannot be parsed is a `400` on every route that takes one — `404` in this package means
     * "no such task `{ref}`", which presupposes that `{ref}` names something.
     */
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

    // --- POST /projects ------------------------------------------------------------------------------

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

    /**
     * The named path says WHICH checkout; the file lands at that checkout's root.
     *
     * `kotgent project init` defaults to the caller's cwd, so the broken case was the ordinary one: run
     * from `/repo/src`, it used to write `/repo/src/.kotgent.json`, and because resolution walks up with
     * NEAREST WINS every session under `src` then belonged to a different project than the rest of the
     * repository — two backlogs for one body of work, which is exactly what "a project is a file, not a
     * path" exists to prevent. The name comes from the ROOT too: a project called `src` would be the same
     * mistake wearing a label.
     */
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

    /**
     * The worktree half of the same rule, and the one that would be committed wrong: pointed at a linked
     * worktree, the file must land in the MAIN checkout — a `.kotgent.json` written inside the worktree is
     * committed on that branch alone, so `/repo` resolves to no project (or mints a second uuid) and the
     * plan's "a worktree and its main checkout share one backlog" criterion is false.
     */
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

    /** The writer's own refusal (`ProjectPathException`) is a `400`, not a 500. */
    @Test
    fun aWriterRefusalIsA400() = withTaskServer { env ->
        env.fs.dirs += "/srv/readonly"
        env.writer.failOn += "/srv/readonly"

        val resp = env.post("/projects", """{"path":"/srv/readonly"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("/srv/readonly"))
    }

    // --- the gate ------------------------------------------------------------------------------------

    /** Every write route lives inside the `authenticated { route(API_PREFIX) }` block and inherits its gate. */
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

    // --- harness -------------------------------------------------------------------------------------

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

        /** A task already in the backlog, created through the same tracker path the route uses. */
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

    // --- fakes ---------------------------------------------------------------------------------------

    /**
     * An in-memory [TaskStore] with real enough behaviour for the routes to be worth testing: `create`
     * mints `local:<n>` and appends at the end, `transition` writes its own activity row, and
     * `addDependency` enforces all four refusals for real rather than from a knob — a knob would prove the
     * route maps an exception it was handed, not that the four inputs the plan names each produce one.
     *
     * Guarded by a [Mutex] because the CIO engine runs handlers on its own threads.
     */
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
            // The author the caller passed, exactly as the real store records it — a fake that hardcoded
            // "board" here would answer the same whether or not the route ever attributes the create.
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

        /** Whether [target] is reachable from [start] by following `depends on` edges. */
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

        override suspend fun upsertProject(id: ProjectId, name: String, path: String?): Unit = mutex.withLock {
            val existing = projects[id]
            projects[id] = ProjectRecord(id, name, path ?: existing?.path, 0L)
        }

        override suspend fun listProjects(): List<ProjectRecord> =
            mutex.withLock { projects.values.sortedBy { it.name } }

        override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock { projects[id] }
    }

    /**
     * An in-memory [EventStore] modelling the session row and the task link. All three link members are
     * overridden — the interface's defaults throw precisely so a fake that forgot one cannot turn a link
     * that persisted nothing into a green test.
     */
    private class FakeEventStore : EventStore {
        private val mutex = Mutex()
        private val rows = mutableMapOf<SessionId, SessionMeta>()

        /** Nothing here ever archives a session; the set exists so a test can assert that. */
        val archived: MutableSet<SessionId> = mutableSetOf()

        fun seed(id: SessionId, cwd: String, projectId: ProjectId?, taskRef: TaskRef? = null) {
            rows[id] = SessionMeta(
                id = id,
                name = id.value,
                agent = "claude",
                cwd = cwd,
                tmuxSession = "kt-${id.value}",
                state = SessionState.running,
                stateSource = EventSource.system,
                createdAt = rows.size.toLong(),
                updatedAt = 0L,
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
        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) =
            unused("setProjectId")
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

    /** A fake tree: a set of directories and a map of file bodies, with `realpath` as identity over both. */
    private class FakeProjectFs : ProjectFs {
        val dirs: MutableSet<String> = mutableSetOf()
        val files: MutableMap<String, String> = mutableMapOf()

        /** Every path [readFile] was asked about — the proof that a branch consulted the disk, or did not. */
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

    /**
     * A fake [ProjectFileWriter]. Task 4 is filling `PosixProjectFileWriter` in parallel, so this test may
     * not depend on its body — only on the interface's contract: an existing file wins, a fresh one gets a
     * new uuid, and a refusal is a [ProjectPathException].
     */
    private class FakeProjectFileWriter(
        private val fs: FakeProjectFs,
        private val mint: ProjectId,
    ) : ProjectFileWriter {
        /** `(dir, name)` per call, in order. */
        val calls: MutableList<Pair<String, String>> = mutableListOf()

        /** Directories the writer refuses, standing in for an unwritable location. */
        val failOn: MutableSet<String> = mutableSetOf()

        override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile {
            calls += dir to name
            if (dir in failOn) throw ProjectPathException(dir, "cannot write $PROJECT_FILE_NAME in '$dir'")
            val path = "$dir/$PROJECT_FILE_NAME"
            fs.files[path]?.let { return ProjectFile(mint, name) }
            fs.files[path] = """{"id":"${mint.value}","name":"$name"}"""
            return ProjectFile(mint, name)
        }
    }

    private fun HttpRequestBuilder.applyIfPresent(name: String, value: String?) {
        if (value != null) header(name, value)
    }
}
