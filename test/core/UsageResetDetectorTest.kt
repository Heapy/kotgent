package io.kotgent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageResetDetectorTest {
    private val end = 1_800_000_000_000L

    private fun observation(
        provider: String = "claude",
        percent: Double = 41.0,
        reset: Long? = end,
        seen: Long = end - 3_600_000,
        window: String = if (provider == "claude") "seven_day" else "primary",
        duration: Long? = null,
    ) = UsageObservation(provider, window, percent, reset, duration, seen)

    @Test
    fun aFirstObservationAndUnchangedOrRisingUsageDoNotReset() {
        val previous = observation()
        assertNull(detectReset(null, previous))
        assertNull(detectReset(previous, previous.copy(observedAt = previous.observedAt + 1)))
        assertNull(detectReset(previous, previous.copy(usedPercent = 42.0)))
    }

    @Test
    fun anyClaudeDecreaseAnHourBeforeThePromisedEndIsEarly() {
        val previous = observation(percent = 41.1, seen = end - 3_700_000)
        val reset = assertNotNull(detectReset(previous, observation(percent = 41.0)))
        assertTrue(reset.early)
        assertFalse(reset.resetsAtMoved)
        assertEquals(end, reset.expectedAt)
        assertEquals(41.1, reset.usedBefore)
        assertEquals(previous.observedAt, reset.usedBeforeSeenAt)
        assertEquals(end - 3_600_000, reset.observedAt)
        assertEquals("claude", reset.provider)
        assertEquals("seven_day", reset.windowKey)
    }

    @Test
    fun theToleranceBoundaryAndScheduledClaudeDropsAreNotEarly() {
        val previous = observation()
        for (seen in listOf(end - EARLY_TOLERANCE_MILLIS, end, end + 1)) {
            assertFalse(assertNotNull(detectReset(previous, observation(percent = 0.0, seen = seen))).early)
        }
        assertTrue(assertNotNull(detectReset(
            previous,
            observation(percent = 0.0, seen = end - EARLY_TOLERANCE_MILLIS - 1),
        )).early)
    }

    @Test
    fun aClaudeDropWithoutAKnownDeadlineIsJournalledWithoutAnEarlyClaim() {
        val reset = assertNotNull(detectReset(
            observation(reset = null),
            observation(percent = 1.0, reset = null),
        ))
        assertFalse(reset.early)
        assertNull(reset.expectedAt)
    }

    @Test
    fun codexRequiresBothAPercentDropAndAMovedKnownDeadline() {
        val previous = observation(provider = "codex")
        assertNull(detectReset(previous, previous.copy(usedPercent = 1.0)))
        assertNull(detectReset(previous, previous.copy(resetsAt = end + 604_800_000)))
        assertNull(detectReset(previous, previous.copy(usedPercent = 1.0, resetsAt = null)))
        assertNull(detectReset(previous.copy(resetsAt = null), previous.copy(usedPercent = 1.0)))
        val early = assertNotNull(detectReset(
            previous,
            previous.copy(usedPercent = 1.0, resetsAt = end + 604_800_000),
        ))
        assertTrue(early.early)
        assertTrue(early.resetsAtMoved)
        val onTime = assertNotNull(detectReset(
            previous,
            previous.copy(usedPercent = 1.0, resetsAt = end + 604_800_000, observedAt = end),
        ))
        assertFalse(onTime.early)
    }

    @Test
    fun anUnknownProviderUsesTheConservativeRule() {
        val previous = observation(provider = "future")
        assertNull(detectReset(previous, previous.copy(usedPercent = 0.0)))
        assertNotNull(detectReset(previous, previous.copy(usedPercent = 0.0, resetsAt = end + 1)))
    }

    @Test
    fun observationsFromDifferentMetersCannotEstablishAReset() {
        val previous = observation()
        assertNull(detectReset(previous, observation(percent = 0.0, provider = "codex")))
        assertNull(detectReset(previous, observation(percent = 0.0, window = "five_hour")))
    }

    @Test
    fun invalidPercentagesCannotEnterTheDomain() {
        for (percent in listOf(-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { observation(percent = percent) }
        }
        assertEquals(0.0, observation(percent = 0.0).usedPercent)
        assertEquals(100.0, observation(percent = 100.0).usedPercent)
    }

    @Test
    fun weeklyEligibilityFollowsTheCodexDurationInsteadOfItsWindowName() {
        assertTrue(isWeeklyUsageWindow("claude", "seven_day", null))
        assertFalse(isWeeklyUsageWindow("claude", "five_hour", null))
        assertTrue(isWeeklyUsageWindow("codex", "primary", 604_800))
        assertTrue(isWeeklyUsageWindow("codex", "secondary", 604_800))
        assertFalse(isWeeklyUsageWindow("codex", "primary", 18_000))
        assertFalse(isWeeklyUsageWindow("codex", "secondary", null))
        assertFalse(isWeeklyUsageWindow("unknown", "seven_day", 604_800))
    }
}
