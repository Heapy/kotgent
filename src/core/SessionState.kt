package io.kotgent.core

/**
 * The 7 canonical session states — the projection the reducer (Task 6) computes by folding the
 * [AgentEvent] log. Constants are named exactly as the plan / `sessions.state` column spell them
 * (lower_snake_case) so [SessionState.name] IS the stored value with no mapping layer.
 *
 * Grouping is intrinsic to the model, so it lives here rather than being re-derived per caller:
 * [ALIVE] vs [DEAD] partition all 7 states, and [NEEDS_ATTENTION] (via [needsAttention]) is the
 * subset the UI surfaces as "needs attention".
 */
enum class SessionState {
    /** Agent process is live and working. */
    running,

    /** Live but blocked asking the human to approve an action. Needs attention. */
    needs_approval,

    /**
     * FORWARD-MODELED. A real state — the agent asked a question and is blocked on an answer —
     * but the v1 Claude adapter never produces it: interactive Claude gives no "asked a
     * question and is waiting" signal. It is kept in the enum (and in [NEEDS_ATTENTION]) so the
     * reducer and UI model it today and a future adapter (a richer Claude signal, or the Codex
     * app-server) can drive it without a schema or state-set change.
     */
    needs_answer,

    /** Live and idle — finished its turn, waiting for the next prompt. */
    ready,

    /** Dead: the agent process exited cleanly (`Exited(0)`). */
    stopped,

    /** Dead: the agent process exited abnormally (`Exited(!=0)`) or was lost. */
    crashed,

    /** Dead, but the conversation transcript survives, so `resume` can revive it. */
    resumable;

    /** The agent process is live in tmux (whether or not it needs attention). */
    val isAlive: Boolean get() = this in ALIVE

    /** The agent process is not live (clean exit, crash, or resumable-from-transcript). */
    val isDead: Boolean get() = this in DEAD

    /** The session is blocked waiting on the human — an approval or (forward-modeled) an answer. */
    val needsAttention: Boolean get() = this in NEEDS_ATTENTION

    companion object {
        /** Live states: the agent process is running. Complement of [DEAD]. */
        val ALIVE: Set<SessionState> = setOf(running, needs_approval, needs_answer, ready)

        /** Dead states: the agent process is not running. Complement of [ALIVE]. */
        val DEAD: Set<SessionState> = setOf(stopped, crashed, resumable)

        /** States that require human input to unblock. Always a subset of [ALIVE]. */
        val NEEDS_ATTENTION: Set<SessionState> = setOf(needs_approval, needs_answer)
    }
}
