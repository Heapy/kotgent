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

/**
 * [ExchangeRateLimit] — the guessing budget that pays for the login code being 40 bits instead of 256.
 *
 * The properties pinned here are the ones the security argument in [TicketStore] actually rests on: the
 * budget is spent only by FAILURES (so an operator signing several devices in is never throttled), it does
 * refuse once the cap is reached (so guessing is bounded per minute), and the refusal expires on its own as
 * the window rolls forward (so a burst of wrong codes cannot lock sign-in out permanently).
 *
 * Time is injected, so a "one minute later" assertion moves a variable instead of sleeping — the suite never
 * pays a real window, and the boundary instant can be hit exactly.
 */
class ExchangeRateLimitTest {

    /** Start of the fake clock. A real epoch value, so nothing accidentally depends on "time near zero". */
    private val start = 1_753_280_000_000L

    /** A clock the tests advance by hand; [ExchangeRateLimit] only ever calls it, never sets it. */
    private var clock = start

    @BeforeTest
    fun resetClock() {
        clock = start
    }

    private fun limiter(
        max: Int = EXCHANGE_FAILURE_LIMIT,
        windowMillis: Long = EXCHANGE_WINDOW_MILLIS,
    ) = ExchangeRateLimit(now = { clock }, max = max, windowMillis = windowMillis)

    /** Drive one completed guess through the same reservation lifecycle the route uses. */
    private suspend fun ExchangeRateLimit.completeFailure(): Boolean {
        val attempt = begin() ?: return false
        attempt.finish(failed = true)
        return true
    }

    /** Drive one successful exchange: it occupies capacity while live, then releases it without a charge. */
    private suspend fun ExchangeRateLimit.completeSuccess(): Boolean {
        val attempt = begin() ?: return false
        attempt.finish(failed = false)
        return true
    }

    // --- the cap ------------------------------------------------------------------------------------

    @Test
    fun aFreshLimiterAllows() = runBlocking {
        val limit = limiter()
        assertNotNull(limit.begin(), "nothing has failed yet").finish(failed = false)
        assertEquals(0, limit.failuresInWindow())
    }

    @Test
    fun everyAttemptUpToTheCapIsAllowed() = runBlocking {
        // The honest path has to fit inside the budget: nine mistyped codes must still leave a tenth attempt
        // open, or a human at the Task-15 form would be cut off before they had a real chance to get it right.
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT - 1) {
            assertTrue(limit.completeFailure(), "attempt ${it + 1} is under the cap")
        }
        assertTrue(limit.completeSuccess(), "the ${EXCHANGE_FAILURE_LIMIT}th attempt is still allowed")
        assertEquals(EXCHANGE_FAILURE_LIMIT - 1, limit.failuresInWindow())
    }

    @Test
    fun theAttemptAfterTheCapIsRefused() = runBlocking {
        // With the default budget of ten: ten failures are recorded, and the ELEVENTH attempt is refused
        // before it can name a code. This is the whole limiter — everything else is bookkeeping around it.
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
                // Start every coroutine up to the same gate, then release the whole burst together. A split
                // check/record API let every one through here before the first failure landed.
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

    // --- successes ----------------------------------------------------------------------------------

    @Test
    fun aSuccessfulExchangeConsumesNoBudget() = runBlocking {
        // A success releases its reservation without leaving a failure. An operator signing a laptop, a
        // phone and a tablet back to back does hundreds of exchanges and must never be throttled.
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

    // --- the sliding window -------------------------------------------------------------------------

    @Test
    fun theWindowSlidesSoTheRefusalIsTemporary() = runBlocking {
        // The denial-of-sign-in trade-off is only acceptable because it ends by itself: once the attacker
        // stops, the daemon is usable again one window later with nothing to reset.
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
        // A fixed-bucket limiter would forget everything the moment the bucket flipped, letting 2 * max
        // guesses land across the boundary. Here a partial slide frees exactly the failures that aged out.
        val limit = limiter()
        repeat(EXCHANGE_FAILURE_LIMIT - 2) { assertTrue(limit.completeFailure()) } // 8 at `start`
        clock = start + EXCHANGE_WINDOW_MILLIS / 2
        repeat(2) { assertTrue(limit.completeFailure()) } // 2 at the half-way mark → at the cap
        assertNull(limit.begin(), "ten failures inside one window")

        clock = start + EXCHANGE_WINDOW_MILLIS
        assertEquals(2, limit.failuresInWindow(), "only the eight old ones aged out")
        assertTrue(limit.completeSuccess(), "so there is budget again — but only what actually expired")

        repeat(EXCHANGE_FAILURE_LIMIT - 2) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin(), "and the two survivors still count towards the new cap")
    }

    @Test
    fun aClockThatStepsBackwardsCannotResurrectAPrunedFailure() = runBlocking {
        // NTP or a laptop waking up can move the clock; a pruned entry is REMOVED, not merely filtered, so it
        // cannot come back and refuse an operator who is entitled to attempt.
        val limit = limiter(max = 1)
        assertTrue(limit.completeFailure())
        clock = start + EXCHANGE_WINDOW_MILLIS
        assertTrue(limit.completeSuccess(), "aged out")

        clock = start + 1
        assertTrue(limit.completeSuccess(), "the entry is gone for good")
        assertEquals(0, limit.failuresInWindow())
    }

    // --- configuration ------------------------------------------------------------------------------

    @Test
    fun theDefaultsAreTenFailuresPerMinute() = runBlocking {
        assertEquals(10, EXCHANGE_FAILURE_LIMIT)
        assertEquals(60_000L, EXCHANGE_WINDOW_MILLIS)
        // And the no-argument constructor really uses them (the shape [authRoutes] mounts by default).
        val limit = ExchangeRateLimit()
        repeat(EXCHANGE_FAILURE_LIMIT) { assertTrue(limit.completeFailure()) }
        assertNull(limit.begin(), "the default limiter refuses after ten failures")
    }

    @Test
    fun aNonPositiveConfigurationIsRefusedAtConstruction() {
        // A zero budget would refuse every sign-in and a zero window would make the limiter a no-op — both
        // are better as a loud failure at wiring time than as a login flow that silently never works.
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, max = 0) }
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, max = -1) }
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, windowMillis = 0) }
        assertFailsWith<IllegalArgumentException> { ExchangeRateLimit(now = { clock }, windowMillis = -1) }
    }
}
