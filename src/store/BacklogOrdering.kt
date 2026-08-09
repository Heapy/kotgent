package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.TaskUpdate
import io.kotgent.task.needsRenormalization
import io.kotgent.task.positionBetween
import io.kotgent.task.positionForEnd
import io.kotgent.task.positionForTop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * ## The arithmetic is `Ordering.kt`'s, and the CHECK comes before the subdivision
 * Nothing here re-derives a rank: [positionForEnd], [positionForTop] and [positionBetween] answer where
 * an entry lands and [needsRenormalization] decides whether that answer is still trustworthy. The order
 * is load-bearing — `positionBetween` validates nothing, because a pair that cannot be halved has no
 * correct rank at all and the only cure is to rewrite the column, which a pure function cannot do. So
 * every placement below is computed *with* its collapse verdict ([Placement.collapsed]) and the verdict
 * is consulted first.
 *
 * ## Where a move deliberately spends gap
 * The neighbour queries do **not** exclude [ref] itself, so "move the top entry to the top" halves its
 * rank and "move X before its immediate successor" halves the gap they already share. Both are
 * order-preserving no-ops that consume a little room, which is exactly what the renormalization exists
 * to reclaim; excluding the moved row would need a second bracketing query per move to buy nothing but
 * the fiction that a no-op writes nothing.
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
    suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = mutex.withLock {
        val row = queries.selectEntry(ref.value).executeAsOneOrNull() ?: return@withLock null
        // `backlog_entries.project` is only ever written from a ProjectId, so a value that fails here is
        // a corrupted database and says so loudly — the rule `BacklogDependencies.entryLocked` follows.
        val project = ProjectId.of(row.project)

        val placement = placementLocked(row.project, target) ?: return@withLock null
        val position = if (!placement.collapsed) {
            placement.position
        } else {
            renormalizeLocked(project)
            // Retried exactly once, and the retry takes its rank unconditionally: a renormalized column
            // is `1.0, 2.0, 3.0, …`, so every gap it can offer is a whole unit and the top's floor gap
            // is `(0.0, 1.0)`. A second collapse would mean the project holds more entries than there
            // are doubles between two integers — unreachable, and looping on it would be worse than
            // taking a rank that is still strictly ordered.
            (placementLocked(row.project, target) ?: return@withLock null).position
        }

        val rev = nextRev()
        queries.setPosition(position, now(), rev, ref.value)
        // Read the row back rather than patching the pre-move copy: this is the one place the derived
        // `blocked` and the freshly written position have to agree, and `entryLocked` is where that rule
        // lives. A null here would mean the row vanished under the store's own mutex.
        val moved = dependencies.entryLocked(ref) ?: return@withLock null
        emit(TaskUpdate(ref, moved, rev))
        moved
    }

    // --- placement ------------------------------------------------------------------------------------

    /**
     * Where [target] would put an entry in [project], together with the verdict on whether that rank is
     * still trustworthy. `null` means a [MoveTarget.Before] / [MoveTarget.After] named a ref with no
     * backlog row, or one in a different project — a cross-project move would silently drop the entry
     * out of the board it was dragged on.
     */
    private fun placementLocked(project: String, target: MoveTarget): Placement? = when (target) {
        // Appending consumes no gap: `max + 1.0` is the one rule that can never force a renormalization.
        MoveTarget.Bottom -> Placement(positionForEnd(queries.maxPosition(project).executeAsOne().MAX))

        MoveTarget.Top -> {
            val min = queries.minPosition(project).executeAsOne().MIN
            // The top is the collapsing direction — it halves toward the zero floor — so the pair to ask
            // about is `(0.0, min)`, the floor standing in for the neighbour a top insert does not have.
            if (min == null) Placement(positionForTop(null))
            else Placement(positionForTop(min), needsRenormalization(0.0, min))
        }

        is MoveTarget.Before -> {
            val anchor = anchorLocked(project, target.ref) ?: return null
            val below = queries.neighboursAround(anchor, project).executeAsOne().below
            if (below == null) Placement(positionForTop(anchor), needsRenormalization(0.0, anchor))
            else Placement(positionBetween(below, anchor), needsRenormalization(below, anchor))
        }

        is MoveTarget.After -> {
            val anchor = anchorLocked(project, target.ref) ?: return null
            val above = queries.neighboursAround(anchor, project).executeAsOne().above
            if (above == null) Placement(positionForEnd(anchor))
            else Placement(positionBetween(anchor, above), needsRenormalization(anchor, above))
        }
    }

    /** The named neighbour's rank, or `null` when it has no backlog row or sits in another project. */
    private fun anchorLocked(project: String, ref: TaskRef): Double? =
        queries.selectEntry(ref.value).executeAsOneOrNull()?.takeIf { it.project == project }?.position

    /**
     * Rewrite [project]'s whole column to `1.0, 2.0, 3.0, …` in rank order, in ONE transaction, stamping
     * every row a fresh revision.
     *
     * The input is [BacklogDependencies.listBacklogLocked] rather than `selectPositionsOrdered`, and the
     * two are the same rows in the same order (`ORDER BY position, task_ref`): every rewritten row has to
     * be EMITTED, an emission carries a whole [BacklogEntry], and the derived `blocked` on it has exactly
     * one implementation, which is the collaborator's. Re-reading each row instead would be a query per
     * card to rebuild what one read already answered.
     *
     * The updates are emitted **after** the transaction commits. `tryEmit` is non-suspending so
     * publishing inside the block would be legal, but a subscriber must never see a position a rollback
     * would take back.
     */
    private fun renormalizeLocked(project: ProjectId) {
        val entries = dependencies.listBacklogLocked(project)
        if (entries.isEmpty()) return
        val stamped = now()
        val rewritten = ArrayList<TaskUpdate>(entries.size)
        queries.transaction {
            entries.forEachIndexed { index, entry ->
                val position = (index + 1).toDouble()
                val rev = nextRev()
                queries.setPosition(position, stamped, rev, entry.ref.value)
                val renumbered = entry.copy(position = position, updatedAt = stamped, rev = rev)
                rewritten += TaskUpdate(entry.ref, renumbered, rev)
            }
        }
        for (update in rewritten) emit(update)
    }

    /**
     * A computed rank plus whether the gap it came out of was too small to subdivide. Carrying both is
     * what lets the retry after a renormalization take the recomputed rank without asking again.
     */
    private data class Placement(val position: Double, val collapsed: Boolean = false)
}
