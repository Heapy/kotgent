package io.kotgent.transport

import io.ktor.server.routing.Route

/**
 * The three endpoints that tie a session to a task: `POST /tasks/{ref}/link`, `POST /tasks/{ref}/unlink`
 * and `POST /tasks/next`.
 *
 * What the implementation owes (task-backlog plan, Task 15):
 *  - **All three REQUIRE session identity** ([resolveCallerSession]) — each writes `sessions.task_ref`
 *    or attributes an activity row, and none means anything without a session. A pane the registry does
 *    not know is REFUSED (`400` naming `--session`) rather than silently attributed to something else.
 *  - `link` is unconditional and may target a task already `in_progress`: kotgent enforces no
 *    exclusivity, so a second session simply appears on the card.
 *  - `unlink` leaves the task's state alone. Whether the work is finished is not inferable from a
 *    session detaching, and other sessions may still be linked.
 *  - `next` answers **"nothing eligible" distinguishably from every error** — a `200` with a null task
 *    (see [NextTaskResponse]) — so the CLI can map it to exit `3` without guessing.
 *
 * `ControlRoutes.kt`'s optional `taskRef` on `POST /sessions` belongs to the same task: the session row
 * and its link are written by ONE request, so `start --task` has nothing to roll back if the launch
 * fails. The link itself cannot fail (it is unconditional), which is a direct dividend of dropping
 * exclusivity.
 *
 * Deliberately empty here: Task 15 implements this file.
 */
fun Route.taskLinkRoutes(routing: TaskRouting) {
    // Task 15.
}
