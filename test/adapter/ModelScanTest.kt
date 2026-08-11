package io.kotgent.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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


    @Test
    fun theDominantModelIsTheMostFrequentNotTheFirst() {
        val text = """{"model":"claude-haiku-4-5"}""" +
            """{"model":"gpt-4.1-mini"}""" +
            """{"model":"claude-fable-5"}""".repeat(40) +
            """{"model":"gpt-5.4-nano"}""".repeat(6)
        assertEquals("claude-fable-5", extractDominantModel(text))
        assertEquals("claude-haiku-4-5", extractModel(text), "…and extractModel would pick the helper")
    }

    @Test
    fun aTieGoesToTheModelSeenFirst() {
        assertEquals("m1", extractDominantModel("""{"model":"m1"}{"model":"m2"}"""))
        assertEquals(
            "m2",
            extractDominantModel("""{"model":"m2"}{"model":"m1"}{"model":"m2"}{"model":"m1"}"""),
            "a tie is broken by first appearance, so order alone decides",
        )
    }

    @Test
    fun aSingleOccurrenceIsAlreadyDominant() {
        assertEquals("gpt-5.5", extractDominantModel("""{"turn":1,"model":"gpt-5.5"}"""))
    }

    @Test
    fun theDominantModelIsNullWhenThereIsNoUsableOccurrence() {
        assertNull(extractDominantModel(""))
        assertNull(extractDominantModel("""{"no":"model here"}"""))
        assertNull(extractDominantModel("""{"model_provider":"openai"}"""), "the false-match guard holds")
        assertNull(extractDominantModel("""{"model":""}{"model":""}"""), "empty values never win")
        assertEquals(
            "m1",
            extractDominantModel("""{"model":""}{"model":""}{"model":"m1"}"""),
            "empty values are skipped, not counted as a candidate",
        )
    }
}
