package io.kotgent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider-neutral events in the append-only session log. The explicit [SerialName] values are
 * persisted in `events.type` and must remain stable.
 */
@Serializable
sealed class AgentEvent {

    @Serializable
    @SerialName("turn_started")
    data object TurnStarted : AgentEvent()

    @Serializable
    @SerialName("turn_completed")
    data object TurnCompleted : AgentEvent()

    /** Providers without a resolution signal clear pending approvals on the next running event. */
    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(val approvalId: String) : AgentEvent()

    /** `approved` is null when resolution is observed but the decision is not. */
    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        val approvalId: String,
        val approved: Boolean? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val name: String) : AgentEvent()

    @Serializable
    @SerialName("exited")
    data class Exited(val code: Int) : AgentEvent()

    @Serializable
    @SerialName("session_bound")
    data class SessionBound(val providerSessionId: ProviderSessionId) : AgentEvent()
}

/** Lowercase enum names are persisted directly in `events.source` and `sessions.state_source`. */
@Serializable
enum class EventSource {
    hook,
    jsonl,
    appserver,
    liveness,
    user,
    system,
}
