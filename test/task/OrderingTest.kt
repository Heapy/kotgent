package io.kotgent.task

import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the gap-based ordering rules — [positionForEnd], [positionBetween], [positionForTop] and
 * [needsRenormalization].
 *
 * Four pure functions with no storage behind them, so the tests are the specification. Three properties
 * are what the store (`BacklogOrdering`) is entitled to assume, and each has its own test rather than
 * being implied by an example:
 *
 *  1. **An empty backlog answers `1.0` at either end** — the first entry of a project gets the same rank
 *     whichever end it was inserted at.
 *  2. **A cleared [needsRenormalization] is a promise about [positionBetween]** — whenever it answers
 *     `false`, the midpoint lands strictly inside the pair. This is checked as an invariant across
 *     magnitudes, not only at the one gap the threshold names, because the threshold is a *fixed*
 *     distance and an ulp is not.
 *  3. **Both collapsing directions trip the threshold, and at a counted number of steps** — thirty
 *     midpoints between one pair, and thirty top inserts against the zero floor. The count is pinned
 *     because it is the whole reason renormalization exists: gaps halve, so the budget is `log2(1/1e-9)`
 *     and not something that grows with the size of the backlog.
 */
class OrderingTest {

    @Test
    fun theThresholdIsOneNanoRank() {
        assertEquals(1e-9, POSITION_EPSILON, 0.0, "the plan and the store both name 1e-9 literally")
    }

    // --- the ends -------------------------------------------------------------------------------

    @Test
    fun anEmptyBacklogTakesRankOneAtEitherEnd() {
        assertEquals(1.0, positionForEnd(null), 0.0, "first entry, appended")
        assertEquals(1.0, positionForTop(null), 0.0, "first entry, inserted at the top")
    }

    @Test
    fun appendingTakesTheRankOneAboveTheHighest() {
        assertEquals(2.0, positionForEnd(1.0), 0.0)
        assertEquals(43.5, positionForEnd(42.5), 0.0)
        // An append never has to subdivide anything, so it is the one rule that cannot collapse: the
        // answer stays a whole rank above the column's top however many times it is applied.
        var max = 1.0
        repeat(1000) {
            val next = positionForEnd(max)
            assertTrue(next > max, "append must land strictly above the previous maximum")
            max = next
        }
        assertEquals(1001.0, max, 0.0)
    }

    @Test
    fun aTopInsertHalvesTowardZeroWithoutEverReachingIt() {
        assertEquals(0.5, positionForTop(1.0), 0.0)
        assertEquals(0.25, positionForTop(0.5), 0.0)

        var min = 1.0
        repeat(30) {
            val next = positionForTop(min)
            assertTrue(next > 0.0, "zero is the floor, never an assigned rank")
            assertTrue(next < min, "a top insert must land strictly below the column's smallest rank")
            min = next
        }
    }

    @Test
    fun theTopFloorIsSpelledAsAMidpointAgainstZero() {
        // Not a tautology worth skipping: the store checks `needsRenormalization(0.0, min)` before a top
        // insert, and that check is only the right one because the insert itself is that same midpoint.
        for (min in listOf(1.0, 0.5, 7.25, 1e-6)) {
            assertEquals(positionBetween(0.0, min), positionForTop(min), 0.0, "top insert of $min")
        }
    }

    // --- midpoints ------------------------------------------------------------------------------

    @Test
    fun aMidpointLandsStrictlyBetweenItsNeighbours() {
        assertEquals(1.5, positionBetween(1.0, 2.0), 0.0)
        assertEquals(3.0, positionBetween(2.0, 4.0), 0.0)
        for ((lower, upper) in listOf(1.0 to 2.0, 41.0 to 42.0, 1e-6 to 2e-6, 1.0 to 1.0000001)) {
            val mid = positionBetween(lower, upper)
            assertTrue(mid > lower && mid < upper, "midpoint of $lower..$upper was $mid")
        }
    }

    @Test
    fun midpointOrderingHoldsThroughAnInterleavedSequenceOfInserts() {
        // A board session: appends, top inserts and drops between two cards, in the order a human would
        // produce them. The column must stay strictly increasing after every single one — that is what
        // makes `ORDER BY position` the board's order.
        val ranks = mutableListOf<Double>()
        fun appendEnd() {
            ranks.add(positionForEnd(ranks.lastOrNull()))
            assertStrictlyIncreasing(ranks)
        }

        fun insertTop() {
            ranks.add(0, positionForTop(ranks.firstOrNull()))
            assertStrictlyIncreasing(ranks)
        }

        fun insertAfter(index: Int) {
            val lower = ranks[index]
            val upper = ranks[index + 1]
            assertFalse(needsRenormalization(lower, upper), "gap $lower..$upper should still subdivide")
            ranks.add(index + 1, positionBetween(lower, upper))
            assertStrictlyIncreasing(ranks)
        }

        appendEnd()
        appendEnd()
        appendEnd()
        insertTop()
        insertAfter(0)
        insertAfter(3)
        appendEnd()
        insertTop()
        insertAfter(1)
        insertAfter(6)
        insertTop()
        appendEnd()

        assertEquals(12, ranks.size)
        assertEquals(ranks.size, ranks.toSet().size, "every rank is distinct")
    }

    // --- the collapse, from both directions -----------------------------------------------------

    @Test
    fun thirtyRepeatedMidpointsBetweenOnePairTripTheThreshold() {
        // Gaps halve, so from 1.0 the budget is exactly ceil(log2(1 / 1e-9)) = 30 inserts; the 30th
        // leaves 2^-30 = 9.31e-10, the first value under the threshold.
        var lower = 1.0
        var upper = 2.0
        var inserts = 0
        while (!needsRenormalization(lower, upper)) {
            val mid = positionBetween(lower, upper)
            assertTrue(mid > lower && mid < upper, "insert $inserts landed on a neighbour")
            upper = mid
            inserts++
            assertTrue(inserts <= 64, "the threshold never tripped — renormalization would never run")
        }
        assertEquals(30, inserts, "the gap budget from 1.0 down to the threshold")
        assertTrue(upper - lower < POSITION_EPSILON)
    }

    @Test
    fun thirtyRepeatedTopInsertsTripTheThresholdAgainstTheZeroFloor() {
        // The same budget from the other end, and the reason the store's top-insert check spells the
        // missing lower neighbour as 0.0: without a floor there is nothing for the gap to be measured
        // against and a top insert could halve forever.
        var min = 1.0
        var inserts = 0
        while (!needsRenormalization(0.0, min)) {
            min = positionForTop(min)
            inserts++
            assertTrue(inserts <= 64, "the threshold never tripped — renormalization would never run")
        }
        assertEquals(30, inserts, "the gap budget from 1.0 down to the threshold")
        assertTrue(min < POSITION_EPSILON)
    }

    // --- where the threshold sits ---------------------------------------------------------------

    @Test
    fun aGapOfExactlyTheThresholdStillSubdivides() {
        assertFalse(needsRenormalization(0.0, POSITION_EPSILON), "'falls below' is strict")
        assertTrue(needsRenormalization(0.0, POSITION_EPSILON.nextDown()), "one ulp under is under")
        assertFalse(needsRenormalization(1.0, 2.0))
        assertTrue(needsRenormalization(1.0, 1.0 + 1e-12))
    }

    @Test
    fun aDegeneratePairAlwaysRenormalizes() {
        assertTrue(needsRenormalization(1.5, 1.5), "equal ranks — a renormalization is the only fix")
        assertTrue(needsRenormalization(2.0, 1.0), "inverted pair")
        assertTrue(needsRenormalization(Double.NaN, 1.0), "NaN answers false to every comparison")
        assertTrue(needsRenormalization(1.0, Double.NaN))
        assertTrue(needsRenormalization(Double.NaN, Double.NaN))
        assertTrue(needsRenormalization(0.0, Double.POSITIVE_INFINITY))
        assertTrue(needsRenormalization(Double.NEGATIVE_INFINITY, 0.0))
        // Large enough that the midpoint itself overflows to infinity, which is not inside the pair.
        assertTrue(needsRenormalization(1e308, 1.5e308), "a midpoint that overflows is not a midpoint")
    }

    @Test
    fun adjacentDoublesRenormalizeEvenThoughTheirGapClearsTheThreshold() {
        // The reason the threshold is backstopped by the midpoint. An ulp is relative and 1e-9 is not:
        // near 1e7 two ADJACENT doubles are 2^-29 = 1.86e-9 apart, so the gap passes a fixed 1e-9 test
        // while holding no value at all. Without the backstop this pair would be subdivided into a rank
        // equal to one of its neighbours — a silently wrong board order, tie-broken by task_ref.
        val lower = 1e7
        val upper = lower.nextUp()
        assertTrue(upper - lower > POSITION_EPSILON, "the threshold alone would clear this pair")
        val mid = positionBetween(lower, upper)
        assertTrue(mid == lower || mid == upper, "adjacent doubles have no value between them")
        assertTrue(needsRenormalization(lower, upper), "the backstop must catch it")
    }

    @Test
    fun clearingRenormalizationIsThePromiseThatAMidpointFits() {
        // The invariant BacklogOrdering relies on: ask first, subdivide only on a `false`. Swept across
        // magnitudes so neither side of the answer is vacuous.
        val magnitudes = listOf(1e-9, 1e-6, 1e-3, 1.0, 42.0, 1e3, 1e6, 1e7, 1e9, 1e15)
        val gaps = listOf(1.0, 1e-3, 1e-8, POSITION_EPSILON, POSITION_EPSILON.nextDown(), 1e-12, 0.0)
        var subdivided = 0
        var renormalized = 0
        for (lower in magnitudes) {
            for (gap in gaps) {
                val upper = lower + gap
                if (needsRenormalization(lower, upper)) {
                    renormalized++
                    continue
                }
                val mid = positionBetween(lower, upper)
                assertTrue(mid > lower, "midpoint $mid did not clear lower $lower (gap $gap)")
                assertTrue(mid < upper, "midpoint $mid did not stay under upper $upper (gap $gap)")
                subdivided++
            }
        }
        assertTrue(subdivided > 0, "every pair renormalized — the sweep proves nothing")
        assertTrue(renormalized > 0, "no pair renormalized — the sweep proves nothing")
    }

    private fun assertStrictlyIncreasing(ranks: List<Double>) {
        for (i in 1 until ranks.size) {
            assertTrue(ranks[i - 1] < ranks[i], "ranks out of order at $i: $ranks")
        }
    }
}
