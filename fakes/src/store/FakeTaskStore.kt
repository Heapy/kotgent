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
 * One [Mutex], the real store's single-writer contract. Emissions happen OUTSIDE the lock: with
 * [updatesBuffer] `0` the flow is a rendezvous and emitting under the lock would deadlock the writer
 * against its own collector.
 */
class FakeTaskStore(
    /**
     * `taskUpdates`' spare capacity. The default mirrors the real store; `0` turns the flow into a
     * RENDEZVOUS, which is the deterministic way to probe "is anybody actually collecting" — a buffered
     * flow answers that question with a race instead of a deadlock.
     */
    updatesBuffer: Int = 1024,
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
     * The `_sessionUpdates` shape the contract names: buffered and `DROP_OLDEST`, so a burst never
     * suspends the writer. Deliberately NOT paired with a reliable companion — that exists for the push
     * notifier, and nothing in the task layer must not-miss an intermediate transition.
     */
    private val updates = MutableSharedFlow<TaskUpdate>(
        extraBufferCapacity = updatesBuffer,
        // A zero-capacity SharedFlow may only SUSPEND, which is exactly what makes the rendezvous
        // variant a deterministic probe of whether anybody is collecting.
        onBufferOverflow = if (updatesBuffer == 0) BufferOverflow.SUSPEND else BufferOverflow.DROP_OLDEST,
    )
    override val taskUpdates: SharedFlow<TaskUpdate> = updates

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
    ): BacklogEntry {
        val created = mutex.withLock {
            tasks[ref] = Task(ref, title, "body of $title", url = null, updatedAt = now())
            val row = BacklogEntry(
                ref, project, position ?: endPosition(project), TaskState.todo, blocked = false,
                createdAt = now(), updatedAt = now(), rev = ++revCounter,
            )
            entries[ref] = row
            ref.key.toIntOrNull()?.let { if (it > nextKey) nextKey = it }
            row
        }
        updates.emit(TaskUpdate(ref, created, created.rev))
        return created
    }

    /**
     * The collapsed-gap branch on demand: the project's whole column rewritten to `1.0, 2.0, 3.0, …`,
     * every row stamping its OWN rev and emitting its own update.
     */
    suspend fun renormalize(project: ProjectId) {
        val rewritten = mutex.withLock { renormalizeLocked(project) }
        rewritten.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
    }

    // --- tracker ------------------------------------------------------------------------------------

    override suspend fun list(project: ProjectId): List<Task> = mutex.withLock {
        entries.values.filter { it.project == project }.mapNotNull { tasks[it.ref] }
    }

    override suspend fun get(ref: TaskRef): Task? = mutex.withLock { tasks[ref] }

    override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task {
        val (task, row) = mutex.withLock {
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
            created to entry
        }
        updates.emit(TaskUpdate(row.ref, row, row.rev))
        return task
    }

    override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? {
        val result = mutex.withLock {
            // A null argument means "leave unchanged", never "clear": both columns are NOT NULL and a
            // PATCH omitting one must not blank it.
            val existing = tasks[ref] ?: return@withLock null
            val updated = existing.copy(
                title = title ?: existing.title, body = body ?: existing.body, updatedAt = now(),
            )
            tasks[ref] = updated
            val row = entries[ref]?.copy(updatedAt = now(), rev = ++revCounter)?.also { entries[ref] = it }
            updated to row
        } ?: return null
        result.second?.let { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
        return result.first
    }

    override suspend fun delete(ref: TaskRef): Boolean {
        val unblocked = mutableListOf<BacklogEntry>()
        val removedRev = mutex.withLock {
            if (tasks.remove(ref) == null) return@withLock null
            // Read the reverse edges BEFORE the cascade drops them, then re-derive: a task deleted out
            // from under its dependents unblocks them exactly as closing it would have.
            val dependents = dependentsLocked(ref)
            entries.remove(ref)
            deps.remove(ref)
            deps.values.forEach { it.remove(ref) }
            activityRows.removeAll { it.ref == ref }
            unblocked += refreshBlockedLocked(dependents)
            ++revCounter
        } ?: return false
        unblocked.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
        updates.emit(TaskUpdate(ref, null, removedRev))
        return true
    }

    // --- backlog reads ------------------------------------------------------------------------------

    override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { entries[ref] }

    override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = mutex.withLock {
        entries.values.filter { it.project == project }.sortedBy { it.position }
    }

    override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = mutex.withLock {
        // `blocked` already means "todo with an unfinished dependency", so the candidate query is the
        // first non-blocked todo in rank order — and `null` is the only "nothing eligible" answer.
        entries.values
            .filter { it.project == project && it.state == TaskState.todo && !it.blocked }
            .minByOrNull { it.position }
    }

    // --- backlog writes -----------------------------------------------------------------------------

    override suspend fun startIfTodo(ref: TaskRef): Boolean {
        val emitted = mutex.withLock {
            val existing = entries[ref] ?: return@withLock emptyList<BacklogEntry>()
            if (existing.state != TaskState.todo) return@withLock emptyList<BacklogEntry>()
            writeStateLocked(existing, TaskState.in_progress)
        }
        if (emitted.isEmpty()) return false
        emitted.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
        return true
    }

    override suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? {
        val emitted = mutex.withLock {
            val existing = entries[ref] ?: return@withLock emptyList<BacklogEntry>()
            // The state change and its activity row are one step, so a `review -m "…"` cannot leave a
            // review with no explanation or a comment on an unreviewed task.
            activityRows += TaskActivityEntry(
                ++activityId, ref, now(), ActivityKind.transition, author, message, existing.state, to,
            )
            writeStateLocked(existing, to)
        }
        if (emitted.isEmpty()) return null
        emitted.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
        return emitted.first()
    }

    override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? {
        val emitted = mutableListOf<BacklogEntry>()
        val moved = mutex.withLock {
            val existing = entries[ref] ?: return@withLock null
            val neighbour = when (target) {
                is MoveTarget.Before -> target.ref
                is MoveTarget.After -> target.ref
                else -> null
            }
            // A named neighbour the store does not hold — or one in another project — is an unknown ref,
            // not a position: there is nothing to move relative to.
            if (neighbour != null && entries[neighbour]?.project != existing.project) return@withLock null

            // A collapsed gap rewrites the column and the move is retried ONCE; after a renormalization
            // every gap is 1.0, so a second refusal would mean the arithmetic itself is wrong.
            val rank = rankForLocked(existing, target) ?: run {
                emitted += renormalizeLocked(existing.project)
                rankForLocked(entries.getValue(ref), target)
                    ?: error("a renormalized column must always subdivide")
            }
            val row = entries.getValue(ref).copy(position = rank, updatedAt = now(), rev = ++revCounter)
            entries[ref] = row
            emitted += row
            row
        }
        emitted.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
        return moved
    }

    // --- dependencies -------------------------------------------------------------------------------

    override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { deps[ref]?.toList().orEmpty() }

    override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = mutex.withLock { dependentsLocked(ref) }

    override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> = mutex.withLock {
        deps.filterKeys { entries[it]?.project == project }.mapValues { it.value.toList() }
    }

    override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) {
        val emitted = mutex.withLock {
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
            if (wouldCycle(edgeSnapshotLocked(), ref, dependsOn)) {
                refuse(DependencyRefusal.cycle, "it would close a ring")
            }
            val edges = deps.getOrPut(ref) { mutableListOf() }
            // Re-adding an existing edge is a no-op, not an error — an idempotent retry must not fail.
            if (dependsOn !in edges) edges += dependsOn
            restampLocked(ref)
        }
        emitted.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
    }

    override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) {
        val emitted = mutex.withLock {
            deps[ref]?.remove(dependsOn)
            restampLocked(ref)
        }
        emitted.forEach { updates.emit(TaskUpdate(it.ref, it, it.rev)) }
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
     * Write [to] onto [existing], re-derive its own `blocked`, and re-derive every reverse dependent's.
     * Returns the moved row first, then the dependents that actually changed — the emission order the
     * real store's one transaction produces.
     */
    private fun writeStateLocked(existing: BacklogEntry, to: TaskState): List<BacklogEntry> {
        val stamped = existing.copy(state = to, updatedAt = now(), rev = ++revCounter)
        val row = stamped.copy(blocked = derivedBlocked(stamped))
        entries[existing.ref] = row
        return listOf(row) + refreshBlockedLocked(dependentsLocked(existing.ref))
    }

    /** Re-stamp [ref] and re-derive its `blocked` — what a dependency edit owes its own row. */
    private fun restampLocked(ref: TaskRef): List<BacklogEntry> {
        val existing = entries[ref] ?: return emptyList()
        val stamped = existing.copy(updatedAt = now(), rev = ++revCounter)
        val row = stamped.copy(blocked = derivedBlocked(stamped))
        entries[ref] = row
        return listOf(row)
    }

    /**
     * Re-derive `blocked` for [refs], re-stamping only the rows whose value actually MOVED. Stamping the
     * unmoved ones too would put a rev bump on the wire for a row nothing changed about, and the client's
     * newest-rev-wins rule would then hide a genuinely newer observation that arrived first.
     */
    private fun refreshBlockedLocked(refs: Collection<TaskRef>): List<BacklogEntry> = refs.mapNotNull { ref ->
        val existing = entries[ref] ?: return@mapNotNull null
        val blocked = derivedBlocked(existing)
        if (blocked == existing.blocked) return@mapNotNull null
        existing.copy(blocked = blocked, rev = ++revCounter).also { entries[ref] = it }
    }

    /**
     * Re-derive `blocked` across the whole tree, with no rev bump and no emission — the seeding-time
     * counterpart of [refreshBlockedLocked]. It is what makes seeds ORDER-INSENSITIVE: a scenario may
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

    private fun dependentsLocked(ref: TaskRef): List<TaskRef> =
        deps.filterValues { ref in it }.keys.toList()

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

    private fun renormalizeLocked(project: ProjectId): List<BacklogEntry> = entries.values
        .filter { it.project == project }
        .sortedBy { it.position }
        .mapIndexed { index, entry ->
            entry.copy(position = index + 1.0, rev = ++revCounter).also { entries[entry.ref] = it }
        }
}
