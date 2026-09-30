package io.kotgent.store

import io.kotgent.core.SessionId
import io.kotgent.mutex.AcquireOutcome
import io.kotgent.mutex.ClaimOutcome
import io.kotgent.mutex.Holding
import io.kotgent.mutex.MutexKey
import io.kotgent.mutex.MutexQueue
import io.kotgent.mutex.MutexState
import io.kotgent.mutex.MutexToken
import io.kotgent.mutex.Step
import io.kotgent.mutex.WaitTicket
import io.kotgent.mutex.Waiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class MutexEntry(val key: MutexKey, val holding: Holding?, val waiters: List<Waiter>)

/** [rev] is monotonic across restarts; entries are ordered by key and each waiter list by arrival. */
data class MutexListing(val rev: Long, val entries: List<MutexEntry>)

sealed interface MutexAcquireResult {
    data class Acquired(val holding: Holding) : MutexAcquireResult

    data class Pending(val ticket: WaitTicket, val position: Int) : MutexAcquireResult

    /** The ticket expired, was dropped at session end, or never existed. */
    data object UnknownTicket : MutexAcquireResult

    /** The ticket belongs to another session or another key. */
    data object ForeignTicket : MutexAcquireResult
}

sealed interface MutexReleaseResult {
    data class Released(val holding: Holding) : MutexReleaseResult

    data object NotHeld : MutexReleaseResult

    data class HeldByAnotherSession(val holding: Holding) : MutexReleaseResult
}

interface MutexStore {
    val listing: StateFlow<MutexListing>

    /**
     * Long-polls up to [wait]. Without [ticket] the caller joins the key's queue; with one it continues the
     * same place. The poll holds the waiter's lease while open and starts its countdown when it ends.
     */
    suspend fun acquire(key: MutexKey, session: SessionId, ticket: WaitTicket?, wait: Duration): MutexAcquireResult

    suspend fun release(token: MutexToken, session: SessionId): MutexReleaseResult

    suspend fun forceRelease(key: MutexKey): Holding?

    /** Never blocks: session-end listeners run on hook, reconciliation and control paths. */
    fun sessionEnded(session: SessionId)

    /** Applies lease expiry at the store clock's current time; the store also does this on its own timer. */
    suspend fun expire()
}

/** Holdings are the only durable part; waiters die with the daemon along with every long-poll. */
interface MutexRows {
    fun load(): PersistedMutexes

    /** Must apply atomically: [released] and [acquired] may name the same key. */
    fun commit(released: List<Holding>, acquired: List<Holding>, rev: Long)
}

class PersistedMutexes(val holdings: List<Holding>, val rev: Long)

/**
 * The single owner of mutex state: the in-memory waiter queue and the persisted holdings change together
 * under one lock, rows first, so a failed write leaves memory unchanged.
 */
class MutexCoordinator(
    private val rows: MutexRows,
    scope: CoroutineScope,
    private val now: () -> Long,
    leaseMillis: Long = MutexQueue.DEFAULT_LEASE_MILLIS,
    private val newToken: () -> MutexToken = { MutexToken(randomOpaqueId()) },
    private val newTicket: () -> WaitTicket = { WaitTicket(randomOpaqueId()) },
    private val onError: (Throwable) -> Unit = {},
) : MutexStore {
    private val queue = MutexQueue(leaseMillis)
    private val lock = Mutex()
    private val ended = Channel<SessionId>(Channel.UNLIMITED)
    private val state: MutableStateFlow<MutexState>
    private var rev: Long

    override val listing: StateFlow<MutexListing>
        field: MutableStateFlow<MutexListing>

    init {
        val persisted = rows.load()
        state = MutableStateFlow(MutexState(holdings = persisted.holdings.associateBy { it.key }))
        rev = persisted.rev
        listing = MutableStateFlow(listingOf(state.value, rev))
        scope.launch {
            for (session in ended) {
                guarded {
                    lock.withLock {
                        val _ = commit(queue.endSession(state.value, session, now()))
                    }
                }
            }
        }
        scope.launch { expireOnDeadlines() }
    }

    override suspend fun acquire(
        key: MutexKey,
        session: SessionId,
        ticket: WaitTicket?,
        wait: Duration,
    ): MutexAcquireResult {
        val polled = lock.withLock {
            if (ticket == null) {
                val step = queue.acquire(state.value, key, session, now(), newToken(), newTicket())
                when (val outcome = commit(step)) {
                    is AcquireOutcome.Acquired -> return MutexAcquireResult.Acquired(outcome.holding)
                    is AcquireOutcome.Queued -> outcome.waiter.ticket
                }
            } else {
                val waiter = state.value.waiters.firstOrNull { it.ticket == ticket }
                if (waiter != null && waiter.key != key) return MutexAcquireResult.ForeignTicket
                when (val outcome = commit(queue.claim(state.value, ticket, session, now(), newToken()))) {
                    is ClaimOutcome.Acquired -> return MutexAcquireResult.Acquired(outcome.holding)
                    is ClaimOutcome.Waiting -> ticket
                    ClaimOutcome.UnknownTicket -> return MutexAcquireResult.UnknownTicket
                    ClaimOutcome.ForeignTicket -> return MutexAcquireResult.ForeignTicket
                }
            }
        }
        var pollOpen = true
        try {
            val settled = withTimeoutOrNull(wait) {
                state.first { current -> current.waiters.none { it.ticket == polled && !it.granted } }
            }
            return lock.withLock {
                pollOpen = false
                if (settled == null) {
                    commit(queue.closePoll(state.value, polled, now()))
                    val position = queue.position(state.value, polled)
                    return@withLock if (position == null) {
                        MutexAcquireResult.UnknownTicket
                    } else {
                        MutexAcquireResult.Pending(polled, position)
                    }
                }
                when (val outcome = commit(queue.claim(state.value, polled, session, now(), newToken()))) {
                    is ClaimOutcome.Acquired -> MutexAcquireResult.Acquired(outcome.holding)
                    is ClaimOutcome.Waiting -> {
                        // The claim opened a second poll on top of this request's own; close both.
                        commit(queue.closePoll(state.value, polled, now()))
                        commit(queue.closePoll(state.value, polled, now()))
                        MutexAcquireResult.Pending(polled, queue.position(state.value, polled) ?: 1)
                    }
                    ClaimOutcome.UnknownTicket -> MutexAcquireResult.UnknownTicket
                    ClaimOutcome.ForeignTicket -> MutexAcquireResult.ForeignTicket
                }
            }
        } finally {
            if (pollOpen) {
                withContext(NonCancellable) {
                    lock.withLock { commit(queue.closePoll(state.value, polled, now())) }
                }
            }
        }
    }

    override suspend fun release(token: MutexToken, session: SessionId): MutexReleaseResult = lock.withLock {
        val holding = state.value.holdings.values.firstOrNull { it.token == token }
            ?: return@withLock MutexReleaseResult.NotHeld
        if (holding.sessionId != session) return@withLock MutexReleaseResult.HeldByAnotherSession(holding)
        val _ = commit(queue.release(state.value, token, now()))
        MutexReleaseResult.Released(holding)
    }

    override suspend fun forceRelease(key: MutexKey): Holding? = lock.withLock {
        commit(queue.forceRelease(state.value, key, now()))
    }

    override fun sessionEnded(session: SessionId) {
        val _ = ended.trySend(session)
    }

    override suspend fun expire(): Unit = lock.withLock {
        val _ = commit(queue.expire(state.value, now()))
    }

    private suspend fun expireOnDeadlines() {
        while (true) {
            val observed = state.value
            val deadline = queue.nextDeadline(observed)
            if (deadline == null) {
                val _ = state.first { it !== observed }
                continue
            }
            val remaining = deadline - now()
            if (remaining > 0) {
                val changed = withTimeoutOrNull(remaining.milliseconds) { state.first { it !== observed } }
                if (changed != null) continue
            }
            guarded { expire() }
        }
    }

    private fun <T> commit(step: Step<T>): T {
        val before = state.value
        val after = step.state
        if (after === before) return step.result
        val released = before.holdings.values.filter { after.holdings[it.key]?.token != it.token }
        val acquired = after.holdings.values.filter { before.holdings[it.key]?.token != it.token }
        val visible = released.isNotEmpty() || acquired.isNotEmpty() ||
            before.visibleWaiters() != after.visibleWaiters()
        val nextRev = if (visible) rev + 1 else rev
        if (visible) rows.commit(released, acquired, nextRev)
        rev = nextRev
        state.value = after
        if (visible) listing.value = listingOf(after, nextRev)
        return step.result
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
}

private fun MutexState.visibleWaiters(): List<Pair<WaitTicket, Boolean>> = waiters.map { it.ticket to it.granted }

private fun listingOf(state: MutexState, rev: Long): MutexListing {
    val keys = (state.holdings.keys + state.waiters.map { it.key }).sortedBy { it.value }
    return MutexListing(
        rev = rev,
        entries = keys.map { key -> MutexEntry(key, state.holdings[key], state.waiters.filter { it.key == key }) },
    )
}

fun randomOpaqueId(random: Random = Random.Default): String =
    random.nextBytes(OPAQUE_ID_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val OPAQUE_ID_BYTES: Int = 16
