package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskTracker
import io.kotgent.task.TaskUpdate
import io.kotgent.task.needsRenormalization
import io.kotgent.task.positionBetween
import io.kotgent.task.positionForEnd
import io.kotgent.task.positionForTop
import io.kotgent.task.wouldCycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A host-free, thread-safe in-memory [TaskStore] — the built-in [TaskTracker] AND kotgent's own workflow
 * (ordering, dependencies, transitions, the activity feed, the project registry), all 24 members
 * implemented rather than a subset over a `unused()` throw.
 *
 * The narrower nested prototypes in the suite each model exactly the members their own routes touch,
 * which is right for a route test and wrong here: this one backs a REAL server that a browser drives, so
 * any member it left throwing would surface as a 500 on whichever board gesture happened to reach it.
 *
 * ## What it models for real, not from a knob
 *  - **`blocked` is derived, never stored input.** It is `state == todo` and some dependency not `done`,
 *    recomputed on every write, exactly as the read path of [SqliteTaskStore] computes it. A card is
 *    seeded blocked by giving it an unfinished dependency ([seedDependency]) — there is deliberately no
 *    flag to force one, because a marker no data supports is the bug a board test would then be blind to.
 *  - **A state change re-stamps and re-emits its reverse dependents.** `blocked` reads a DEPENDENCY's
 *    state, so closing a task changes rows it never touched; without the re-stamp a connected board keeps
 *    a stale blocked marker until a reload, which is the whole reason the real store does it.
 *  - **Ordering is the real gap arithmetic** ([positionForEnd] / [positionForTop] / [positionBetween]),
 *    including the collapsed-gap branch: the project's column is rewritten to `1.0, 2.0, 3.0, …`, every
 *    row stamping a fresh rev and emitting, and the move is retried once.
 *  - **All four dependency refusals are validated for real** — self, unknown ref, cross-project and
 *    cycle (through the pure [wouldCycle]) — so a route test proves the four inputs each produce one,
 *    not that a hand-thrown exception maps to a status.
 *
 * ## Concurrency
 * One [Mutex], the real store's single-writer contract — and, like the real store, every emission
 * happens INSIDE it (see [publishing]). Emitting after the lock was released let two writers stamp their
 * revisions in one order and publish them in another, so a subscriber could see a newer row before an
 * older one; the client's newest-rev-wins rule survives that, but the events socket's conflating sender
 * banks by ARRIVAL and would then ship the stale row last.
 */
class FakeTaskStore(
    /** Injected clock, so a seeded board renders the same timestamps on every run. */
    private val now: () -> Long = { 1_000L },
) : TaskStore {

    private val mutex = Mutex()
    private val projects = LinkedHashMap<ProjectId, ProjectRecord>()
    private val tasks = LinkedHashMap<TaskRef, Task>()
    private val entries = LinkedHashMap<TaskRef, BacklogEntry>()
    private val deps = LinkedHashMap<TaskRef, MutableList<TaskRef>>()
    private val activityRows = mutableListOf<TaskActivityEntry>()
    private var revCounter = 0L
    private var nextKey = 0
    private var activityId = 0L

    override val id: String = TaskRef.LOCAL_TRACKER

    /**
     * `_taskUpdates`' shape, copied from the real store (`src/store/SqliteTaskStore.kt:107-112`):
     * non-replaying, buffered and `DROP_OLDEST` at 1024, so a burst — one renormalization of a large
     * project is one update per row — never suspends the writer holding [mutex]. Deliberately NOT paired
     * with a reliable companion: that exists for the push notifier, and nothing in the task layer must
     * not-miss an intermediate transition.
     */
    private val updates = MutableSharedFlow<TaskUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val taskUpdates: SharedFlow<TaskUpdate> = updates

    /** [TaskUpdateOutbox]'s staging buffer (`src/store/SqliteTaskStore.kt:628-649`). Guarded by [mutex]. */
    private val staged = mutableListOf<TaskUpdate>()

    /** Record one change, published only if — and only when — the surrounding [publishing] block succeeds. */
    private fun stage(update: TaskUpdate) {
        staged += update
    }

    /**
     * [TaskUpdateOutbox.publishing]: run [block], then publish everything it staged, oldest first; a
     * throw discards them instead. Called with [mutex] held, and `tryEmit` never suspends, so the
     * publication is part of the write — the rev order and the emission order are the same order, which
     * is the property a subscriber cannot re-derive.
     */
    private fun <T> publishing(block: () -> T): T {
        try {
            val result = block()
            for (index in staged.indices) updates.tryEmit(staged[index])
            return result
        } finally {
            staged.clear()
        }
    }

    // --- seeding ------------------------------------------------------------------------------------
    //
    // Seeds take no lock and emit nothing: they run while a scenario is being assembled, before the
    // server binds and therefore before any socket could observe them. A seeded row that emitted would
    // put a scenario's whole fixture on the wire as if an operator had just typed it.

    /** Register a project so the board's selector can reach its backlog. */
    fun seedProject(id: ProjectId, name: String, path: String? = null) {
        projects[id] = ProjectRecord(id, name, path, now())
    }

    /**
     * Add a task with a CALLER-CHOSEN ref. [position] defaults to the end of the project's column, so a
     * scenario can seed a board by listing its cards in order and never spelling a rank.
     */
    fun seedTask(
        ref: TaskRef,
        project: ProjectId,
        title: String,
        body: String = "body of $title",
        state: TaskState = TaskState.todo,
        position: Double? = null,
    ) {
        tasks[ref] = Task(ref, title, body, url = null, updatedAt = now())
        entries[ref] = BacklogEntry(
            ref, project, position ?: endPosition(project), state, blocked = false,
            createdAt = now(), updatedAt = now(), rev = ++revCounter,
        )
        // Keep the minted-ref counter above every seeded numeric key, or the first `create` would answer
        // a ref a seeded card already owns and silently overwrite it.
        ref.key.toIntOrNull()?.let { if (it > nextKey) nextKey = it }
        reseedBlocked()
    }

    /** Add "[ref] depends on [dependsOn]" with no validation and no emission, then re-derive `blocked`. */
    fun seedDependency(ref: TaskRef, dependsOn: TaskRef) {
        val edges = deps.getOrPut(ref) { mutableListOf() }
        if (dependsOn !in edges) edges += dependsOn
        reseedBlocked()
    }

    /** Append an activity row directly — a seeded task detail needs a feed to render. */
    fun seedActivity(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String? = null,
        fromState: TaskState? = null,
        toState: TaskState? = null,
    ) {
        activityRows += TaskActivityEntry(++activityId, ref, now(), kind, author, text, fromState, toState)
    }

    // --- mutations a harness drives, beyond what the interface offers -------------------------------

    /**
     * A create at a caller-chosen [ref] — the `tasks` row, its `backlog_entries` row and one emission,
     * as [create] does. It exists because [create] mints its own ref, and the "a ref the socket has not
     * carried yet arrives as a full `task_row`" path has to be driven at a ref the driver can name.
     */
    suspend fun addTask(
        ref: TaskRef,
        project: ProjectId,
        title: String,
        position: Double? = null,
    ): BacklogEntry = mutex.withLock {
        publishing {
            tasks[ref] = Task(ref, title, "body of $title", url = null, updatedAt = now())
            val row = BacklogEntry(
                ref, project, position ?: endPosition(project), TaskState.todo, blocked = false,
                createdAt = now(), updatedAt = now(), rev = ++revCounter,
            )
            entries[ref] = row
            ref.key.toIntOrNull()?.let { if (it > nextKey) nextKey = it }
            stage(TaskUpdate(ref, row, row.rev))
            row
        }
    }

    /**
     * The collapsed-gap branch on demand: the project's whole column rewritten to `1.0, 2.0, 3.0, …`,
     * every row stamping its OWN rev and emitting its own update.
     */
    suspend fun renormalize(project: ProjectId): Unit = mutex.withLock {
        publishing { renormalizeLocked(project) }
    }

    // --- tracker ------------------------------------------------------------------------------------

    override suspend fun list(project: ProjectId): List<Task> = mutex.withLock {
        // `ORDER BY t.id` — `Tasks.sq`'s `selectTasksByProject`, not the order the rows were inserted in.
        entries.values.filter { it.project == project }
            .mapNotNull { tasks[it.ref] }
            .sortedBy { it.ref.value }
    }

    override suspend fun get(ref: TaskRef): Task? = mutex.withLock { tasks[ref] }

    override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
        mutex.withLock {
            publishing {
                val ref = TaskRef("${TaskRef.LOCAL_TRACKER}:${++nextKey}")
                val created = Task(ref, title, body, url = null, updatedAt = now())
                tasks[ref] = created
                val entry = BacklogEntry(
                    ref, project, endPosition(project), TaskState.todo, blocked = false,
                    createdAt = now(), updatedAt = now(), rev = ++revCounter,
                )
                entries[ref] = entry
                // The author the caller passed, exactly as the real store records it — hardcoding "board"
                // here would answer the same whether or not the route ever attributes the create.
                activityRows += TaskActivityEntry(
                    ++activityId, ref, now(), ActivityKind.created, author, null, null, null,
                )
                stage(TaskUpdate(ref, entry, entry.rev))
                created
            }
        }

    override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = mutex.withLock {
        publishing {
            // A null argument means "leave unchanged", never "clear": both columns are NOT NULL and a
            // PATCH omitting one must not blank it.
            val existing = tasks[ref] ?: return@publishing null
            val updated = existing.copy(
                title = title ?: existing.title, body = body ?: existing.body, updatedAt = now(),
            )
            tasks[ref] = updated
            // The row that MOVED is `tasks`; the ENTRY only owes a fresh rev and an emission, and its
            // `updated_at` deliberately stays put — `SqliteTaskStore.update` (:526-531, through
            // `restampAndStageLocked`) says so in as many words.
            restampLocked(ref)
            updated
        }
    }

    override suspend fun delete(ref: TaskRef): Boolean = mutex.withLock {
        publishing {
            if (tasks.remove(ref) == null) return@publishing false
            // Read the reverse edges BEFORE the cascade drops them, then re-stamp them AFTER: a task
            // deleted out from under its dependents unblocks them exactly as closing it would have.
            val dependents = dependentsLocked(ref)
            entries.remove(ref)
            deps.remove(ref)
            deps.values.forEach { it.remove(ref) }
            activityRows.removeAll { it.ref == ref }
            // The removal is staged FIRST and takes the lower revision — `SqliteTaskStore.delete`
            // (:268-271) stages `TaskUpdate(ref, null, nextRev())` and only then re-stamps.
            stage(TaskUpdate(ref, null, ++revCounter))
            restampAllLocked(dependents)
            true
        }
    }

    // --- backlog reads ------------------------------------------------------------------------------

    override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { entries[ref] }

    override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = mutex.withLock {
        entries.values.filter { it.project == project }.sortedWith(RANK_ORDER)
    }

    override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = mutex.withLock {
        // `blocked` already means "todo with an unfinished dependency", so the candidate query is the
        // first non-blocked todo in rank order — and `null` is the only "nothing eligible" answer.
        entries.values
            .filter { it.project == project && it.state == TaskState.todo && !it.blocked }
            .minWithOrNull(RANK_ORDER)
    }

    // --- backlog writes -----------------------------------------------------------------------------

    override suspend fun startIfTodo(ref: TaskRef): Boolean = mutex.withLock {
        publishing {
            val existing = entries[ref] ?: return@publishing false
            if (existing.state != TaskState.todo) return@publishing false
            writeStateLocked(existing, TaskState.in_progress)
            true
        }
    }

    override suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? = mutex.withLock {
        publishing {
            val existing = entries[ref] ?: return@publishing null
            // The state change and its activity row are one step, so a `review -m "…"` cannot leave a
            // review with no explanation or a comment on an unreviewed task.
            activityRows += TaskActivityEntry(
                ++activityId, ref, now(), ActivityKind.transition, author, message, existing.state, to,
            )
            writeStateLocked(existing, to)
        }
    }

    override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = mutex.withLock {
        publishing {
            val existing = entries[ref] ?: return@publishing null
            val neighbour = when (target) {
                is MoveTarget.Before -> target.ref
                is MoveTarget.After -> target.ref
                else -> null
            }
            // A named neighbour the store does not hold — or one in another project — is an unknown ref,
            // not a position: there is nothing to move relative to.
            if (neighbour != null && entries[neighbour]?.project != existing.project) return@publishing null

            // A collapsed gap rewrites the column and the move is retried ONCE; after a renormalization
            // every gap is 1.0, so a second refusal would mean the arithmetic itself is wrong.
            val rank = rankForLocked(existing, target) ?: run {
                renormalizeLocked(existing.project)
                rankForLocked(entries.getValue(ref), target)
                    ?: error("a renormalized column must always subdivide")
            }
            val row = entries.getValue(ref).copy(position = rank, updatedAt = now(), rev = ++revCounter)
            entries[ref] = row
            stage(TaskUpdate(ref, row, row.rev))
            row
        }
    }

    // --- dependencies -------------------------------------------------------------------------------
    //
    // All three reads are ORDERED, because `Backlog.sq` orders them and both lists reach the browser
    // verbatim as `TaskDetailDto.dependsOn` / `dependents` (`src/transport/TaskReadRoutes.kt:135-136`).
    // A `LinkedHashMap`'s insertion order agrees only until a fixture seeds two edges the other way
    // round, and then the disagreement shows up as a browser assertion about the wrong row.

    /** `ORDER BY depends_on` — `Backlog.sq`'s `selectDependencies`. */
    override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { deps[ref].orEmpty().sortedBy { it.value } }

    override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = mutex.withLock { dependentsLocked(ref) }

    /** `ORDER BY d.task_ref, d.depends_on` — `Backlog.sq`'s `selectDependencyEdges`. */
    override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> = mutex.withLock {
        // A LinkedHashMap filled in sorted order, because `toSortedMap` is a JVM-only extension.
        deps.entries
            .filter { entries[it.key]?.project == project }
            .sortedBy { it.key.value }
            .associateTo(LinkedHashMap()) { entry -> entry.key to entry.value.sortedBy { it.value } }
    }

    override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        publishing {
            fun refuse(refusal: DependencyRefusal, why: String): Nothing = throw DependencyRefusedException(
                refusal, ref, dependsOn,
                "cannot add '${ref.value}' depends on '${dependsOn.value}': $why (${refusal.name})",
            )
            // All four refusals are validated ON INSERT: a dangling or cross-project edge would be read
            // as "already satisfied" by the candidate query and silently unblock a task that is not ready.
            if (ref == dependsOn) refuse(DependencyRefusal.self, "a task cannot depend on itself")
            val from = entries[ref] ?: refuse(DependencyRefusal.unknownRef, "no such task '${ref.value}'")
            val to = entries[dependsOn]
                ?: refuse(DependencyRefusal.unknownRef, "no such task '${dependsOn.value}'")
            if (from.project != to.project) {
                refuse(DependencyRefusal.crossProject, "they belong to different projects")
            }
            val edges = deps.getOrPut(ref) { mutableListOf() }
            // Re-adding an existing edge returns BEFORE the write, and before the cycle check, exactly
            // where `BacklogDependencies.addLocked` (src/store/BacklogDependencies.kt:209) returns:
            // `INSERT OR IGNORE` would make the statement a no-op anyway, but the emissions after it
            // would not be, and "no-op" has to mean the board hears nothing.
            if (dependsOn in edges) return@publishing
            if (wouldCycle(edgeSnapshotLocked(), ref, dependsOn)) {
                refuse(DependencyRefusal.cycle, "it would close a ring")
            }
            edges += dependsOn
            restampAfterEditLocked(ref)
        }
    }

    override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        publishing {
            // Removing an edge that is not there is the same no-op, and leaves the same silence —
            // `BacklogDependencies.removeLocked` (src/store/BacklogDependencies.kt:230).
            if (deps[ref]?.remove(dependsOn) != true) return@publishing
            restampAfterEditLocked(ref)
        }
    }

    // --- activity -----------------------------------------------------------------------------------

    override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
        mutex.withLock {
            if (ref !in entries) return@withLock null
            val row = TaskActivityEntry(++activityId, ref, now(), ActivityKind.comment, author, text, null, null)
            activityRows += row
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
        val row = TaskActivityEntry(++activityId, ref, now(), kind, author, text, fromState, toState)
        activityRows += row
        row
    }

    override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> =
        mutex.withLock { activityRows.filter { it.ref == ref } }

    // --- projects -----------------------------------------------------------------------------------

    override suspend fun upsertProject(id: ProjectId, name: String, path: String?): Unit = mutex.withLock {
        // A null path leaves the stored one alone (Projects.sq), so a refresh that only knows the name
        // cannot erase the checkout the daemon last saw.
        val existing = projects[id]
        projects[id] = ProjectRecord(id, name, path ?: existing?.path, now())
    }

    override suspend fun listProjects(): List<ProjectRecord> =
        mutex.withLock { projects.values.sortedBy { it.name } }

    override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock { projects[id] }

    // --- internals (all callers hold [mutex]) --------------------------------------------------------

    /**
     * Write [to] onto [existing], re-derive its own `blocked`, stage it, then re-stamp and stage every
     * reverse dependent — the emission order the real store's one transaction produces
     * (`SqliteTaskStore.transitionLocked`, :365-372, and `startIfTodoLocked`, :316-329).
     */
    private fun writeStateLocked(existing: BacklogEntry, to: TaskState): BacklogEntry {
        val stamped = existing.copy(state = to, updatedAt = now(), rev = ++revCounter)
        val row = stamped.copy(blocked = derivedBlocked(stamped))
        entries[existing.ref] = row
        stage(TaskUpdate(row.ref, row, row.rev))
        restampDependentsLocked(existing.ref)
        return row
    }

    /**
     * Stamp one row a fresh rev, re-derive its `blocked`, and stage it —
     * `SqliteTaskStore.restampAndStageLocked` (:526-531) over `Backlog.sq`'s `restamp` (:148-154).
     *
     * **`updated_at` is deliberately untouched.** That statement is `SET rev = ?` and says why in its own
     * comment: the row's derived `blocked` may have moved, but nothing about it was EDITED, and
     * `updated_at` is activity (the same reason `setReadCursor` leaves it alone in `Sessions.sq`).
     * Writing the clock here is invisible under a frozen fixture clock and wrong the moment a scenario
     * uses a real one — which the harness does.
     */
    private fun restampLocked(ref: TaskRef) {
        val existing = entries[ref] ?: return
        val stamped = existing.copy(rev = ++revCounter)
        val row = stamped.copy(blocked = derivedBlocked(stamped))
        entries[ref] = row
        stage(TaskUpdate(row.ref, row, row.rev))
    }

    /**
     * Re-stamp every reverse dependent of [ref] — UNCONDITIONALLY, exactly as
     * `BacklogDependencies.restampDependentsLocked` (src/store/BacklogDependencies.kt:167-169) does.
     *
     * Filtering to the rows whose `blocked` actually moved was the tempting optimisation and it is the
     * wrong one: the real store cannot see that (its `blocked` is derived in the read path, never
     * stored), so it emits for all of them, and a redundant emission is invisible under the client's
     * newest-rev-wins rule while a missing one is a stale marker until a reload. A fake that emits
     * strictly fewer frames than the daemon is a fake a "the board heard about it" assertion passes
     * against for the wrong reason.
     */
    private fun restampDependentsLocked(ref: TaskRef) = restampAllLocked(dependentsLocked(ref))

    /** [restampLocked] over a pre-read set — `delete` reads its dependents before the cascade drops them. */
    private fun restampAllLocked(refs: Collection<TaskRef>) = refs.forEach { restampLocked(it) }

    /**
     * What an accepted edit to [ref]'s own edge set owes the board — `[ref]` itself, then its reverse
     * dependents (`BacklogDependencies.restampAfterEditLocked`, :249-252). The second half is
     * conservative and known to be: an edge edit changes no STATE, so no dependent's `blocked` can
     * actually have moved. It is emitted because `TaskStore.addDependency` declares it.
     */
    private fun restampAfterEditLocked(ref: TaskRef) {
        restampLocked(ref)
        restampDependentsLocked(ref)
    }

    /**
     * Re-derive `blocked` across the whole tree, with no rev bump and no emission — the seeding-time
     * counterpart of [restampLocked]. It is what makes seeds ORDER-INSENSITIVE: a scenario may
     * list a dependency before the task it points at, or mark a dependency `done` afterwards, and every
     * card still renders the marker its data supports.
     */
    private fun reseedBlocked() {
        for (ref in entries.keys.toList()) {
            val existing = entries.getValue(ref)
            entries[ref] = existing.copy(blocked = derivedBlocked(existing))
        }
    }

    /**
     * `todo` with some dependency that is PRESENT IN THE SAME PROJECT and not `done` — the derived rule,
     * never a stored input.
     *
     * The two qualifiers are not defensive padding, they are what the SQL says. `listBacklogLocked`
     * (`src/store/BacklogDependencies.kt`) builds `present` and `done` from the rows of ONE
     * `WHERE project = ?` and then asks `it in present && it !in done` — the in-memory form of
     * `unfinishedDependencyCount`'s JOIN, where an edge pointing outside that row set simply has no row
     * to be un-`done`. So production does NOT block on a dangling edge, and does NOT block on an edge
     * into another project; a bare `entries[it]?.state != TaskState.done` blocks on both (a missing
     * entry answers `null`, and `null != done`). No scenario reaches either case today, which is
     * exactly why it had to be written down rather than left to a future fixture to discover: browser
     * assertions are measured against this implementation, so a second rule here is a second product.
     */
    private fun derivedBlocked(entry: BacklogEntry): Boolean =
        entry.state == TaskState.todo &&
            deps[entry.ref].orEmpty().any { dependency ->
                val row = entries[dependency]
                row != null && row.project == entry.project && row.state != TaskState.done
            }

    /** `ORDER BY task_ref` — `Backlog.sq`'s `selectDependents`, the reverse lookup. */
    private fun dependentsLocked(ref: TaskRef): List<TaskRef> =
        deps.filterValues { ref in it }.keys.sortedBy { it.value }

    private fun edgeSnapshotLocked(): Map<TaskRef, List<TaskRef>> = deps.mapValues { it.value.toList() }

    private fun endPosition(project: ProjectId): Double =
        positionForEnd(entries.values.filter { it.project == project }.maxOfOrNull { it.position })

    /**
     * The rank [target] asks for, or `null` when the gap is too small to subdivide and the caller owes a
     * renormalization. Named neighbours are validated by the caller, so a `null` here is never "unknown".
     */
    private fun rankForLocked(existing: BacklogEntry, target: MoveTarget): Double? {
        val siblings = entries.values
            .filter { it.project == existing.project && it.ref != existing.ref }
            .map { it.position }
            .sorted()
        return when (target) {
            MoveTarget.Top -> {
                // The floor stands in for the lower neighbour a top insert does not have; halving toward
                // zero is the collapsing direction, so this is the check that actually fires.
                val min = siblings.firstOrNull() ?: return positionForTop(null)
                if (needsRenormalization(0.0, min)) null else positionForTop(min)
            }

            MoveTarget.Bottom -> positionForEnd(siblings.lastOrNull())

            is MoveTarget.Before -> {
                val upper = entries.getValue(target.ref).position
                val lower = siblings.lastOrNull { it < upper } ?: 0.0
                if (needsRenormalization(lower, upper)) null else positionBetween(lower, upper)
            }

            is MoveTarget.After -> {
                val lower = entries.getValue(target.ref).position
                val upper = siblings.firstOrNull { it > lower } ?: return positionForEnd(lower)
                if (needsRenormalization(lower, upper)) null else positionBetween(lower, upper)
            }
        }
    }

    private fun renormalizeLocked(project: ProjectId) {
        entries.values
            .filter { it.project == project }
            .sortedWith(RANK_ORDER)
            .forEachIndexed { index, entry ->
                val row = entry.copy(position = index + 1.0, rev = ++revCounter)
                entries[entry.ref] = row
                stage(TaskUpdate(row.ref, row, row.rev))
            }
    }

    private companion object {
        /**
         * `ORDER BY position, task_ref` — the one entry order `Backlog.sq` declares, shared by
         * `selectEntriesByProject` (:49-54) and `nextCandidate` (:67-79). The tie-break is not padding:
         * a renormalization observed mid-flight, or a fixture that seeds two cards at one rank, would
         * otherwise let two reads swap them.
         */
        val RANK_ORDER: Comparator<BacklogEntry> = compareBy({ it.position }, { it.ref.value })
    }
}
