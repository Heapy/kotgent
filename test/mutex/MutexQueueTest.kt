package io.kotgent.mutex

import io.kotgent.core.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutexQueueTest {
    private val queue = MutexQueue(leaseMillis = LEASE)
    private val build = MutexKey("kotlin-build")
    private val other = MutexKey("other")
    private val alice = SessionId("alice")
    private val bob = SessionId("bob")
    private val carol = SessionId("carol")
    private var minted = 0

    private fun token() = MutexToken("tok${++minted}")

    private fun ticket() = WaitTicket("tkt${++minted}")

    private fun MutexState.acquire(key: MutexKey, session: SessionId, now: Long = T0) =
        queue.acquire(this, key, session, now, token(), ticket())

    private fun Step<AcquireOutcome>.held(): Pair<MutexState, Holding> =
        state to assertIs<AcquireOutcome.Acquired>(result).holding

    private fun Step<AcquireOutcome>.queued(): Pair<MutexState, Waiter> =
        state to assertIs<AcquireOutcome.Queued>(result).waiter

    @Test
    fun aFreeKeyIsAcquiredAtOnce() {
        val [state, holding] = MutexState().acquire(build, alice).held()
        assertEquals(Holding(build, holding.token, alice, T0), holding)
        assertEquals(mapOf(build to holding), state.holdings)
        assertTrue(state.waiters.isEmpty())
    }

    @Test
    fun waitersAreGrantedInArrivalOrder() {
        val [s1, first] = MutexState().acquire(build, alice).held()
        val [s2, bobWaits] = s1.acquire(build, bob).queued()
        val [s3, carolWaits] = s2.acquire(build, carol).queued()
        assertEquals(2, queue.position(s3, carolWaits.ticket))

        val released = queue.release(s3, first.token, T0 + 1)
        assertEquals(first, released.result)
        assertEquals(listOf(bobWaits.ticket), released.grants.map { it.ticket })
        assertNull(released.state.holdings[build])

        val claimed = queue.claim(released.state, bobWaits.ticket, bob, T0 + 2, token())
        val bobHolds = assertIs<ClaimOutcome.Acquired>(claimed.result).holding
        assertEquals(bob, bobHolds.sessionId)
        assertEquals(1, queue.position(claimed.state, carolWaits.ticket))

        val next = queue.release(claimed.state, bobHolds.token, T0 + 3)
        assertEquals(listOf(carolWaits.ticket), next.grants.map { it.ticket })
    }

    @Test
    fun twoAcquisitionsOfOneSessionExcludeEachOther() {
        val [s1, first] = MutexState().acquire(build, alice).held()
        val [s2, second] = s1.acquire(build, alice).queued()
        assertEquals(alice, second.sessionId)
        assertEquals(first, s2.holdings[build])

        val released = queue.release(s2, first.token, T0 + 1)
        assertEquals(listOf(second.ticket), released.grants.map { it.ticket })
    }

    @Test
    fun releaseRemovesOnlyTheNamedToken() {
        val [s1, buildHeld] = MutexState().acquire(build, alice).held()
        val [s2, otherHeld] = s1.acquire(other, alice).held()
        val released = queue.release(s2, buildHeld.token, T0 + 1)
        assertEquals(buildHeld, released.result)
        assertEquals(mapOf(other to otherHeld), released.state.holdings)
    }

    @Test
    fun releasingAnUnknownTokenIsANoOp() {
        val [state, _] = MutexState().acquire(build, alice).held()
        val released = queue.release(state, MutexToken("nobody"), T0 + 1)
        assertNull(released.result)
        assertSame(state, released.state)
        assertTrue(released.grants.isEmpty())
    }

    @Test
    fun forceReleaseFreesTheKeyForTheNextWaiter() {
        val [s1, held] = MutexState().acquire(build, alice).held()
        val [s2, waiting] = s1.acquire(build, bob).queued()
        val forced = queue.forceRelease(s2, build, T0 + 1)
        assertEquals(held, forced.result)
        assertEquals(listOf(waiting.ticket), forced.grants.map { it.ticket })
        assertNull(queue.forceRelease(forced.state, other, T0 + 1).result)
    }

    @Test
    fun sessionEndReleasesItsHoldingsAndDropsItsWaiters() {
        val [s1, aliceBuild] = MutexState().acquire(build, alice).held()
        val [s2, _] = s1.acquire(other, bob).held()
        val [s3, aliceWaitsOther] = s2.acquire(other, alice).queued()
        val [s4, carolWaitsBuild] = s3.acquire(build, carol).queued()

        val ended = queue.endSession(s4, alice, T0 + 1)
        assertEquals(listOf(aliceBuild), ended.result)
        assertEquals(listOf(carolWaitsBuild.ticket), ended.grants.map { it.ticket })
        assertTrue(ended.state.waiters.none { it.ticket == aliceWaitsOther.ticket })
        assertEquals(setOf(other), ended.state.holdings.keys)
    }

    @Test
    fun anUnleasedTicketIsSkippedAndDroppedAtGrant() {
        val [s1, held] = MutexState().acquire(build, alice).held()
        val [s2, bobWaits] = s1.acquire(build, bob).queued()
        val s3 = queue.closePoll(s2, bobWaits.ticket, T0).state
        val [s4, carolWaits] = s3.acquire(build, carol, T0 + 1).queued()

        val released = queue.release(s4, held.token, T0 + LEASE + 1)
        assertEquals(listOf(carolWaits.ticket), released.grants.map { it.ticket })
        assertTrue(released.state.waiters.none { it.ticket == bobWaits.ticket })
        val late = queue.claim(released.state, bobWaits.ticket, bob, T0 + LEASE + 2, token())
        assertIs<ClaimOutcome.UnknownTicket>(late.result)
    }

    @Test
    fun aLeasedGrantBecomesAHoldingOnlyWhenClaimed() {
        val [s1, held] = MutexState().acquire(build, alice).held()
        val [s2, bobWaits] = s1.acquire(build, bob).queued()
        val s3 = queue.closePoll(s2, bobWaits.ticket, T0).state

        val released = queue.release(s3, held.token, T0 + LEASE / 2)
        assertEquals(listOf(bobWaits.ticket), released.grants.map { it.ticket })
        assertNull(released.state.holdings[build])
        val [s4, carolWaits] = released.state.acquire(build, carol, T0 + LEASE / 2).queued()
        assertEquals(2, queue.position(s4, carolWaits.ticket))

        val claimed = queue.claim(s4, bobWaits.ticket, bob, T0 + LEASE - 1, token())
        val holding = assertIs<ClaimOutcome.Acquired>(claimed.result).holding
        assertEquals(Holding(build, holding.token, bob, T0 + LEASE - 1), holding)
        assertEquals(holding, claimed.state.holdings[build])
    }

    @Test
    fun anUnclaimedGrantPassesOnWhenItsLeaseRunsOut() {
        val [s1, held] = MutexState().acquire(build, alice).held()
        val [s2, bobWaits] = s1.acquire(build, bob).queued()
        val s3 = queue.closePoll(s2, bobWaits.ticket, T0).state
        val [s4, carolWaits] = s3.acquire(build, carol).queued()
        val granted = queue.release(s4, held.token, T0 + 1).state

        assertEquals(T0 + LEASE, queue.nextDeadline(granted))
        val expired = queue.expire(granted, T0 + LEASE)
        assertEquals(listOf(bobWaits.ticket), expired.result.map { it.ticket })
        assertEquals(listOf(carolWaits.ticket), expired.grants.map { it.ticket })
        assertNull(queue.nextDeadline(expired.state))
    }

    @Test
    fun anOpenPollKeepsItsLeaseAndClosingItStartsTheCountdown() {
        val [s1, _] = MutexState().acquire(build, alice).held()
        val [s2, bobWaits] = s1.acquire(build, bob).queued()
        assertNull(queue.nextDeadline(s2))
        assertTrue(queue.expire(s2, T0 + 10 * LEASE).result.isEmpty())

        val closedOnce = queue.closePoll(s2, bobWaits.ticket, T0).state
        val reopened = queue.claim(closedOnce, bobWaits.ticket, bob, T0 + 1, token())
        assertIs<ClaimOutcome.Waiting>(reopened.result)
        assertNull(queue.nextDeadline(reopened.state))
        val closed = queue.closePoll(reopened.state, bobWaits.ticket, T0 + 5).state
        assertEquals(T0 + 5 + LEASE, queue.nextDeadline(closed))
    }

    @Test
    fun aTicketIsClaimedOnlyByItsOwnSession() {
        val [s1, held] = MutexState().acquire(build, alice).held()
        val [s2, bobWaits] = s1.acquire(build, bob).queued()
        val granted = queue.release(s2, held.token, T0 + 1).state
        assertIs<ClaimOutcome.ForeignTicket>(queue.claim(granted, bobWaits.ticket, carol, T0 + 2, token()).result)
        assertIs<ClaimOutcome.UnknownTicket>(queue.claim(granted, WaitTicket("missing"), bob, T0 + 2, token()).result)
    }

    @Test
    fun keysAreBoundedSafeIdentifiers() {
        for (good in listOf("a", "kotlin-build.v2_x", "x".repeat(MutexKey.MAX_LENGTH))) {
            assertEquals(good, MutexKey.parseOrNull(good)?.value)
        }
        for (bad in listOf("", "-lead", ".hidden", "a/b", "a b", "x".repeat(MutexKey.MAX_LENGTH + 1), "ключ")) {
            assertFailsWith<IllegalArgumentException>(bad) { MutexKey(bad) }
            assertNull(MutexKey.parseOrNull(bad))
        }
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        const val LEASE: Long = 30_000L
    }
}
