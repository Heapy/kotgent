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

/**
 * [TicketStore] — the one-shot login tickets (plan Task 6, reshaped into a typable code in Task 13).
 *
 * The two properties the login flow leans on are pinned here directly: a ticket is redeemable EXACTLY once
 * (a fragment left in a phone's history, a QR someone photographed off a screen, a double-tapped link — all
 * of them are replays), and it stops being redeemable when its TTL runs out. Everything else the store does
 * is bookkeeping in service of those two.
 *
 * A third property joined them with the short code: what the operator TYPES has to reach the value that was
 * MINTED. A code read off a screen arrives lower-cased, split by spaces or dashes, and with the letters
 * Crockford deliberately left out substituted back in (`I`/`l` for `1`, `O` for `0`) — every one of those
 * shapes is asserted to redeem, because a code that only works when transcribed perfectly is a code that
 * does not work on a phone.
 *
 * Time is injected, so expiry is asserted by moving a variable rather than by sleeping — the suite never
 * pays five minutes of wall clock, and the boundary instant can be hit exactly.
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
    fun anIssuedTicketIsEightCrockfordBase32Symbols() = runBlocking {
        // NOT the master token's 256 bits — deliberately 40, because this value has to be typed into a phone
        // that has its own empty cookie jar. What pays for the missing bits is elsewhere: single use, the
        // five-minute TTL, and the global failed-exchange rate limit. What is pinned here is that the format
        // is exactly what a human can transcribe — 8 symbols, none of them the ones that get misread.
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
        // The whole point of Crockford's alphabet: I/L cannot be confused with 1, O cannot be confused with 0,
        // because they are not in the set at all. U is out so a random code cannot spell something unfortunate.
        assertEquals(32, TICKET_CODE_ALPHABET.length, "exactly 32 symbols — five bits each, no modulo bias")
        assertEquals(32, TICKET_CODE_ALPHABET.toSet().size, "no symbol appears twice")
        for (excluded in "ILOU") {
            assertFalse(excluded in TICKET_CODE_ALPHABET, "$excluded must not be issuable")
        }
        assertTrue(TICKET_CODE_ALPHABET.all { it in '0'..'9' || it in 'A'..'Z' }, "digits and upper-case only")
    }

    @Test
    fun theEncodingIsAStraightRegroupingOfTheBits() {
        // Expected values from an independent encoder (python3, the same alphabet applied by hand), not from
        // this code agreeing with itself. All-zero and all-one pin the ends; 0x80… pins that the FIRST symbol
        // carries the HIGH bits (an little-endian mistake would put the G at the other end).
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
        // 40 bits divides evenly into 8 five-bit symbols, so the map is a bijection: 256 distinct inputs must
        // produce 256 distinct codes. A regrouping that dropped or reused a bit would collide here.
        val codes = (0..255).map { crockfordBase32(byteArrayOf(0, 0, it.toByte(), 0, 0)) }
        assertEquals(256, codes.toSet().size, "no two inputs share a code")
        assertTrue(codes.all { it.length == TICKET_CODE_LENGTH }, "every code is full length")
    }

    @Test
    fun encodingRefusesAnythingThatIsNotFortyBits() {
        // A short or long input would either lose entropy silently or produce a code of the wrong length —
        // both are worse than a loud failure at the one call site that mints.
        assertFailsWith<IllegalArgumentException> { crockfordBase32(ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { crockfordBase32(ByteArray(6)) }
        assertFailsWith<IllegalArgumentException> { crockfordBase32(ByteArray(0)) }
    }

    // --- normalising what a human types -------------------------------------------------------------

    @Test
    fun normalizationFoldsCaseSeparatorsAndTheOmittedLetters() {
        assertEquals("A1B2C3D4", normalizeTicketCode("a1b2c3d4"), "lower-case is upper-cased")
        assertEquals("A1B2C3D4", normalizeTicketCode(" A1B2 C3D4 "), "spaces are dropped, inside and outside")
        assertEquals("A1B2C3D4", normalizeTicketCode("A1B2-C3D4"), "a dash is a grouping character, not a symbol")
        assertEquals("A1B2C3D4", normalizeTicketCode("\ta1b2\nc3d4\r\n"), "any whitespace, not just the space bar")
        // Crockford's substitutions, both directions of case. None of I/L/O is issuable, so this cannot
        // collide with a code that was actually minted — it only rescues input that could never be valid.
        assertEquals("11110000", normalizeTicketCode("ILil OoOo"))
        assertEquals("1", normalizeTicketCode("I"))
        assertEquals("1", normalizeTicketCode("l"))
        assertEquals("0", normalizeTicketCode("o"))
    }

    @Test
    fun normalizationLeavesAnAlreadyCanonicalCodeAlone() = runBlocking {
        // The value the store hands out must be a fixed point, or issue and redeem would disagree.
        val ticket = store().issue("test-bound-token")
        assertEquals(ticket.value, normalizeTicketCode(ticket.value))
    }

    @Test
    fun normalizationDoesNotInventASymbol() {
        // `U` is excluded from the alphabet but has no substitution — it stays a `U` and therefore fails the
        // lookup, which is correct: silently mapping it onto something would let one typo redeem another code.
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
        store.issue("test-bound-token") // there IS something outstanding — the refusal is about the value, not about emptiness

        assertNull(store.redeem("ZZZZZZZZ"), "a well-formed code this store never minted")
        assertNull(store.redeem("f".repeat(64)), "a value of the wrong shape entirely")
        assertNull(store.redeem(""), "the empty string is not a ticket")
        assertNull(store.redeem("  -  "), "and neither is a handful of separators")
    }

    @Test
    fun aCodeRedeemsHoweverAHumanTypedIt() = runBlocking {
        // The reason the format changed at all: an installed iOS PWA has its own cookie jar, so the operator
        // READS this code off one screen and TYPES it into another. Every transcription a phone keyboard
        // produces has to land on the same ticket, or the sign-in path built on top of it is unusable.
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
        // A code containing 1 or 0 is the one a reader gets wrong: they see the digit and type the letter.
        // Both Crockford substitutions are honoured — I AND L for 1, O for 0 — in either case. Each variant
        // gets a code that actually CONTAINS both digits, so the substitution is exercised rather than
        // vacuously passing on a code that has neither.
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

    /** Mint until the code carries both `1` and `0` — 40 bits of randomness gets there in a handful of tries. */
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
    fun theDefaultTtlIsFiveMinutes() = runBlocking {
        // Halved along with the format change: the TTL IS the window in which a 40-bit code can be guessed
        // at, so it is kept to the span an operator needs to carry a code from a screen to a phone.
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

        assertNull(store.redeem("ZZZZZZZZ"), "a value never issued → null")
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
