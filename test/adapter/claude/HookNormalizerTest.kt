package io.kotgent.adapter.claude

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Projection
import io.kotgent.core.SessionState
import io.kotgent.core.reduce
import io.kotgent.core.replay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Unit tests for [ClaudeHookNormalizer] (plan Task 12) — the pure `hook payload → AgentEvent` mapping,
 * the INCOMING half of the Claude adapter. Two kinds of assertion:
 *
 *  1. Each representative hook payload maps to the expected [AgentEvent] (or `null` for ignored hooks).
 *  2. A realistic hook SEQUENCE, once normalized, folds through the real [reduce] into the expected
 *     state trajectory — in particular that a `PostToolUse` (→ [AgentEvent.ToolCall], a running-
 *     producer) CLEARS the `pendingApprovals` a prior `Notification` raised, since Claude has no
 *     "permission answered" signal.
 *
 * Pure Kotlin, no cinterop / no IO, so these run for real in the test binary.
 */
class HookNormalizerTest {

    private val pane = PaneId("%1")

    private fun norm(event: String, payload: JsonElement = JsonObject(emptyMap())): AgentEvent? =
        ClaudeHookNormalizer.normalize(event, payload, pane)

    // ---- per-hook mapping ----

    @Test
    fun userPromptSubmitBecomesTurnStarted() {
        assertEquals(AgentEvent.TurnStarted, norm(ClaudeHookConfig.USER_PROMPT_SUBMIT))
    }

    @Test
    fun postToolUseBecomesToolCallCarryingTheToolName() {
        val event = norm(ClaudeHookConfig.POST_TOOL_USE, buildJsonObject { put("tool_name", "Bash") })
        assertEquals(AgentEvent.ToolCall("Bash"), event)
    }

    @Test
    fun postToolUseWithoutAToolNameFallsBackToUnknown() {
        assertEquals(
            AgentEvent.ToolCall(ClaudeHookNormalizer.UNKNOWN_TOOL),
            norm(ClaudeHookConfig.POST_TOOL_USE),
        )
    }

    @Test
    fun stopBecomesTurnCompleted() {
        assertEquals(AgentEvent.TurnCompleted, norm(ClaudeHookConfig.STOP))
    }

    @Test
    fun anyNotificationBecomesApprovalRequestedCoarse() {
        // COARSE mapping: ANY Notification → ApprovalRequested (→ needs_attention), regardless of text.
        val withMessage = norm(
            ClaudeHookConfig.NOTIFICATION,
            buildJsonObject { put("message", "Claude needs your permission to use Bash") },
        )
        assertIs<AgentEvent.ApprovalRequested>(withMessage)
        assertEquals("Claude needs your permission to use Bash", withMessage.approvalId)

        // Even a Notification with no message still maps (coarse) — the label falls back to the pane.
        val bare = norm(ClaudeHookConfig.NOTIFICATION)
        assertIs<AgentEvent.ApprovalRequested>(bare)
        assertEquals("notification@%1", bare.approvalId)
    }

    @Test
    fun sessionStartBecomesSessionBoundWithTheProviderSessionId() {
        val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val event = norm(ClaudeHookConfig.SESSION_START, buildJsonObject { put("session_id", uuid) })
        assertEquals(AgentEvent.SessionBound(ProviderSessionId(uuid)), event)
    }

    @Test
    fun sessionStartWithoutAValidSessionIdIsIgnored() {
        assertNull(norm(ClaudeHookConfig.SESSION_START), "no session_id → nothing to bind")
        assertNull(
            norm(ClaudeHookConfig.SESSION_START, buildJsonObject { put("session_id", "not-a-uuid") }),
            "a non-UUID session_id is ignored rather than throwing on an untrusted body",
        )
    }

    @Test
    fun unknownHookNamesAreIgnored() {
        assertNull(norm("PreToolUse"))
        assertNull(norm("SubagentStop"))
        assertNull(norm("PreCompact"))
        assertNull(norm(""))
    }

    // ---- realistic sequence folded through the reducer ----

    @Test
    fun aRealisticHookSequenceFoldsToTheExpectedStateTrajectory() {
        // UserPromptSubmit → PostToolUse → Notification → PostToolUse → Stop.
        val names = listOf(
            ClaudeHookConfig.USER_PROMPT_SUBMIT,
            ClaudeHookConfig.POST_TOOL_USE,
            ClaudeHookConfig.NOTIFICATION,
            ClaudeHookConfig.POST_TOOL_USE,
            ClaudeHookConfig.STOP,
        )
        // A payload rich enough for every hook to pull the field it reads.
        val payload = buildJsonObject {
            put("tool_name", "Bash")
            put("message", "Claude needs your permission to use Bash")
        }
        val events = names.map { norm(it, payload) ?: error("hook $it should normalize") }

        // Step-by-step (state, pendingApprovals) trajectory as the reducer folds each event.
        var projection = Projection.EMPTY
        val trajectory = mutableListOf<Pair<SessionState, Int>>()
        for (event in events) {
            projection = reduce(projection, event)
            trajectory.add(projection.state to projection.pendingApprovals)
        }

        assertEquals(
            listOf(
                SessionState.running to 0,        // UserPromptSubmit → TurnStarted
                SessionState.running to 0,        // PostToolUse → ToolCall
                SessionState.needs_approval to 1, // Notification → ApprovalRequested (needs attention)
                SessionState.running to 0,        // PostToolUse → ToolCall — CLEARS the pending approval
                SessionState.ready to 0,          // Stop → TurnCompleted
            ),
            trajectory,
            "the hook sequence drives running → needs_approval → (cleared) running → ready",
        )

        // replay determinism: incremental fold equals a fold-from-scratch over the same events.
        assertEquals(projection, replay(events))
        assertEquals(SessionState.ready, projection.state)
        assertEquals(0, projection.pendingApprovals, "entering running on PostToolUse cleared the approval")
    }
}
