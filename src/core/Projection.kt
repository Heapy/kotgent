package io.kotgent.core

/**
 * Reducer read model. Every transition preserves `pendingApprovals > 0` iff
 * `state == needs_approval`.
 */
data class Projection(
    val state: SessionState,
    val pendingApprovals: Int,
    val lastSeq: Seq,
    val providerSessionId: ProviderSessionId?,
    /**
     * Distinguishes an operator stop from a crash when the resulting process exit is non-zero.
     * Consumed by `Exited` and `Resume`.
     */
    val stopRequested: Boolean,
) {
    init {
        require(pendingApprovals >= 0) { "pendingApprovals must be non-negative, was $pendingApprovals" }
    }

    val needsAttention: Boolean get() = state.needsAttention

    fun unread(readCursor: Seq): Long = unread(lastSeq.value, readCursor.value)

    fun hasUnread(readCursor: Seq): Boolean = lastSeq > readCursor

    companion object {
        /** Sessions enter the log only after launch, so the no-event fold identity is live. */
        val EMPTY: Projection = Projection(
            state = SessionState.running,
            pendingApprovals = 0,
            lastSeq = Seq(0),
            providerSessionId = null,
            stopRequested = false,
        )
    }
}

fun unread(lastSeq: Long, readCursor: Long): Long = (lastSeq - readCursor).coerceAtLeast(0)
