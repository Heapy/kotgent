package io.kotgent.task

// Positions are gap-based ranks. The store renormalizes before a midpoint ceases to be strictly interior.
const val POSITION_EPSILON: Double = 1e-9

fun positionForEnd(maxPosition: Double?): Double = (maxPosition ?: 0.0) + 1.0

fun positionBetween(lower: Double, upper: Double): Double = (lower + upper) / 2.0

fun positionForTop(minPosition: Double?): Double =
    if (minPosition == null) 1.0 else positionBetween(0.0, minPosition)

fun needsRenormalization(lower: Double, upper: Double): Boolean {
    // Negated comparisons put NaN, infinities, equal ranks, and inverted ranks on the repair path.
    if (!(upper - lower >= POSITION_EPSILON)) return true
    val mid = positionBetween(lower, upper)
    return !(mid > lower && mid < upper)
}
