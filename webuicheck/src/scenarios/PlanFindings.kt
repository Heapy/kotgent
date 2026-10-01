package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.plan.*
import io.kotgent.store.PlanResult
import io.kotgent.webuicheck.Scenario

fun planFindingsScenario(mode: ExecutionMode = ExecutionMode.supervised): Scenario = Scenario(
    name = if (mode == ExecutionMode.supervised) "plan-findings" else "plan-findings-auto",
    seed = { fakes ->
        planReviewScenario().seed(fakes)
        seedSessionRow(fakes, harnessSession(id = "s-worker", name = "plan worker", agent = "claude", cwd = "/repo/worker",
            state = SessionState.running, createdAt = SEED_EPOCH_MS + 3, parentSessionId = "s-work").copy(taskRef = TaskRef("local:1")))
        val plans = fakes.planStore
        val root = PlanActor.Session("s-work")
        val worker = PlanActor.Session("s-worker")
        suspend fun act(action: PlanAction, actor: PlanActor = root): io.kotgent.store.PlanDocument {
            val result = plans.execute("local:1", action, actor)
            check(result is PlanResult.Accepted) { result.toString() }
            return result.document
        }
        check(plans.submitReview("local:1", 1, ReviewVerdict.approved) is PlanResult.Accepted)
        act(PlanAction.Settings(mode = mode))
        act(PlanAction.Claim(null))
        act(PlanAction.Start("t_3"))
        act(PlanAction.Worker("t_3", PlanWorker("s-worker", "worker/plan", "/repo/worker")))
        act(PlanAction.Status("t_3", PlanTaskStatus.in_review), worker)
        for ([condition, danger] in listOf("A rare redraw loses focus." to FindingLevel.low, "A stale reply replaces newer data." to FindingLevel.high)) {
            val doc = act(PlanAction.AddFinding(Finding(taskId = "t_3", condition = condition, impact = "The operator loses their review context.",
                location = "webui/src/plan.ts:12", danger = danger, likelihood = FindingLevel.high,
                options = listOf(FindingOption("Check the revision.", "Keep the newer document.", FindingLevel.low, FindingLevel.high),
                    FindingOption("Reload the page.", "Recover at the cost of the draft.", FindingLevel.medium, FindingLevel.low)), recommended = 0)))
            val finding = doc.execution.findings.last()
            act(PlanAction.Verify(requireNotNull(finding.id), finding.rev, FindingVerifier(danger, FindingLevel.medium,
                listOf(VerifierOption(FindingLevel.low, FindingLevel.high), VerifierOption(FindingLevel.medium, FindingLevel.low)),
                VerifierVerdict.confirmed, "The stale-response fixture reproduces this.")))
        }
        act(PlanAction.Status("t_3", PlanTaskStatus.awaiting_decision))
    },
    terminalUpstream = deterministicUpstream(WORKSPACE_BANNER),
)
