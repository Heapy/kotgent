package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.task.BacklogEntry
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.sync.Mutex

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
 * ## Locking
 * The `…Locked` members are **non-suspending and assume the caller already holds [mutex]** (a Kotlin
 * `Mutex` is not reentrant, and the store core calls them from inside its own transactions). The
 * suspending entry points take [mutex] themselves. Never suspend inside a `db.transaction { }`.
 *
 * @param queries the generated `Backlog.sq` accessor. Deliberately NOT the whole database: nothing here
 *   may touch `sessions` (see [TaskStore]).
 * @param nextRev the store's revision allocator — one owner for the counter, callers hold [mutex].
 * @param emit the store's non-suspending publisher onto `taskUpdates`.
 *
 * Bodies are [TODO] on purpose: Task 9 implements this file.
 */
class BacklogDependencies(
    private val queries: BacklogQueries,
    private val mutex: Mutex,
    private val nextRev: () -> Long,
    private val emit: (TaskUpdate) -> Unit,
    private val now: () -> Long,
) {

    // --- read path (caller holds the store mutex) --------------------------------------------------

    /** One entry with its derived `blocked`, or `null` for an unknown ref. */
    fun entryLocked(ref: TaskRef): BacklogEntry? = TODO("Task 9: entry + derived blocked")

    /**
     * A project's entries in rank order with their derived `blocked`, computed from ONE edge read and
     * ONE entry read rather than a query per card.
     */
    fun listBacklogLocked(project: ProjectId): List<BacklogEntry> =
        TODO("Task 9: project backlog + derived blocked")

    /** The first eligible `todo` entry, or `null` — the only "nothing eligible" signal. */
    fun nextCandidateLocked(project: ProjectId): BacklogEntry? = TODO("Task 9: next candidate")

    /** What [ref] depends on. */
    fun dependenciesOfLocked(ref: TaskRef): List<TaskRef> = TODO("Task 9: dependencies of")

    /** What depends on [ref]. */
    fun dependentsOfLocked(ref: TaskRef): List<TaskRef> = TODO("Task 9: dependents of")

    /** The project's whole edge set, `ref → what it depends on`. */
    fun edgesLocked(project: ProjectId): Map<TaskRef, List<TaskRef>> = TODO("Task 9: project edges")

    /**
     * Re-stamp every reverse dependent of [ref] with a fresh revision and emit each on `taskUpdates`.
     * Called after every dependency edit and every state transition — including a DELETE, where the
     * dependents must be read BEFORE the `backlog_deps` rows go away.
     *
     * Deliberately does not touch `updated_at`: the dependents' derived `blocked` moved, but nothing
     * about them was edited, and `updated_at` is activity (the reason `setReadCursor` leaves it alone).
     */
    fun restampDependentsLocked(ref: TaskRef) {
        TODO("Task 9: re-stamp and re-emit the reverse dependents")
    }

    // --- suspending entry points (take the store mutex) --------------------------------------------

    /**
     * Add "[ref] depends on [dependsOn]" after validating all four refusals, then re-stamp [ref] and its
     * reverse dependents. Re-adding an existing edge is a no-op.
     *
     * @throws io.kotgent.task.DependencyRefusedException self / unknown ref / cross-project / cycle.
     */
    suspend fun add(ref: TaskRef, dependsOn: TaskRef) {
        TODO("Task 9: validated dependency insert")
    }

    /** Remove the edge and re-stamp as [add] does. Removing a missing edge is a no-op. */
    suspend fun remove(ref: TaskRef, dependsOn: TaskRef) {
        TODO("Task 9: dependency removal")
    }
}
