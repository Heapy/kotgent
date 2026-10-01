package io.kotgent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CliPlanParseTest {
    private val document = """{"taskRef":"local:1","title":"Plan"}"""
    private fun parse(vararg args: String, input: String = document) = parseArgs(listOf("plan", *args)) { input }

    @Test
    fun putReadsStdinAndCarriesTheBaseRevisionAndSession() {
        assertEquals(PlanPut("local:1", document, 0, null), parse("put", "local:1"))
        assertEquals(PlanPut("local:1", document, 7, "alice"), parse("put", "local:1", "--base-rev", "7", "--session", "alice"))
        assertIs<CliCommand.Invalid>(parse("put", "local:2"))
        assertIs<CliCommand.Invalid>(parse("put", "local:1", input = "bad JSON"))
        assertIs<CliCommand.Invalid>(parse("put", "local:1", "--base-rev", "-1"))
    }

    @Test
    fun showReviewAndReplyHaveStrictArguments() {
        assertEquals(PlanShow("local:1", null), parse("show", "local:1"))
        assertEquals(PlanReviewCommand("local:1", 90, false, null), parse("review", "local:1"))
        assertEquals(PlanReviewCommand("local:1", 90, false, null, 1), parse("review", "local:1", "--after-round", "1"))
        assertEquals(PlanReviewCommand("local:1", 540, true, "alice"), parse("review", "local:1", "--wait", "540", "--json", "--session", "alice"))
        assertEquals(PlanReply("local:1", "th_1", "Answer", null), parse("reply", "local:1", "th_1", "-m", "-", input = "Answer\n"))
        for (args in listOf(
            listOf("show"), listOf("show", "local:1", "extra"), listOf("review", "local:1", "--wait", "541"),
            listOf("reply", "local:1", "th_1"), listOf("reply", "local:1", "s_bad", "-m", "text"),
            listOf("put", "local:1", "--force"), listOf("review", "local:1", "--wait", "1", "--wait", "2"),
        )) assertIs<CliCommand.Invalid>(parse(*args.toTypedArray()))
    }
}
