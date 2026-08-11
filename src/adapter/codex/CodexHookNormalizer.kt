package io.kotgent.adapter.codex

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.isCanonicalUuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Normalizes Codex hooks. `SessionEnd` has no status, so it is treated as a normal exit; vanished panes
 * are classified separately by reconciliation.
 */
object CodexHookNormalizer {
    private const val FIELD_TOOL_NAME = "tool_name"

    private const val FIELD_SESSION_ID = "session_id"

    private const val FIELD_REASON = "reason"

    const val UNKNOWN_TOOL: String = "unknown"

    const val UNKNOWN_EXIT: Int = 0

    fun normalize(hookEventName: String, payload: JsonElement, paneId: PaneId): AgentEvent? =
        when (hookEventName) {
            CodexHookConfig.USER_PROMPT_SUBMIT -> AgentEvent.TurnStarted
            CodexHookConfig.POST_TOOL_USE ->
                AgentEvent.ToolCall(payload.stringField(FIELD_TOOL_NAME) ?: UNKNOWN_TOOL)
            CodexHookConfig.PERMISSION_REQUEST -> AgentEvent.ApprovalRequested(approvalId(payload, paneId))
            CodexHookConfig.STOP -> AgentEvent.TurnCompleted
            CodexHookConfig.SESSION_START -> sessionBound(payload)
            CodexHookConfig.SESSION_END -> AgentEvent.Exited(UNKNOWN_EXIT)
            else -> null
        }

    /** This is a display label; hook approvals have no resolution correlation key. */
    private fun approvalId(payload: JsonElement, paneId: PaneId): String =
        payload.stringField(FIELD_TOOL_NAME)
            ?: payload.stringField(FIELD_REASON)
            ?: "permission@${paneId.value}"

    private fun sessionBound(payload: JsonElement): AgentEvent? {
        val raw = payload.stringField(FIELD_SESSION_ID) ?: return null
        // Codex ids are UUIDs; the shared ProviderSessionId is broader because Junie's ids are not.
        if (!isCanonicalUuid(raw)) return null
        return runCatching { ProviderSessionId(raw) }.getOrNull()?.let(AgentEvent::SessionBound)
    }

    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }
}
