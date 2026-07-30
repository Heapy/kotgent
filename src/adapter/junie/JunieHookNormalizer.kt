package io.kotgent.adapter.junie

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PURE normalization of a Junie hook callback into the provider-neutral [AgentEvent] vocabulary — the
 * INCOMING half of the Junie adapter (the OUTGOING half is [JunieHookConfig]).
 *
 * Given a hook event name, its JSON payload (delivered on the hook's stdin, forwarded verbatim by the
 * generated hook script) and the [PaneId] the callback arrived on, it returns the single [AgentEvent]
 * that hook denotes, or `null` for a hook we ignore. No IO, no reducer state — a total function of
 * `(name, payload)`, directly unit-testable.
 *
 * ## Mapping, aligned with the reducer
 *  - `UserPromptSubmit`  → [AgentEvent.TurnStarted]      — a turn begins → `running`.
 *  - `PreToolUse`        → [AgentEvent.ToolCall]         — a running-PRODUCER, so the reducer resets
 *                          `pendingApprovals = 0`. Junie has no `PostToolUse`, so the PRE-call hook is
 *                          this provider's running signal — and it is also how an approval CLEARS
 *                          (kotgent never answers one; the operator does, in the terminal).
 *  - `PermissionRequest` → [AgentEvent.ApprovalRequested] — a REAL approval signal, like Codex's.
 *  - `Stop`              → [AgentEvent.TurnCompleted]     — the turn finished → `ready`.
 *  - `StopFailure`       → [AgentEvent.TurnCompleted]     — see below.
 *  - `SessionStart`      → [AgentEvent.SessionBound]      — only if the payload carries a usable
 *                          `session_id`; see below.
 *  - `SessionEnd`        → [AgentEvent.Exited]            — the session ended.
 *  - anything else       → `null` (ignored).
 *
 * ## Why `StopFailure` is also a `TurnCompleted`
 * `StopFailure` fires when the LLM/API call behind a turn ends in a classified failure (rate limit, auth,
 * server error, refusal…). Junie surfaces the error and the TUI goes back to idle — it is a
 * running-EXIT exactly like `Stop`, just an unhappy one. Without this mapping the session would sit at
 * `running` forever with nothing left to move it. The v1 event vocabulary has no "turn failed" member and
 * the reducer treats both the same way, so mapping to [AgentEvent.TurnCompleted] loses nothing that is
 * modeled today. Note the hook is observability-only on Junie's side: its exit code and output are
 * ignored, so kotgent cannot (and must not try to) influence the failing turn.
 *
 * ## Why `SessionStart` usually maps to nothing
 * Junie's documented `SessionStart` payload carries only `hook_event_name` and `source`, so there is
 * normally no id to bind and this returns `null` (the ingress answers `ignored`). The id then comes from
 * [io.kotgent.daemon.JunieSessionScan] reading Junie's own session index. The mapping is kept for the day
 * the payload does carry a `session_id`: the hook is authoritative for the session it fires in, so it
 * WINS over the scan — which is exactly why the junie ingress surfaces the rebind correction seam
 * (`onProviderIdRebound`), like the codex one.
 *
 * Junie session ids are NOT UUIDs (`session-260730-015553-1j1h`), so — unlike the Claude and Codex
 * normalizers — there is no UUID guard here; [ProviderSessionId]'s own path/argv-safe charset invariant is
 * the whole check, and a value that fails it is ignored rather than thrown on an untrusted callback body.
 *
 * ## Exit code
 * `SessionEnd` reports a `reason`, not a status, so [AgentEvent.Exited] is stamped [UNKNOWN_EXIT] (`0`):
 * the reducer routes an [AgentEvent.Exited] to `stopped` vs `crashed` by exit code alone, and a session
 * that ended by reaching its own `SessionEnd` is a normal termination. A hard crash never reaches this
 * hook at all — it is the reconciler that classifies a vanished pane.
 */
object JunieHookNormalizer {

    /** Payload field carrying the tool's name on a `PreToolUse` / `PermissionRequest` hook. */
    private const val FIELD_TOOL_NAME = "tool_name"

    /** Payload field that would carry Junie's own session id (see the class KDoc). */
    private const val FIELD_SESSION_ID = "session_id"

    /** Defensive fallback label field — not part of Junie's documented `PermissionRequest` payload. */
    private const val FIELD_REASON = "reason"

    /** [AgentEvent.ToolCall.name] fallback when a `PreToolUse` payload omits `tool_name`. */
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
            JunieHookConfig.USER_PROMPT_SUBMIT -> AgentEvent.TurnStarted
            JunieHookConfig.PRE_TOOL_USE ->
                AgentEvent.ToolCall(payload.stringField(FIELD_TOOL_NAME) ?: UNKNOWN_TOOL)
            JunieHookConfig.PERMISSION_REQUEST -> AgentEvent.ApprovalRequested(approvalId(payload, paneId))
            JunieHookConfig.STOP, JunieHookConfig.STOP_FAILURE -> AgentEvent.TurnCompleted
            JunieHookConfig.SESSION_START -> sessionBound(payload)
            JunieHookConfig.SESSION_END -> AgentEvent.Exited(UNKNOWN_EXIT)
            else -> null
        }

    /**
     * Approval-correlation id: a human-readable LABEL, not a real correlation key (kotgent emits no
     * [AgentEvent.ApprovalResolved] from hooks — approvals clear when the session next enters `running`).
     * Prefers the tool the permission is about, then a reason text, and falls back to the pane.
     */
    private fun approvalId(payload: JsonElement, paneId: PaneId): String =
        payload.stringField(FIELD_TOOL_NAME)
            ?: payload.stringField(FIELD_REASON)
            ?: "permission@${paneId.value}"

    /** `SessionStart` → [AgentEvent.SessionBound], or `null` when there is no usable `session_id`. */
    private fun sessionBound(payload: JsonElement): AgentEvent? {
        val raw = payload.stringField(FIELD_SESSION_ID) ?: return null
        return runCatching { ProviderSessionId(raw) }.getOrNull()?.let(AgentEvent::SessionBound)
    }

    /** Read a string field from a JSON object [this]; `null` if not an object, absent, JSON null, or non-primitive. */
    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }
}
