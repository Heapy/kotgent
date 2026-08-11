package io.kotgent.core

/**
 * Canonical states. Lowercase names are persisted directly in `sessions.state`; [ALIVE] and [DEAD]
 * partition the enum.
 */
enum class SessionState {
    running,

    needs_approval,

    /** Forward-modeled; current provider hooks cannot detect this state. */
    needs_answer,

    ready,

    stopped,

    crashed,

    resumable;

    val isAlive: Boolean get() = this in ALIVE

    val isDead: Boolean get() = this in DEAD

    val needsAttention: Boolean get() = this in NEEDS_ATTENTION

    companion object {
        val ALIVE: Set<SessionState> = setOf(running, needs_approval, needs_answer, ready)

        val DEAD: Set<SessionState> = setOf(stopped, crashed, resumable)

        val NEEDS_ATTENTION: Set<SessionState> = setOf(needs_approval, needs_answer)
    }
}
