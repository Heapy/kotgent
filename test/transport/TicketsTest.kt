package io.kotgent.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

class TicketsTest {

    private val start = 1_753_280_000_000L

    private var clock = start

    @BeforeTest
    fun resetClock() {
        clock = start
    }

    private fun store(ttlMillis: Long = TICKET_TTL_MILLIS) = TicketStore(now = { clock }, ttlMillis = ttlMillis)


    @Test
    fun anIssuedTicketIsEightCrockfordBase32Symbols() = runBlocking {
        val ticket = store().issue("test-bound-token")

        assertEquals(8, TICKET_CODE_LENGTH, "40 bits over a 32-symbol alphabet is 8 symbols exactly")
        assertEquals(TICKET_CODE_LENGTH, ticket.value.length, "the code is ${TICKET_CODE_LENGTH} symbols")
        assertTrue(
            ticket.value.all { it in TICKET_CODE_ALPHABET },
            "every symbol comes from the Crockford alphabet: ${ticket.value}",
        )
    }

    @Test
    fun theAlphabetOmitsTheSymbolsAHumanMisreads() {
        assertEquals(32, TICKET_CODE_ALPHABET.length, "exactly 32 symbols — five bits each, no modulo bias")
        assertEquals(32, TICKET_CODE_ALPHABET.toSet().size, "no symbol appears twice")
        for (excluded in "ILOU") {
            assertFalse(excluded in TICKET_CODE_ALPHABET, "$excluded must not be issuable")
        }
        assertTrue(TICKET_CODE_ALPHABET.all { it in '0'..'9' || it in 'A'..'Z' }, "digits and upper-case only")
    }

    @Test
    fun theEncodingIsAStraightRegroupingOfTheBits() {
        assertEquals("00000000", crockfordBase32(byteArrayOf(0, 0, 0, 0, 0)))
        assertEquals("ZZZZZZZZ", crockfordBase32(ByteArray(5) { 0xFF.toByte() }))
        assertEquals("G0000000", crockfordBase32(byteArrayOf(0x80.toByte(), 0, 0, 0, 0)))
        assertEquals("00000001", crockfordBase32(byteArrayOf(0, 0, 0, 0, 1)))
        assertEquals("04HMASW9", crockfordBase32(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte())))
        assertEquals("VTPVXVR1", crockfordBase32(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01)))
        assertEquals("ZW000000", crockfordBase32(byteArrayOf(0xFF.toByte(), 0, 0, 0, 0)))
    }

    @Test
    fun theEncodingIsInjectiveOverEveryValueOfAByte() = runBlocking {
        val codes = (0..255).map { crockfordBase32(byteArrayOf(0, 0, it.toByte(), 0, 0)) }
        assertEquals(256, codes.toSet().size, "no two inputs share a code")
        assertTrue(codes.all { it.length == TICKET_CODE_LENGTH }, "every code is full length")
    }

    @Test
    fun encodingRefusesAnythingThatIsNotFortyBits() {
        assertFailsWith<IllegalArgumentException> { crockfordBase32(ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { crockfordBase32(ByteArray(6)) }
        assertFailsWith<IllegalArgumentException> { crockfordBase32(ByteArray(0)) }
    }


    @Test
    fun normalizationFoldsCaseSeparatorsAndTheOmittedLetters() {
        assertEquals("A1B2C3D4", normalizeTicketCode("a1b2c3d4"), "lower-case is upper-cased")
        assertEquals("A1B2C3D4", normalizeTicketCode(" A1B2 C3D4 "), "spaces are dropped, inside and outside")
        assertEquals("A1B2C3D4", normalizeTicketCode("A1B2-C3D4"), "a dash is a grouping character, not a symbol")
        assertEquals("A1B2C3D4", normalizeTicketCode("\ta1b2\nc3d4\r\n"), "any whitespace, not just the space bar")
        assertEquals("11110000", normalizeTicketCode("ILil OoOo"))
        assertEquals("1", normalizeTicketCode("I"))
        assertEquals("1", normalizeTicketCode("l"))
        assertEquals("0", normalizeTicketCode("o"))
    }

    @Test
    fun normalizationLeavesAnAlreadyCanonicalCodeAlone() = runBlocking {
        val ticket = store().issue("test-bound-token")
        assertEquals(ticket.value, normalizeTicketCode(ticket.value))
    }

    @Test
    fun normalizationDoesNotInventASymbol() {
        assertEquals("U", normalizeTicketCode("u"))
        assertEquals("", normalizeTicketCode("  - - "), "separators alone normalise to nothing")
        assertEquals("", normalizeTicketCode(""))
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
        assertFailsWith<IllegalArgumentException> { TicketStore(now = { clock }, ttlMillis = 0) }
        assertFailsWith<IllegalArgumentException> { TicketStore(now = { clock }, ttlMillis = -1) }
    }


    @Test
    fun aFreshTicketIsRedeemedOnce() = runBlocking<Unit> {
        val store = store()
        val ticket = store.issue("test-bound-token")

        assertNotNull(store.redeem(ticket.value), "the first redemption wins")
    }

    @Test
    fun redeemingTheSameTicketTwiceFailsTheSecondTime() = runBlocking {
        val store = store()
        val boundToken = "test-bound-token"
        val ticket = store.issue(boundToken)
        val gate = CompletableDeferred<Unit>()
        val ready = Channel<Unit>(capacity = 2)

        val results = coroutineScope {
            val redemptions = List(2) {
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    ready.send(Unit)
                    gate.await()
                    store.redeem(ticket.value)
                }
            }
            repeat(2) { ready.receive() }
            gate.complete(Unit)
            redemptions.awaitAll()
        }

        assertEquals(1, results.count { it == boundToken }, "exactly one simultaneous redemption wins")
        assertEquals(1, results.count { it == null }, "the other concurrent replay is refused")
    }

    @Test
    fun anUnknownValueIsRefused() = runBlocking {
        val store = store()
        store.issue("test-bound-token")

        assertNull(store.redeem("ZZZZZZZZ"), "a well-formed code this store never minted")
        assertNull(store.redeem("f".repeat(64)), "a value of the wrong shape entirely")
        assertNull(store.redeem(""), "the empty string is not a ticket")
        assertNull(store.redeem("  -  "), "and neither is a handful of separators")
    }

    @Test
    fun aCodeRedeemsHoweverAHumanTypedIt() = runBlocking {
        val typed = listOf<Pair<String, (String) -> String>>(
            "lower-case" to { code -> code.lowercase() },
            "grouped with a space" to { code -> code.take(4) + " " + code.drop(4) },
            "grouped with a dash" to { code -> code.take(4) + "-" + code.drop(4) },
            "padded by the keyboard" to { code -> "  $code " },
            "mixed case with spaces" to { code -> code.take(2).lowercase() + " " + code.drop(2) },
        )
        for ((label, mangle) in typed) {
            val store = store()
            val ticket = store.issue("test-bound-token")
            assertNotNull(store.redeem(mangle(ticket.value)), "$label must redeem: ${mangle(ticket.value)}")
        }
    }

    @Test
    fun theLettersCrockfordDroppedAreTypedBackOntoTheirDigits() = runBlocking {
        val variants = listOf<Pair<String, (String) -> String>>(
            "I for 1, O for 0" to { code -> code.replace('1', 'I').replace('0', 'O') },
            "l for 1, o for 0" to { code -> code.replace('1', 'l').replace('0', 'o') },
            "L for 1" to { code -> code.replace('1', 'L') },
        )
        for ((label, mangle) in variants) {
            val store = store()
            val ticket = issueWithBothDigits(store)
            assertNotNull(store.redeem(mangle(ticket.value)), "$label must redeem: ${mangle(ticket.value)}")
        }
    }

    private suspend fun issueWithBothDigits(store: TicketStore): Ticket {
        repeat(500) {
            val ticket = store.issue("test-bound-token")
            if ('1' in ticket.value && '0' in ticket.value) return ticket
        }
        throw AssertionError("no code with both digits in 500 tries — the encoder is not random")
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


    @Test
    fun expiredTicketsAreSweptOnTheNextAccess() = runBlocking {
        val store = store(ttlMillis = 60_000)
        repeat(3) { store.issue("test-bound-token") }
        assertEquals(3, store.outstandingCount())

        clock = start + 60_001
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

        clock = start + 60_001
        assertEquals(1, store.outstandingCount())
        assertNull(store.redeem(old.value), "the expired one is gone")
        assertNotNull(store.redeem(young.value), "the live one survived the same sweep")
    }

    @Test
    fun aRedemptionAfterTheTtlDoesNotResurrectTheTicketLater() = runBlocking {
        val store = store(ttlMillis = 60_000)
        val ticket = store.issue("test-bound-token")

        clock = start + 60_001
        assertNull(store.redeem(ticket.value))

        clock = start + 1
        assertNull(store.redeem(ticket.value), "an expired ticket stays spent")
        assertEquals(0, store.outstandingCount())
    }

    @Test
    fun theDefaultTtlIsFiveMinutes() = runBlocking {
        assertEquals(5L * 60 * 1000, TICKET_TTL_MILLIS)
        assertEquals(start + TICKET_TTL_MILLIS, store().issue("test-bound-token").expiresAt)
    }

    @Test
    fun aTicketDiesAtExactlyFiveMinutes() = runBlocking {
        val store = store()
        val ticket = store.issue("test-bound-token")

        clock = start + TICKET_TTL_MILLIS - 1
        assertNotNull(store.redeem(ticket.value), "one millisecond before the deadline it still works")

        clock = start
        val other = store()
        val second = other.issue("test-bound-token")
        clock = start + TICKET_TTL_MILLIS
        assertNull(other.redeem(second.value), "at five minutes exactly it is gone")
    }


    @Test
    fun redeemReturnsTheMasterTokenThatWasLiveAtIssueTime() = runBlocking {
        val store = store()
        val ticket = store.issue(boundToken = "master-token-at-issue")

        assertEquals("master-token-at-issue", store.redeem(ticket.value), "redeem returns the issue-time token")
    }

    @Test
    fun redeemReturnsNullForAnUnknownReplayedOrExpiredTicket() = runBlocking {
        val store = store(ttlMillis = 60_000)
        val ticket = store.issue(boundToken = "tok")

        assertNull(store.redeem("ZZZZZZZZ"), "a value never issued → null")
        assertEquals("tok", store.redeem(ticket.value), "the first redemption yields the bound token")
        assertNull(store.redeem(ticket.value), "a replay yields null, never the token a second time")

        val other = store.issue(boundToken = "tok2")
        clock = start + 60_001
        assertNull(store.redeem(other.value), "an expired ticket → null")
    }

    @Test
    fun aCookieSignedFromARedeemedTicketVerifiesUnderTheIssueTimeTokenNotARotatedOne() = runBlocking {
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
