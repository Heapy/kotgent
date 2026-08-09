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
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectFs
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 12 — the daemon's half of project resolution: `SessionManager.start` / `importSession` stamping
 * `sessions.project_id` and registering the `projects` row, and `Reconciler` backfilling the one and
 * clearing a dangling `sessions.task_ref`.
 *
 * ## What these tests are built around
 *  1. **The two facts are written together or not at all.** A resolved project means BOTH a stamped row
 *     and a `projects` row the board's selector can list; a registration that fails leaves the row
 *     unstamped precisely so the backfill — which only looks at rows whose `project_id` is null — is
 *     still the thing that retries it. [aFailedProjectRegistrationLeavesTheRowUnstampedSoTheBackfillRetriesIt]
 *     is the only place that decision is visible.
 *  2. **The task pass runs AFTER every state write.** `Sessions.sq`'s `upsert` COALESCEs `task_ref`, so a
 *     clear written first is resurrected by the stale full-row snapshot the state loop writes. [TaskLinkStore]
 *     models that COALESCE deliberately, which is what makes
 *     [aClearedTaskRefIsNotResurrectedByTheSameSessionsStateWrite] falsifiable rather than decorative.
 *  3. **Nothing else about tasks is reconciled.** [FakeTaskStore] implements exactly the three members
 *     this pass may touch and throws from all twenty-odd others, so "the reconciler wrote a task" is a
 *     test failure by construction rather than an assertion somebody has to remember to write.
 *  4. **Neither task-pass write is activity, so neither moves `updated_at`** — the key `kotgent list`
 *     sorts by. [theTaskPassCarriesTheRowsOwnSortKeyRatherThanTheRestartTime] is the only place the
 *     timestamp handed to the two targeted setters is observable, which is why [TaskLinkStore] records
 *     it: these two overrides deliberately never reach the real SQL.
 *
 * Host-free throughout: [FakeTmux] + an in-memory [SqliteEventStore] + a fake tree behind [ProjectFs], so
 * no `.kotgent.json` and no `.git` is ever read off the real disk. The `sessions` side is wrapped in
 * [TaskLinkStore], which implements the three task-link members (`setTaskRef` / `setProjectId` /
 * `sessionsHoldingTask`) and delegates everything else to the real store — so the rest of the row,
 * including the `project_id` an `upsert` carries, round-trips through real SQL while the assertions here
 * measure THIS task's wiring rather than another one's storage. Every body is bounded by [withTimeout]
 * as an anti-hang tripwire.
 */
class TaskProjectWiringTest {

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")

    /** An id no `.kotgent.json` in these fixtures carries — the "already stamped" marker. */
    private val beta = ProjectId.of("11111111-2222-4333-8444-555555555555")

    private val preallocated = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    // --- the fake tree ------------------------------------------------------------------------------

    /**
     * `/repo` is a checkout with a committed project file; `/repo/sub` is a subdirectory of it;
     * `/wt/feature` is an ordinary linked worktree of the same repository (its `.git` FILE names
     * `/repo/.git/worktrees/feature`); `/elsewhere` is outside any project.
     */
    private fun tree(): FakeProjectFs = FakeProjectFs(
        dirs = setOf(
            "/", "/repo", "/repo/sub", "/repo/.git", "/repo/.git/worktrees",
            "/repo/.git/worktrees/feature", "/wt", "/wt/feature", "/elsewhere",
        ),
        files = mapOf(
            "/repo/.kotgent.json" to """{"id": "${alpha.value}", "name": "kotgent"}""",
            "/wt/feature/.git" to "gitdir: /repo/.git/worktrees/feature\n",
        ),
    )

    // --- start / import -----------------------------------------------------------------------------

    /**
     * The headline: a session started anywhere under a project gets its uuid on the row AND puts the
     * project in the registry the board's selector reads. Asserting only the stamp would pass against an
     * implementation whose project never appears in `GET /api/v1/projects` — the backlog would exist and
     * be unreachable.
     */
    @Test
    fun aStartInsideAProjectStampsTheRowAndRegistersTheProject() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)

            val started = f.manager().start("claude", "/repo/sub")

            assertEquals(alpha, started.projectId, "the returned meta carries the project")
            assertEquals(
                alpha,
                f.store.getSession(started.id)!!.projectId,
                "and so does the committed row — the id is written by the insert itself",
            )
            assertEquals(
                listOf(ProjectRegistration(alpha, "kotgent", "/repo")),
                f.tasks.registrations,
                "the projects row names the CHECKOUT ROOT, not the session's cwd",
            )
        }
    }

    /** Outside any `.kotgent.json` there is nothing to resolve: a null column and an empty registry. */
    @Test
    fun aStartOutsideAnyProjectLeavesTheProjectNullAndRegistersNothing() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)

            val started = f.manager().start("claude", "/elsewhere")

            assertNull(started.projectId)
            assertNull(f.store.getSession(started.id)!!.projectId)
            assertTrue(f.tasks.registrations.isEmpty(), "no project file, nothing to register")
        }
    }

    /**
     * An import is registration, not launch — but it resolves the SAME project a `start` in that
     * directory would, off the canonicalized cwd the row already stores.
     */
    @Test
    fun anImportStampsTheSameProjectAStartInThatDirectoryWould() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            // importSession canonicalizes through the REAL filesystem before it resolves anything, so the
            // fake tree is keyed by a real directory: /tmp resolves to its own canonical spelling, which is
            // where this fixture's project file lives.
            val real = canonicalPath("/tmp")!!
            val fs = FakeProjectFs(
                dirs = setOf("/", real),
                files = mapOf("$real/.kotgent.json" to """{"id": "${alpha.value}", "name": "kotgent"}"""),
            )

            val imported = f.manager(fs = fs).importSession("claude", preallocated, cwd = "/tmp")

            assertEquals(alpha, imported.projectId)
            assertEquals(alpha, f.store.getSession(imported.id)!!.projectId)
            assertEquals(
                listOf(ProjectRegistration(alpha, "kotgent", real)),
                f.tasks.registrations,
                "the import registers the project it resolved, exactly as a start does",
            )
        }
    }

    /**
     * The reason a project is a committed uuid rather than a path: `/repo` and its linked worktree
     * `/wt/feature` are one body of work, so both sessions land on ONE project and one backlog. The
     * registry keeps a single row whose `path` is the checkout seen most recently — worktrees
     * deliberately overwrite it, which is why the second registration is asserted too.
     */
    @Test
    fun twoWorktreesOfOneRepositoryLandOnOneProject() = runBlocking {
        withTimeout(20_000) {
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
                    ProjectRegistration(alpha, "kotgent", "/repo"),
                    ProjectRegistration(alpha, "kotgent", "/repo"),
                ),
                f.tasks.registrations,
                "both resolve to the main checkout root, so the one projects row is refreshed with it",
            )
        }
    }

    /**
     * The decision this pass makes alone: when the `projects` upsert fails, the resolution is discarded
     * rather than stamped. Stamping it would persist a `project_id` and thereby remove the ONE thing that
     * ever retries the pair — the backfill only looks at rows whose `project_id` is null — leaving a
     * session pinned to a project the board cannot list. And it must not fail the launch: the session
     * still starts.
     */
    @Test
    fun aFailedProjectRegistrationLeavesTheRowUnstampedSoTheBackfillRetriesIt() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            f.tasks.upsertProjectFailure = IllegalStateException("disk is on fire")

            val started = f.manager().start("claude", "/repo/sub")

            assertEquals(SessionState.running, started.state, "a registry failure must never fail the launch")
            assertNull(started.projectId, "write both or neither")
            assertNull(f.store.getSession(started.id)!!.projectId)

            // ... and the next daemon start heals it, which is the whole reason it was left null.
            f.tasks.upsertProjectFailure = null
            f.reconciler().reconcile()

            assertEquals(alpha, f.store.getSession(started.id)!!.projectId)
            assertEquals(listOf(ProjectRegistration(alpha, "kotgent", "/repo")), f.tasks.registrations)
        }
    }

    /**
     * The same "write both or neither" rule, taken from the other direction: with a filesystem but no
     * task store there is nowhere to register, so there is no id to report either. The two are
     * independent constructor parameters, and answering the resolved id here would stamp
     * `sessions.project_id` with a project that has no `projects` row AND no backfill left to repair it —
     * `Reconciler.backfillProjectId` only ever looks at rows whose `project_id` is null.
     */
    @Test
    fun withNoTaskStoreToRegisterInTheProjectIsNotStampedEither() = runBlocking {
        withTimeout(20_000) {
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

    // --- startup reconciliation ---------------------------------------------------------------------

    /**
     * The backfill: a row written before the backlog existed (or before its `.kotgent.json` was
     * committed) gets its project on the next daemon start. A row that already names one is left alone —
     * re-resolving every session on every start would walk the filesystem per row for an answer only a
     * moved project file could change, and would silently re-point a session at a nearer project.
     */
    @Test
    fun startupReconciliationBackfillsAMissingProjectIdAndLeavesAnExistingOneAlone() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            f.seedSession("old00001", cwd = "/repo/sub")
            f.seedSession("stamped1", cwd = "/repo/sub", projectId = beta)

            f.reconciler().reconcile()

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

    /**
     * `sessions.task_ref` is a reference, not a foreign key: the ordinary delete unlinks its holders
     * first, and this pass closes the racing case. A link that still resolves must survive — clearing it
     * would silently drop a live worker's assignment on every restart.
     */
    @Test
    fun startupReconciliationClearsADanglingTaskRefAndKeepsAValidOne() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            val live = TaskRef("local:1")
            val gone = TaskRef("local:404")
            f.tasks.entries[live] = f.entry(live, TaskState.in_progress)
            f.seedSession("holder01", cwd = "/repo", projectId = alpha, taskRef = live)
            f.seedSession("dangler1", cwd = "/repo", projectId = alpha, taskRef = gone)

            f.reconciler().reconcile()

            assertEquals(live, f.store.getSession(SessionId("holder01"))!!.taskRef)
            assertNull(f.store.getSession(SessionId("dangler1"))!!.taskRef)
            assertEquals(
                listOf<Pair<SessionId, TaskRef?>>(SessionId("dangler1") to null),
                f.store.taskRefWrites,
                "only the dangling row is written — a valid link costs no write at all",
            )
        }
    }

    /**
     * The ordering rule, and the only test that can see it: the state loop upserts a FULL row built from
     * the meta it read, and `Sessions.sq`'s `upsert` COALESCEs `task_ref` — so a clear written BEFORE
     * that upsert is resurrected by the stale snapshot. The session here has no live pane, so its state
     * really does change and the upsert really does fire.
     */
    @Test
    fun aClearedTaskRefIsNotResurrectedByTheSameSessionsStateWrite() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            f.seedSession("dangler2", cwd = "/repo", taskRef = TaskRef("local:404"), state = SessionState.running)

            f.reconciler().reconcile()

            val row = f.store.getSession(SessionId("dangler2"))!!
            assertEquals(SessionState.crashed, row.state, "the pane is gone, so the state write did happen")
            assertNull(row.taskRef, "and the clear survived it")
        }
    }

    /**
     * Nothing else about tasks is reconciled. An `in_progress` entry with no linked session is
     * legitimate — a human dragged the card, or its worker was archived — so the pass must not touch it;
     * [FakeTaskStore] turns any other write into a thrown `UnsupportedOperationException`, so this
     * asserts the entry is byte-identical and lets the fake police the rest.
     */
    @Test
    fun anInProgressEntryWithNoLinkedSessionSurvivesReconciliationUntouched() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            val orphan = TaskRef("local:7")
            val before = f.entry(orphan, TaskState.in_progress)
            f.tasks.entries[orphan] = before
            // A session in the same project, deliberately NOT linked to it — otherwise the pass would
            // have nothing to do and the test would pass vacuously.
            f.seedSession("worker01", cwd = "/repo/sub")

            f.reconciler().reconcile()

            assertEquals(before, f.tasks.entries[orphan], "an unlinked in_progress card is not a defect")
            assertEquals(alpha, f.store.getSession(SessionId("worker01"))!!.projectId, "the pass did run")
            assertTrue(f.store.taskRefWrites.isEmpty(), "and it wrote no link")
        }
    }

    /**
     * Neither task-pass write is ACTIVITY, so neither may move `updated_at` — the key `kotgent list`
     * sorts by. Without this the first daemon start after a `.kotgent.json` was committed re-stamped
     * every session under that repository with the restart time, collapsing the whole history into one
     * timestamp, once and permanently.
     *
     * Both halves are here, and the second is what makes the fix a RE-READ rather than "pass
     * `meta.updatedAt`": `quiet001`'s state does not change, so its sort key must survive untouched;
     * `moved001` is a genuine liveness change (`running` → `crashed`, which IS activity), so the state
     * loop stamps it and the clear that follows must carry that FRESH value instead of rolling it back
     * to the snapshot the pass started from.
     */
    @Test
    fun theTaskPassCarriesTheRowsOwnSortKeyRatherThanTheRestartTime() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            val gone = TaskRef("local:404")
            // Already `crashed` with no pane: `classify` answers `crashed`, so the state loop writes nothing.
            f.seedSession(
                "quiet001",
                cwd = "/repo/sub",
                taskRef = gone,
                state = SessionState.crashed,
                updatedAt = SEEDED,
            )
            f.seedSession(
                "moved001",
                cwd = "/repo",
                projectId = alpha,
                taskRef = gone,
                state = SessionState.running,
                updatedAt = SEEDED,
            )

            f.reconciler(now = RESTART).reconcile()

            assertEquals(
                SessionState.crashed,
                f.store.getSession(SessionId("moved001"))!!.state,
                "the second row really did take a state write, or its half of this test is vacuous",
            )
            assertNull(f.store.getSession(SessionId("quiet001"))!!.taskRef, "and both clears happened")
            assertNull(f.store.getSession(SessionId("moved001"))!!.taskRef)
            assertEquals(
                alpha,
                f.store.getSession(SessionId("quiet001"))!!.projectId,
                "as did the backfill the quiet row needed",
            )

            assertEquals(
                mapOf(SessionId("quiet001") to SEEDED, SessionId("moved001") to RESTART),
                f.store.writeTimestamps.toMap(),
                "a derived backfill and a reference GC keep the row's own sort key; a liveness change owns its",
            )
        }
    }

    /**
     * The pass runs before the daemon binds its server, so one unreadable row must not take the daemon
     * down with it: the failure is logged and the remaining sessions are still reconciled.
     */
    @Test
    fun oneFailingRowDoesNotAbortTheRestOfTheTaskPass() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture(this)
            f.tasks.entryFailure = IllegalStateException("the task store is unreadable")
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

    // --- fixture ------------------------------------------------------------------------------------

    /** The collaborators plus the two units under test, over one in-memory database. */
    private inner class Fixture(private val scope: CoroutineScope) {
        val tmux = FakeTmux()
        val tasks = FakeTaskStore()
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
                        // Preallocated on purpose: an id-less launch would start ProviderIdCapture's
                        // background poll on this scope and outlive the assertion by seconds.
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

    /** One `TaskStore.upsertProject` call, as the tests read it. */
    private data class ProjectRegistration(val id: ProjectId, val name: String, val path: String?)

    /**
     * An in-memory tree behind [ProjectFs]: [dirs] are directories, [files] map a path to its bytes, and
     * [canonicalize] collapses `.` / `..` / duplicate slashes and then answers only for a path that
     * exists — the `realpath(3)` contract the rules in `ProjectFile.kt` are written against.
     */
    private class FakeProjectFs(
        private val dirs: Set<String>,
        private val files: Map<String, String>,
    ) : ProjectFs {

        override fun isDirectory(path: String): Boolean = normalize(path) in dirs

        override fun readFile(path: String, maxBytes: Int): String? =
            files[normalize(path)]?.take(maxBytes)

        override fun canonicalize(path: String): String? {
            val normalized = normalize(path)
            return if (normalized in dirs || normalized in files) normalized else null
        }

        private fun normalize(path: String): String {
            val out = ArrayList<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(segment)
                }
            }
            return "/" + out.joinToString("/")
        }
    }

    /**
     * The three members startup reconciliation and a session start may touch — `upsertProject`, `entry`
     * and (for completeness of the tracker seam) nothing else. Every other member throws, which is what
     * makes "nothing else about tasks is reconciled" a property of the fixture rather than an assertion
     * somebody has to remember.
     */
    private class FakeTaskStore : TaskStore {
        val registrations = mutableListOf<ProjectRegistration>()
        val entries = HashMap<TaskRef, BacklogEntry>()

        /** When set, `upsertProject` throws it — a store failure the daemon must survive. */
        var upsertProjectFailure: Throwable? = null

        /** When set, `entry` throws it — an unreadable row the reconcile pass must step over. */
        var entryFailure: Throwable? = null

        override val id: String = TaskRef.LOCAL_TRACKER

        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        override suspend fun upsertProject(id: ProjectId, name: String, path: String?) {
            upsertProjectFailure?.let { throw it }
            registrations += ProjectRegistration(id, name, path)
        }

        override suspend fun entry(ref: TaskRef): BacklogEntry? {
            entryFailure?.let { throw it }
            return entries[ref]
        }

        override suspend fun listProjects(): List<ProjectRecord> = unused("listProjects")

        override suspend fun project(id: ProjectId): ProjectRecord? = unused("project")

        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = unused("listBacklog")

        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = unused("nextCandidate")

        override suspend fun startIfTodo(ref: TaskRef): Boolean = unused("startIfTodo")

        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = unused("transition")

        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = unused("move")

        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> = unused("dependenciesOf")

        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = unused("dependentsOf")

        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
            unused("dependencyEdges")

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

        override suspend fun list(project: ProjectId): List<Task> = unused("list")

        override suspend fun get(ref: TaskRef): Task? = unused("get")

        override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
            unused("create")

        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")

        override suspend fun delete(ref: TaskRef): Boolean = unused("delete")

        private fun unused(member: String): Nothing =
            throw UnsupportedOperationException("the daemon's project wiring must not call TaskStore.$member")
    }

    /**
     * The real in-memory [SqliteEventStore] plus the three task-link members, modelled here rather than
     * depended on: their SQL is another task's, written in a sibling worktree, and a wiring test that
     * died inside somebody else's unimplemented member would be reporting on the wrong thing.
     *
     * [upsertSession] deliberately reproduces `Sessions.sq`'s `COALESCE(excluded.x, sessions.x)`: a
     * full-row write carrying a non-null link overwrites, a null one keeps what is stored. That is not
     * decoration — it is what makes the "a clear must not be resurrected by a later state write"
     * ordering rule observable from a test.
     */
    private class TaskLinkStore(private val delegate: EventStore) : EventStore by delegate {

        /** (session, new value) for every targeted link write, in order. */
        val taskRefWrites = mutableListOf<Pair<SessionId, TaskRef?>>()

        /** (session, new value) for every targeted project write, in order. */
        val projectWrites = mutableListOf<Pair<SessionId, ProjectId?>>()

        /**
         * The `updated_at` each targeted write was handed, per session. Kept apart from the two lists
         * above so their assertions stay readable; this is the only place the sort key is observable,
         * because these two overrides deliberately do not reach the real SQL.
         */
        val writeTimestamps = mutableListOf<Pair<SessionId, Long>>()

        private val taskRefs = HashMap<SessionId, TaskRef?>()
        private val projectIds = HashMap<SessionId, ProjectId?>()

        override suspend fun upsertSession(meta: SessionMeta) {
            delegate.upsertSession(meta)
            meta.taskRef?.let { taskRefs[meta.id] = it }
            meta.projectId?.let { projectIds[meta.id] = it }
        }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) {
            if (delegate.getSession(sessionId) == null) return // a no-op on a missing row, per the contract
            taskRefs[sessionId] = taskRef
            taskRefWrites += sessionId to taskRef
            writeTimestamps += sessionId to updatedAt
        }

        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) {
            if (delegate.getSession(sessionId) == null) return
            projectIds[sessionId] = projectId
            projectWrites += sessionId to projectId
            writeTimestamps += sessionId to updatedAt
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
        /** One frozen clock for the whole fixture; nothing here asserts on time. */
        const val CLOCK: Long = 7_000L

        /** A seeded row's own `updated_at` — its place in `kotgent list`'s ordering. */
        const val SEEDED: Long = 1_000L

        /** A reconciler clock distinct from every seeded row's, so "the restart time" is visible. */
        const val RESTART: Long = 90_000L
    }
}
