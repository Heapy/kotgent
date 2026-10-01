package io.kotgent.cli

import io.kotgent.transport.*
import io.kotgent.plan.*
import kotlinx.serialization.decodeFromString
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
    @Test
    fun executionCommandsCarryIdentityRevisionsAndReplayCursors() {
        assertEquals(PlanMutation("local:1", "/claim", session = "root"), parse("claim", "local:1", "--session", "root"))
        assertEquals(PlanWait("local:1", "t_1", 540, 7, true, "worker"),
            parse("task", "local:1", "t_1", "wait", "--wait", "540", "--after", "7", "--json", "--session", "worker"))
        assertEquals(PlanWait("local:1", null, 90, 0, false, null), parse("wait", "local:1"))
        val settings = assertIs<PlanMutation>(parse("set", "local:1", "mode", "autonomous"))
        assertEquals(true, settings.patch)
        assertEquals(ExecutionMode.autonomous, TRANSPORT_JSON.decodeFromString<PlanSettingsRequest>(settings.body).mode)
        val worker = assertIs<PlanMutation>(parse("task", "local:1", "t_1", "worker", "--worker-session", "child", "--branch", "feature", "--worktree", "/tmp/tree"))
        assertEquals(PlanWorker("child", "feature", "/tmp/tree"), TRANSPORT_JSON.decodeFromString<PlanWorker>(worker.body))
        val feedback = assertIs<PlanMutation>(parse("task", "local:1", "t_1", "feedback", "--findings", "f_1,f_2"))
        assertEquals(listOf("f_1", "f_2"), TRANSPORT_JSON.decodeFromString<PlanFeedbackRequest>(feedback.body).findingIds)
        val decision = assertIs<PlanMutation>(parse("finding", "local:1", "decide", "f_1", "--rev", "3", "--kind", "fix_now", "--option", "0", "--note", "The branch needs this"))
        val body = TRANSPORT_JSON.decodeFromString<PlanFindingDecisionRequest>(decision.body)
        assertEquals(3L, body.rev)
        assertEquals(FindingDecision(FindingDecisionKind.fix_now, 0, "The branch needs this"), body.decision)
        val verify = assertIs<PlanMutation>(parse("finding", "local:1", "verify", "f_1", "--rev", "2", input = "{}"))
        assertEquals(2L, TRANSPORT_JSON.decodeFromString<PlanFindingVerifyRequest>(verify.body).rev)
        val ask = assertIs<PlanMutation>(parse("ask", "local:1", "st_1", "--kind", "decision", "-m", "-", input = "Choose A or B?\n"))
        assertEquals(PlanThreadRequest("st_1", ThreadKind.decision, "Choose A or B?"), TRANSPORT_JSON.decodeFromString<PlanThreadRequest>(ask.body))
    }

    @Test
    fun executionParserRejectsAmbiguousOrIgnoredArguments() {
        for (args in listOf(
            listOf("claim", "local:1", "--rev", "1"),
            listOf("wait", "local:1", "--after", "-1"),
            listOf("task", "local:1", "t_1", "wait", "--wait", "541"),
            listOf("task", "local:1", "t_1", "feedback"),
            listOf("task", "local:1", "t_1", "feedback", "--findings", "f_1", "--rebase-onto", "main"),
            listOf("task", "local:1", "t_1", "feedback", "--findings", "f_1,f_1"),
            listOf("task", "local:1", "t_1", "status", "running"),
            listOf("finding", "local:1", "verify", "f_1"),
            listOf("finding", "local:1", "verify", "f_1", "--rev", "0"),
            listOf("finding", "local:1", "decide", "f_1", "--rev", "1", "--kind", "fix_now"),
            listOf("finding", "local:1", "decide", "f_1", "--rev", "1", "--kind", "wont_fix", "--option", "0"),
            listOf("step", "local:1", "t_1", "done"),
            listOf("set", "local:1", "concurrency", "0"),
            listOf("ask", "local:1", "s_1", "--kind", "unknown", "-m", "text"),
        )) assertIs<CliCommand.Invalid>(parse(*args.toTypedArray()), args.joinToString(" "))
        assertIs<CliCommand.Invalid>(parse("finding", "local:1", "add", input = "bad JSON"))
    }

}
