package io.kotgent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The canonical, provider-neutral agent event (v1) — the append-only spine of the
 * event-sourcing model. An [io.kotgent.adapter] normalizes its provider-specific signals
 * (Claude hooks, later Codex rollout-JSONL / app-server) into this closed hierarchy; the
 * reducer (Task 6) folds a log of these into a [SessionState] projection. Because state is a
 * pure function of the log, the event shapes must stay stable — hence the explicit,
 * snake_case [SerialName] discriminators, which double as the `events.type` column value.
 *
 * v1 subtypes are exactly: [TurnStarted], [TurnCompleted], [ApprovalRequested],
 * [ApprovalResolved], [ToolCall], [Exited], [SessionBound]. `QuestionAsked` / `QuestionAnswered`
 * are deliberately NOT here — they are backlog (see plan `needs_answer` is forward-modeled).
 *
 * Fields are kept minimal but sufficient for the reducer to compute state and the pending
 * approval count; richer telemetry (tool arguments, timings, transcripts) is backlog.
 */
@Serializable
sealed class AgentEvent {

    /** The agent began working on a turn (Claude `UserPromptSubmit`) — drives `running`. */
    @Serializable
    @SerialName("turn_started")
    data object TurnStarted : AgentEvent()

    /** The agent finished its turn and is idle (Claude `Stop`) — drives `ready`. */
    @Serializable
    @SerialName("turn_completed")
    data object TurnCompleted : AgentEvent()

    /**
     * The agent is blocked asking the human to approve an action (Claude `Notification`) —
     * drives `needs_approval`. [approvalId] correlates the request with a future
     * [ApprovalResolved]; the v1 Claude slice has no "permission answered" signal, so the
     * reducer instead clears pending approvals on the next entry into `running`.
     */
    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(val approvalId: String) : AgentEvent()

    /**
     * A previously requested approval was resolved. Not emitted by the v1 Claude adapter
     * (there is no such hook) — it is the forward-modeled seam a richer adapter/UI will use.
     */
    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(val approvalId: String, val approved: Boolean) : AgentEvent()

    /**
     * A tool invocation observed (Claude `PostToolUse`). Counts as re-entering `running`, so
     * per the "no permission answered" rule it also clears any pending approvals.
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val name: String) : AgentEvent()

    /** The agent process exited. `0` -> `stopped`, non-zero -> `crashed`. */
    @Serializable
    @SerialName("exited")
    data class Exited(val code: Int) : AgentEvent()

    /**
     * The provider's session id was captured (preallocated `--session-id`, or the
     * `SessionStart` hook fallback). Persisted so `resume` can address the conversation.
     */
    @Serializable
    @SerialName("session_bound")
    data class SessionBound(val providerSessionId: ProviderSessionId) : AgentEvent()
}

/**
 * Provenance of an event / of the current state — the `events.source` and
 * `sessions.state_source` column values. Serialized by its (lower-case) constant name, so the
 * enum name IS the stored/wire value with no mapping:
 *
 * - [hook]      — an agent CLI hook callback (Claude hooks POSTing `/hooks/claude`).
 * - [jsonl]     — a provider transcript / rollout-JSONL watcher (Codex; backlog).
 * - [appserver] — a provider app-server stream (backlog).
 * - [liveness]  — a reconciliation/liveness probe (tmux pane alive, exit detection).
 * - [user]      — an explicit user/operator action (input, stop, interrupt).
 * - [system]    — kotgent itself (daemon bootstrap, provider-id preallocation).
 */
@Serializable
enum class EventSource {
    hook,
    jsonl,
    appserver,
    liveness,
    user,
    system,
}
