package io.kotgent.push

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.store.SessionUpdate

/**
 * Converts repeated session levels into false-to-true attention edges. One notifier owns and confines
 * each unsynchronized instance to its collector coroutine.
 */
class AttentionTracker {
    private val waiting = mutableMapOf<SessionId, Boolean>()

    /** Establishes the restart baseline so already-waiting sessions do not notify again. */
    fun seed(sessions: List<SessionMeta>) {
        waiting.clear()
        for (meta in sessions) waiting[meta.id] = meta.isWaiting()
    }

    fun isNewAttention(update: SessionUpdate): Boolean {
        val now = update.isWaiting()
        val before = waiting.put(update.sessionId, now) ?: false
        return now && !before
    }
}

/** Mirrors the service worker's filter so every push has a visible session to show. */
private fun SessionMeta.isWaiting(): Boolean = state.needsAttention && !archived

private fun SessionUpdate.isWaiting(): Boolean = state.needsAttention && !archived
