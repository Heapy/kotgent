package io.kotgent.transport

import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TicketStore] — the one-shot login tickets (plan Task 6).
 *
 * The two properties the login flow leans on are pinned here directly: a ticket is redeemable EXACTLY once
 * (a fragment left in a phone's history, a QR someone photographed off a screen, a double-tapped link — all
 * of them are replays), and it stops being redeemable when its TTL runs out. Everything else the store does
 * is bookkeeping in service of those two.
 *
 * Time is injected, so expiry is asserted by moving a variable rather than by sleeping — the suite never
 * pays ten minutes of wall clock, and the boundary instant can be hit exactly.
 */
class TicketsTest {

    /** Start of the fake clock. A real epoch value, so nothing accidentally depends on "time near zero". */
    private val start = 1_753_280_000_000L

    /** A clock the tests advance by hand; [TicketStore] only ever calls it, never sets it. */
    private var clock = start

    /** Explicit rather than relying on a fresh instance per test — every assertion below is relative to [start]. */
    @BeforeTest
    fun resetClock() {
        clock = start
    }

    private fun store(ttlMillis: Long = TICKET_TTL_MILLIS) = TicketStore(now = { clock }, ttlMillis = ttlMillis)

    // --- issuing ------------------------------------------------------------------------------------

    @Test
    fun anIssuedTicketCarries32BytesOfEntropyHexEncoded() = runBlocking {
        val ticket = store().issue("test-bound-token")

        // Same strength as the master token: for its ten minutes a ticket buys the same access, so it must
        // not be the cheap end of the flow.
        assertEquals(SECRET_BYTES * 2, ticket.value.length, "32 bytes, hex-encoded")
        assertTrue(ticket.value.all { it in '0'..'9' || it in 'a'..'f' }, "lowercase hex: ${ticket.value}")
    }

    @Test
    fun eachIssueMintsADifferentValue() = runBlocking {
        val store = store()
        val values = List(16) { store.issue("test-bound-token").value }
        assertEquals(values.size, values.toSet().size, "no two tickets share a value")
    }

    @Test
    fun expiresAtIsTheIssueInstantPlusTheTtl() = runBlocking {
        val ticket = store(ttlMillis = 60_000).issue("test-bound-token")
        assertEquals(start + 60_000, ticket.expiresAt)
    }

    @Test
    fun aNonPositiveTtlIsRefusedAtConstruction() {
        // A zero or negative TTL would make every ticket dead on arrival — better a loud failure at wiring
        // time than a login flow that silently never works.
        assertFailsWith<IllegalArgumentException> { TicketStore(now = { clock }, ttlMillis = 0) }
        assertFailsWith<IllegalArgumentException> { TicketStore(now = { clock }, ttlMillis = -1) }
    }

    // --- redeeming ----------------------------------------------------------------------------------

    @Test
    fun aFreshTicketIsRedeemedOnce() = runBlocking<Unit> {
        val store = store()
        val ticket = store.issue("test-bound-token")

        assertNotNull(store.redeem(ticket.value), "the first redemption wins")
    }

    @Test
    fun redeemingTheSameTicketTwiceFailsTheSecondTime() = runBlocking {
        val store = store()
        val ticket = store.issue("test-bound-token")

        assertNotNull(store.redeem(ticket.value))
        assertNull(store.redeem(ticket.value), "a replay of the same fragment must not log anyone in again")
    }

    @Test
    fun anUnknownValueIsRefused() = runBlocking {
        val store = store()
        store.issue("test-bound-token") // there IS something outstanding — the refusal is about the value, not about emptiness

        assertNull(store.redeem("f".repeat(64)), "a value this store never minted")
        assertNull(store.redeem(""), "the empty string is not a ticket")
    }

    @Test
    fun aTicketRedeemedAfterItsTtlIsRefused() = runBlocking {
        val store = store(ttlMillis = 60_000)
        val ticket = store.issue("test-bound-token")

        clock = start + 60_001
        assertNull(store.redeem(ticket.value), "the TTL has run out")
    }

    @Test
    fun aTicketIsValidUpToButNotIncludingItsExpiryInstant() = runBlocking {
        val justBefore = store(ttlMillis = 60_000)
        val ticket = justBefore.issue("test-bound-token")
        clock = start + 59_999
        assertNotNull(justBefore.redeem(ticket.value), "still inside the TTL")

        clock = start
        val atExpiry = store(ttlMillis = 60_000)
        val other = atExpiry.issue("test-bound-token")
        clock = other.expiresAt
        assertNull(atExpiry.redeem(other.value), "expiresAt is a deadline, not a grace period")
    }

    @Test
    fun redeemingOneTicketLeavesTheOthersAlone() = runBlocking {
        val store = store()
        val first = store.issue("test-bound-token")
        val second = store.issue("test-bound-token")

        assertNotNull(store.redeem(first.value))
        assertNotNull(store.redeem(second.value), "a concurrent login on another device is unaffected")
        assertNotEquals(first.value, second.value)
    }

    // --- sweeping -----------------------------------------------------------------------------------

    @Test
    fun expiredTicketsAreSweptOnTheNextAccess() = runBlocking {
        val store = store(ttlMillis = 60_000)
        repeat(3) { store.issue("test-bound-token") }
        assertEquals(3, store.outstandingCount())

        clock = start + 60_001
        // The sweep runs inside the access itself — there is no timer in the daemon to hang one off.
        assertEquals(0, store.outstandingCount(), "everything past its TTL is gone")
    }

    @Test
    fun issuingSweepsExpiredTicketsSoTheMapDoesNotGrowWithoutBound() = runBlocking<Unit> {
        val store = store(ttlMillis = 60_000)
        repeat(5) { store.issue("test-bound-token") }

        clock = start + 60_001
        val fresh = store.issue("test-bound-token")

        assertEquals(1, store.outstandingCount(), "only the newly issued ticket survives the sweep")
        assertNotNull(store.redeem(fresh.value), "and it is the one that still works")
    }

    @Test
    fun aSweepDoesNotTouchTicketsThatAreStillInsideTheirTtl() = runBlocking<Unit> {
        val store = store(ttlMillis = 60_000)
        val old = store.issue("test-bound-token")
        clock = start + 30_000
        val young = store.issue("test-bound-token")

        clock = start + 60_001 // old is past its deadline, young has 30s left
        assertEquals(1, store.outstandingCount())
        assertNull(store.redeem(old.value), "the expired one is gone")
        assertNotNull(store.redeem(young.value), "the live one survived the same sweep")
    }

    @Test
    fun aRedemptionAfterTheTtlDoesNotResurrectTheTicketLater() = runBlocking {
        // Even a clock that goes BACKWARDS (NTP step, a suspended laptop waking up) must not turn an already
        // swept ticket back into a valid one — the entry is dropped, not just failed.
        val store = store(ttlMillis = 60_000)
        val ticket = store.issue("test-bound-token")

        clock = start + 60_001
        assertNull(store.redeem(ticket.value))

        clock = start + 1
        assertNull(store.redeem(ticket.value), "an expired ticket stays spent")
        assertEquals(0, store.outstandingCount())
    }

    @Test
    fun theDefaultTtlIsTenMinutes() = runBlocking {
        assertEquals(10L * 60 * 1000, TICKET_TTL_MILLIS)
        assertEquals(start + TICKET_TTL_MILLIS, store().issue("test-bound-token").expiresAt)
    }

    // --- token binding ------------------------------------------------------------------------------

    @Test
    fun redeemReturnsTheMasterTokenThatWasLiveAtIssueTime() = runBlocking {
        // The cross-lock fix: a ticket captures the master token it was minted under, and redeem hands it back
        // so the exchange signs its cookie with THAT token — not with whatever is current at redeem time.
        val store = store()
        val ticket = store.issue(boundToken = "master-token-at-issue")

        assertEquals("master-token-at-issue", store.redeem(ticket.value), "redeem returns the issue-time token")
    }

    @Test
    fun redeemReturnsNullForAnUnknownReplayedOrExpiredTicket() = runBlocking {
        // Same single-use + TTL semantics as before, just expressed as "the bound token, or null".
        val store = store(ttlMillis = 60_000)
        val ticket = store.issue(boundToken = "tok")

        assertNull(store.redeem("f".repeat(64)), "a value never issued → null")
        assertEquals("tok", store.redeem(ticket.value), "the first redemption yields the bound token")
        assertNull(store.redeem(ticket.value), "a replay yields null, never the token a second time")

        val other = store.issue(boundToken = "tok2")
        clock = start + 60_001
        assertNull(store.redeem(other.value), "an expired ticket → null")
    }

    @Test
    fun aCookieSignedFromARedeemedTicketVerifiesUnderTheIssueTimeTokenNotARotatedOne() = runBlocking {
        // The whole security story after the fix, end to end: validity flows from the ticket's bound token.
        // Sign a cookie with the token redeem hands back; it verifies under that token, and a rotation to a
        // DIFFERENT token makes the very same cookie fail verification — dead on arrival, no clear() needed.
        val store = store()
        val issueTimeToken = "issue-time-master-token-0123456789ab"
        val ticket = store.issue(boundToken = issueTimeToken)

        val boundToken = store.redeem(ticket.value)
        assertEquals(issueTimeToken, boundToken, "the redeemed token is the one live at mint time")

        val cookie = issueSessionCookie(boundToken!!, start)
        assertTrue(verifySessionCookie(issueTimeToken, cookie), "the cookie verifies under the issue-time token")
        assertFalse(
            verifySessionCookie("a-rotated-master-token-0123456789abcd", cookie),
            "but NOT under a rotated token — a rotation between mint and redeem revokes it by construction",
        )
    }
}
