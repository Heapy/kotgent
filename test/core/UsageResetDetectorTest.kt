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

    private fun detectAtFixtureTimes(previous: UsageObservation?, current: UsageObservation): UsageReset? =
        detectReset(previous, current, previous?.observedAt ?: 0, current.observedAt)

    @Test
    fun receiptTimesDetermineResetTimingEvenWhenOrderingRevisionsAreInTheFuture() {
        val previous = observation(percent = 70.0).copy(observedAt = end + 86_400_000)
        val current = previous.copy(usedPercent = 2.0, observedAt = previous.observedAt + 1)
        val previousReceipt = end - 3_700_000
        val receipt = end - 3_600_000
        val reset = assertNotNull(detectReset(previous, current, previousReceipt, receipt))
        assertTrue(reset.early)
        assertEquals(previousReceipt, reset.usedBeforeSeenAt)
        assertEquals(receipt, reset.observedAt)
        assertFalse(assertNotNull(detectReset(previous, current, previousReceipt, end)).early)
    }

    @Test
    fun aFirstObservationAndUnchangedOrRisingUsageDoNotReset() {
        val previous = observation()
        assertNull(detectAtFixtureTimes(null, previous))
        assertNull(detectAtFixtureTimes(previous, previous.copy(observedAt = previous.observedAt + 1)))
        assertNull(detectAtFixtureTimes(previous, previous.copy(usedPercent = 42.0)))
    }

    @Test
    fun anyClaudeDecreaseAnHourBeforeThePromisedEndIsEarly() {
        val previous = observation(percent = 41.1, seen = end - 3_700_000)
        val reset = assertNotNull(detectAtFixtureTimes(previous, observation(percent = 41.0)))
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
            assertFalse(assertNotNull(detectAtFixtureTimes(previous, observation(percent = 0.0, seen = seen))).early)
        }
        assertTrue(assertNotNull(detectAtFixtureTimes(
            previous,
            observation(percent = 0.0, seen = end - EARLY_TOLERANCE_MILLIS - 1),
        )).early)
    }

    @Test
    fun aClaudeDropWithoutAKnownDeadlineIsJournalledWithoutAnEarlyClaim() {
        val reset = assertNotNull(detectAtFixtureTimes(
            observation(reset = null),
            observation(percent = 1.0, reset = null),
        ))
        assertFalse(reset.early)
        assertNull(reset.expectedAt)
    }

    @Test
    fun codexRequiresBothAPercentDropAndAMovedKnownDeadline() {
        val previous = observation(provider = "codex")
        assertNull(detectAtFixtureTimes(previous, previous.copy(usedPercent = 1.0)))
        assertNull(detectAtFixtureTimes(previous, previous.copy(resetsAt = end + 604_800_000)))
        assertNull(detectAtFixtureTimes(previous, previous.copy(usedPercent = 1.0, resetsAt = null)))
        assertNull(detectAtFixtureTimes(previous.copy(resetsAt = null), previous.copy(usedPercent = 1.0)))
        val early = assertNotNull(detectAtFixtureTimes(
            previous,
            previous.copy(usedPercent = 1.0, resetsAt = end + 604_800_000),
        ))
        assertTrue(early.early)
        assertTrue(early.resetsAtMoved)
        val onTime = assertNotNull(detectAtFixtureTimes(
            previous,
            previous.copy(usedPercent = 1.0, resetsAt = end + 604_800_000, observedAt = end),
        ))
        assertFalse(onTime.early)
    }

    @Test
    fun codexUsesTheCurrentDurationAndFallsBackOnlyWhenItIsAbsent() {
        val previous = observation(provider = "codex")
        val current = previous.copy(usedPercent = 1.0, resetsAt = end + 604_800_000, windowSeconds = 604_800)
        assertTrue(assertNotNull(detectAtFixtureTimes(previous, current)).isNotificationEligible)
        assertEquals(604_800L, assertNotNull(detectAtFixtureTimes(previous.copy(windowSeconds = 604_800), current.copy(windowSeconds = null))).windowSeconds)
        assertFalse(assertNotNull(detectAtFixtureTimes(previous.copy(windowSeconds = 604_800), current.copy(windowSeconds = 18_000))).isNotificationEligible)
    }

    @Test
    fun anUnknownProviderUsesTheConservativeRule() {
        val previous = observation(provider = "future")
        assertNull(detectAtFixtureTimes(previous, previous.copy(usedPercent = 0.0)))
        assertNotNull(detectAtFixtureTimes(previous, previous.copy(usedPercent = 0.0, resetsAt = end + 1)))
    }

    @Test
    fun observationsFromDifferentMetersCannotEstablishAReset() {
        val previous = observation()
        assertNull(detectAtFixtureTimes(previous, observation(percent = 0.0, provider = "codex")))
        assertNull(detectAtFixtureTimes(previous, observation(percent = 0.0, window = "five_hour")))
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
