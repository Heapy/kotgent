package io.kotgent.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanExecutionActionsTest {
    private class Scenario {
        var plan = Plan("local:1", "Execution", status = PlanStatus.approved, concurrency = 1,
            tasks = listOf(PlanTask("t_one", 1, "First", steps = listOf(Step("st_one", "Check"))),
                PlanTask("t_two", 2, "Second", dependsOn = listOf("t_one"))))
        var execution = PlanExecutionState(orchestratorSessionId = "root")
        var nextId = 0
        fun run(action: PlanAction, actor: PlanActor = PlanActor.Session("root")): ExecutionChange =
            applyPlanAction(plan, execution, action, actor, 100, { it + (++nextId) }).also {
                if (it is ExecutionChange.Accepted) { plan = it.plan; execution = it.execution }
            }
        fun accept(action: PlanAction, actor: PlanActor = PlanActor.Session("root")) { assertIs<ExecutionChange.Accepted>(run(action, actor)) }
        fun reject(action: PlanAction, actor: PlanActor, failure: ExecutionFailure) {
            assertEquals(failure, assertIs<ExecutionChange.Rejected>(run(action, actor)).failure)
        }
        fun start() {
            accept(PlanAction.Start("t_one"))
            accept(PlanAction.Worker("t_one", PlanWorker("worker", "work/one", "/work/one")))
        }
        fun review() { accept(PlanAction.Status("t_one", PlanTaskStatus.in_review), PlanActor.Session("worker")) }
        fun finding(): Finding {
            accept(PlanAction.AddFinding(Finding(taskId = "t_one", condition = "When data is empty", impact = "Export crashes",
                danger = FindingLevel.high, likelihood = FindingLevel.medium,
                options = listOf(FindingOption("Guard empty input", "No crash", FindingLevel.low, FindingLevel.high)), recommended = 0)), PlanActor.Session("reviewer"))
            return execution.findings.last()
        }
        fun verify(finding: Finding): Finding {
            accept(PlanAction.Verify(requireNotNull(finding.id), finding.rev, FindingVerifier(FindingLevel.high, FindingLevel.medium,
                listOf(VerifierOption(FindingLevel.low, FindingLevel.high)), VerifierVerdict.confirmed, "Reproduced")), PlanActor.Session("verifier"))
            return execution.findings.last()
        }
    }

    @Test fun dependenciesConcurrencyAndActorChecksGuardTheCompleteLifecycle() {
        val s = Scenario()
        s.reject(PlanAction.Start("t_one"), PlanActor.Operator, ExecutionFailure.forbidden)
        s.reject(PlanAction.Start("t_two"), PlanActor.Session("root"), ExecutionFailure.conflict)
        s.start()
        s.reject(PlanAction.Status("t_one", PlanTaskStatus.in_review), PlanActor.Session("stranger"), ExecutionFailure.forbidden)
        s.reject(PlanAction.StepDone("st_one"), PlanActor.Session("stranger"), ExecutionFailure.forbidden)
        s.accept(PlanAction.StepDone("st_one"), PlanActor.Session("worker"))
        assertTrue(s.plan.tasks.first().steps.single().done)
        s.review()
        s.accept(PlanAction.Status("t_one", PlanTaskStatus.merging))
        s.accept(PlanAction.Status("t_one", PlanTaskStatus.done))
        s.accept(PlanAction.Start("t_two"))
        assertEquals(PlanStatus.executing, s.plan.status)
        s.reject(PlanAction.Complete, PlanActor.Session("root"), ExecutionFailure.conflict)
    }

    @Test fun verifiedSupervisedDecisionsAreFrozenPerReviewAndFeedbackIsDurable() {
        val s = Scenario()
        s.start(); s.review()
        val finding = s.finding()
        s.reject(PlanAction.Verify(finding.id!!, finding.rev, FindingVerifier()), PlanActor.Operator, ExecutionFailure.forbidden)
        val verified = s.verify(finding)
        val decision = FindingDecision(FindingDecisionKind.fix_now, 0)
        s.reject(PlanAction.Decide(verified.id!!, verified.rev, decision), PlanActor.Session("worker"), ExecutionFailure.forbidden)
        s.accept(PlanAction.Settings(mode = ExecutionMode.autonomous), PlanActor.Operator)
        assertEquals(ExecutionMode.supervised, s.execution.reviews.last().mode)
        s.reject(PlanAction.Feedback("t_one", listOf(verified.id)), PlanActor.Session("root"), ExecutionFailure.conflict)
        s.accept(PlanAction.Decide(verified.id, verified.rev, decision), PlanActor.Operator)
        s.accept(PlanAction.Send("t_one"), PlanActor.Operator)
        val event = s.execution.events.last()
        assertEquals("feedback", event.kind)
        assertEquals("worker", event.workerSessionId)
        assertEquals(listOf(verified.id), event.findingIds)
        s.review()
        assertEquals(2L, s.execution.reviews.last().iteration)
        assertEquals(ExecutionMode.autonomous, s.execution.reviews.last().mode)
        val second = s.verify(s.finding())
        s.reject(PlanAction.Decide(second.id!!, second.rev, decision), PlanActor.Operator, ExecutionFailure.forbidden)
        s.accept(PlanAction.Decide(second.id, second.rev, decision.copy(note = "Reproduced and will fix")), PlanActor.Session("worker"))
    }

    @Test fun amendmentsInvalidateVerifierAndAStaleDecisionCannotApply() {
        val s = Scenario()
        s.start(); s.review()
        val finding = s.verify(s.finding())
        s.accept(PlanAction.Amend(finding.id!!, finding.rev, finding.copy(impact = "Export loses selected rows")), PlanActor.Session("investigator"))
        val changed = s.execution.findings.single()
        assertNull(changed.verifier)
        assertNotNull(changed.revisions.single())
        assertEquals(finding.rev + 1, changed.rev)
        s.reject(PlanAction.Decide(finding.id, finding.rev, FindingDecision(FindingDecisionKind.wont_fix)), PlanActor.Operator, ExecutionFailure.conflict)
        val before = s.execution.events.size
        s.accept(PlanAction.WorkerEnded("worker"), PlanActor.Operator)
        s.accept(PlanAction.WorkerEnded("worker"), PlanActor.Operator)
        assertEquals(before + 1, s.execution.events.size, "duplicate end callbacks do not duplicate durable events")
        assertEquals("worker_lost", s.execution.events.last().kind)
        assertEquals(PlanTaskStatus.blocked, s.plan.tasks.first().status)
    }
}
