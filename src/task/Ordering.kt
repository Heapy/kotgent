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
 * ## The invariant that ties the four functions together
 *
 * **[needsRenormalization] answering `false` is the promise that [positionBetween] still lands STRICTLY
 * between its arguments.** The caller asks first and subdivides only then, so every degenerate pair —
 * equal ranks, an inverted pair, a non-finite one — belongs on the renormalizing side and none of them
 * ever reaches the arithmetic. That is also why [positionBetween] validates nothing itself: a rank that
 * ties or inverts is a silently wrong board order, and the only correct answer to a pair that cannot be
 * subdivided is to rewrite the column, which a pure function cannot do.
 *
 * The `1e-9` threshold alone does not carry that promise at every magnitude, which is why
 * [needsRenormalization] backstops it with the midpoint itself: two ADJACENT doubles near `1e7` are
 * already `1.86e-9` apart, so their gap clears the threshold while having no value inside it at all.
 * Nothing a real backlog produces reaches that magnitude — ranks grow by `1.0` per append, and the
 * collapsing direction ([positionForTop]) runs toward zero, not away from it — but the backstop is what
 * makes "too small to subdivide" literally true instead of true-for-the-expected-range.
 *
 * ## An empty backlog answers `1.0` at either end
 *
 * [positionForEnd] and [positionForTop] agree there on purpose: the FIRST entry of a project takes rank
 * `1.0` whichever end it was inserted at — the same rank a renormalization starts the column at. Zero is
 * the floor a top insert halves toward and is never itself assigned.
 */

/**
 * The gap below which a midpoint is no longer worth taking. `1e-9` is far above the point where two
 * doubles stop having a value between them, so renormalization happens while the ordering is still
 * strictly correct rather than after it has silently collapsed.
 */
const val POSITION_EPSILON: Double = 1e-9

/**
 * The rank for an entry appended at the END of a project: `max + 1.0`, or `1.0` for an empty backlog
 * ([maxPosition] `null`).
 *
 * Appending consumes no gap, so this is the one rule that can never force a renormalization: for any
 * rank a backlog can actually hold, the answer is strictly above [maxPosition].
 */
fun positionForEnd(maxPosition: Double?): Double = (maxPosition ?: 0.0) + 1.0

/**
 * The rank strictly between two neighbours: their midpoint.
 *
 * Defined only for a pair [needsRenormalization] has already cleared — it checks nothing itself, because
 * the caller owes a renormalization rather than a degraded rank.
 */
fun positionBetween(lower: Double, upper: Double): Double = (lower + upper) / 2.0

/**
 * The rank for an entry moved to the TOP: `positionBetween(0.0, min)`, i.e. halving toward zero, or
 * `1.0` for an empty backlog. Zero is the floor and is never itself assigned.
 *
 * Halving makes the top the collapsing direction: thirty consecutive top inserts take the smallest rank
 * from `1.0` to below [POSITION_EPSILON]. The caller's check is therefore `needsRenormalization(0.0,
 * min)` — the floor standing in for the lower neighbour a top insert does not have.
 */
fun positionForTop(minPosition: Double?): Double =
    if (minPosition == null) 1.0 else positionBetween(0.0, minPosition)

/**
 * Whether the gap between [lower] and [upper] is too small to subdivide — the trigger for renormalizing
 * the project's column and retrying the move once.
 *
 * `true` for a gap under [POSITION_EPSILON], and also for any pair whose midpoint would not land
 * strictly inside it: equal ranks, an inverted pair, adjacent doubles at a magnitude where the threshold
 * has become finer than an ulp, and anything non-finite. Both comparisons are written NEGATED so a `NaN`
 * — which answers `false` to every ordering question — falls on the renormalizing side: rewriting a
 * column is recoverable, handing back a rank that ties or inverts is a silently wrong board.
 */
fun needsRenormalization(lower: Double, upper: Double): Boolean {
    if (!(upper - lower >= POSITION_EPSILON)) return true
    val mid = positionBetween(lower, upper)
    return !(mid > lower && mid < upper)
}
