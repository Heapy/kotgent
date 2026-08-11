package io.kotgent.task

import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderingTest {

    @Test
    fun theThresholdIsOneNanoRank() {
        assertEquals(1e-9, POSITION_EPSILON, 0.0, "the plan and the store both name 1e-9 literally")
    }


    @Test
    fun anEmptyBacklogTakesRankOneAtEitherEnd() {
        assertEquals(1.0, positionForEnd(null), 0.0, "first entry, appended")
        assertEquals(1.0, positionForTop(null), 0.0, "first entry, inserted at the top")
    }

    @Test
    fun appendingTakesTheRankOneAboveTheHighest() {
        assertEquals(2.0, positionForEnd(1.0), 0.0)
        assertEquals(43.5, positionForEnd(42.5), 0.0)
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
        for (min in listOf(1.0, 0.5, 7.25, 1e-6)) {
            assertEquals(positionBetween(0.0, min), positionForTop(min), 0.0, "top insert of $min")
        }
    }


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


    @Test
    fun thirtyRepeatedMidpointsBetweenOnePairTripTheThreshold() {
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
        assertTrue(needsRenormalization(1e308, 1.5e308), "a midpoint that overflows is not a midpoint")
    }

    @Test
    fun adjacentDoublesRenormalizeEvenThoughTheirGapClearsTheThreshold() {
        val lower = 1e7
        val upper = lower.nextUp()
        assertTrue(upper - lower > POSITION_EPSILON, "the threshold alone would clear this pair")
        val mid = positionBetween(lower, upper)
        assertTrue(mid == lower || mid == upper, "adjacent doubles have no value between them")
        assertTrue(needsRenormalization(lower, upper), "the backstop must catch it")
    }

    @Test
    fun clearingRenormalizationIsThePromiseThatAMidpointFits() {
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
