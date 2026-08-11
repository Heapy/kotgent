package io.kotgent.adapter.claude

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.isCanonicalUuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Normalizes Claude hooks. Claude has no permission-specific hook, so every `Notification` is treated
 * as an approval request and is cleared by the next running event.
 */
object ClaudeHookNormalizer {
    private const val FIELD_TOOL_NAME = "tool_name"

    private const val FIELD_MESSAGE = "message"

    private const val FIELD_SESSION_ID = "session_id"

    const val UNKNOWN_TOOL: String = "unknown"

    fun normalize(hookEventName: String, payload: JsonElement, paneId: PaneId): AgentEvent? =
        when (hookEventName) {
            ClaudeHookConfig.USER_PROMPT_SUBMIT -> AgentEvent.TurnStarted
            ClaudeHookConfig.POST_TOOL_USE ->
                AgentEvent.ToolCall(payload.stringField(FIELD_TOOL_NAME) ?: UNKNOWN_TOOL)
            ClaudeHookConfig.STOP -> AgentEvent.TurnCompleted
            ClaudeHookConfig.NOTIFICATION -> AgentEvent.ApprovalRequested(approvalId(payload, paneId))
            ClaudeHookConfig.SESSION_START -> sessionBound(payload)
            else -> null
        }

    /** This is a display label; hook approvals have no resolution correlation key. */
    private fun approvalId(payload: JsonElement, paneId: PaneId): String =
        payload.stringField(FIELD_MESSAGE) ?: "notification@${paneId.value}"

    private fun sessionBound(payload: JsonElement): AgentEvent? {
        val raw = payload.stringField(FIELD_SESSION_ID) ?: return null
        // Claude ids are UUIDs; the shared ProviderSessionId is broader because Junie's ids are not.
        if (!isCanonicalUuid(raw)) return null
        return runCatching { ProviderSessionId(raw) }.getOrNull()?.let(AgentEvent::SessionBound)
    }

    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }
}
