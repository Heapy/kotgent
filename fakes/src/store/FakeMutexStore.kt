package io.kotgent.store

import io.kotgent.mutex.Holding
import io.kotgent.mutex.MutexQueue
import io.kotgent.mutex.MutexToken
import io.kotgent.mutex.WaitTicket
import kotlinx.coroutines.CoroutineScope

/** The real queue coordinator over in-memory rows, so fixtures exercise the same grant and lease rules. */
class FakeMutexStore private constructor(
    private val rows: MemoryMutexRows,
    scope: CoroutineScope,
    now: () -> Long,
    leaseMillis: Long,
    newToken: () -> MutexToken,
    newTicket: () -> WaitTicket,
) : MutexStore by MutexCoordinator(rows, scope, now, leaseMillis, newToken, newTicket) {

    constructor(
        scope: CoroutineScope,
        now: () -> Long,
        seeded: List<Holding> = emptyList(),
        leaseMillis: Long = MutexQueue.DEFAULT_LEASE_MILLIS,
        newToken: () -> MutexToken = { MutexToken(randomOpaqueId()) },
        newTicket: () -> WaitTicket = { WaitTicket(randomOpaqueId()) },
    ) : this(MemoryMutexRows(seeded), scope, now, leaseMillis, newToken, newTicket)

    /** What a restart would load. */
    fun persistedHoldings(): List<Holding> = rows.load().holdings
}

private class MemoryMutexRows(seeded: List<Holding>) : MutexRows {
    private val holdings = LinkedHashMap<String, Holding>().apply { for (h in seeded) put(h.token.value, h) }
    private var rev = if (seeded.isEmpty()) 0L else 1L

    override fun load(): PersistedMutexes = PersistedMutexes(holdings.values.sortedBy { it.key.value }, rev)

    override fun commit(released: List<Holding>, acquired: List<Holding>, rev: Long) {
        for (holding in released) {
            val _ = holdings.remove(holding.token.value)
        }
        for (holding in acquired) holdings[holding.token.value] = holding
        this.rev = maxOf(this.rev, rev)
    }
}
