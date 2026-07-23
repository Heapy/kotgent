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
 * weight: it is single-use ([redeem] returns `true` at most once per value), and it expires
 * ([TICKET_TTL_MILLIS]). Both are enforced here rather than at the route, so no route can forget them.
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

    /** value → the instant it stops being redeemable. Swept on every access ([purgeExpired]). */
    private val outstanding = mutableMapOf<String, Long>()

    /**
     * Mint a ticket: [SECRET_BYTES] of entropy from the daemon's one entropy source ([randomBytes]),
     * hex-encoded — the same strength as the master token itself, because for its ten minutes it grants the
     * same access.
     */
    suspend fun issue(): Ticket = mutex.withLock {
        val at = now()
        purgeExpired(at)
        val ticket = Ticket(value = hex(randomBytes(SECRET_BYTES)), expiresAt = at + ttlMillis)
        outstanding[ticket.value] = ticket.expiresAt
        ticket
    }

    /**
     * Spend [value]: `true` exactly once, for a ticket that was issued here and has not expired; `false` for
     * an unknown value, a replay, or one whose TTL has run out.
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
    suspend fun redeem(value: String): Boolean = mutex.withLock {
        purgeExpired(now())
        outstanding.remove(value) != null
    }

    /** How many tickets are outstanding right now (expired ones swept first). For tests and diagnostics. */
    suspend fun outstandingCount(): Int = mutex.withLock {
        purgeExpired(now())
        outstanding.size
    }

    /**
     * Drop EVERY outstanding ticket at once — the "revoke all" half of a master-token rotation.
     *
     * An unredeemed ticket is a pending browser credential (a QR code or sign-in link that has been handed
     * out but not yet spent). Rotation's whole promise is "revoke every browser credential at once" — cookies
     * stop verifying the instant the HMAC key changes — but a ticket is redeemed AFTER the fact and signs its
     * cookie with whatever token is current at redeem time, so a ticket minted before the rotation would
     * otherwise still exchange into a valid cookie under the NEW token for up to [TICKET_TTL_MILLIS]. That is
     * exactly the window an operator rotates to close (a shoulder-surfed or intercepted link), so
     * `/auth/rotate` calls this as part of the rotation. Under the same [mutex] as [issue]/[redeem] so a
     * concurrent redeem cannot slip a pre-rotation ticket through mid-clear.
     */
    suspend fun invalidateAll() = mutex.withLock {
        outstanding.clear()
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
            if (iterator.next().value <= at) iterator.remove()
        }
    }
}

/** Wall clock in epoch millis — the production [TicketStore.now]. */
private fun ticketEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
