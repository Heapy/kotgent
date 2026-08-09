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
 * Bodies are [TODO] here on purpose: Task 6 implements this file.
 */

/**
 * Whether adding "[from] depends on [to]" would close a cycle in [edges].
 *
 * [edges] maps a ref to the refs it DEPENDS ON (the same direction as `backlog_deps.task_ref →
 * depends_on`), and need not already contain the proposed edge. A pure ancestor walk: `true` when [from]
 * is reachable from [to] by following dependencies, and `true` for the degenerate `from == to`.
 */
fun wouldCycle(edges: Map<TaskRef, List<TaskRef>>, from: TaskRef, to: TaskRef): Boolean =
    TODO("Task 6: ancestor walk")
