package io.kotgent.mutex

import io.kotgent.core.SessionId
import kotlin.jvm.JvmInline

/** Travels in URL paths and argv, so it keeps the provider-id charset and a short bound. */
@JvmInline
value class MutexKey(val value: String) {
    init {
        require(value.length <= MAX_LENGTH && FORMAT.matches(value)) {
            "a mutex key is 1-$MAX_LENGTH characters: a letter or digit, then letters, digits, '.', '_' or '-'"
        }
    }

    companion object {
        const val MAX_LENGTH: Int = 64

        private val FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

        fun parseOrNull(raw: String): MutexKey? = runCatching { MutexKey(raw) }.getOrNull()
    }
}

@JvmInline
value class MutexToken(val value: String) {
    init {
        require(value.length <= OPAQUE_MAX_LENGTH && OPAQUE_FORMAT.matches(value)) { "malformed mutex token" }
    }

    companion object {
        fun parseOrNull(raw: String): MutexToken? = runCatching { MutexToken(raw) }.getOrNull()
    }
}

@JvmInline
value class WaitTicket(val value: String) {
    init {
        require(value.length <= OPAQUE_MAX_LENGTH && OPAQUE_FORMAT.matches(value)) { "malformed wait ticket" }
    }

    companion object {
        fun parseOrNull(raw: String): WaitTicket? = runCatching { WaitTicket(raw) }.getOrNull()
    }
}

private const val OPAQUE_MAX_LENGTH: Int = 64

private val OPAQUE_FORMAT = Regex("^[A-Za-z0-9_-]+$")

data class Holding(
    val key: MutexKey,
    val token: MutexToken,
    val sessionId: SessionId,
    val acquiredAt: Long,
)

/** [leaseUntil] counts only once [openPolls] drops to zero. A granted waiter reserves its key until it claims. */
data class Waiter(
    val ticket: WaitTicket,
    val key: MutexKey,
    val sessionId: SessionId,
    val enqueuedAt: Long,
    val openPolls: Int,
    val leaseUntil: Long,
    val grantedAt: Long? = null,
) {
    val granted: Boolean get() = grantedAt != null

    fun leasedAt(now: Long): Boolean = openPolls > 0 || now < leaseUntil
}

/** [waiters] is the global arrival order; each key's queue is its subsequence. */
data class MutexState(
    val holdings: Map<MutexKey, Holding> = emptyMap(),
    val waiters: List<Waiter> = emptyList(),
)

data class Step<out T>(val state: MutexState, val result: T, val grants: List<Waiter> = emptyList())

sealed interface AcquireOutcome {
    data class Acquired(val holding: Holding) : AcquireOutcome

    data class Queued(val waiter: Waiter) : AcquireOutcome
}

sealed interface ClaimOutcome {
    data class Acquired(val holding: Holding) : ClaimOutcome

    data class Waiting(val waiter: Waiter) : ClaimOutcome

    data object UnknownTicket : ClaimOutcome

    data object ForeignTicket : ClaimOutcome
}

/**
 * Every operation first expires lapsed leases, so no grant ever reaches a waiter nobody is polling for.
 * Operations never mint identifiers: callers pass the token or ticket an outcome may need.
 */
class MutexQueue(private val leaseMillis: Long = DEFAULT_LEASE_MILLIS) {

    fun acquire(
        state: MutexState,
        key: MutexKey,
        session: SessionId,
        now: Long,
        token: MutexToken,
        ticket: WaitTicket,
    ): Step<AcquireOutcome> {
        val live = expire(state, now)
        val current = live.state
        if (current.holdings[key] == null && current.waiters.none { it.key == key }) {
            val holding = Holding(key, token, session, now)
            val next = current.copy(holdings = current.holdings + (key to holding))
            return Step(next, AcquireOutcome.Acquired(holding), live.grants)
        }
        val waiter = Waiter(ticket, key, session, enqueuedAt = now, openPolls = 1, leaseUntil = now)
        return Step(current.copy(waiters = current.waiters + waiter), AcquireOutcome.Queued(waiter), live.grants)
    }

    /** Claims a granted ticket, or opens another poll on a ticket still waiting. */
    fun claim(
        state: MutexState,
        ticket: WaitTicket,
        session: SessionId,
        now: Long,
        token: MutexToken,
    ): Step<ClaimOutcome> {
        val live = expire(state, now)
        val current = live.state
        val waiter = current.waiters.firstOrNull { it.ticket == ticket }
            ?: return Step(current, ClaimOutcome.UnknownTicket, live.grants)
        if (waiter.sessionId != session) return Step(current, ClaimOutcome.ForeignTicket, live.grants)
        if (!waiter.granted) {
            val polled = waiter.copy(openPolls = waiter.openPolls + 1)
            return Step(current.replace(polled), ClaimOutcome.Waiting(polled), live.grants)
        }
        val holding = Holding(waiter.key, token, session, now)
        val next = MutexState(current.holdings + (waiter.key to holding), current.waiters - waiter)
        return Step(next, ClaimOutcome.Acquired(holding), live.grants)
    }

    fun closePoll(state: MutexState, ticket: WaitTicket, now: Long): Step<Unit> {
        val waiter = state.waiters.firstOrNull { it.ticket == ticket } ?: return Step(state, Unit)
        val openPolls = (waiter.openPolls - 1).coerceAtLeast(0)
        val leaseUntil = if (openPolls == 0) now + leaseMillis else waiter.leaseUntil
        val closed = waiter.copy(openPolls = openPolls, leaseUntil = leaseUntil)
        return Step(state.replace(closed), Unit)
    }

    fun release(state: MutexState, token: MutexToken, now: Long): Step<Holding?> {
        val holding = state.holdings.values.firstOrNull { it.token == token } ?: return Step(state, null)
        return freeing(state, now, listOf(holding), holding)
    }

    fun forceRelease(state: MutexState, key: MutexKey, now: Long): Step<Holding?> {
        val holding = state.holdings[key] ?: return Step(state, null)
        return freeing(state, now, listOf(holding), holding)
    }

    fun endSession(state: MutexState, session: SessionId, now: Long): Step<List<Holding>> {
        val holdings = state.holdings.values.filter { it.sessionId == session }
        val waiters = state.waiters.filter { it.sessionId == session }
        if (holdings.isEmpty() && waiters.isEmpty()) return Step(state, emptyList())
        val remaining = state.copy(waiters = state.waiters - waiters.toSet())
        return freeing(remaining, now, holdings, holdings)
    }

    /** Returns the waiters dropped because their lease ran out. */
    fun expire(state: MutexState, now: Long): Step<List<Waiter>> {
        val lapsed = state.waiters.filter { !it.leasedAt(now) }
        if (lapsed.isEmpty()) return Step(state, emptyList())
        val remaining = state.copy(waiters = state.waiters - lapsed.toSet())
        val granted = grantFree(remaining, now)
        return Step(granted.state, lapsed, granted.grants)
    }

    /** The earliest instant at which [expire] could change [state]. */
    fun nextDeadline(state: MutexState): Long? =
        state.waiters.filter { it.openPolls == 0 }.minOfOrNull { it.leaseUntil }

    /** 1-based place in its key's queue; a granted waiter is first. */
    fun position(state: MutexState, ticket: WaitTicket): Int? {
        val waiter = state.waiters.firstOrNull { it.ticket == ticket } ?: return null
        return state.waiters.filter { it.key == waiter.key }.indexOf(waiter) + 1
    }

    private fun <T> freeing(state: MutexState, now: Long, released: List<Holding>, result: T): Step<T> {
        val live = expire(state.copy(holdings = state.holdings - released.map { it.key }.toSet()), now)
        val granted = grantFree(live.state, now)
        return Step(granted.state, result, live.grants + granted.grants)
    }

    private fun grantFree(state: MutexState, now: Long): Step<Unit> {
        var waiters = state.waiters
        val grants = mutableListOf<Waiter>()
        for (key in waiters.map { it.key }.distinct()) {
            if (state.holdings[key] != null) continue
            val queue = waiters.filter { it.key == key }
            if (queue.any { it.granted }) continue
            val next = queue.firstOrNull { it.leasedAt(now) } ?: continue
            val skipped = queue.takeWhile { it !== next }.toSet()
            val granted = next.copy(grantedAt = now)
            waiters = waiters.mapNotNull { if (it in skipped) null else if (it === next) granted else it }
            grants += granted
        }
        return Step(state.copy(waiters = waiters), Unit, grants)
    }

    private fun MutexState.replace(waiter: Waiter): MutexState =
        copy(waiters = waiters.map { if (it.ticket == waiter.ticket) waiter else it })

    companion object {
        const val DEFAULT_LEASE_MILLIS: Long = 30_000L
    }
}
