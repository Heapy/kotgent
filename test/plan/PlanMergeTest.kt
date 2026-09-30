package io.kotgent.plan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanMergeTest {

    private fun document() = Plan(
        taskRef = "local:42",
        title = "Plan",
        sections = listOf(Section(kind = SectionKind.overview, body = "Overview")),
        decisions = listOf(Decision(title = "Decision", body = "Reason")),
        tasks = listOf(PlanTask(ordinal = 1, title = "Task", steps = listOf(Step(text = "Test")))),
    )

    private fun put(current: PlanSnapshot?, incoming: Plan, baseRev: Long = current?.plan?.rev ?: 0): PlanPutResult {
        var n = 0
        return putPlan(current, incoming, baseRev) { prefix -> "${prefix}${++n}" }
    }

    private fun created() = assertIs<PlanPutResult.Accepted>(put(null, document())).snapshot

    private fun executing(): PlanSnapshot {
        val current = created()
        return current.copy(plan = current.plan.copy(
            status = PlanStatus.executing,
            featureBranch = "feature/execution",
            concurrency = 2,
            mode = ExecutionMode.autonomous,
            tasks = current.plan.tasks.map { task -> task.copy(
                status = PlanTaskStatus.running,
                worker = PlanWorker("ses_worker", "task/worker", "/worktrees/task"),
                steps = task.steps.map { it.copy(done = true) },
            ) },
        ))
    }

    @Test
    fun createsBlocksWithInjectedIdsAndServerOwnedRevisions() {
        val incoming = document().copy(rev = 80, sections = listOf(
            Section(kind = SectionKind.overview, body = "Overview", rev = 50),
        ))
        val result = assertIs<PlanPutResult.Accepted>(put(null, incoming))
        assertEquals(1L, result.snapshot.plan.rev)
        assertEquals(listOf("s_1", "d_2", "t_3", "st_4"), result.changedBlockIds)
        assertEquals(emptyList(), result.deletedBlockIds)
        assertEquals(setOf(1L), result.snapshot.plan.blocks().map { it.rev }.toSet())
        assertEquals(mapOf("s_1" to 1L, "d_2" to 1L, "t_3" to 1L, "st_4" to 1L), result.snapshot.blockChangedAtRev)
        assertEquals(null, incoming.sections[0].id)
    }

    @Test
    fun firstPutUsesDefaultsForExecutionAndSettings() {
        val incoming = document().copy(
            status = PlanStatus.done,
            featureBranch = "",
            concurrency = 0,
            mode = ExecutionMode.autonomous,
            tasks = document().tasks.map { task -> task.copy(
                status = PlanTaskStatus.done,
                worker = PlanWorker("", "", ""),
                steps = task.steps.map { it.copy(done = true) },
            ) },
        )
        val result = assertIs<PlanPutResult.Accepted>(put(null, incoming))
        val plan = result.snapshot.plan
        assertEquals(PlanStatus.draft, plan.status)
        assertEquals(null, plan.featureBranch)
        assertEquals(3, plan.concurrency)
        assertEquals(ExecutionMode.supervised, plan.mode)
        assertEquals(PlanTaskStatus.pending, plan.tasks.single().status)
        assertEquals(null, plan.tasks.single().worker)
        assertFalse(plan.tasks.single().steps.single().done)
        assertEquals(1L, plan.rev)
        assertEquals(setOf(1L), plan.blocks().map { it.rev }.toSet())
    }

    @Test
    fun putDuringExecutionPreservesOwnedState() {
        val current = executing()
        val task = current.plan.tasks.single()
        val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(
            status = PlanStatus.draft,
            featureBranch = null,
            concurrency = 3,
            mode = ExecutionMode.supervised,
            tasks = listOf(task.copy(
                title = "Updated task",
                status = PlanTaskStatus.pending,
                worker = null,
                steps = task.steps.map { it.copy(text = "Updated step", done = false) },
            )),
        )))
        val plan = result.snapshot.plan
        assertEquals(current.plan.status, plan.status)
        assertEquals(current.plan.featureBranch, plan.featureBranch)
        assertEquals(current.plan.concurrency, plan.concurrency)
        assertEquals(current.plan.mode, plan.mode)
        assertEquals(task.status, plan.tasks.single().status)
        assertEquals(task.worker, plan.tasks.single().worker)
        assertTrue(plan.tasks.single().steps.single().done)
        assertEquals("Updated task", plan.tasks.single().title)
        assertEquals("Updated step", plan.tasks.single().steps.single().text)
        assertEquals(2L, plan.rev)
        assertEquals(2L, plan.tasks.single().rev)
        assertEquals(2L, plan.tasks.single().steps.single().rev)
        assertEquals(listOf("t_3", "st_4"), result.changedBlockIds)
    }

    @Test
    fun newBlocksUseDefaultsDuringExecution() {
        val current = executing()
        val task = current.plan.tasks.single()
        val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(tasks = listOf(
            task.copy(steps = task.steps + Step(text = "New step", done = true)),
            PlanTask(
                ordinal = 2,
                title = "New task",
                status = PlanTaskStatus.done,
                worker = PlanWorker("", "", ""),
                steps = listOf(Step(text = "New task step", done = true)),
            ),
        ))))
        val existing = result.snapshot.plan.tasks[0]
        val added = result.snapshot.plan.tasks[1]
        assertEquals(task.status, existing.status)
        assertEquals(task.worker, existing.worker)
        assertEquals(task.rev, existing.rev)
        assertTrue(existing.steps[0].done)
        assertFalse(existing.steps[1].done)
        assertEquals(PlanTaskStatus.pending, added.status)
        assertEquals(null, added.worker)
        assertFalse(added.steps.single().done)
        assertEquals(1L, added.rev)
        assertEquals(2L, result.snapshot.plan.rev)
        assertEquals(listOf("st_1", "t_2", "st_3"), result.changedBlockIds)
        assertEquals(1L, result.snapshot.blockChangedAtRev["t_3"])
    }

    @Test
    fun statusAndOtherOwnedStateDifferencesAreNoOpPuts() {
        val current = executing()
        val task = current.plan.tasks.single()
        val marks = current.plan.blocks().associate { it.id to ViewMark(it.id!!, it.rev) }
        val differences = listOf(
            current.plan.copy(status = PlanStatus.done),
            current.plan.copy(featureBranch = ""),
            current.plan.copy(concurrency = 0),
            current.plan.copy(mode = ExecutionMode.supervised),
            current.plan.copy(tasks = listOf(task.copy(status = PlanTaskStatus.done))),
            current.plan.copy(tasks = listOf(task.copy(worker = PlanWorker("", "", "")))),
            current.plan.copy(tasks = listOf(task.copy(steps = task.steps.map { it.copy(done = false) }))),
        )
        for (incoming in differences) {
            val result = assertIs<PlanPutResult.Accepted>(putPlan(current, incoming, current.plan.rev) { error("unused") })
            assertEquals(current, result.snapshot)
            assertEquals(emptyList(), result.changedBlockIds)
            assertEquals(emptyList(), result.deletedBlockIds)
            for (block in result.snapshot.plan.blocks()) {
                assertTrue(isViewed(block, marks[block.id]))
            }
        }
    }

    @Test
    fun unchangedPutDoesNotIssueIdsOrBumpAnyRevision() {
        val current = created()
        val incoming = current.plan.copy(rev = 900, sections = current.plan.sections.map { it.copy(rev = 800) })
        val result = assertIs<PlanPutResult.Accepted>(putPlan(current, incoming, current.plan.rev) { error("unused") })
        assertEquals(current, result.snapshot)
        assertEquals(emptyList(), result.changedBlockIds)
        assertEquals(emptyList(), result.deletedBlockIds)
    }

    @Test
    fun onePutBumpsThePlanOnceAndOnlyChangedBlocks() {
        val current = created()
        val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(
            title = "Updated",
            sections = current.plan.sections.map { it.copy(body = "New body") },
            decisions = current.plan.decisions.map { it.copy(title = "New decision") },
        )))
        assertEquals(2L, result.snapshot.plan.rev)
        assertEquals(2L, result.snapshot.plan.sections[0].rev)
        assertEquals(2L, result.snapshot.plan.decisions[0].rev)
        assertEquals(1L, result.snapshot.plan.tasks[0].rev)
        assertEquals(listOf("s_1", "d_2"), result.changedBlockIds)
        assertEquals(1L, current.plan.rev)
    }

    @Test
    fun stepContentChangesBumpAndReportOnlyTheStep() {
        val current = created()
        val task = current.plan.tasks[0]
        val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(tasks = listOf(
            task.copy(steps = task.steps.map { it.copy(text = "Updated step") }),
        ))))
        assertEquals(2L, result.snapshot.plan.rev)
        assertEquals(1L, result.snapshot.plan.tasks[0].rev)
        assertEquals(2L, result.snapshot.plan.tasks[0].steps[0].rev)
        assertEquals(listOf("st_4"), result.changedBlockIds)
        assertEquals(current.blockChangedAtRev + ("st_4" to 2L), result.snapshot.blockChangedAtRev)
        assertEquals(PlanPutResult.Conflict(2, listOf("st_4")), put(result.snapshot, current.plan, 1))
        assertTrue(isViewed(result.snapshot.plan.tasks[0], ViewMark(task.id!!, task.rev)))
        assertFalse(isViewed(result.snapshot.plan.tasks[0].steps[0], ViewMark(task.steps[0].id!!, task.steps[0].rev)))
    }

    @Test
    fun suppliedStepRevisionsAloneDoNotChangeContent() {
        val current = created()
        val task = current.plan.tasks[0]
        val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(tasks = listOf(
            task.copy(rev = 500, steps = task.steps.map { it.copy(rev = 600) }),
        ))))
        assertEquals(current, result.snapshot)
    }

    @Test
    fun everyBlockContentFieldParticipatesInRevisionComparison() {
        val current = created()
        val sectionChanges = listOf(
            current.plan.sections[0].copy(kind = SectionKind.context),
            current.plan.sections[0].copy(body = "Changed"),
        )
        for (section in sectionChanges) {
            val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(sections = listOf(section))))
            assertEquals(2L, result.snapshot.plan.sections[0].rev)
        }
        val decisionChanges = listOf(
            current.plan.decisions[0].copy(title = "Changed"),
            current.plan.decisions[0].copy(body = "Changed"),
        )
        for (decision in decisionChanges) {
            val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(decisions = listOf(decision))))
            assertEquals(2L, result.snapshot.plan.decisions[0].rev)
        }
        val task = current.plan.tasks[0]
        val taskChanges = listOf(
            task.copy(ordinal = 2),
            task.copy(title = "Changed"),
            task.copy(files = listOf(PlanFile("src/plan/Plan.kt", FileAction.modify))),
            task.copy(agent = PlanAgent.codex),
        )
        for (changed in taskChanges) {
            val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(tasks = listOf(changed))))
            assertEquals(2L, result.snapshot.plan.tasks[0].rev)
            assertEquals(listOf("t_3"), result.changedBlockIds)
            assertEquals(2L, result.snapshot.blockChangedAtRev["t_3"])
        }
    }

    @Test
    fun addingAStepKeepsExistingBlockRevisions() {
        val current = created()
        val task = current.plan.tasks[0]
        val result = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(tasks = listOf(
            task.copy(steps = task.steps + Step(text = "Implement")),
        ))))
        assertEquals(2L, result.snapshot.plan.rev)
        assertEquals(1L, result.snapshot.plan.tasks[0].rev)
        assertEquals(1L, result.snapshot.plan.tasks[0].steps[0].rev)
        assertEquals("st_1", result.snapshot.plan.tasks[0].steps[1].id)
        assertEquals(listOf("st_1"), result.changedBlockIds)
        assertEquals(current.blockChangedAtRev + ("st_1" to 2L), result.snapshot.blockChangedAtRev)
    }

    @Test
    fun deletingTasksAndStepsReportsOnlyDeletedIds() {
        val current = created()
        val deletedTask = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(tasks = emptyList())))
        assertEquals(listOf("t_3", "st_4"), deletedTask.deletedBlockIds)
        assertEquals(listOf("t_3", "st_4"), deletedTask.changedBlockIds)
        assertEquals(2L, deletedTask.snapshot.plan.rev)
        val deletedStep = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(
            tasks = current.plan.tasks.map { it.copy(steps = emptyList()) },
        )))
        assertEquals(listOf("st_4"), deletedStep.deletedBlockIds)
        assertEquals(listOf("st_4"), deletedStep.changedBlockIds)
        assertEquals(2L, deletedStep.snapshot.plan.rev)
        assertEquals(1L, deletedStep.snapshot.plan.tasks[0].rev)
        assertEquals(current.blockChangedAtRev + ("st_4" to 2L), deletedStep.snapshot.blockChangedAtRev)
    }

    @Test
    fun absentSectionsAndDecisionsAreDeletedAndNewIdsAreIssued() {
        val current = created()
        var n = 10
        val result = assertIs<PlanPutResult.Accepted>(putPlan(current, current.plan.copy(
            sections = listOf(Section(kind = SectionKind.context, body = "New")),
            decisions = emptyList(),
        ), 1) { prefix -> "${prefix}${++n}" })
        assertEquals("s_11", result.snapshot.plan.sections.single().id)
        assertEquals(listOf("s_1", "d_2"), result.deletedBlockIds)
        assertEquals(listOf("s_11", "s_1", "d_2"), result.changedBlockIds)
        assertEquals(2L, result.snapshot.blockChangedAtRev["s_1"])
    }

    @Test
    fun staleBaseReturnsIdsChangedSinceThePlanRevisionIncludingDeletedBlocks() {
        val initial = created()
        val changed = assertIs<PlanPutResult.Accepted>(put(initial, initial.plan.copy(
            sections = initial.plan.sections.map { it.copy(body = "Changed") },
        ))).snapshot
        val deleted = assertIs<PlanPutResult.Accepted>(put(changed, changed.plan.copy(decisions = emptyList()))).snapshot
        assertEquals(
            PlanPutResult.Conflict(3, listOf("s_1", "d_2")),
            put(deleted, initial.plan, 1),
        )
        assertEquals(PlanPutResult.Conflict(3, listOf("d_2")), put(deleted, changed.plan, 2))
    }

    @Test
    fun blockRevisionAndPlanRevisionAreIndependentForConflicts() {
        val initial = created()
        val metadata = assertIs<PlanPutResult.Accepted>(put(initial, initial.plan.copy(title = "Two"))).snapshot
        val changed = assertIs<PlanPutResult.Accepted>(put(metadata, metadata.plan.copy(
            sections = metadata.plan.sections.map { it.copy(body = "Changed") },
        ))).snapshot
        assertEquals(2L, changed.plan.sections[0].rev)
        assertEquals(3L, changed.blockChangedAtRev["s_1"])
        assertEquals(PlanPutResult.Conflict(3, listOf("s_1")), put(changed, metadata.plan, 2))
        assertEquals(PlanPutResult.Conflict(2, emptyList()), put(metadata, initial.plan, 1))
    }

    @Test
    fun aStalePutReturnsConflictBeforeValidationOrIdIssuance() {
        val current = created()
        assertIs<PlanPutResult.Conflict>(putPlan(current, Plan("", ""), 0) { error("unused") })
    }

    @Test
    fun titleAndOrderingChangeThePlanWithoutChangingBlockRevisions() {
        val current = created()
        val title = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(title = "Updated plan")))
        assertEquals(2L, title.snapshot.plan.rev)
        assertEquals(emptyList(), title.changedBlockIds)
        assertEquals(current.blockChangedAtRev, title.snapshot.blockChangedAtRev)
        val two = assertIs<PlanPutResult.Accepted>(put(null, document().copy(sections = listOf(
            Section(kind = SectionKind.overview, body = "A"),
            Section(kind = SectionKind.context, body = "B"),
        )))).snapshot
        val reordered = assertIs<PlanPutResult.Accepted>(put(two, two.plan.copy(sections = two.plan.sections.reversed())))
        assertEquals(2L, reordered.snapshot.plan.rev)
        assertEquals(emptyList(), reordered.changedBlockIds)
        assertEquals(two.blockChangedAtRev, reordered.snapshot.blockChangedAtRev)
    }

    @Test
    fun listPositionsDoNotChangeTaskOrStepRevisions() {
        val current = assertIs<PlanPutResult.Accepted>(put(null, document().copy(tasks = listOf(
            PlanTask(ordinal = 1, title = "First", steps = listOf(Step(text = "A"), Step(text = "B"))),
            PlanTask(ordinal = 2, title = "Second", steps = listOf(Step(text = "C"), Step(text = "D"))),
        )))).snapshot
        val tasks = current.plan.tasks.reversed().map { it.copy(steps = it.steps.reversed()) }
        val result = assertIs<PlanPutResult.Accepted>(putPlan(current, current.plan.copy(tasks = tasks), 1) { error("unused") })
        assertEquals(tasks, result.snapshot.plan.tasks)
        assertEquals(2L, result.snapshot.plan.rev)
        assertEquals(emptyList(), result.changedBlockIds)
        assertEquals(emptyList(), result.deletedBlockIds)
        assertEquals(current.blockChangedAtRev, result.snapshot.blockChangedAtRev)
    }

    @Test
    fun movingAStepPreservesItsOwnedStateAndRevision() {
        val created = assertIs<PlanPutResult.Accepted>(put(null, document().copy(tasks = listOf(
            PlanTask(ordinal = 1, title = "First", steps = listOf(Step(text = "Move"))),
            PlanTask(ordinal = 2, title = "Second"),
        )))).snapshot
        val current = created.copy(plan = created.plan.copy(tasks = listOf(
            created.plan.tasks[0].copy(steps = created.plan.tasks[0].steps.map { it.copy(done = true) }),
            created.plan.tasks[1],
        )))
        val step = current.plan.tasks[0].steps.single()
        val result = assertIs<PlanPutResult.Accepted>(putPlan(current, current.plan.copy(tasks = listOf(
            current.plan.tasks[0].copy(steps = emptyList()),
            current.plan.tasks[1].copy(steps = listOf(step.copy(done = false))),
        )), 1) { error("unused") })
        assertEquals(emptyList(), result.snapshot.plan.tasks[0].steps)
        assertEquals(listOf(step), result.snapshot.plan.tasks[1].steps)
        assertEquals(current.plan.tasks.map { it.rev }, result.snapshot.plan.tasks.map { it.rev })
        assertEquals(2L, result.snapshot.plan.rev)
        assertEquals(emptyList(), result.changedBlockIds)
        assertEquals(emptyList(), result.deletedBlockIds)
        assertEquals(current.blockChangedAtRev, result.snapshot.blockChangedAtRev)
    }

    @Test
    fun unknownIdsAndChangedTaskRefAreFieldErrors() {
        val current = created()
        val result = assertIs<PlanPutResult.Invalid>(put(current, current.plan.copy(
            taskRef = "local:other",
            sections = listOf(Section("s_unknown", SectionKind.overview, "Body")),
        )))
        assertEquals(setOf("taskRef", "sections[0].id"), result.errors.map { it.path }.toSet())
        assertIs<PlanPutResult.Invalid>(put(null, document().copy(
            decisions = listOf(Decision("d_unknown", "Title", "Body")),
        )))
    }

    @Test
    fun malformedInputAndFutureOrNegativeBasesAreRejectedWithoutIssuingIds() {
        val current = created()
        assertIs<PlanPutResult.Invalid>(putPlan(current, document().copy(title = ""), 1) { error("unused") })
        assertEquals(listOf("baseRev"), assertIs<PlanPutResult.Invalid>(put(current, current.plan, 2)).errors.map { it.path })
        assertEquals(listOf("baseRev"), assertIs<PlanPutResult.Invalid>(put(null, document(), -1)).errors.map { it.path })
    }

    @Test
    fun aGeneratorCannotReuseExistingDeletedOrNewIds() {
        val current = created()
        val incoming = current.plan.copy(sections = listOf(Section(kind = SectionKind.context, body = "New")))
        assertIs<PlanPutResult.Invalid>(putPlan(current, incoming, 1) { "s_1" })
        val deleted = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(sections = emptyList()))).snapshot
        assertIs<PlanPutResult.Invalid>(putPlan(deleted, incoming, 2) { "s_1" })
        assertIs<PlanPutResult.Invalid>(putPlan(null, document().copy(sections = listOf(
            Section(kind = SectionKind.overview, body = "One"),
            Section(kind = SectionKind.context, body = "Two"),
        )), 0) { prefix -> "${prefix}same" })
        assertIs<PlanPutResult.Invalid>(putPlan(null, document(), 0) { "bad" })
    }

    @Test
    fun revisionsNeverOverflow() {
        val current = created()
        assertIs<PlanPutResult.Invalid>(put(current.copy(plan = current.plan.copy(rev = Long.MAX_VALUE)), current.plan.copy(title = "New")))
        val maxBlock = current.copy(plan = current.plan.copy(sections = current.plan.sections.map { it.copy(rev = Long.MAX_VALUE) }))
        assertIs<PlanPutResult.Invalid>(put(maxBlock, maxBlock.plan.copy(sections = maxBlock.plan.sections.map { it.copy(body = "New") })))
    }

    @Test
    fun acceptedDocumentsStayWithinTheBoundAfterRevisionGrowth() {
        val json = Json { encodeDefaults = true }
        val plan = Plan("local:42", "Before", rev = 9)
        val current = PlanSnapshot(plan, emptyMap())
        val incoming = plan.copy(title = "x")
        val overhead = json.encodeToString(incoming).encodeToByteArray().size - 1
        val exact = incoming.copy(title = "a".repeat(MAX_PLAN_DOCUMENT_BYTES - overhead))
        assertEquals(emptyList(), validatePlan(exact))
        val result = assertIs<PlanPutResult.Invalid>(put(current, exact))
        assertEquals(listOf("document"), result.errors.map { it.path })
    }

    @Test
    fun unchangedBlocksKeepViewMarksAndChangedBlocksResetThem() {
        val current = created()
        val section = current.plan.sections[0]
        val mark = ViewMark(section.id!!, section.rev)
        val unchanged = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(title = "New title"))).snapshot
        assertTrue(isViewed(unchanged.plan.sections[0], mark))
        val changed = assertIs<PlanPutResult.Accepted>(put(current, current.plan.copy(
            sections = listOf(section.copy(body = "New body")),
        ))).snapshot
        assertFalse(isViewed(changed.plan.sections[0], mark))
    }

    @Test
    fun revisionMetadataSurvivesSerialization() {
        val current = created()
        assertEquals(current, Json.decodeFromString<PlanSnapshot>(Json.encodeToString(current)))
    }
}
