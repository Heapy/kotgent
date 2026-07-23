package io.kotgent.adapter.codex

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PURE normalization of a Codex hook callback into the provider-neutral [AgentEvent] vocabulary — the
 * INCOMING half of the Codex adapter (the OUTGOING half is [CodexHookConfig]).
 *
 * Given a hook event name, its JSON payload (delivered on the hook's stdin, forwarded verbatim by the
 * generated hook script) and the [PaneId] the callback arrived on, it returns the single [AgentEvent]
 * that hook denotes, or `null` for a hook we ignore. No IO, no reducer state — a total function of
 * `(name, payload)`, directly unit-testable.
 *
 * ## Mapping, aligned with the reducer
 *  - `UserPromptSubmit`   → [AgentEvent.TurnStarted]      — a turn begins → `running`.
 *  - `PostToolUse`        → [AgentEvent.ToolCall]         — a running-PRODUCER, so the reducer resets
 *                           `pendingApprovals = 0`.
 *  - `PermissionRequest`  → [AgentEvent.ApprovalRequested] — a REAL approval signal.
 *  - `Stop`               → [AgentEvent.TurnCompleted]     — the turn finished → `ready`.
 *  - `SessionStart`       → [AgentEvent.SessionBound]      — carries Codex's own `session_id`.
 *  - `SessionEnd`         → [AgentEvent.Exited]            — the session ended.
 *  - anything else        → `null` (ignored).
 *
 * ## Where this differs from Claude — and why it matters
 * Claude has no "permission" hook at all, so [io.kotgent.adapter.claude.ClaudeHookNormalizer] maps ANY
 * `Notification` to an approval and the reducer clears it on the next running-entry. Codex fires a
 * dedicated `PermissionRequest`, so `needs_approval` here is precise rather than inferred. The clearing
 * rule is unchanged (a `PostToolUse` after the user answers resets `pendingApprovals`): Codex's hook
 * output can carry a decision, but kotgent does not answer approvals — the operator does, in the
 * terminal — so there is no "resolved" callback to key an [AgentEvent.ApprovalResolved] off.
 *
 * ## Exit code
 * `SessionEnd` reports no exit status, so [AgentEvent.Exited] is stamped [UNKNOWN_EXIT] (`0`): the
 * reducer routes an [AgentEvent.Exited] to `stopped` vs `crashed` by exit code alone, and a session that
 * ended by reaching its own `SessionEnd` is a normal termination. A hard crash never reaches this hook
 * at all — it is the reconciler that classifies a vanished pane.
 */
object CodexHookNormalizer {

    /** Payload field carrying the invoked tool's name on a `PostToolUse` hook. */
    private const val FIELD_TOOL_NAME = "tool_name"

    /** Payload field carrying Codex's own session id on a `SessionStart` hook. */
    private const val FIELD_SESSION_ID = "session_id"

    /** Payload field carrying the human-readable reason on a `PermissionRequest` hook. */
    private const val FIELD_REASON = "reason"

    /** [AgentEvent.ToolCall.name] fallback when a `PostToolUse` payload omits `tool_name`. */
    const val UNKNOWN_TOOL: String = "unknown"

    /** Exit code stamped on the `SessionEnd`-derived [AgentEvent.Exited] (see the class KDoc). */
    const val UNKNOWN_EXIT: Int = 0

    /**
     * Normalize the [hookEventName] callback (with its [payload]) that arrived on [paneId] into the one
     * [AgentEvent] it denotes, or `null` if this hook is ignored (unknown name, or a `SessionStart`
     * without a usable session id).
     */
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

    /**
     * Approval-correlation id: a human-readable LABEL, not a real correlation key (kotgent emits no
     * [AgentEvent.ApprovalResolved] from hooks — approvals clear when the session next enters `running`).
     * Prefers the tool the permission is about, then the reason text, and falls back to the pane.
     */
    private fun approvalId(payload: JsonElement, paneId: PaneId): String =
        payload.stringField(FIELD_TOOL_NAME)
            ?: payload.stringField(FIELD_REASON)
            ?: "permission@${paneId.value}"

    /** `SessionStart` → [AgentEvent.SessionBound], or `null` when there is no valid-UUID `session_id`. */
    private fun sessionBound(payload: JsonElement): AgentEvent? {
        val raw = payload.stringField(FIELD_SESSION_ID) ?: return null
        // Codex session ids are UUIDs (v7); a malformed one can't address a resume, so ignore it rather
        // than throwing on an untrusted callback body.
        return runCatching { ProviderSessionId(raw) }.getOrNull()?.let(AgentEvent::SessionBound)
    }

    /** Read a string field from a JSON object [this]; `null` if not an object, absent, JSON null, or non-primitive. */
    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }
}
