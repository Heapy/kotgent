package io.kotgent.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExchangeRateLimitTest {

    private val start = 1_753_280_000_000L

    private var clock = start

    @BeforeTest
    fun resetClock() {
        clock = start
    }

    private fun limiter(
        max: Int = EXCHANGE_FAILURE_LIMIT,
        windowMillis: Long = EXCHANGE_WINDOW_MILLIS,
    ) = ExchangeRateLimit(now = { clock }, max = max, windowMillis = windowMillis)

    private suspend fun ExchangeRateLimit.completeFailure(): Boolean {
        val attempt = begin() ?: return false
        attempt.finish(failed = true)
        return true
    }

    private suspend fun ExchangeRateLimit.completeSuccess(): Boolean {
        val attempt = begin() ?: return false
        attempt.finish(failed = false)
        return true
    }


    @Test
    fun aFreshLimiterAllows() = runBlocking {
        val limit = limiter()
        assertNotNull(limit.begin(), "nothing has failed yet").finish(failed = false)
        assertEquals(0, limit.failuresInWindow())
    }

    @Test
    fun everyAttemptUpToTheCapIsAllowed() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT - 1) {
            assertTrue(limit.completeFailure(), "attempt ${it + 1} is under the cap")
        }
        assertTrue(limit.completeSuccess(), "the ${EXCHANGE_FAILURE_LIMIT}th attempt is still allowed")
        assertEquals(EXCHANGE_FAILURE_LIMIT - 1, limit.failuresInWindow())
    }

    @Test
    fun theAttemptAfterTheCapIsRefused() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT) {
            assertTrue(limit.completeFailure(), "failure ${it + 1} was still permitted to be attempted")
        }
        assertNull(limit.begin(), "the ${EXCHANGE_FAILURE_LIMIT + 1}th attempt inside the window is refused")
        assertEquals(EXCHANGE_FAILURE_LIMIT, limit.failuresInWindow())
    }

    @Test
    fun concurrentAttemptsCannotReservePastTheCap() = runBlocking {
        withTimeout(20_000) {
            val limit = limiter()
            val start = CompletableDeferred<Unit>()
            val total = EXCHANGE_FAILURE_LIMIT * 5
            val attempts = List(total) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    start.await()
                    limit.begin()
                }
            }

            start.complete(Unit)
            val results = attempts.awaitAll()
            val admitted = results.filterNotNull()
            assertEquals(
                EXCHANGE_FAILURE_LIMIT,
                admitted.size,
                "in-flight reservations count against the same global budget as completed failures",
            )
            assertEquals(total - EXCHANGE_FAILURE_LIMIT, results.count { it == null }, "the rest are throttled")

            admitted.forEach { it.finish(failed = true) }
            assertEquals(EXCHANGE_FAILURE_LIMIT, limit.failuresInWindow())
            assertNull(limit.begin(), "finishing the burst as failures keeps the budget saturated")
        }
    }

    @Test
    fun aRefusalPersistsForTheRestOfTheWindow() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT) { assertTrue(limit.completeFailure()) }

        clock = start + EXCHANGE_WINDOW_MILLIS - 1
        assertNull(limit.begin(), "one millisecond before the oldest failure ages out, still refused")
    }


    @Test
    fun aSuccessfulExchangeConsumesNoBudget() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT * 100) {
            assertTrue(limit.completeSuccess(), "a successful exchange leaves the limiter exactly as it found it")
        }
        assertEquals(0, limit.failuresInWindow(), "no budget was spent")
    }

    @Test
    fun successesInterleavedWithFailuresOnlyChargeTheFailures() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT - 1) {
            assertTrue(limit.completeFailure())
            assertTrue(limit.completeSuccess(), "a success between two failures")
        }
        assertEquals(EXCHANGE_FAILURE_LIMIT - 1, limit.failuresInWindow(), "only the failures counted")
        assertTrue(limit.completeSuccess(), "so there is still budget left")
    }


    @Test
    fun theWindowSlidesSoTheRefusalIsTemporary() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin())

        clock = start + EXCHANGE_WINDOW_MILLIS
        assertTrue(limit.completeSuccess(), "a full window after the failures, the budget is back")
        assertEquals(0, limit.failuresInWindow(), "and they are gone, not merely ignored")
    }

    @Test
    fun aFailureAgesOutAtExactlyTheWindowWidth() = runBlocking {
        val limit = limiter(max = 1)
        assertTrue(limit.completeFailure())

        clock = start + EXCHANGE_WINDOW_MILLIS - 1
        assertNull(limit.begin(), "one millisecond short of the width it still counts")

        clock = start + EXCHANGE_WINDOW_MILLIS
        assertTrue(
            limit.completeSuccess(),
            "the window is half-open — at exactly its width the failure is gone",
        )
    }

    @Test
    fun theWindowIsRollingNotABucketThatResetsWholesale() = runBlocking {
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT - 2) { assertTrue(limit.completeFailure()) }
        clock = start + EXCHANGE_WINDOW_MILLIS / 2
        repeat(2) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin(), "ten failures inside one window")

        clock = start + EXCHANGE_WINDOW_MILLIS
        assertEquals(2, limit.failuresInWindow(), "only the eight old ones aged out")
        assertTrue(limit.completeSuccess(), "so there is budget again — but only what actually expired")

        repeat(EXCHANGE_FAILURE_LIMIT - 2) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin(), "and the two survivors still count towards the new cap")
    }

    @Test
    fun aPrunedFailureCannotReturn() = runBlocking {
        val limit = limiter(max = 1)
        assertTrue(limit.completeFailure())
        clock = start + EXCHANGE_WINDOW_MILLIS
        assertTrue(limit.completeSuccess(), "aged out")

        clock += 1
        assertTrue(limit.completeSuccess(), "the entry is gone for good")
        assertEquals(0, limit.failuresInWindow())
    }

    @Test
    fun aBackwardWallClockStepBetweenSparseRequestsCannotExtendTheWindow() = runBlocking {
        var elapsed = 0L
        var wallClock = start + EXCHANGE_WINDOW_MILLIS * 2
        val limit = ExchangeRateLimit(now = { elapsed }, max = 2)

        repeat(2) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin(), "saturated before the adjustment")

        wallClock = start
        elapsed = EXCHANGE_WINDOW_MILLIS / 2
        wallClock += EXCHANGE_WINDOW_MILLIS / 2
        assertNull(
            limit.begin(),
            "the first post-rollback request arrives halfway through the original monotonic window",
        )
        assertEquals(start + EXCHANGE_WINDOW_MILLIS / 2, wallClock)

        elapsed = EXCHANGE_WINDOW_MILLIS - 1
        assertNull(limit.begin(), "the failures still count for the rest of their monotonic window")

        elapsed = EXCHANGE_WINDOW_MILLIS
        assertTrue(limit.completeSuccess(), "capacity returns one elapsed window after the attacker stopped")
        assertEquals(0, limit.failuresInWindow(), "no future-dated failure extends the lockout")
    }


    @Test
    fun theDefaultsAreTenFailuresPerMinute() = runBlocking {
        assertEquals(10, EXCHANGE_FAILURE_LIMIT)
        assertEquals(60_000L, EXCHANGE_WINDOW_MILLIS)
        val limit = ExchangeRateLimit()
        repeat(EXCHANGE_FAILURE_LIMIT) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin(), "the default limiter refuses after ten failures")
    }

    @Test
    fun aNonPositiveConfigurationIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, max = 0) }
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, max = -1) }
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, windowMillis = 0) }
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, windowMillis = -1) }
    }
}
