package io.kotgent.adapter.codex

import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionState
import io.kotgent.core.reduce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexHookNormalizerTest {

    private val pane = PaneId("%7")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun payload(text: String) = json.parseToJsonElement(text)
    private fun normalize(event: String, body: String = "{}") =
        CodexHookNormalizer.normalize(event, payload(body), pane)


    @Test
    fun userPromptSubmitStartsATurn() {
        assertEquals(AgentEvent.TurnStarted, normalize(CodexHookConfig.USER_PROMPT_SUBMIT))
    }

    @Test
    fun postToolUseCarriesTheToolName() {
        assertEquals(
            AgentEvent.ToolCall("shell"),
            normalize(CodexHookConfig.POST_TOOL_USE, """{"tool_name":"shell","cwd":"/work"}"""),
        )
    }

    @Test
    fun postToolUseWithoutAToolNameStillMapsToARunningEvent() {
        assertEquals(
            AgentEvent.ToolCall(CodexHookNormalizer.UNKNOWN_TOOL),
            normalize(CodexHookConfig.POST_TOOL_USE, """{"turn_id":"t1"}"""),
        )
    }

    @Test
    fun permissionRequestIsAnApproval() {
        val event = normalize(CodexHookConfig.PERMISSION_REQUEST, """{"tool_name":"shell"}""")
        assertEquals(AgentEvent.ApprovalRequested("shell"), event)
    }

    @Test
    fun permissionRequestFallsBackToReasonThenToThePane() {
        assertEquals(
            AgentEvent.ApprovalRequested("write outside the sandbox"),
            normalize(CodexHookConfig.PERMISSION_REQUEST, """{"reason":"write outside the sandbox"}"""),
        )
        assertEquals(
            AgentEvent.ApprovalRequested("permission@%7"),
            normalize(CodexHookConfig.PERMISSION_REQUEST, "{}"),
            "with nothing to label it by, the pane keeps the approval identifiable",
        )
    }

    @Test
    fun stopCompletesTheTurn() {
        assertEquals(AgentEvent.TurnCompleted, normalize(CodexHookConfig.STOP))
    }

    @Test
    fun sessionStartBindsCodexOwnSessionId() {
        val id = "019f8ea0-2548-7871-9835-947ff7623ccf"
        val event = normalize(CodexHookConfig.SESSION_START, """{"session_id":"$id","cwd":"/work"}""")
        assertEquals(AgentEvent.SessionBound(ProviderSessionId(id)), event)
    }

    @Test
    fun sessionEndIsACleanExit() {
        val event = normalize(CodexHookConfig.SESSION_END, """{"reason":"exit"}""")
        assertIs<AgentEvent.Exited>(event)
        assertEquals(CodexHookNormalizer.UNKNOWN_EXIT, event.code)
        assertEquals(
            SessionState.stopped,
            reduce(Projection.EMPTY, event).state,
            "reaching SessionEnd is a normal termination, not a crash",
        )
    }


    @Test
    fun anUnmappedHookIsIgnored() {
        assertNull(normalize("PreToolUse"), "wired-but-unmapped and never-wired hooks alike map to null")
        assertNull(normalize("SubagentStop"))
        assertNull(normalize(""))
    }

    @Test
    fun aClaudeOnlyHookNameIsNotMapped() {
        assertNull(normalize("Notification"), "Claude's Notification has no meaning on the codex route")
    }

    @Test
    fun sessionStartWithoutAUsableIdIsIgnoredRatherThanThrowing() {
        assertNull(normalize(CodexHookConfig.SESSION_START, "{}"), "no session_id -> nothing to bind")
        assertNull(
            normalize(CodexHookConfig.SESSION_START, """{"session_id":"not-a-uuid"}"""),
            "a malformed id cannot address a resume: ignored, not thrown",
        )
        assertNull(normalize(CodexHookConfig.SESSION_START, """{"session_id":null}"""))
    }

    @Test
    fun aNonObjectPayloadDegradesInsteadOfThrowing() {
        assertEquals(
            AgentEvent.ToolCall(CodexHookNormalizer.UNKNOWN_TOOL),
            CodexHookNormalizer.normalize(CodexHookConfig.POST_TOOL_USE, payload("[1,2,3]"), pane),
        )
        assertNull(CodexHookNormalizer.normalize(CodexHookConfig.SESSION_START, JsonObject(emptyMap()), pane))
    }


    @Test
    fun aPermissionThenAToolCallIsNeedsApprovalThenRunning() {
        var projection = Projection.EMPTY
        projection = reduce(projection, normalize(CodexHookConfig.USER_PROMPT_SUBMIT)!!)
        assertEquals(SessionState.running, projection.state)

        projection = reduce(projection, normalize(CodexHookConfig.PERMISSION_REQUEST, """{"tool_name":"shell"}""")!!)
        assertEquals(SessionState.needs_approval, projection.state)
        assertEquals(1, projection.pendingApprovals)

        projection = reduce(projection, normalize(CodexHookConfig.POST_TOOL_USE, """{"tool_name":"shell"}""")!!)
        assertEquals(SessionState.running, projection.state, "running-entry clears the approval")
        assertEquals(0, projection.pendingApprovals)

        projection = reduce(projection, normalize(CodexHookConfig.STOP)!!)
        assertEquals(SessionState.ready, projection.state)
    }

    @Test
    fun theReducerInvariantHoldsAcrossTheCodexMapping() {
        val bodies = listOf(
            CodexHookConfig.USER_PROMPT_SUBMIT to "{}",
            CodexHookConfig.PERMISSION_REQUEST to """{"tool_name":"shell"}""",
            CodexHookConfig.PERMISSION_REQUEST to """{"tool_name":"apply_patch"}""",
            CodexHookConfig.POST_TOOL_USE to """{"tool_name":"shell"}""",
            CodexHookConfig.STOP to "{}",
            CodexHookConfig.SESSION_END to "{}",
        )
        var projection = Projection.EMPTY
        for ((event, body) in bodies) {
            projection = reduce(projection, normalize(event, body)!!)
            assertEquals(
                projection.pendingApprovals > 0,
                projection.state == SessionState.needs_approval,
                "invariant after $event: $projection",
            )
        }
    }
}
