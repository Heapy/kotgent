package io.kotgent.daemon

import io.kotgent.core.SessionId
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Called when a session's pane is gone. Callers hold per-session control locks or run reconciliation, so a
 * listener must only enqueue its work: it never suspends, blocks or throws into the caller.
 */
fun interface SessionEndListener {
    fun onSessionEnded(sessionId: SessionId)
}

@OptIn(ExperimentalAtomicApi::class)
class SessionEndListeners(private val onError: (Throwable) -> Unit = {}) {
    private val listeners = AtomicReference<List<SessionEndListener>>(emptyList())

    fun add(listener: SessionEndListener) {
        while (true) {
            val current = listeners.load()
            if (listeners.compareAndSet(current, current + listener)) return
        }
    }

    fun fire(sessionId: SessionId) {
        for (listener in listeners.load()) {
            try {
                listener.onSessionEnded(sessionId)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }
}
