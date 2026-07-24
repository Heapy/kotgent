package io.kotgent.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [extractModel] — pure model-name extraction from provider record text (Claude
 * transcript / Codex rollout), including the `model_provider` false-match guard.
 */
class ModelScanTest {

    @Test
    fun extractsTheModelFromAClaudeTranscriptLine() {
        val line = """{"type":"assistant","message":{"model":"claude-opus-4-8","role":"assistant"}}"""
        assertEquals("claude-opus-4-8", extractModel(line))
    }

    @Test
    fun extractsTheModelFromACodexTurnContextRecord() {
        val line = """{"type":"turn_context","payload":{"cwd":"/x","model_provider":"openai","model":"gpt-5.5"}}"""
        assertEquals("gpt-5.5", extractModel(line))
    }

    @Test
    fun doesNotFalseMatchModelProviderAlone() {
        // A Codex session_meta head has model_provider but NOT model — must yield null, not "openai".
        val head = """{"type":"session_meta","payload":{"cwd":"/x","model_provider":"openai"}}"""
        assertNull(extractModel(head))
    }

    @Test
    fun toleratesWhitespaceAroundTheColon() {
        assertEquals("gpt-5.5", extractModel("""..."model" : "gpt-5.5"..."""))
    }

    @Test
    fun returnsNullForAbsentOrEmpty() {
        assertNull(extractModel(""))
        assertNull(extractModel("""{"no":"model here"}"""))
        assertNull(extractModel("""{"model":""}"""), "an empty model value is treated as unknown")
    }

    @Test
    fun takesTheFirstModelWhenSeveralArePresent() {
        assertEquals("m1", extractModel("""{"model":"m1"} ... {"model":"m2"}"""))
    }
}
