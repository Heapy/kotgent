package io.kotgent.task

/*
 * Gap-based ordering for a project's backlog — pure arithmetic, no storage.
 *
 * `backlog_entries.position` is a `REAL` rank, not an index: a move rewrites ONE row instead of
 * renumbering the column, which is what makes a drag on the board a single `UPDATE` and a single
 * `TaskUpdate` on the socket.
 *
 * Floating-point ranks run out of room, so the store pairs these with a renormalization: when the gap a
 * move would land in falls below [POSITION_EPSILON], the project's whole column is rewritten to
 * `1.0, 2.0, 3.0, …` in one transaction — every row stamping a fresh `rev` and emitting, or a connected
 * board silently holds stale positions — and the move is retried once.
 *
 * Bodies are [TODO] here on purpose: Task 5 implements this file.
 */

/**
 * The gap below which a midpoint is no longer worth taking. `1e-9` is far above the point where two
 * doubles stop having a value between them, so renormalization happens while the ordering is still
 * strictly correct rather than after it has silently collapsed.
 */
const val POSITION_EPSILON: Double = 1e-9

/** The rank for an entry appended at the END of a project: `max + 1.0`, or `1.0` for an empty backlog. */
fun positionForEnd(maxPosition: Double?): Double = TODO("Task 5: position at the end")

/** The rank strictly between two neighbours: their midpoint. */
fun positionBetween(lower: Double, upper: Double): Double = TODO("Task 5: midpoint")

/**
 * The rank for an entry moved to the TOP: `positionBetween(0.0, min)`, i.e. halving toward zero, or
 * `1.0` for an empty backlog. Zero is the floor and is never itself assigned.
 */
fun positionForTop(minPosition: Double?): Double = TODO("Task 5: position at the top")

/**
 * Whether the gap between [lower] and [upper] is too small to subdivide — the trigger for renormalizing
 * the project's column and retrying the move once.
 */
fun needsRenormalization(lower: Double, upper: Double): Boolean =
    TODO("Task 5: collapsed-gap detection")
