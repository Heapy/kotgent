package io.kotgent.store

import kotlin.time.Clock

class FakePlanStore(
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    newId: (String) -> String = { it + randomOpaqueId() },
) : PlanStore by PlanCoordinator(MemoryPlanRows(), now, newId)

private class MemoryPlanRows : PlanRows {
    private val plans = linkedMapOf<String, StoredPlan>()
    override fun get(ref: String): StoredPlan? = plans[ref]
    override fun all(): List<StoredPlan> = plans.values.toList()
    override fun save(plan: StoredPlan) { plans[plan.snapshot.plan.taskRef] = plan }
    override fun delete(ref: String) { val _ = plans.remove(ref) }
}
