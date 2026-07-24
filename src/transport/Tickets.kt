package io.kotgent.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * One-shot login tickets — the short-lived value that carries a login from an already-authenticated place
 * (the CLI, which can read `~/.kotgent/token`) into a browser that has no credential yet.
 *
 * ## Where a ticket sits in the login flow
 * `POST /auth/ticket` (Bearer, loopback-only) mints one; it travels to the browser inside a URL FRAGMENT
 * (`…/auth#ticket=…`), which means the server never sees it on the `GET /auth` that loads the page — so a
 * link prefetcher, a mail scanner or an antivirus cannot burn it, and it never lands in a tunnel's request
 * log. The page's script then spends it on `POST /auth/exchange`, which redeems it exactly once and returns
 * the real, long-lived credential (the session cookie).
 *
 * A ticket is therefore a BEARER of full daemon access for its short life. Three properties carry the whole
 * weight: it is single-use ([redeem] hands back its bound token at most once per value, `null` thereafter),
 * it expires ([TICKET_TTL_MILLIS]), and guessing at it is rate-limited globally on the exchange route. The
 * first two are enforced here rather than at the route, so no route can forget them; the third cannot live
 * here, because this store never sees the requests that fail to name a real ticket in the first place.
 *
 * ## Why the value is a short code and not 32 bytes of hex
 * An installed iOS PWA has its OWN cookie jar: the QR link opened in Safari signs Safari in, and the
 * home-screen app still launches with nothing. The only way through that is a code the operator can READ off
 * one screen and TYPE into another, so the ticket has to survive being typed on a phone keyboard. Hence
 * [TICKET_CODE_LENGTH] symbols of Crockford base32 ([TICKET_CODE_ALPHABET]): no `I`/`L`/`O`/`U`, so there is
 * no `1`-vs-`l` or `0`-vs-`O` to get wrong, and [normalizeTicketCode] forgives the confusions that remain
 * (case, the spaces or dashes someone types to break the code up, and a typed `I`/`L`/`O` anyway).
 *
 * This is a DELIBERATE trade of entropy for typability: 40 bits, not the 256 the master token carries. What
 * replaces the missing bits is that a ticket cannot be attacked at speed — every wrong guess has to arrive as
 * a `POST /auth/exchange`, and that route is globally rate-limited on FAILURES, so an attacker gets a few
 * dozen guesses a minute against 2^40 values that also expire in five minutes and die on first use. Keep all
 * three: drop the rate limit and 40 bits is genuinely guessable; lengthen the TTL and the guessing window
 * grows with it.
 *
 * ## Why a ticket carries the token it was minted under
 * A ticket is redeemed AFTER it is issued, and the cookie the exchange mints must be signed with the master
 * token. If the exchange read the *current* token at redeem time, a rotation landing between issue and redeem
 * would sign a fresh cookie with the NEW token — a pre-rotation ticket would yield a live post-rotation
 * session, defeating "revoke all". So each ticket captures the token that was live when it was MINTED and
 * [redeem] returns it: the exchange signs with the ticket's issue-time token, and a rotation in between makes
 * the resulting cookie fail [verifySessionCookie] against the now-current token — dead on arrival, with no
 * cross-lock timing to get wrong (issue and rotate live in different synchronization domains).
 *
 * ## Why in memory and not in SQLite
 * A ticket lives five minutes. Persisting it would mean a schema migration and a table that has to be swept,
 * to buy the ability to redeem — after a daemon restart — a ticket whose whole purpose is to be redeemed
 * immediately. Losing outstanding tickets on restart is the CORRECT behaviour, not a limitation: the
 * operator reissues with `kotgent web`, which costs one command.
 *
 * ## Why no cap on the map
 * Deliberately unbounded. The TTL already bounds growth (only tickets issued in the last five minutes are
 * kept, and every access sweeps the expired ones), and issuing requires the master token — an attacker who
 * could flood this already holds the key to everything. A cap with eviction would trade that non-problem
 * for a real one: the operator's ticket silently stops working because someone (possibly they themselves,
 * in another tab) issued a few more, with no way to tell that apart from a genuine expiry.
 */

/**
 * How long a freshly issued ticket stays redeemable: five minutes.
 *
 * Half of what it used to be, and the cut is part of the short-code trade above: the TTL is exactly the
 * window in which a 40-bit value can be guessed at, so it is kept to the span an operator actually needs to
 * walk a code from a screen to a phone. Long enough to type; short enough that the whole guessing window is
 * five minutes wide.
 */
const val TICKET_TTL_MILLIS: Long = 5L * 60 * 1000

/**
 * Crockford base32 — the digits and the upper-case letters with `I`, `L`, `O` and `U` removed.
 *
 * `I`/`L` (against `1`) and `O` (against `0`) are dropped because they are the pairs a human misreads off a
 * screen; `U` is dropped because leaving it out is what keeps an accidental obscenity out of a random code.
 * Exactly 32 symbols, so one symbol is exactly five bits and the encoding below has no padding and no modulo
 * bias to reason about.
 */
const val TICKET_CODE_ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** Bytes of entropy behind one login code: 5 bytes = 40 bits = exactly [TICKET_CODE_LENGTH] symbols. */
const val TICKET_CODE_BYTES: Int = 5

/** How many symbols an issued login code carries. `TICKET_CODE_BYTES * 8 / 5`, with nothing left over. */
const val TICKET_CODE_LENGTH: Int = TICKET_CODE_BYTES * 8 / 5

/**
 * A minted ticket: the [value] to put in the login URL's fragment (and to read aloud into a phone), and the
 * [expiresAt] wall-clock instant (epoch millis) it stops being redeemable — the deadline [TicketStore] sweeps
 * against, echoed back in the ticket response for any client that wants to show how long the code is good for
 * (the QR dialog states the fixed five-minute TTL in its copy rather than reading this).
 */
data class Ticket(val value: String, val expiresAt: Long)

/**
 * Encode exactly [TICKET_CODE_BYTES] bytes as [TICKET_CODE_LENGTH] Crockford base32 symbols, most
 * significant bits first.
 *
 * 40 bits over an alphabet of 32 divides evenly, so this is a straight regrouping of the bits — every
 * 5-byte input maps to one code and every code maps back to one input. No padding, and no `% 32` anywhere,
 * which is what would have skewed some symbols more likely than others.
 */
fun crockfordBase32(bytes: ByteArray): String {
    require(bytes.size == TICKET_CODE_BYTES) { "a login code is exactly $TICKET_CODE_BYTES bytes, got ${bytes.size}" }
    var bits = 0L
    for (byte in bytes) bits = (bits shl 8) or (byte.toLong() and 0xFF)
    val code = CharArray(TICKET_CODE_LENGTH)
    for (i in 0 until TICKET_CODE_LENGTH) {
        val shift = 5 * (TICKET_CODE_LENGTH - 1 - i)
        code[i] = TICKET_CODE_ALPHABET[((bits shr shift) and 0x1F).toInt()]
    }
    return code.concatToString()
}

/**
 * Fold the ways a human retypes a code back onto the one value that was issued: trim, drop every space and
 * dash, upper-case, and apply Crockford's own substitutions — `I` and `L` are `1`, `O` is `0`.
 *
 * Those three letters are not IN [TICKET_CODE_ALPHABET], so nothing here can collide with a symbol that was
 * actually issued: the mapping only rescues input that could not have been minted anyway. The dash and space
 * stripping is for the reader, who will group `A1B2C3D4` as `A1B2 C3D4` about half the time.
 *
 * Applied by [redeem] before the lookup, so every caller gets the same forgiveness without having to know
 * about it.
 */
fun normalizeTicketCode(raw: String): String {
    val out = StringBuilder(raw.length)
    for (ch in raw) {
        if (ch.isWhitespace() || ch == '-') continue
        out.append(
            when (ch) {
                'I', 'i', 'L', 'l' -> '1'
                'O', 'o' -> '0'
                else -> ch.uppercaseChar()
            },
        )
    }
    return out.toString()
}

/**
 * The daemon's outstanding one-shot tickets.
 *
 * Thread-safe through a [Mutex], the same single-writer shape [io.kotgent.store.SqliteEventStore] uses:
 * `redeem` is a read-modify-write (find, then remove), and two browsers replaying the same fragment
 * concurrently must not both win. Holding a lock is fine here — the critical sections are map operations,
 * and both callers are already suspending route handlers.
 *
 * @param now injected clock (epoch millis) so expiry is testable without sleeping; `kotlin.system.getTimeMillis`
 *   is ERROR-level deprecated, hence [ticketEpochMillis] over [Clock].
 * @param ttlMillis how long an issued ticket stays redeemable.
 */
class TicketStore(
    private val now: () -> Long = ::ticketEpochMillis,
    private val ttlMillis: Long = TICKET_TTL_MILLIS,
) {
    init {
        require(ttlMillis > 0) { "ticket TTL must be positive, got $ttlMillis ms" }
    }

    private val mutex = Mutex()

    /** What an outstanding ticket carries: when it stops being redeemable, and the master token it was minted
     *  under (the value [redeem] hands back, which the exchange signs its cookie with). */
    private data class Outstanding(val expiresAt: Long, val boundToken: String)

    /** value → its [Outstanding] record. Swept on every access ([purgeExpired]). */
    private val outstanding = mutableMapOf<String, Outstanding>()

    /**
     * Mint a ticket: [TICKET_CODE_BYTES] of entropy from the daemon's one entropy source ([randomBytes]),
     * encoded as [TICKET_CODE_LENGTH] Crockford base32 symbols — a value that can be typed into a phone,
     * which is the whole reason it is not another 32-byte hex secret (see the class KDoc for the trade and
     * the controls that pay for it).
     *
     * The minted value is already canonical, so [normalizeTicketCode] is the identity on it: only input from
     * a human ever needs normalising.
     *
     * [boundToken] is the master token live at this instant; it is stored on the ticket and returned by
     * [redeem] so the exchange signs its cookie with the token that was current at MINT time, not at redeem
     * time (see the class KDoc on why this is what makes rotation revoke by construction).
     */
    suspend fun issue(boundToken: String): Ticket = mutex.withLock {
        val at = now()
        purgeExpired(at)
        val ticket = Ticket(value = crockfordBase32(randomBytes(TICKET_CODE_BYTES)), expiresAt = at + ttlMillis)
        outstanding[ticket.value] = Outstanding(ticket.expiresAt, boundToken)
        ticket
    }

    /**
     * Spend [value] — as typed, so it is run through [normalizeTicketCode] first: return the ticket's
     * issue-time [boundToken][Outstanding.boundToken] exactly once, for a ticket that was issued here and has
     * not expired; `null` for an unknown value, a replay, or one whose TTL has run out.
     *
     * Expiry is decided in ONE place — the sweep runs first, so whatever is still in the map is live by the
     * current clock and the redemption is a plain "was it there?". Once a sweep has OBSERVED a ticket past its
     * expiry it is gone, so a clock that then steps backwards (NTP, a laptop waking up) cannot bring that one
     * back. The gap this does not cover is a ticket whose expiry passed with no intervening access at all: if
     * the clock steps back below its `expiresAt` before any sweep sees it, it is still redeemable. That is not
     * exploitable — minting a ticket needs the master token in the first place — but it is why this is a
     * best-effort sweep, not a monotonic guarantee.
     *
     * ## Why a plain map lookup and not a [constantTimeEquals] scan
     * NOT because the value is unguessable — at 40 bits it is the weakest secret in the daemon, and the
     * argument this KDoc used to make ("256 bits of entropy") died with the hex format. It survives on a
     * narrower claim: what a timing side channel could leak here is which BUCKET a guess hashed into, and a
     * bucket is not a prefix of the code, so there is no hill for an attacker to climb one character at a
     * time the way a byte-by-byte compare of a guessable secret offers. The thing that actually protects 40
     * bits is the global failed-exchange rate limit on the route above, which bounds guesses per minute no
     * matter how the lookup is written — an O(n) constant-time scan here would buy nothing and add a second
     * thing to get wrong.
     */
    suspend fun redeem(value: String): String? = mutex.withLock {
        purgeExpired(now())
        outstanding.remove(normalizeTicketCode(value))?.boundToken
    }

    /** How many tickets are outstanding right now (expired ones swept first). For tests and diagnostics. */
    suspend fun outstandingCount(): Int = mutex.withLock {
        purgeExpired(now())
        outstanding.size
    }

    /**
     * Drop every ticket whose life has run out, as of [at]. A ticket is redeemable while `at < expiresAt`,
     * so one sampled at exactly its expiry instant is already gone — expiry is a deadline, not a grace.
     *
     * Called from every entry point rather than from a timer: there is no scheduler in the daemon to hang a
     * sweep off, and the map cannot grow between accesses anyway. Caller holds [mutex].
     */
    private fun purgeExpired(at: Long) {
        if (outstanding.isEmpty()) return
        val iterator = outstanding.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= at) iterator.remove()
        }
    }
}

/** Wall clock in epoch millis — the production [TicketStore.now]. */
private fun ticketEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
