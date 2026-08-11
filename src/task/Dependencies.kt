package io.kotgent.task

import io.kotgent.core.TaskRef

/**
 * Tests whether adding `from -> to` reaches [from] again by following dependency edges from [to].
 * Existing edges are idempotent, so re-adding one is not reported as a cycle.
 */
fun wouldCycle(edges: Map<TaskRef, List<TaskRef>>, from: TaskRef, to: TaskRef): Boolean {
    if (from == to) return true
    val seen = mutableSetOf(to)
    val pending = ArrayDeque<TaskRef>()
    pending.addLast(to)
    while (pending.isNotEmpty()) {
        for (next in edges[pending.removeLast()].orEmpty()) {
            if (next == from) return true
            if (seen.add(next)) pending.addLast(next)
        }
    }
    return false
}
