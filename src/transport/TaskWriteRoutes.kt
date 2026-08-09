package io.kotgent.transport

import io.ktor.server.routing.Route

/**
 * The task layer's mutating surface that is not a session link: `POST /tasks`, `PATCH /tasks/{ref}`,
 * `DELETE /tasks/{ref}`, `POST /tasks/{ref}/{move,deps,comment}`, and `POST /projects`.
 *
 * What the implementation owes (task-backlog plan, Task 14):
 *  - **`POST /tasks` resolves the project in exactly this order**: explicit `project` in the body → the
 *    calling session's `project_id` → `resolveProject(session cwd)` → create the file at
 *    `mainCheckoutRoot(session cwd)` through [io.kotgent.daemon.TaskService.projectFiles] → `400` naming
 *    `--project`, and **only** when there is no resolvable session at all (the board path with nothing
 *    selected). The two halves of that — "400 when there is no project" and "create the file when there
 *    is no project" — read as contradictory unless the order is written out, so it is.
 *    Whatever branch answers, the `projects` row is upserted: a project that never appears in
 *    `GET /projects` has a backlog the board's selector can never reach.
 *  - `PATCH` takes title / body / state, with an optional `message` on a state change so
 *    `kotgent task review -m "…"` is ONE operation — the transition and its activity row commit in one
 *    task-store transaction.
 *  - `DELETE` goes through [io.kotgent.daemon.TaskService.delete], which unlinks every holder first.
 *  - `/deps` answers `400` for each of the four refusals (self, unknown ref, cross-project, cycle) with a
 *    message that says which.
 *  - `POST /projects` writes `.kotgent.json` at a browser-supplied ABSOLUTE path — the bounded departure
 *    from the upload rule recorded on [CreateProjectRequest].
 *
 * Deliberately empty here: Task 14 implements this file.
 */
fun Route.taskWriteRoutes(routing: TaskRouting) {
    // Task 14.
}
