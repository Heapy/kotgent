package io.kotgent.transport

import io.ktor.server.routing.Route

/**
 * The task layer's read surface: `GET /whoami`, `GET /tasks?project=`, `GET /tasks/{ref}`,
 * `GET /projects`. Mounted by [taskRoutes] inside the authenticated `route(API_PREFIX)` block, so every
 * endpoint here takes either credential (the CLI's master-token `Bearer` or the browser's cookie).
 *
 * What the implementation owes (task-backlog plan, Task 13):
 *  - `GET /whoami` resolves the calling PANE through the registry ([resolveCallerSession]) and answers
 *    `400` naming `--session` when it cannot. It is pane resolution, not a session lookup — a caller that
 *    already knows its id never comes here.
 *  - `GET /tasks?project=` lists in `position` order and carries the derived `blocked`.
 *  - `GET /tasks/{ref}` carries deps (both directions), every linked session, the activity feed and the
 *    project's last-seen path. An unknown ref is `404`; a malformed one is `400` (by the route
 *    convention `404` means "no such task `{ref}`", so a ref that cannot even be parsed is a bad
 *    request).
 *  - `GET /projects` is the board selector's only source.
 *
 * Deliberately empty here: Task 13 implements this file.
 */
fun Route.taskReadRoutes(routing: TaskRouting) {
    // Task 13.
}
