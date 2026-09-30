package io.kotgent.plan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionTest {

    private val worker = PlanWorker("worker", "task/core", "/worktrees/core")
    private val orchestrator = ExecutionCaller.Orchestrator("orchestrator")
    private val session = ExecutionCaller.Session("worker")

    private fun task(id: String, ordinal: Int, status: PlanTaskStatus = PlanTaskStatus.pending, dependsOn: List<String> = emptyList()) =
        PlanTask(id, ordinal, id, status = status, dependsOn = dependsOn, worker = worker)

    private fun plan(vararg tasks: PlanTask, concurrency: Int = 3) =
        Plan("local:42", "Plan", tasks = tasks.toList(), concurrency = concurrency)

    private fun rejection(task: PlanTask, to: PlanTaskStatus, caller: ExecutionCaller) =
        assertIs<TaskTransitionResult.Rejected>(transitionTask(task, to, caller)).reason

    @Test
    fun dependencyValidationAcceptsAChainDiamondAndDuplicateEdges() {
        val tasks = listOf(
            task("t_a", 1, dependsOn = listOf("t_b", "t_c", "t_b")),
            task("t_b", 2, dependsOn = listOf("t_d")),
            task("t_c", 3, dependsOn = listOf("t_d")),
            task("t_d", 4),
        )
        assertEquals(emptyList(), validatePlanDependencies(tasks))
        assertEquals(emptyList(), validatePlan(plan(*tasks.toTypedArray())))
    }

    @Test
    fun danglingAndNonTaskDependenciesReturnTheEdgePath() {
        val document = plan(task("t_a", 1, dependsOn = listOf("t_missing", "s_other")))
        assertEquals(
            setOf("tasks[0].dependsOn[0]", "tasks[0].dependsOn[1]"),
            validatePlanDependencies(document.tasks).map { it.path }.toSet(),
        )
        assertEquals(validatePlanDependencies(document.tasks), validatePlan(document))
        assertEquals(emptyList(), readyTasks(document))
    }

    @Test
    fun selfEdgesAndTransitiveCyclesAreRejected() {
        val self = listOf(task("t_a", 1, dependsOn = listOf("t_a")))
        assertEquals(listOf("tasks[0].dependsOn[0]"), validatePlanDependencies(self).map { it.path })
        val ring = listOf(
            task("t_a", 1, dependsOn = listOf("t_b")),
            task("t_b", 2, dependsOn = listOf("t_c")),
            task("t_c", 3, dependsOn = listOf("t_a")),
            task("t_d", 4),
        )
        assertTrue(validatePlanDependencies(ring).any { it.message.contains("cycle") })
        assertTrue(validatePlan(plan(*ring.toTypedArray())).any { it.path.contains("dependsOn") })
    }

    @Test
    fun deepGraphsAreWalkedWithoutRecursion() {
        val depth = 10_000
        val chain = (0 until depth).map { index ->
            task("t_$index", index + 1, dependsOn = if (index + 1 < depth) listOf("t_${index + 1}") else emptyList())
        }
        assertEquals(emptyList(), validatePlanDependencies(chain))
        val ring = chain.dropLast(1) + chain.last().copy(dependsOn = listOf("t_0"))
        assertTrue(validatePlanDependencies(ring).isNotEmpty())
    }

    @Test
    fun readyTasksRequireCompletedDependenciesAndUseOrdinalOrder() {
        val document = plan(
            task("t_later", 3),
            task("t_dependent", 2, dependsOn = listOf("t_done")),
            task("t_done", 4, PlanTaskStatus.done),
            task("t_first", 1),
            task("t_waiting", 5, dependsOn = listOf("t_later")),
            task("t_blocked", 6, PlanTaskStatus.blocked),
        )
        assertEquals(listOf("t_first", "t_dependent", "t_later"), readyTasks(document).map { it.id })
    }

    @Test
    fun everyActiveStatusOccupiesAConcurrencySlot() {
        for (status in listOf(PlanTaskStatus.running, PlanTaskStatus.in_review, PlanTaskStatus.awaiting_decision, PlanTaskStatus.merging)) {
            val document = plan(task("t_active", 1, status), task("t_a", 2), task("t_b", 3), concurrency = 2)
            assertEquals(listOf("t_a"), readyTasks(document).map { it.id }, status.name)
            assertEquals(emptyList(), readyTasks(document.copy(concurrency = 1)), status.name)
        }
        assertEquals(emptyList(), readyTasks(plan(
            task("t_one", 1, PlanTaskStatus.running), task("t_two", 2, PlanTaskStatus.merging), task("t_pending", 3),
            concurrency = 1,
        )))
    }

    @Test
    fun blockedAndDoneTasksDoNotOccupySlotsAndUnissuedTasksAreNotReady() {
        val document = plan(task("t_blocked", 1, PlanTaskStatus.blocked), task("t_done", 2, PlanTaskStatus.done), task("t_ready", 3), concurrency = 1)
        assertEquals(listOf("t_ready"), readyTasks(document).map { it.id })
        assertEquals(emptyList(), readyTasks(document.copy(concurrency = 0)))
        assertEquals(emptyList(), readyTasks(plan(PlanTask(ordinal = 1, title = "New"))))
    }

    @Test
    fun executionSettingsAndWorkerFieldsReturnIndividualErrors() {
        val document = plan(task("t_one", 1).copy(worker = PlanWorker("", " ", "")), concurrency = 0).copy(featureBranch = " ")
        assertEquals(
            setOf("concurrency", "featureBranch", "tasks[0].worker.sessionId", "tasks[0].worker.branch", "tasks[0].worker.worktree"),
            validatePlan(document).map { it.path }.toSet(),
        )
        assertEquals(emptyList(), validatePlan(plan(task("t_one", 1)).copy(featureBranch = "feature/plans")))
    }

    @Test
    fun onlyTheOrchestratorMayStartOrRestartTasks() {
        for (from in listOf(PlanTaskStatus.pending, PlanTaskStatus.blocked)) {
            val before = task("t_one", 1, from)
            val after = assertIs<TaskTransitionResult.Accepted>(transitionTask(before, PlanTaskStatus.running, orchestrator)).task
            assertEquals(before.copy(status = PlanTaskStatus.running), after)
            assertEquals(TaskTransitionRejection.orchestrator_required, rejection(before, PlanTaskStatus.running, session))
            assertEquals(TaskTransitionRejection.orchestrator_required, rejection(before, PlanTaskStatus.running, ExecutionCaller.Operator))
        }
    }

    @Test
    fun onlyTheAssignedWorkerMayFinishOrBlock() {
        val running = task("t_one", 1, PlanTaskStatus.running)
        assertIs<TaskTransitionResult.Accepted>(transitionTask(running, PlanTaskStatus.in_review, session))
        for (from in listOf(PlanTaskStatus.running, PlanTaskStatus.in_review, PlanTaskStatus.awaiting_decision, PlanTaskStatus.merging)) {
            val before = running.copy(status = from)
            assertIs<TaskTransitionResult.Accepted>(transitionTask(before, PlanTaskStatus.blocked, session))
            assertEquals(TaskTransitionRejection.wrong_worker, rejection(before, PlanTaskStatus.blocked, ExecutionCaller.Session("other")))
            assertEquals(TaskTransitionRejection.worker_required, rejection(before, PlanTaskStatus.blocked, orchestrator))
            assertEquals(TaskTransitionRejection.worker_required, rejection(before, PlanTaskStatus.blocked, ExecutionCaller.Operator))
        }
        assertEquals(TaskTransitionRejection.wrong_worker, rejection(running, PlanTaskStatus.in_review, ExecutionCaller.Session("other")))
        assertEquals(TaskTransitionRejection.worker_required, rejection(running.copy(worker = null), PlanTaskStatus.in_review, session))
    }

    @Test
    fun theOrchestratorOwnsReviewAndMergeProgress() {
        for (edge in listOf(
            PlanTaskStatus.in_review to PlanTaskStatus.awaiting_decision,
            PlanTaskStatus.in_review to PlanTaskStatus.merging,
            PlanTaskStatus.awaiting_decision to PlanTaskStatus.merging,
            PlanTaskStatus.merging to PlanTaskStatus.done,
        )) {
            val from = edge.first
            val to = edge.second
            val before = task("t_one", 1, from)
            assertIs<TaskTransitionResult.Accepted>(transitionTask(before, to, orchestrator))
            assertEquals(TaskTransitionRejection.orchestrator_required, rejection(before, to, session))
            assertEquals(TaskTransitionRejection.orchestrator_required, rejection(before, to, ExecutionCaller.Operator))
        }
    }

    @Test
    fun feedbackAndRebaseResumeWorkOnlyForTheOrchestratorOrOperator() {
        for (from in listOf(PlanTaskStatus.in_review, PlanTaskStatus.awaiting_decision, PlanTaskStatus.merging)) {
            val before = task("t_one", 1, from)
            assertIs<TaskTransitionResult.Accepted>(transitionTask(before, PlanTaskStatus.running, orchestrator))
            assertIs<TaskTransitionResult.Accepted>(transitionTask(before, PlanTaskStatus.running, ExecutionCaller.Operator))
            assertEquals(TaskTransitionRejection.feedback_caller_required, rejection(before, PlanTaskStatus.running, session))
        }
    }

    @Test
    fun allOtherStatusEdgesAndNoOpTransitionsAreRejected() {
        val allowed = setOf(
            PlanTaskStatus.pending to PlanTaskStatus.running,
            PlanTaskStatus.blocked to PlanTaskStatus.running,
            PlanTaskStatus.running to PlanTaskStatus.in_review,
            PlanTaskStatus.running to PlanTaskStatus.blocked,
            PlanTaskStatus.in_review to PlanTaskStatus.awaiting_decision,
            PlanTaskStatus.in_review to PlanTaskStatus.merging,
            PlanTaskStatus.in_review to PlanTaskStatus.running,
            PlanTaskStatus.in_review to PlanTaskStatus.blocked,
            PlanTaskStatus.awaiting_decision to PlanTaskStatus.merging,
            PlanTaskStatus.awaiting_decision to PlanTaskStatus.running,
            PlanTaskStatus.awaiting_decision to PlanTaskStatus.blocked,
            PlanTaskStatus.merging to PlanTaskStatus.done,
            PlanTaskStatus.merging to PlanTaskStatus.running,
            PlanTaskStatus.merging to PlanTaskStatus.blocked,
        )
        for (from in PlanTaskStatus.entries) {
            for (to in PlanTaskStatus.entries) {
                if (from to to in allowed) continue
                for (caller in listOf(orchestrator, session, ExecutionCaller.Operator)) {
                    assertEquals(TaskTransitionRejection.invalid_transition, rejection(task("t_one", 1, from), to, caller), "$from -> $to")
                }
            }
        }
    }

    @Test
    fun modeChangesAreRecordedButApplyOnlyToTheNextIteration() {
        val review = TaskReview("t_one", mode = ExecutionMode.autonomous)
        val switched = switchReviewMode(review, ExecutionMode.supervised)
        assertEquals(ExecutionMode.autonomous, switched.mode)
        assertEquals(ExecutionMode.supervised, switched.nextMode)
        assertEquals(review.iteration, switched.iteration)
        val next = nextTaskReview(switched)
        assertEquals(TaskReview("t_one", 2, ExecutionMode.supervised), next)
        assertEquals(next, Json.decodeFromString<TaskReview>(Json.encodeToString(next)))
        assertEquals(ExecutionMode.autonomous, review.nextMode)
    }

    @Test
    fun theMostRecentModeSwitchWinsForTheNextIteration() {
        val review = TaskReview("t_one", mode = ExecutionMode.supervised)
        val switched = switchReviewMode(switchReviewMode(review, ExecutionMode.autonomous), ExecutionMode.supervised)
        assertEquals(ExecutionMode.supervised, nextTaskReview(switched).mode)
    }

    @Test
    fun authoredExecutionFieldsParticipateInMergeRevisions() {
        val created = assertIs<PlanPutResult.Accepted>(putPlan(null, plan(PlanTask(ordinal = 1, title = "Task")), 0) { prefix -> "${prefix}one" }).snapshot
        val original = created.plan.tasks.single()
        for (changed in listOf(
            original.copy(agent = PlanAgent.claude),
            original.copy(agent = PlanAgent.codex),
        )) {
            val result = assertIs<PlanPutResult.Accepted>(putPlan(created, created.plan.copy(tasks = listOf(changed)), 1) { error("unused") })
            assertEquals(2L, result.snapshot.plan.tasks.single().rev)
            assertEquals(listOf("t_one"), result.changedBlockIds)
        }
        var n = 0
        val two = assertIs<PlanPutResult.Accepted>(putPlan(null, plan(
            PlanTask(ordinal = 1, title = "A"), PlanTask(ordinal = 2, title = "B"),
        ), 0) { prefix -> "${prefix}${++n}" }).snapshot
        val result = assertIs<PlanPutResult.Accepted>(putPlan(two, two.plan.copy(tasks = listOf(
            two.plan.tasks[0].copy(dependsOn = listOf("t_2")), two.plan.tasks[1],
        )), 1) { error("unused") })
        assertEquals(listOf("t_1"), result.changedBlockIds)
    }
}
