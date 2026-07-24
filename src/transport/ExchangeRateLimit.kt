package io.kotgent.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The guessing budget on `POST /auth/exchange` — the compensating control that pays for the login code
 * being [TICKET_CODE_LENGTH] typable symbols instead of 32 bytes of hex.
 *
 * ## What it actually protects
 * A ticket carries 40 bits ([TICKET_CODE_BYTES]). Offline that is nothing; the only way to spend a guess,
 * though, is to send it to this daemon as a `POST /auth/exchange` and be told whether it worked. So the
 * value's real strength is `guesses per unit time × its lifetime`, not its bit length: at
 * [EXCHANGE_FAILURE_LIMIT] failures per [EXCHANGE_WINDOW_MILLIS] an attacker gets a few hundred tries
 * inside a ticket's five-minute [TICKET_TTL_MILLIS], against 2^40 values — a chance around 1 in 10^9 per
 * outstanding ticket, and the ticket dies on its first successful use anyway. Remove this limiter and that
 * argument collapses: an unthrottled attacker walks the whole 40-bit space in an afternoon.
 *
 * ## Why the counter is GLOBAL and not per-IP
 * There is no usable client identity here. The daemon binds `127.0.0.1` and the public surface arrives
 * through cloudflared, which connects to it FROM loopback — so every tunnelled request, from every phone
 * anywhere in the world, presents the same peer address. A per-IP bucket would therefore be one bucket
 * that everything shares (i.e. exactly this, with extra machinery), or a bucket keyed on a header the
 * client controls (i.e. no limit at all). The same reasoning that makes [loopbackOnly] a `Host` check and
 * not a peer-address check applies here.
 *
 * ## The trade-off, stated plainly
 * A global counter means anyone who can reach `/auth/exchange` can DENY sign-in: [EXCHANGE_FAILURE_LIMIT]
 * deliberate wrong codes a minute keep the limiter saturated, and the operator's own correct code is
 * refused with a `429` for as long as the attacker keeps it up. That is accepted, for three reasons:
 *  - the alternative is worse — unbounded guessing against 40 bits is a real credential compromise, while
 *    this is a temporary denial of a flow that has a loopback fallback (`kotgent web` on the machine
 *    itself, and the CLI's `Bearer`, are not on this route at all);
 *  - the public surface sits behind Cloudflare Access, so reaching this route at all already requires
 *    passing an identity provider;
 *  - the window is rolling and short, so the denial ends [EXCHANGE_WINDOW_MILLIS] after the attacker stops
 *    — there is no lockout state to clear and nothing an operator has to un-stick.
 *
 * ## What counts against the budget
 * Only a FAILED redemption ([TicketStore.redeem] returning `null`: unknown, replayed or expired). A
 * successful exchange consumes nothing — an operator signing several devices in must never walk into the
 * limit — and neither does a request refused earlier by the `Host`/`Origin` gate or one with an
 * unparseable body, because neither of those ever names a candidate code and so neither is a guess.
 *
 * ## Concurrency
 * [Mutex]-guarded, like [TicketStore]: route handlers run concurrently on the CIO engine, and both entry
 * points here are read-modify-writes over the same list. A plain field would be a data race, and a limiter
 * that miscounts under exactly the concurrency an attacker would use is not a limiter.
 *
 * One instance per daemon — it is a parameter of [authRoutes] with a default rather than something the
 * handler constructs, because a per-call instance would count to one and start over on every request:
 * a silent no-op that no unit test of this class would ever notice.
 *
 * @param now injected clock (epoch millis) so the window can be asserted by moving a variable instead of
 *   sleeping a minute; `kotlin.system.getTimeMillis` is ERROR-level deprecated, hence [Clock].
 * @param max how many failures may be recorded inside one window before attempts are refused.
 * @param windowMillis the width of the rolling window.
 */
class ExchangeRateLimit(
    private val now: () -> Long = ::rateLimitEpochMillis,
    private val max: Int = EXCHANGE_FAILURE_LIMIT,
    private val windowMillis: Long = EXCHANGE_WINDOW_MILLIS,
) {
    init {
        require(max > 0) { "the failed-exchange budget must be positive, got $max" }
        require(windowMillis > 0) { "the rate-limit window must be positive, got $windowMillis ms" }
    }

    private val mutex = Mutex()

    /**
     * When each still-counting failure happened, oldest first. Append-only at the tail and pruned from the
     * head, which is what makes an [ArrayDeque] the right shape: entries leave in the order they arrived.
     */
    private val failures = ArrayDeque<Long>()

    /**
     * May another exchange attempt proceed? `false` once [max] failures have been recorded inside the
     * current window.
     *
     * Consumes nothing on its own — the budget is spent by [recordFailure], so a caller that goes on to
     * succeed leaves the limiter exactly as it found it.
     */
    suspend fun allow(): Boolean = mutex.withLock {
        val at = now()
        pruneAged(at)
        failures.size < max
    }

    /**
     * Charge one failed exchange to the current window.
     *
     * Called only after [allow] has already said yes, so the list cannot grow without bound: it is capped
     * at [max] plus however many attempts were in flight concurrently when the cap was reached.
     */
    suspend fun recordFailure() = mutex.withLock {
        val at = now()
        pruneAged(at)
        failures.addLast(at)
    }

    /** How many failures still count against the budget right now (aged ones pruned). Tests and diagnostics. */
    suspend fun failuresInWindow(): Int = mutex.withLock {
        pruneAged(now())
        failures.size
    }

    /**
     * Drop every failure that has aged out as of [at]. A failure recorded at `t` counts while
     * `at - t < windowMillis`, so one sampled exactly [windowMillis] later is already gone — the window is
     * half-open, matching how [TicketStore] treats an expiry instant.
     *
     * The list is ordered by insertion (the clock only moves forward within a run), so pruning stops at the
     * first entry that is still young. Caller holds [mutex].
     */
    private fun pruneAged(at: Long) {
        while (failures.isNotEmpty() && at - failures.first() >= windowMillis) failures.removeFirst()
    }
}

/**
 * How many failed exchanges the daemon tolerates inside one [EXCHANGE_WINDOW_MILLIS] before refusing.
 *
 * Ten is far above what any honest flow produces — the page spends its ticket once, and a human typing a
 * code into the Task-15 form gets a handful of mistypes at most — and far below what makes 40 bits
 * approachable (see the class KDoc's arithmetic).
 */
const val EXCHANGE_FAILURE_LIMIT: Int = 10

/** The width of the rolling window the failures are counted in: one minute. */
const val EXCHANGE_WINDOW_MILLIS: Long = 60_000L

/** Wall clock in epoch millis — the production [ExchangeRateLimit.now]. */
private fun rateLimitEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
