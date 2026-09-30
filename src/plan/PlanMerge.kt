package io.kotgent.plan

import kotlinx.serialization.Serializable

@Serializable
data class PlanSnapshot(
    val plan: Plan,
    // Deleted ids remain here so stale puts report deletions and generators cannot reuse their ids.
    val blockChangedAtRev: Map<String, Long>,
)

sealed interface PlanPutResult {
    data class Accepted(
        val snapshot: PlanSnapshot,
        val changedBlockIds: List<String>,
        val deletedBlockIds: List<String>,
    ) : PlanPutResult

    data class Conflict(val currentRev: Long, val changedBlockIds: List<String>) : PlanPutResult

    data class Invalid(val errors: List<FieldError>) : PlanPutResult
}

fun putPlan(
    current: PlanSnapshot?,
    incoming: Plan,
    baseRev: Long,
    generateId: (prefix: String) -> String,
): PlanPutResult {
    val currentRev = current?.plan?.rev ?: 0
    if (baseRev < 0 || baseRev > currentRev) {
        return PlanPutResult.Invalid(listOf(FieldError("baseRev", "must be between 0 and $currentRev")))
    }
    if (baseRev < currentRev) {
        return PlanPutResult.Conflict(currentRev, current!!.blockChangedAtRev.filterValues { it > baseRev }.keys.toList())
    }
    val basePlan = current?.plan ?: Plan(incoming.taskRef, incoming.title)
    val oldBlocks = basePlan.blocks().associateBy { it.id }
    val document = incoming.copy(
        status = basePlan.status,
        featureBranch = basePlan.featureBranch,
        concurrency = basePlan.concurrency,
        mode = basePlan.mode,
        tasks = incoming.tasks.map { task ->
            val old = oldBlocks[task.id] as? PlanTask
            task.copy(
                status = old?.status ?: PlanTaskStatus.pending,
                worker = old?.worker,
                steps = task.steps.map { step -> step.copy(done = (oldBlocks[step.id] as? Step)?.done ?: false) },
            )
        },
    )
    val errors = validatePlan(document).toMutableList()
    if (current != null && current.plan.taskRef != incoming.taskRef) {
        errors.add(FieldError("taskRef", "must match the current plan"))
    }
    fun known(block: PlanBlock, path: String) {
        if (block.id != null && block.id !in oldBlocks) errors.add(FieldError("$path.id", "does not identify a current block"))
    }
    document.sections.forEachIndexed { index, block -> known(block, "sections[$index]") }
    document.decisions.forEachIndexed { index, block -> known(block, "decisions[$index]") }
    document.tasks.forEachIndexed { index, task ->
        known(task, "tasks[$index]")
        task.steps.forEachIndexed { stepIndex, step -> known(step, "tasks[$index].steps[$stepIndex]") }
    }
    if (errors.isNotEmpty()) return PlanPutResult.Invalid(errors)

    val reserved = current?.blockChangedAtRev?.keys.orEmpty().toMutableSet()
    reserved.addAll(oldBlocks.keys.filterNotNull())
    fun id(block: PlanBlock, prefix: String, path: String): String {
        block.id?.let { return it }
        val generated = generateId(prefix)
        if (!isPlanId(generated, prefix)) errors.add(FieldError("$path.id", "generator must issue an id starting with $prefix"))
        if (!reserved.add(generated)) errors.add(FieldError("$path.id", "generator must issue an unused id"))
        return generated
    }
    fun revision(block: PlanBlock, old: PlanBlock?, path: String): Long {
        if (old == null) return 1
        if (sameAuthoredContent(block, old)) return old.rev
        if (old.rev == Long.MAX_VALUE) {
            errors.add(FieldError("$path.rev", "cannot increase the maximum revision"))
            return old.rev
        }
        return old.rev + 1
    }
    val sections = document.sections.mapIndexed { index, block ->
        val path = "sections[$index]"
        val old = oldBlocks[block.id] as? Section
        val merged = block.copy(id = id(block, "s_", path), rev = old?.rev ?: 1)
        merged.copy(rev = revision(merged, old, path))
    }
    val decisions = document.decisions.mapIndexed { index, block ->
        val path = "decisions[$index]"
        val old = oldBlocks[block.id] as? Decision
        val merged = block.copy(id = id(block, "d_", path), rev = old?.rev ?: 1)
        merged.copy(rev = revision(merged, old, path))
    }
    val tasks = document.tasks.mapIndexed { index, task ->
        val path = "tasks[$index]"
        val old = oldBlocks[task.id] as? PlanTask
        val taskId = id(task, "t_", path)
        val steps = task.steps.mapIndexed { stepIndex, step ->
            val stepPath = "$path.steps[$stepIndex]"
            val oldStep = oldBlocks[step.id] as? Step
            val merged = step.copy(id = id(step, "st_", stepPath), rev = oldStep?.rev ?: 1)
            merged.copy(rev = revision(merged, oldStep, stepPath))
        }
        val merged = task.copy(id = taskId, rev = old?.rev ?: 1, steps = steps)
        merged.copy(rev = revision(merged, old, path))
    }
    val merged = document.copy(rev = currentRev, sections = sections, decisions = decisions, tasks = tasks)
    if (errors.isNotEmpty()) return PlanPutResult.Invalid(errors)
    if (merged == current?.plan) return PlanPutResult.Accepted(current, emptyList(), emptyList())
    if (currentRev == Long.MAX_VALUE) {
        return PlanPutResult.Invalid(listOf(FieldError("rev", "cannot increase the maximum revision")))
    }
    val rev = currentRev + 1
    val plan = merged.copy(rev = rev)
    errors.addAll(validatePlan(plan))
    if (errors.isNotEmpty()) return PlanPutResult.Invalid(errors)
    val blocks = plan.blocks()
    val remainingIds = blocks.mapNotNull { it.id }.toSet()
    val deleted = oldBlocks.keys.filterNotNull().filter { it !in remainingIds }
    val changed = blocks.filter { !sameAuthoredContent(it, oldBlocks[it.id]) }.mapNotNull { it.id } + deleted
    val changedAtRev = current?.blockChangedAtRev.orEmpty() + changed.associateWith { rev }
    return PlanPutResult.Accepted(PlanSnapshot(plan, changedAtRev), changed, deleted)
}

private fun sameAuthoredContent(block: PlanBlock, old: PlanBlock?): Boolean = when (block) {
    is Section -> old is Section && block.kind == old.kind && block.body == old.body
    is Decision -> old is Decision && block.title == old.title && block.body == old.body
    is PlanTask -> old is PlanTask && block.title == old.title && block.ordinal == old.ordinal &&
        block.files == old.files && block.dependsOn == old.dependsOn && block.agent == old.agent
    is Step -> old is Step && block.text == old.text
}
