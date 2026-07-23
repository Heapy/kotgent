package io.kotgent.transport

import io.kotgent.crypto.hex
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
 * A ticket is therefore a BEARER of full daemon access for its short life. Two properties carry the whole
 * weight: it is single-use ([redeem] hands back its bound token at most once per value, `null` thereafter),
 * and it expires ([TICKET_TTL_MILLIS]). Both are enforced here rather than at the route, so no route can
 * forget them.
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
 * A ticket lives ten minutes. Persisting it would mean a schema migration and a table that has to be swept,
 * to buy the ability to redeem — after a daemon restart — a ticket whose whole purpose is to be redeemed
 * immediately. Losing outstanding tickets on restart is the CORRECT behaviour, not a limitation: the
 * operator reissues with `kotgent web`, which costs one command.
 *
 * ## Why no cap on the map
 * Deliberately unbounded. The TTL already bounds growth (only tickets issued in the last ten minutes are
 * kept, and every access sweeps the expired ones), and issuing requires the master token — an attacker who
 * could flood this already holds the key to everything. A cap with eviction would trade that non-problem
 * for a real one: the operator's ticket silently stops working because someone (possibly they themselves,
 * in another tab) issued a few more, with no way to tell that apart from a genuine expiry.
 */

/** How long a freshly issued ticket stays redeemable: ten minutes. */
const val TICKET_TTL_MILLIS: Long = 10L * 60 * 1000

/**
 * A minted ticket: the [value] to put in the login URL's fragment, and the [expiresAt] wall-clock instant
 * (epoch millis) it stops being redeemable — the deadline [TicketStore] sweeps against, echoed back in the
 * ticket response for any client that wants to show how long the link is good for (the QR dialog states the
 * fixed ten-minute TTL in its copy rather than reading this).
 */
data class Ticket(val value: String, val expiresAt: Long)

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
     * Mint a ticket: [SECRET_BYTES] of entropy from the daemon's one entropy source ([randomBytes]),
     * hex-encoded — the same strength as the master token itself, because for its ten minutes it grants the
     * same access.
     *
     * [boundToken] is the master token live at this instant; it is stored on the ticket and returned by
     * [redeem] so the exchange signs its cookie with the token that was current at MINT time, not at redeem
     * time (see the class KDoc on why this is what makes rotation revoke by construction).
     */
    suspend fun issue(boundToken: String): Ticket = mutex.withLock {
        val at = now()
        purgeExpired(at)
        val ticket = Ticket(value = hex(randomBytes(SECRET_BYTES)), expiresAt = at + ttlMillis)
        outstanding[ticket.value] = Outstanding(ticket.expiresAt, boundToken)
        ticket
    }

    /**
     * Spend [value]: return the ticket's issue-time [boundToken][Outstanding.boundToken] exactly once, for a
     * ticket that was issued here and has not expired; `null` for an unknown value, a replay, or one whose TTL
     * has run out. Single-use, TTL and sweep semantics are unchanged — only the success value differs (the
     * bound token instead of a Boolean), so the exchange can sign with the token that was live at MINT time.
     *
     * Expiry is decided in ONE place — the sweep runs first, so whatever is still in the map is live by the
     * current clock and the redemption is a plain "was it there?". Once a sweep has OBSERVED a ticket past its
     * expiry it is gone, so a clock that then steps backwards (NTP, a laptop waking up) cannot bring that one
     * back. The gap this does not cover is a ticket whose expiry passed with no intervening access at all: if
     * the clock steps back below its `expiresAt` before any sweep sees it, it is still redeemable. That is not
     * exploitable — minting a ticket needs the master token in the first place — but it is why this is a
     * best-effort sweep, not a monotonic guarantee.
     *
     * A plain map lookup rather than a [constantTimeEquals] scan: the values are 256 bits of entropy, so the
     * timing of a hash lookup cannot be walked into a forgery the way a byte-by-byte string compare of a
     * guessable secret could, and an O(n) constant-time scan would only add a second thing to get wrong.
     */
    suspend fun redeem(value: String): String? = mutex.withLock {
        purgeExpired(now())
        outstanding.remove(value)?.boundToken
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
