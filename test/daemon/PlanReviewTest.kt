package io.kotgent.daemon

import io.kotgent.plan.*
import io.kotgent.store.FakePlanStore
import io.kotgent.store.PlanDocument
import io.kotgent.store.PlanResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlanReviewTest {
    @Test
    fun pendingContinuesTheRoundAndOnlyOpeningItWakesTheOperator() = runBlocking {
        withTimeout(10.seconds) {
            val store = FakePlanStore()
            val _ = store.put(Plan("local:1", "Plan"), 0)
            val wakes = mutableListOf<String>()
            val review = PlanReview(store) { wakes.add(it) }
            val first = review.wait("local:1", Duration.ZERO).second!!
            assertEquals("pending", first.verdict)
            assertEquals(first, review.wait("local:1", Duration.ZERO).second)
            assertEquals(listOf("plan.review:local:1"), wakes)
            val waiting = async { review.wait("local:1", 5.seconds) }
            assertIs<PlanResult.Accepted>(store.submitReview("local:1", 1, ReviewVerdict.approved))
            assertEquals("approved", waiting.await().second!!.verdict)
            assertEquals(1, wakes.size)
            assertEquals("approved", review.wait("local:1", Duration.ZERO).second!!.verdict)
            val next = review.wait("local:1", Duration.ZERO, afterRound = 1).second!!
            assertEquals(2, next.round)
            assertEquals("pending", next.verdict)
            assertEquals(next, review.wait("local:1", Duration.ZERO, afterRound = 1).second)
            assertEquals(2, wakes.size)
        }
    }

    @Test
    fun deletionWakesALongPollWithMissing() = runBlocking {
        withTimeout(10.seconds) {
            val store = FakePlanStore()
            val _ = store.put(Plan("local:1", "Plan"), 0)
            val review = PlanReview(store)
            val waiting = async { review.wait("local:1", 5.seconds) }
            val _ = store.revisions.first { (it["local:1"] ?: 0) > 1 }
            store.delete("local:1")
            val [failure, response] = waiting.await()
            assertEquals(PlanResult.Missing, failure)
            assertNull(response)
        }
    }

    @Test
    fun reviewOutputIncludesOperatorDiffsAndUnresolvedQuestions() {
        val document = PlanDocument(Plan("local:1", "Plan", rev = 8), PlanReviewState(
            edits = listOf(EditRecord("s_intro", 1, 2, "Old\nline", "New", PlanActor.Operator, 1)),
            threads = listOf(PlanThread("th_why", "s_intro", ThreadKind.question, messages = listOf(
                ThreadMessage(PlanActor.Operator, "Why?", 0), ThreadMessage(PlanActor.Session("alice"), "Because.", 1),
            ))),
        ))
        assertEquals("""
            verdict: changes
            plan-rev: 8
            round: 1

            edit: s_intro (1 -> 2)
            - Old
            - line
            + New

            thread: th_why block: s_intro kind: question status: open
            operator: Why?
            session(alice): Because.
        """.trimIndent(), formatPlanReview(PlanReviewResponse("changes", 1, document)))
    }
}
