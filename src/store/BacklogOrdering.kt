package io.kotgent.store

import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.sync.Mutex

/**
 * The ordering half of [SqliteTaskStore]: `move`, and the renormalization that keeps gap-based ranking
 * from running out of room.
 *
 * Split out of the store **for parallel execution** — see [SqliteTaskStore]'s KDoc for the honest reason.
 *
 * ## What `move` owes
 *  - Neighbour resolution and the single `UPDATE` both run inside [mutex]; reading the neighbours and
 *    then writing under a different lock would let two concurrent moves compute the same midpoint.
 *  - When the gap the move would land in falls below [io.kotgent.task.POSITION_EPSILON], the project's
 *    whole column is renormalized to `1.0, 2.0, 3.0, …` in ONE transaction and the move is retried
 *    **once**. Renormalizing is a LOOP over `selectPositionsOrdered` + `setPosition`, deliberately not a
 *    bulk `UPDATE`: **every** rewritten row must stamp its own `rev` and emit its own [TaskUpdate], or a
 *    connected board silently holds stale positions.
 *  - Every emitted entry carries the derived `blocked`, so this class reads through [dependencies]
 *    rather than growing a second implementation of that rule.
 *
 * Bodies are [TODO] on purpose: Task 8 implements this file.
 *
 * @param queries the generated `Backlog.sq` accessor — nothing here may touch `sessions` (see [TaskStore]).
 * @param nextRev the store's revision allocator; callers hold [mutex].
 * @param emit the store's non-suspending publisher onto `taskUpdates`.
 */
class BacklogOrdering(
    private val queries: BacklogQueries,
    private val mutex: Mutex,
    private val dependencies: BacklogDependencies,
    private val nextRev: () -> Long,
    private val emit: (TaskUpdate) -> Unit,
    private val now: () -> Long,
) {

    /**
     * Re-rank [ref] to [target] and emit it. Returns the moved entry, or `null` when [ref] — or a
     * neighbour named by [MoveTarget.Before] / [MoveTarget.After] — is unknown, or when the neighbour
     * belongs to a different project.
     */
    suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = TODO("Task 8: move + renormalize")
}
