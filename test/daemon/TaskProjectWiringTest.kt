package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProjectId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.FailingInterceptor
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.store.ProjectUpsert
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.BacklogEntry
import io.kotgent.task.FakeProjectFs
import io.kotgent.task.ProjectFs
import io.kotgent.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TaskProjectWiringTest {


    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")

    private val beta = ProjectId.of("11111111-2222-4333-8444-555555555555")

    private val preallocated = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


    private fun tree(): FakeProjectFs = FakeProjectFs(
        dirs = listOf(
            "/", "/repo", "/repo/sub", "/repo/.git", "/repo/.git/worktrees",
            "/repo/.git/worktrees/feature", "/wt", "/wt/feature", "/elsewhere",
        ),
        files = mapOf(
            "/repo/.kotgent.json" to """{"id": "${alpha.value}", "name": "kotgent"}""",
            "/wt/feature/.git" to "gitdir: /repo/.git/worktrees/feature\n",
        ),
    )


    @Test
    fun aStartInsideAProjectStampsTheRowAndRegistersTheProject() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)

            val started = f.manager().start("claude", "/repo/sub")

            assertEquals(alpha, started.projectId, "the returned meta carries the project")
            assertEquals(
                alpha,
                f.store.getSession(started.id)!!.projectId,
                "and so does the committed row — the id is written by the insert itself",
            )
            assertEquals(
                listOf(ProjectUpsert(alpha, "kotgent", "/repo")),
                f.tasks.upsertProjectWrites,
                "the projects row names the CHECKOUT ROOT, not the session's cwd",
            )
        }
    }

    @Test
    fun aStartOutsideAnyProjectLeavesTheProjectNullAndRegistersNothing() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)

            val started = f.manager().start("claude", "/elsewhere")

            assertNull(started.projectId)
            assertNull(f.store.getSession(started.id)!!.projectId)
            assertTrue(f.tasks.upsertProjectWrites.isEmpty(), "no project file, nothing to register")
        }
    }

    @Test
    fun anImportStampsTheSameProjectAStartInThatDirectoryWould() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            val real = canonicalPath("/tmp")!!
            val fs = FakeProjectFs(
                dirs = listOf("/", real),
                files = mapOf("$real/.kotgent.json" to """{"id": "${alpha.value}", "name": "kotgent"}"""),
            )

            val imported = f.manager(fs = fs).importSession("claude", preallocated, cwd = "/tmp")

            assertEquals(alpha, imported.projectId)
            assertEquals(alpha, f.store.getSession(imported.id)!!.projectId)
            assertEquals(
                listOf(ProjectUpsert(alpha, "kotgent", real)),
                f.tasks.upsertProjectWrites,
                "the import registers the project it resolved, exactly as a start does",
            )
        }
    }

    @Test
    fun twoWorktreesOfOneRepositoryLandOnOneProject() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            val manager = f.manager(newIds = listOf(SessionId("main0001"), SessionId("wt000001")))

            val inMain = manager.start("claude", "/repo/sub")
            val inWorktree = manager.start("claude", "/wt/feature")

            assertEquals(alpha, inMain.projectId)
            assertEquals(
                inMain.projectId,
                inWorktree.projectId,
                "a worktree resolves through its .git file to the main checkout's project file",
            )
            assertEquals(
                listOf(
                    ProjectUpsert(alpha, "kotgent", "/repo"),
                    ProjectUpsert(alpha, "kotgent", "/repo"),
                ),
                f.tasks.upsertProjectWrites,
                "both resolve to the main checkout root, so the one projects row is refreshed with it",
            )
        }
    }

    @Test
    fun aFailedProjectRegistrationLeavesTheRowUnstampedSoTheBackfillRetriesIt() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.failures.failures["upsertProject"] = IllegalStateException("disk is on fire")

            val started = f.manager().start("claude", "/repo/sub")

            assertEquals(SessionState.running, started.state, "a registry failure must never fail the launch")
            assertNull(started.projectId, "write both or neither")
            assertNull(f.store.getSession(started.id)!!.projectId)

            val _ = f.failures.failures.remove("upsertProject")
            val _ = f.reconciler().reconcile()

            assertEquals(alpha, f.store.getSession(started.id)!!.projectId)
            assertEquals(listOf(ProjectUpsert(alpha, "kotgent", "/repo")), f.tasks.upsertProjectWrites)
        }
    }

    @Test
    fun aStartInsideAnArchivedProjectIsNotStampedAndResurrectsNothing() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.tasks.seedArchived(alpha, archived = true)

            val started = f.manager().start("claude", "/repo/sub")

            assertEquals(SessionState.running, started.state, "a tombstone must never fail a launch")
            assertNull(started.projectId, "the file is still on disk, but the project it names was deleted")
            assertNull(f.store.getSession(started.id)!!.projectId)
            assertTrue(f.tasks.upsertProjectWrites.isEmpty(), "and the row it would have written was refused")
        }
    }

    @Test
    fun restoringTheProjectMakesTheNextStartBindItAgain() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.tasks.seedArchived(alpha, archived = true)
            val manager = f.manager(newIds = listOf(SessionId("while001"), SessionId("after001")))

            assertNull(manager.start("claude", "/repo/sub").projectId)

            f.tasks.seedArchived(alpha, archived = false)

            assertEquals(
                alpha,
                manager.start("claude", "/repo/sub").projectId,
                "the guard reads the mark on every registration; it is not a latch",
            )
            assertEquals(listOf(ProjectUpsert(alpha, "kotgent", "/repo")), f.tasks.upsertProjectWrites)
        }
    }

    @Test
    fun withNoTaskStoreToRegisterInTheProjectIsNotStampedEither() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)

            val started = f.manager(taskStore = null).start("claude", "/repo/sub")

            assertEquals(SessionState.running, started.state, "the launch is unaffected")
            assertNull(started.projectId, "an unregistered project is not reported")
            assertNull(
                f.store.getSession(started.id)!!.projectId,
                "and it is certainly not persisted — nothing would ever repair that row",
            )
        }
    }


    @Test
    fun startupReconciliationBackfillsAMissingProjectIdAndLeavesAnExistingOneAlone() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.seedSession("old00001", cwd = "/repo/sub")
            f.seedSession("stamped1", cwd = "/repo/sub", projectId = beta)

            val _ = f.reconciler().reconcile()

            assertEquals(alpha, f.store.getSession(SessionId("old00001"))!!.projectId)
            assertEquals(
                beta,
                f.store.getSession(SessionId("stamped1"))!!.projectId,
                "an already-resolved row is never re-resolved",
            )
            assertEquals(
                listOf<Pair<SessionId, ProjectId?>>(SessionId("old00001") to alpha),
                f.store.projectWrites,
                "exactly one targeted write, for the row that had none",
            )
        }
    }

    @Test
    fun aRefusedBackfillRetriesUntilRestoringTheProjectLetsTheNextPassBindIt() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.tasks.seedArchived(alpha, archived = true)
            f.seedSession("tombed01", cwd = "/repo/sub")
            val registration = ProjectUpsert(alpha, "kotgent", "/repo")
            val reconciler = f.reconciler()

            val _ = reconciler.reconcile()

            assertNull(
                f.store.getSession(SessionId("tombed01"))!!.projectId,
                "a refused registration leaves the session unbound while the project is tombstoned",
            )
            assertEquals(
                listOf(registration),
                f.tasks.upsertProjectAttempts,
                "the first pass did resolve the file and ask the task store to register its project",
            )
            assertTrue(
                f.store.projectWrites.isEmpty(),
                "a refusal must not persist a project binding",
            )
            assertTrue(f.tasks.upsertProjectWrites.isEmpty(), "and the task store wrote no project row")

            val _ = reconciler.reconcile()

            assertNull(
                f.store.getSession(SessionId("tombed01"))!!.projectId,
                "the second refusal still leaves the session eligible for another pass",
            )
            assertEquals(
                listOf(registration, registration),
                f.tasks.upsertProjectAttempts,
                "a null projectId makes a later reconciliation retry the refused registration",
            )
            assertTrue(f.store.projectWrites.isEmpty(), "neither refused attempt binds the session")
            assertTrue(f.tasks.upsertProjectWrites.isEmpty(), "neither refused attempt resurrects the project")

            f.tasks.seedArchived(alpha, archived = false)
            val _ = reconciler.reconcile()

            assertEquals(
                alpha,
                f.store.getSession(SessionId("tombed01"))!!.projectId,
                "restoring the project lets the very next backfill pass bind the waiting session",
            )
            assertEquals(
                listOf<Pair<SessionId, ProjectId?>>(SessionId("tombed01") to alpha),
                f.store.projectWrites,
                "only the accepted registration produces the targeted session write",
            )
            assertEquals(
                listOf(registration),
                f.tasks.upsertProjectWrites,
                "the restored project is registered exactly once",
            )
            assertEquals(
                listOf(registration, registration, registration),
                f.tasks.upsertProjectAttempts,
                "the accepted attempt follows both refusals instead of relying on a latched result",
            )
        }
    }

    @Test
    fun startupReconciliationClearsADanglingTaskRefAndKeepsAValidOne() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            val live = TaskRef("local:1")
            val gone = TaskRef("local:404")
            f.tasks.seedEntry(f.entry(live, TaskState.in_progress))
            f.seedSession("holder01", cwd = "/repo", projectId = alpha, taskRef = live)
            f.seedSession("dangler1", cwd = "/repo", projectId = alpha, taskRef = gone)

            val _ = f.reconciler().reconcile()

            assertEquals(live, f.store.getSession(SessionId("holder01"))!!.taskRef)
            assertNull(f.store.getSession(SessionId("dangler1"))!!.taskRef)
            assertEquals(
                listOf<Pair<SessionId, TaskRef?>>(SessionId("dangler1") to null),
                f.store.taskRefWrites,
                "only the dangling row is written — a valid link costs no write at all",
            )
        }
    }

    @Test
    fun aClearedTaskRefIsNotResurrectedByTheSameSessionsStateWrite() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.seedSession("dangler2", cwd = "/repo", taskRef = TaskRef("local:404"), state = SessionState.running)

            val _ = f.reconciler().reconcile()

            val row = f.store.getSession(SessionId("dangler2"))!!
            assertEquals(SessionState.crashed, row.state, "the pane is gone, so the state write did happen")
            assertNull(row.taskRef, "and the clear survived it")
        }
    }

    @Test
    fun anInProgressEntryWithNoLinkedSessionSurvivesReconciliationUntouched() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            val orphan = TaskRef("local:7")
            val before = f.entry(orphan, TaskState.in_progress)
            f.tasks.seedEntry(before)
            f.seedSession("worker01", cwd = "/repo/sub")

            val _ = f.reconciler().reconcile()

            assertEquals(before, f.tasks.snapshotEntries()[orphan], "an unlinked in_progress card is not a defect")
            assertEquals(alpha, f.store.getSession(SessionId("worker01"))!!.projectId, "the pass did run")
            assertTrue(f.store.taskRefWrites.isEmpty(), "and it wrote no link")
        }
    }

    @Test
    fun oneFailingRowDoesNotAbortTheRestOfTheTaskPass() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture(this)
            f.failures.failures["entry"] = IllegalStateException("the task store is unreadable")
            f.seedSession("poison01", cwd = "/repo", taskRef = TaskRef("local:9"))
            f.seedSession("healthy1", cwd = "/repo/sub")

            val result = f.reconciler().reconcile()

            assertEquals(2, result.sessions.size, "the pass completed")
            assertEquals(
                TaskRef("local:9"),
                f.store.getSession(SessionId("poison01"))!!.taskRef,
                "the row whose lookup threw is left exactly as it was",
            )
            assertEquals(
                alpha,
                f.store.getSession(SessionId("healthy1"))!!.projectId,
                "and the next row is still reconciled",
            )
        }
    }


    private inner class Fixture(private val scope: CoroutineScope) {
        val tmux = FakeTmux()
        val failures = FailingInterceptor()
        val tasks = FakeTaskStore(now = { CLOCK }).also {
            it.interceptor = ForbiddingInterceptor(UNREACHED, failures, ::refusal)
        }
        val store = TaskLinkStore(SqliteEventStore.inMemory(now = { CLOCK }))

        fun manager(
            fs: ProjectFs = tree(),
            newIds: List<SessionId> = listOf(SessionId("sess0001")),
            taskStore: TaskStore? = tasks,
        ): SessionManager {
            val ids = newIds.iterator()
            return SessionManager(
                tmux, store, PaneRegistry(),
                AgentFactory { _, cwd ->
                    object : AgentAdapter {
                        override val events: Flow<AgentEvent> = emptyFlow()
                        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                            LaunchSpec(listOf("cat"), emptyMap(), cwd, preallocated)
                    }
                },
                ProviderIdCapture(store, scope),
                VendorStoreProbe { _, _, _ -> true },
                VendorSessionLocator { _, _ -> null },
                setOf("claude", "codex"),
                newSessionId = { ids.next() },
                now = { CLOCK },
                taskStore = taskStore,
                projectFs = fs,
            )
        }

        fun reconciler(fs: ProjectFs = tree(), now: Long = CLOCK): Reconciler = Reconciler(
            tmux, store, VendorStoreProbe { _, _, _ -> false }, PaneRegistry(),
            now = { now },
            taskStore = tasks,
            projectFs = fs,
        )

        fun seedSession(
            id: String,
            cwd: String,
            projectId: ProjectId? = null,
            taskRef: TaskRef? = null,
            state: SessionState = SessionState.resumable,
            updatedAt: Long = CLOCK,
        ) = runBlocking {
            store.upsertSession(
                SessionMeta(
                    id = SessionId(id),
                    name = "kt-$id",
                    agent = "claude",
                    providerSessionId = null,
                    cwd = cwd,
                    tmuxSession = "kt-$id",
                    paneId = null,
                    state = state,
                    stateSource = EventSource.system,
                    createdAt = CLOCK,
                    updatedAt = updatedAt,
                    taskRef = taskRef,
                    projectId = projectId,
                ),
            )
        }

        fun entry(ref: TaskRef, state: TaskState) = BacklogEntry(
            ref = ref,
            project = alpha,
            position = 1.0,
            state = state,
            blocked = false,
            createdAt = CLOCK,
            updatedAt = CLOCK,
            rev = 1,
        )
    }

    private class TaskLinkStore(private val delegate: EventStore) : EventStore by delegate {

        val taskRefWrites = mutableListOf<Pair<SessionId, TaskRef?>>()

        val projectWrites = mutableListOf<Pair<SessionId, ProjectId?>>()

        private val taskRefs = HashMap<SessionId, TaskRef?>()
        private val projectIds = HashMap<SessionId, ProjectId?>()

        override suspend fun upsertSession(meta: SessionMeta) {
            delegate.upsertSession(meta)
            meta.taskRef?.let { taskRefs[meta.id] = it }
            meta.projectId?.let { projectIds[meta.id] = it }
        }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?) {
            if (delegate.getSession(sessionId) == null) return
            taskRefs[sessionId] = taskRef
            taskRefWrites += sessionId to taskRef
        }

        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?) {
            if (delegate.getSession(sessionId) == null) return
            projectIds[sessionId] = projectId
            projectWrites += sessionId to projectId
        }

        override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> =
            listSessions().filter { it.taskRef == taskRef }

        override suspend fun getSession(sessionId: SessionId): SessionMeta? =
            delegate.getSession(sessionId)?.let(::withLinks)

        override suspend fun listSessions(): List<SessionMeta> = delegate.listSessions().map(::withLinks)

        private fun withLinks(meta: SessionMeta): SessionMeta = meta.copy(
            taskRef = if (taskRefs.containsKey(meta.id)) taskRefs[meta.id] else meta.taskRef,
            projectId = if (projectIds.containsKey(meta.id)) projectIds[meta.id] else meta.projectId,
        )
    }

    private companion object {
        const val CLOCK: Long = 7_000L

        val UNREACHED = setOf(
            "listProjects", "listAllProjects", "project", "listBacklog", "nextCandidate", "startIfTodo",
            "startIfTodoInLiveProject", "transition", "move", "dependenciesOf", "dependentsOf",
            "dependencyEdges", "addDependency", "removeDependency", "comment", "appendActivity",
            "activity", "list", "get", "create", "update", "delete",
        )

        fun refusal(store: String, method: String): String =
            "the daemon's project wiring must not call $store.$method"
    }
}
