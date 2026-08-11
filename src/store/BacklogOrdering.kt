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

/** Moves and any required renormalization commit together; every rewritten rank is emitted after commit. */
class BacklogOrdering(
    private val queries: BacklogQueries,
    private val mutex: Mutex,
    private val dependencies: BacklogDependencies,
    private val nextRev: () -> Long,
    private val outbox: TaskUpdateOutbox,
    private val now: () -> Long,
) {

    suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = mutex.withLock {
        outbox.publishing { queries.transactionWithResult { moveLocked(ref, target) } }
    }

    private fun moveLocked(ref: TaskRef, target: MoveTarget): BacklogEntry? {
        val row = queries.selectEntry(ref.value).executeAsOneOrNull() ?: return null
        val project = ProjectId.of(row.project)

        val placement = placementLocked(row.project, target) ?: return null
        val position = if (!placement.collapsed) {
            placement.position
        } else {
            renormalizeLocked(project)
            (placementLocked(row.project, target) ?: return null).position
        }

        val rev = nextRev()
        queries.setPosition(position, now(), rev, ref.value)
        val moved = dependencies.entryLocked(ref) ?: return null
        outbox.stage(TaskUpdate(ref, moved, rev))
        return moved
    }


    private fun placementLocked(project: String, target: MoveTarget): Placement? = when (target) {
        MoveTarget.Bottom -> Placement(positionForEnd(queries.maxPosition(project).executeAsOne().MAX))

        MoveTarget.Top -> {
            val min = queries.minPosition(project).executeAsOne().MIN
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

    private fun anchorLocked(project: String, ref: TaskRef): Double? =
        queries.selectEntry(ref.value).executeAsOneOrNull()?.takeIf { it.project == project }?.position

    private fun renormalizeLocked(project: ProjectId) {
        val entries = dependencies.listBacklogLocked(project)
        if (entries.isEmpty()) return
        val stamped = now()
        entries.forEachIndexed { index, entry ->
            val position = (index + 1).toDouble()
            val rev = nextRev()
            queries.setPosition(position, stamped, rev, entry.ref.value)
            val renumbered = entry.copy(position = position, updatedAt = stamped, rev = rev)
            outbox.stage(TaskUpdate(entry.ref, renumbered, rev))
        }
    }

    private data class Placement(val position: Double, val collapsed: Boolean = false)
}
