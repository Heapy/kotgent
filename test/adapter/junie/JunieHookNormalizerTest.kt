package io.kotgent.adapter.junie

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

/**
 * Unit tests for [JunieHookNormalizer] — the INCOMING half of the Junie adapter. Pure `(name, payload)`
 * → `AgentEvent?`, so every case is a plain assertion with a representative payload (the bodies below are
 * the shapes Junie's hook documentation specifies). A few tests fold the result through the real reducer,
 * because what a mapping is FOR is the state it produces.
 */
class JunieHookNormalizerTest {

    private val pane = PaneId("%7")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun payload(text: String) = json.parseToJsonElement(text)
    private fun normalize(event: String, body: String = "{}") =
        JunieHookNormalizer.normalize(event, payload(body), pane)

    // ---- the mapped events ----

    @Test
    fun userPromptSubmitStartsATurn() {
        assertEquals(
            AgentEvent.TurnStarted,
            normalize(JunieHookConfig.USER_PROMPT_SUBMIT, """{"hook_event_name":"UserPromptSubmit","prompt":"hi"}"""),
        )
    }

    @Test
    fun preToolUseCarriesTheToolName() {
        assertEquals(
            AgentEvent.ToolCall("Bash"),
            normalize(
                JunieHookConfig.PRE_TOOL_USE,
                """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"sleep 60"}}""",
            ),
        )
    }

    @Test
    fun preToolUseWithoutAToolNameStillMapsToARunningEvent() {
        assertEquals(
            AgentEvent.ToolCall(JunieHookNormalizer.UNKNOWN_TOOL),
            normalize(JunieHookConfig.PRE_TOOL_USE, """{"hook_event_name":"PreToolUse"}"""),
        )
    }

    @Test
    fun permissionRequestIsAnApproval() {
        assertEquals(
            AgentEvent.ApprovalRequested("Bash"),
            normalize(
                JunieHookConfig.PERMISSION_REQUEST,
                """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{}}""",
            ),
        )
    }

    @Test
    fun permissionRequestFallsBackToReasonThenToThePane() {
        assertEquals(
            AgentEvent.ApprovalRequested("edit outside the project"),
            normalize(JunieHookConfig.PERMISSION_REQUEST, """{"reason":"edit outside the project"}"""),
        )
        assertEquals(
            AgentEvent.ApprovalRequested("permission@%7"),
            normalize(JunieHookConfig.PERMISSION_REQUEST, "{}"),
            "with nothing to label it by, the pane keeps the approval identifiable",
        )
    }

    @Test
    fun stopCompletesTheTurn() {
        assertEquals(
            AgentEvent.TurnCompleted,
            normalize(JunieHookConfig.STOP, """{"hook_event_name":"Stop","stop_hook_active":false}"""),
        )
    }

    @Test
    fun stopFailureAlsoCompletesTheTurn() {
        // A turn that dies in an LLM/API failure fires StopFailure INSTEAD of Stop and leaves the TUI
        // idle. Without this mapping the session would sit at `running` with nothing left to move it.
        assertEquals(
            AgentEvent.TurnCompleted,
            normalize(
                JunieHookConfig.STOP_FAILURE,
                """{"hook_event_name":"StopFailure","error":"rate_limit","error_details":"429 Too Many Requests"}""",
            ),
        )
    }

    @Test
    fun sessionEndIsACleanExit() {
        val event = normalize(JunieHookConfig.SESSION_END, """{"reason":"prompt_input_exit"}""")
        assertIs<AgentEvent.Exited>(event)
        assertEquals(JunieHookNormalizer.UNKNOWN_EXIT, event.code)
        assertEquals(
            SessionState.stopped,
            reduce(Projection.EMPTY, event).state,
            "reaching SessionEnd is a normal termination, not a crash",
        )
    }

    // ---- SessionStart: normally nothing to bind ----

    @Test
    fun sessionStartAsDocumentedCarriesNoIdSoItIsIgnored() {
        // Junie's documented payload is `{"hook_event_name":"SessionStart","source":"startup"}` — the id
        // comes from JunieSessionScan instead, and every documented `source` behaves the same way here.
        for (source in listOf("startup", "resume", "clear", "compact")) {
            assertNull(
                normalize(JunieHookConfig.SESSION_START, """{"hook_event_name":"SessionStart","source":"$source"}"""),
                "source=$source carries no session_id",
            )
        }
    }

    @Test
    fun sessionStartBindsAJunieIdWhenOneIsPresent() {
        // Future-proof: junie ids are NOT UUIDs, so this must bind without a UUID guard.
        val id = "session-260730-015553-1j1h"
        assertEquals(
            AgentEvent.SessionBound(ProviderSessionId(id)),
            normalize(JunieHookConfig.SESSION_START, """{"session_id":"$id"}"""),
        )
    }

    @Test
    fun sessionStartWithAnUnusableIdIsIgnoredRatherThanThrowing() {
        assertNull(normalize(JunieHookConfig.SESSION_START, """{"session_id":null}"""))
        assertNull(normalize(JunieHookConfig.SESSION_START, """{"session_id":""}"""), "blank is not an id")
        assertNull(
            normalize(JunieHookConfig.SESSION_START, """{"session_id":"../../etc/passwd"}"""),
            "an id kotgent could not put in a path/argv is ignored, not thrown, on an untrusted body",
        )
    }

    // ---- ignored / malformed ----

    @Test
    fun anUnmappedHookIsIgnored() {
        assertNull(normalize("PostToolUse"), "junie has no PostToolUse")
        assertNull(normalize("SubagentStop"))
        assertNull(normalize(""))
    }

    @Test
    fun aClaudeOnlyHookNameIsNotMapped() {
        // The three providers share an ingress SHAPE but not a vocabulary; `Notification` is Claude's.
        assertNull(normalize("Notification"), "Claude's Notification has no meaning on the junie route")
    }

    @Test
    fun aNonObjectPayloadDegradesInsteadOfThrowing() {
        // The ingress hands over whatever the hook posted; an untrusted body must never crash the route.
        assertEquals(
            AgentEvent.ToolCall(JunieHookNormalizer.UNKNOWN_TOOL),
            JunieHookNormalizer.normalize(JunieHookConfig.PRE_TOOL_USE, payload("[1,2,3]"), pane),
        )
        assertEquals(
            AgentEvent.ApprovalRequested("permission@%7"),
            JunieHookNormalizer.normalize(JunieHookConfig.PERMISSION_REQUEST, payload("\"nonsense\""), pane),
        )
        assertNull(JunieHookNormalizer.normalize(JunieHookConfig.SESSION_START, JsonObject(emptyMap()), pane))
    }

    // ---- the mapping seen through the reducer ----

    @Test
    fun aPermissionThenAToolCallIsNeedsApprovalThenRunning() {
        // The approval lifecycle kotgent actually observes: junie asks (and keeps showing its own dialog),
        // the operator answers in the terminal (no callback for that), and the next PreToolUse is what
        // proves it was answered.
        var projection = Projection.EMPTY
        projection = reduce(projection, normalize(JunieHookConfig.USER_PROMPT_SUBMIT)!!)
        assertEquals(SessionState.running, projection.state)

        projection = reduce(projection, normalize(JunieHookConfig.PERMISSION_REQUEST, """{"tool_name":"Bash"}""")!!)
        assertEquals(SessionState.needs_approval, projection.state)
        assertEquals(1, projection.pendingApprovals)

        projection = reduce(projection, normalize(JunieHookConfig.PRE_TOOL_USE, """{"tool_name":"Bash"}""")!!)
        assertEquals(SessionState.running, projection.state, "running-entry clears the approval")
        assertEquals(0, projection.pendingApprovals)

        projection = reduce(projection, normalize(JunieHookConfig.STOP)!!)
        assertEquals(SessionState.ready, projection.state)
    }

    @Test
    fun aFailedTurnLandsReadyRatherThanStickingAtRunning() {
        var projection = reduce(Projection.EMPTY, normalize(JunieHookConfig.USER_PROMPT_SUBMIT)!!)
        assertEquals(SessionState.running, projection.state)
        projection = reduce(projection, normalize(JunieHookConfig.STOP_FAILURE, """{"error":"server_error"}""")!!)
        assertEquals(SessionState.ready, projection.state, "an errored turn is idle, not running")
    }

    @Test
    fun theReducerInvariantHoldsAcrossTheJunieMapping() {
        // pendingApprovals > 0 <=> needs_approval, for every event this normalizer can produce.
        val bodies = listOf(
            JunieHookConfig.USER_PROMPT_SUBMIT to "{}",
            JunieHookConfig.PERMISSION_REQUEST to """{"tool_name":"Bash"}""",
            JunieHookConfig.PERMISSION_REQUEST to """{"tool_name":"Edit"}""",
            JunieHookConfig.PRE_TOOL_USE to """{"tool_name":"Bash"}""",
            JunieHookConfig.STOP to "{}",
            JunieHookConfig.STOP_FAILURE to """{"error":"unknown"}""",
            JunieHookConfig.SESSION_END to "{}",
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
