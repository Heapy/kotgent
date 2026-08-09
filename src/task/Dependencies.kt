package io.kotgent.task

import io.kotgent.core.TaskRef

/*
 * Dependency-graph rules — pure, no storage.
 *
 * An edge means "[from] depends on [to]", i.e. [to] must be `done` before [from] is workable. A task is
 * `blocked` when it is `todo` and some dependency is not `done`; `nextCandidate` skips those.
 *
 * All four refusals are validated ON INSERT — self, unknown ref, cross-project, cycle — because a
 * dangling or cross-project edge would otherwise be accepted and then read as "already satisfied" by the
 * candidate query's join, silently unblocking a task that is not ready.
 *
 * ## Only two of those four live here
 *
 * [wouldCycle] answers the cycle refusal and, as a degenerate case of it, the self refusal. The other two
 * — the refs exist, and both sit in the same project — are questions about rows, so they belong to the
 * store (`BacklogDependencies.add`) and are deliberately NOT re-asked here: a ref this function has never
 * heard of simply has no dependencies, exactly like a leaf. Feeding it a graph read from one project is
 * the caller's job, and is what makes "same project" and "reachable" separate questions.
 *
 * ## Why a false negative is the expensive direction
 *
 * The cycle refusal is not a tidiness rule. `blocked` is "some dependency is not `done`", so every task on
 * a dependency cycle is blocked by another task on that same cycle, and nothing on it can ever be `done`
 * first. `nextCandidate` then skips the whole ring forever: the backlog still lists the work, the board
 * still shows the cards, and `task next` answers "nothing eligible" with no indication of why. There is no
 * repair path short of a human deleting an edge. So the walk is exhaustive rather than depth-bounded, and
 * the test file spells out the shapes — direct, transitive, self, diamond — instead of trusting one
 * example.
 */

/**
 * Whether adding "[from] depends on [to]" would close a cycle in [edges].
 *
 * [edges] maps a ref to the refs it DEPENDS ON (the same direction as `backlog_deps.task_ref →
 * depends_on`), and need not already contain the proposed edge. A pure ancestor walk: `true` when [from]
 * is reachable from [to] by following dependencies, and `true` for the degenerate `from == to`.
 *
 * The walk starts at [to] and looks for [from] — never the other way round — because the proposed edge is
 * the only one that can complete a ring: whatever [from] already reaches is unchanged by adding it. A ref
 * absent from [edges] is a leaf, so an unknown [to] answers `false`; that is the same answer a task with
 * no dependencies gets, and it is why the store must ask its own "does this ref exist" question rather
 * than reading a `false` here as proof.
 *
 * **Re-adding an edge already in [edges] is not a cycle** and must answer `false` — `add` treats a
 * duplicate as a no-op, and refusing it would turn an idempotent retry into an error.
 *
 * Iterative and `seen`-guarded, so a graph that somehow already contains a ring terminates with an answer
 * instead of recursing forever. That state should be unreachable — this very function is what keeps it out
 * — but the caller reaches this code holding the store mutex, and a wedged writer is a worse failure than
 * a redundant `MutableSet`.
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
