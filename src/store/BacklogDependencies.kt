package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import io.kotgent.task.wouldCycle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The dependency half of [SqliteTaskStore]: the graph, the derived `blocked` read path, the candidate
 * query, and the reverse-dependent re-stamp every state change owes.
 *
 * Split out of the store **for parallel execution** — it lets a second agent implement this while a
 * third implements [BacklogOrdering] and a fourth the store core, without two of them touching one file.
 * See [SqliteTaskStore]'s KDoc.
 *
 * ## The rule that makes this class necessary
 * [BacklogEntry.blocked] is DERIVED (`state == todo` and some dependency is not `done`) and computed
 * here, in the read path, so the board does not recompute it per card. That makes it stale by
 * construction: closing or deleting task A changes the blocked-ness of everything depending on A without
 * touching those rows. So every dependency edit and every state transition calls
 * [restampDependentsLocked], which stamps each reverse dependent a fresh `rev` and emits it. Without
 * that the board shows a blocked marker on a ready task until a reload.
 *
 * ## One `blocked` rule, three statements — and a dangling edge reads as SATISFIED
 * [entryLocked] asks `unfinishedDependencyCount`, [listBacklogLocked] folds `selectDependencyEdges`
 * against the project's own rows, and [nextCandidateLocked] gets the answer as a `NOT EXISTS` filter
 * inside `nextCandidate`. All three must agree, and the two SQL forms JOIN `backlog_deps` to
 * `backlog_entries`, so an edge naming a ref with no row contributes NOTHING — it reads as already
 * satisfied. [listBacklogLocked] therefore ignores an unknown `depends_on` too, deliberately, rather
 * than failing safe in the other direction: a detail view and a board card disagreeing about one card is
 * worse than either answer, and the state is unreachable anyway (this class refuses an edge naming a
 * missing ref, and `delete` cascades both directions of `backlog_deps`).
 *
 * ## Locking
 * The `…Locked` members are **non-suspending and assume the caller already holds [mutex]** (a Kotlin
 * `Mutex` is not reentrant, and the store core calls them from inside its own transactions). The
 * suspending entry points take [mutex] themselves. Never suspend inside a `db.transaction { }`.
 *
 * @param queries the generated `Backlog.sq` accessor. Deliberately NOT the whole database: nothing here
 *   may touch `sessions` (see [TaskStore]).
 * @param nextRev the store's revision allocator — one owner for the counter, callers hold [mutex].
 * @param emit the store's non-suspending publisher onto `taskUpdates`.
 * @param now the store's injected clock. **Unused, and that is not an oversight**: the constructor shape
 *   is the contract [SqliteTaskStore] constructs against, and nothing this class writes is timestamped —
 *   `backlog_deps` has no timestamp column, and `restamp` deliberately leaves `updated_at` alone (a
 *   dependent's derived `blocked` moved, but nothing about it was edited). Dropping the parameter would
 *   change a declared signature to save a field.
 */
class BacklogDependencies(
    private val queries: BacklogQueries,
    private val mutex: Mutex,
    private val nextRev: () -> Long,
    private val emit: (TaskUpdate) -> Unit,
    @Suppress("unused") private val now: () -> Long,
) {

    // --- read path (caller holds the store mutex) --------------------------------------------------

    /** One entry with its derived `blocked`, or `null` for an unknown ref. */
    fun entryLocked(ref: TaskRef): BacklogEntry? {
        val row = queries.selectEntry(ref.value).executeAsOneOrNull() ?: return null
        val state = TaskState.valueOf(row.state)
        return BacklogEntry(
            ref = ref,
            // `backlog_entries.project` is only ever written from a ProjectId, so a value that fails
            // here is a corrupted database and says so loudly, rather than making one card vanish.
            project = ProjectId.of(row.project),
            position = row.position,
            state = state,
            // The `state == todo` half short-circuits the count query, and it is the definition rather
            // than an optimisation: an in_progress / review / done entry is never `blocked`.
            blocked = state == TaskState.todo &&
                queries.unfinishedDependencyCount(ref.value).executeAsOne() > 0L,
            createdAt = row.created_at,
            updatedAt = row.updated_at,
            rev = row.rev,
        )
    }

    /**
     * A project's entries in rank order with their derived `blocked`, computed from ONE edge read and
     * ONE entry read rather than a query per card.
     */
    fun listBacklogLocked(project: ProjectId): List<BacklogEntry> {
        val rows = queries.selectEntriesByProject(project.value).executeAsList()
        if (rows.isEmpty()) return emptyList()
        // The two sets are the in-memory form of `unfinishedDependencyCount`'s JOIN: a `depends_on`
        // outside `present` has no row to be un-`done`, exactly as the SQL sees it.
        val present = HashSet<String>(rows.size)
        val done = HashSet<String>()
        for (row in rows) {
            present += row.task_ref
            if (row.state == DONE_STATE) done += row.task_ref
        }
        val dependencies = HashMap<String, MutableList<String>>()
        for (edge in queries.selectDependencyEdges(project.value).executeAsList()) {
            dependencies.getOrPut(edge.task_ref) { mutableListOf() } += edge.depends_on
        }
        return rows.map { row ->
            val state = TaskState.valueOf(row.state)
            BacklogEntry(
                ref = TaskRef(row.task_ref),
                // Every row came back from a `WHERE project = ?`, so the caller's value IS the row's.
                project = project,
                position = row.position,
                state = state,
                blocked = state == TaskState.todo && dependencies[row.task_ref].orEmpty().any {
                    it in present && it !in done
                },
                createdAt = row.created_at,
                updatedAt = row.updated_at,
                rev = row.rev,
            )
        }
    }

    /** The first eligible `todo` entry, or `null` — the only "nothing eligible" signal. */
    fun nextCandidateLocked(project: ProjectId): BacklogEntry? {
        val row = queries.nextCandidate(project.value).executeAsOneOrNull() ?: return null
        return BacklogEntry(
            ref = TaskRef(row.task_ref),
            project = project,
            position = row.position,
            state = TaskState.valueOf(row.state),
            // `nextCandidate`'s NOT EXISTS is the `blocked` rule as a filter, so an answered row is by
            // construction unblocked. Re-asking it with a second query could only disagree.
            blocked = false,
            createdAt = row.created_at,
            updatedAt = row.updated_at,
            rev = row.rev,
        )
    }

    /** What [ref] depends on. */
    fun dependenciesOfLocked(ref: TaskRef): List<TaskRef> =
        queries.selectDependencies(ref.value).executeAsList().map(::TaskRef)

    /** What depends on [ref]. */
    fun dependentsOfLocked(ref: TaskRef): List<TaskRef> =
        queries.selectDependents(ref.value).executeAsList().map(::TaskRef)

    /** The project's whole edge set, `ref → what it depends on`. */
    fun edgesLocked(project: ProjectId): Map<TaskRef, List<TaskRef>> = edgesOfProjectLocked(project.value)

    /**
     * Re-stamp every reverse dependent of [ref] with a fresh revision and emit each on `taskUpdates`.
     * Called after every dependency edit and every state transition — including a DELETE, where the
     * dependents must be read BEFORE the `backlog_deps` rows go away.
     *
     * Exactly one level deep, and that is the whole rule rather than a shortcut: a dependent's `blocked`
     * asks about its dependencies' STATE, and re-stamping moves a rev, not a state. So the grand-parents
     * of a closed task see nothing change and are deliberately not walked.
     *
     * Deliberately does not touch `updated_at`: the dependents' derived `blocked` moved, but nothing
     * about them was edited, and `updated_at` is activity (the reason `setReadCursor` leaves it alone).
     */
    fun restampDependentsLocked(ref: TaskRef) {
        for (dependent in dependentsOfLocked(ref)) restampLocked(dependent)
    }

    // --- suspending entry points (take the store mutex) --------------------------------------------

    /**
     * Add "[ref] depends on [dependsOn]" after validating all four refusals, then re-stamp [ref] and its
     * reverse dependents. Re-adding an existing edge is a no-op.
     *
     * The duplicate check comes from the edge map the cycle walk needs anyway, and it returns BEFORE the
     * write: `INSERT OR IGNORE` would make the statement a no-op regardless, but the emissions after it
     * would not be, and "no-op" has to mean the board hears nothing.
     *
     * @throws io.kotgent.task.DependencyRefusedException self / unknown ref / cross-project / cycle.
     */
    suspend fun add(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        if (ref == dependsOn) {
            refuse(DependencyRefusal.self, ref, dependsOn, "a task cannot depend on itself: '${ref.value}'")
        }
        val source = queries.selectEntry(ref.value).executeAsOneOrNull()
            ?: refuse(DependencyRefusal.unknownRef, ref, dependsOn, unknownRefMessage(ref))
        val target = queries.selectEntry(dependsOn.value).executeAsOneOrNull()
            ?: refuse(DependencyRefusal.unknownRef, ref, dependsOn, unknownRefMessage(dependsOn))
        if (source.project != target.project) {
            refuse(
                DependencyRefusal.crossProject, ref, dependsOn,
                "'${ref.value}' and '${dependsOn.value}' are in different projects — a dependency " +
                    "edge is resolved within one project's backlog",
            )
        }
        val edges = edgesOfProjectLocked(source.project)
        if (dependsOn in edges[ref].orEmpty()) return@withLock
        if (wouldCycle(edges, ref, dependsOn)) {
            refuse(
                DependencyRefusal.cycle, ref, dependsOn,
                "'${ref.value}' cannot depend on '${dependsOn.value}': the edge would close a " +
                    "dependency cycle, and every task on a cycle blocks every other one forever",
            )
        }
        queries.transaction {
            queries.insertDep(ref.value, dependsOn.value)
            restampAfterEditLocked(ref)
        }
    }

    /** Remove the edge and re-stamp as [add] does. Removing a missing edge is a no-op. */
    suspend fun remove(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        if (dependsOn !in dependenciesOfLocked(ref)) return@withLock
        queries.transaction {
            queries.deleteDep(ref.value, dependsOn.value)
            restampAfterEditLocked(ref)
        }
    }

    // --- internals ---------------------------------------------------------------------------------

    /**
     * What an accepted edit to [ref]'s own edge set owes the board: [ref] itself, whose `blocked` and
     * whose `dependsOn` list both just moved, and then its reverse dependents.
     *
     * The second half is CONSERVATIVE and known to be so. A dependent's `blocked` asks whether [ref] is
     * `done`, and an edge edit changes no state, so nothing about those rows can actually have changed.
     * It is done because [TaskStore.addDependency] declares it ("re-stamp and re-emit [ref] itself and
     * every reverse dependent") and because a redundant emission is invisible under the client's
     * newest-rev-wins rule, whereas a missing one shows a stale blocked marker until a reload.
     */
    private fun restampAfterEditLocked(ref: TaskRef) {
        restampLocked(ref)
        restampDependentsLocked(ref)
    }

    /**
     * Stamp one row a fresh rev and emit it. The entry is read BEFORE the write and re-emitted with the
     * new rev rather than re-read after it: `restamp` touches no other column, so the copy is exactly
     * what a second `selectEntry` would answer, at one query instead of two. A ref with no row consumes
     * no revision and emits nothing — a null-entry [TaskUpdate] means DELETED, which this is not.
     */
    private fun restampLocked(ref: TaskRef) {
        val entry = entryLocked(ref) ?: return
        val rev = nextRev()
        queries.restamp(rev, ref.value)
        emit(TaskUpdate(ref, entry.copy(rev = rev), rev))
    }

    /** [edgesLocked] over the raw column value, so an edit path need not re-parse a [ProjectId]. */
    private fun edgesOfProjectLocked(project: String): Map<TaskRef, List<TaskRef>> {
        val edges = LinkedHashMap<TaskRef, MutableList<TaskRef>>()
        for (edge in queries.selectDependencyEdges(project).executeAsList()) {
            edges.getOrPut(TaskRef(edge.task_ref)) { mutableListOf() } += TaskRef(edge.depends_on)
        }
        return edges
    }

    private fun refuse(
        refusal: DependencyRefusal,
        ref: TaskRef,
        dependsOn: TaskRef,
        message: String,
    ): Nothing = throw DependencyRefusedException(refusal, ref, dependsOn, message)

    private fun unknownRefMessage(missing: TaskRef): String =
        "no such task '${missing.value}' — a dependency edge must name two entries that are already " +
            "in the backlog, or it would read as already satisfied and silently unblock a task"

    private companion object {
        /** The one storage spelling of [TaskState.done], for the string-level fold in [listBacklogLocked]. */
        val DONE_STATE: String = TaskState.done.name
    }
}
