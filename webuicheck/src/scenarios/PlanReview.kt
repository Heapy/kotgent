package io.kotgent.webuicheck.scenarios

import io.kotgent.plan.*
import io.kotgent.store.PlanResult
import io.kotgent.webuicheck.Scenario

fun planReviewScenario(): Scenario = Scenario(
    name = "plan-review",
    seed = { fakes ->
        workspaceScenario().seed(fakes)
        val plan = Plan(
            taskRef = "local:1", title = "Review the workspace plan",
            sections = listOf(
                Section(kind = SectionKind.overview, body = "A **safe** plan.\n\n<script>window.planXss=true</script>\n\n[bad](javascript:alert(1))\n\n| Choice | Result |\n| --- | --- |\n| Tabs | Keep terminal |"),
                Section(kind = SectionKind.solution, body = "Keep one terminal connection."),
            ),
            tasks = listOf(PlanTask(ordinal = 1, title = "Render the plan", steps = listOf(Step(text = "Test the review flow")))),
        )
        val put = fakes.planStore.put(plan, 0)
        check(put is PlanResult.Accepted) { put.toString() }
        check(fakes.planStore.openReview("local:1") is PlanResult.Accepted)
    },
    terminalUpstream = deterministicUpstream(WORKSPACE_BANNER),
)
