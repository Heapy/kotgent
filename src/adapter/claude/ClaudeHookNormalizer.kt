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
 * PURE normalization of a Claude Code hook callback into the provider-neutral [AgentEvent] vocabulary
 * (plan Task 12) — the INCOMING half of the Claude adapter (the OUTGOING half is [ClaudeHookConfig]).
 *
 * Given a hook event name, its JSON payload (the body Claude delivers on the hook's stdin, forwarded
 * verbatim by the [ClaudeHookConfig] `curl`), and the [PaneId] the callback arrived on, it returns the
 * single [AgentEvent] that hook denotes, or `null` for a hook we ignore. No IO, no reducer state — a
 * total function of `(name, payload)`, so it is directly unit-testable with representative payloads.
 *
 * The route ([io.kotgent.transport.claudeHookRoutes]) authenticates the callback, resolves the
 * pane → session, calls this, and appends any non-null result to the `EventStore` with source `hook`.
 *
 * ## Mapping (v1 slice), aligned with the reducer
 *  - `UserPromptSubmit` → [AgentEvent.TurnStarted]  — a turn begins → `running`.
 *  - `PostToolUse`      → [AgentEvent.ToolCall]      — a running-PRODUCER, so the reducer resets
 *                         `pendingApprovals = 0`; this is EXACTLY how an approval clears when Claude
 *                         resumes (there is no "permission answered" hook).
 *  - `Stop`             → [AgentEvent.TurnCompleted] — the turn finished → `ready`.
 *  - `Notification`     → [AgentEvent.ApprovalRequested] — COARSE: ANY `Notification` → needs_attention.
 *                         The permission-vs-idle discriminator (likely the payload `message` field) is a
 *                         future refinement, deliberately NOT modeled here (plan Task 11 spike note).
 *  - `SessionStart`     → [AgentEvent.SessionBound]  — carries Claude's own `session_id`. If the payload
 *                         has no valid-UUID `session_id`, returns `null` (nothing to bind — the id then
 *                         comes from the preallocated `--session-id`, which is the normal path).
 *  - anything else      → `null` (ignored).
 */
object ClaudeHookNormalizer {

    /** Payload field carrying the invoked tool's name on a `PostToolUse` hook. */
    private const val FIELD_TOOL_NAME = "tool_name"

    /** Payload field carrying the notification text on a `Notification` hook. */
    private const val FIELD_MESSAGE = "message"

    /** Payload field carrying Claude's own session id on a `SessionStart` hook. */
    private const val FIELD_SESSION_ID = "session_id"

    /** [AgentEvent.ToolCall.name] fallback when a `PostToolUse` payload omits `tool_name`. */
    const val UNKNOWN_TOOL: String = "unknown"

    /**
     * Normalize the [hookEventName] callback (with its [payload]) that arrived on [paneId] into the one
     * [AgentEvent] it denotes, or `null` if this hook is ignored (unknown name, or a `SessionStart`
     * without a usable session id).
     */
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

    /**
     * Coarse approval-correlation id. v1 emits no [AgentEvent.ApprovalResolved] from hooks (approvals
     * clear when the session next enters `running`), so this is a human-readable LABEL, not a real
     * correlation key: the notification `message` if present, else the pane it arrived on.
     */
    private fun approvalId(payload: JsonElement, paneId: PaneId): String =
        payload.stringField(FIELD_MESSAGE) ?: "notification@${paneId.value}"

    /** `SessionStart` → [AgentEvent.SessionBound], or `null` when there is no valid-UUID `session_id`. */
    private fun sessionBound(payload: JsonElement): AgentEvent? {
        val raw = payload.stringField(FIELD_SESSION_ID) ?: return null
        // Claude session ids are UUIDs; a malformed one can't address a resume, so ignore it rather
        // than throwing on an untrusted callback body. The UUID check is EXPLICIT here because
        // ProviderSessionId itself only enforces a path/argv-safe charset (it is the union of all
        // providers, and Junie's ids are not UUIDs) — without it, arbitrary callback text would bind.
        if (!isCanonicalUuid(raw)) return null
        return runCatching { ProviderSessionId(raw) }.getOrNull()?.let(AgentEvent::SessionBound)
    }

    /** Read a string field from a JSON object [this]; `null` if not an object, absent, JSON null, or non-primitive. */
    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }
}
