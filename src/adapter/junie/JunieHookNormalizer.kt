package io.kotgent.adapter.junie

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Normalizes Junie hooks. `StopFailure` completes the turn because Junie's TUI has returned to idle.
 * `SessionStart` usually lacks an id; when present, Junie's non-UUID id uses ProviderSessionId's safe
 * charset. `SessionEnd` has no status and is treated as a normal exit.
 */
object JunieHookNormalizer {
    private const val FIELD_TOOL_NAME = "tool_name"

    private const val FIELD_SESSION_ID = "session_id"

    private const val FIELD_REASON = "reason"

    const val UNKNOWN_TOOL: String = "unknown"

    const val UNKNOWN_EXIT: Int = 0

    fun normalize(hookEventName: String, payload: JsonElement, paneId: PaneId): AgentEvent? =
        when (hookEventName) {
            JunieHookConfig.USER_PROMPT_SUBMIT -> AgentEvent.TurnStarted
            JunieHookConfig.PRE_TOOL_USE ->
                AgentEvent.ToolCall(payload.stringField(FIELD_TOOL_NAME) ?: UNKNOWN_TOOL)
            JunieHookConfig.PERMISSION_REQUEST -> AgentEvent.ApprovalRequested(approvalId(payload, paneId))
            JunieHookConfig.STOP, JunieHookConfig.STOP_FAILURE -> AgentEvent.TurnCompleted
            JunieHookConfig.SESSION_START -> sessionBound(payload)
            JunieHookConfig.SESSION_END -> AgentEvent.Exited(UNKNOWN_EXIT)
            else -> null
        }

    /** This is a display label; hook approvals have no resolution correlation key. */
    private fun approvalId(payload: JsonElement, paneId: PaneId): String =
        payload.stringField(FIELD_TOOL_NAME)
            ?: payload.stringField(FIELD_REASON)
            ?: "permission@${paneId.value}"

    private fun sessionBound(payload: JsonElement): AgentEvent? {
        val raw = payload.stringField(FIELD_SESSION_ID) ?: return null
        return runCatching { ProviderSessionId(raw) }.getOrNull()?.let(AgentEvent::SessionBound)
    }

    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }
}
