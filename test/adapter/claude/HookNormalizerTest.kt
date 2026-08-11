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

class HookNormalizerTest {

    private val pane = PaneId("%1")

    private fun norm(event: String, payload: JsonElement = JsonObject(emptyMap())): AgentEvent? =
        ClaudeHookNormalizer.normalize(event, payload, pane)


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
        val withMessage = norm(
            ClaudeHookConfig.NOTIFICATION,
            buildJsonObject { put("message", "Claude needs your permission to use Bash") },
        )
        assertIs<AgentEvent.ApprovalRequested>(withMessage)
        assertEquals("Claude needs your permission to use Bash", withMessage.approvalId)

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


    @Test
    fun aRealisticHookSequenceFoldsToTheExpectedStateTrajectory() {
        val names = listOf(
            ClaudeHookConfig.USER_PROMPT_SUBMIT,
            ClaudeHookConfig.POST_TOOL_USE,
            ClaudeHookConfig.NOTIFICATION,
            ClaudeHookConfig.POST_TOOL_USE,
            ClaudeHookConfig.STOP,
        )
        val payload = buildJsonObject {
            put("tool_name", "Bash")
            put("message", "Claude needs your permission to use Bash")
        }
        val events = names.map { norm(it, payload) ?: error("hook $it should normalize") }

        var projection = Projection.EMPTY
        val trajectory = mutableListOf<Pair<SessionState, Int>>()
        for (event in events) {
            projection = reduce(projection, event)
            trajectory.add(projection.state to projection.pendingApprovals)
        }

        assertEquals(
            listOf(
                SessionState.running to 0,
                SessionState.running to 0,
                SessionState.needs_approval to 1,
                SessionState.running to 0,
                SessionState.ready to 0,
            ),
            trajectory,
            "the hook sequence drives running → needs_approval → (cleared) running → ready",
        )

        assertEquals(projection, replay(events))
        assertEquals(SessionState.ready, projection.state)
        assertEquals(0, projection.pendingApprovals, "entering running on PostToolUse cleared the approval")
    }
}
