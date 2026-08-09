/*
 * Task view state: the API calls the board and the detail view make, and the newest-rev-wins merge
 * helpers that keep a list of backlog entries consistent however frames and responses interleave.
 *
 * The merge rules mirror `lib/sessions.js` exactly, including the half that is easy to lose: a patch's
 * `rev` must be STAMPED ONTO the stored row. Without that, the next stale full row compares against the
 * old rev and wins, and the if-newer invariant self-destructs after the first patch.
 *
 * STUB: Task 23 of the task-backlog plan implements this module. Every export below is present with its
 * final signature so `app.js` compiles against it and `WebUiServingTest` can register it now.
 */

import { apiRequest } from "./api.js";

// --- merge helpers (pure) --------------------------------------------------------------------------

/**
 * Replace the whole list from a `tasks_snapshot` frame. A connect/reconnect baseline, so it REPLACES
 * rather than merges — a reconnect must not resurrect a row deleted while the socket was down.
 */
export function applyTasksSnapshot(list, rows) {
  // Task 23.
  return rows || [];
}

/** Replace-or-append a full row (`task_row`, or a fetched/POSTed DTO), newest-rev-wins. */
export function upsertTaskIfNewer(list, row) {
  // Task 23.
  return list;
}

/** Apply a `task_update`, newest-rev-wins; an unknown ref leaves the list untouched. */
export function patchTaskIfNewer(list, msg) {
  // Task 23.
  return list;
}

/** Drop a row (`task_removed`). Returns the SAME array when the ref was not there. */
export function removeTask(list, ref) {
  // Task 23.
  return list;
}

// --- API calls -------------------------------------------------------------------------------------

/** `GET /tasks?project=<id>`. */
export async function fetchTasks(projectId) {
  // Task 23.
  return apiRequest("/tasks?project=" + encodeURIComponent(projectId));
}

/** `GET /tasks/{ref}` — entry, deps, linked sessions, activity and the project path. */
export async function fetchTaskDetail(ref) {
  // Task 23.
  return apiRequest("/tasks/" + encodeURIComponent(ref));
}

/** `POST /tasks` — the board always sends the SELECTED project id; it has no session to infer one from. */
export async function createTask(projectId, title, body) {
  // Task 23.
  return null;
}

/** `PATCH /tasks/{ref}` — title / body / state, with an optional message on a state change. */
export async function patchTask(ref, patch) {
  // Task 23.
  return null;
}

/** `POST /tasks/{ref}/move` — `{ before | after | top | bottom }`; never a state. */
export async function moveTask(ref, target) {
  // Task 23.
  return null;
}

/** `POST /tasks/{ref}/deps` — `{ action: "add" | "remove", on }`. */
export async function editTaskDependency(ref, action, on) {
  // Task 23.
  return null;
}

/** `POST /tasks/{ref}/comment`. */
export async function commentOnTask(ref, text) {
  // Task 23.
  return null;
}

/** `DELETE /tasks/{ref}` — unlinks every holder, then removes the task, its deps and its feed. */
export async function deleteTask(ref) {
  // Task 23.
  return null;
}

/** `GET /projects` — the board selector's only source. */
export async function fetchProjects() {
  // Task 23.
  return apiRequest("/projects");
}

/** `POST /projects` — write `.kotgent.json` at an absolute path (an existing file always wins). */
export async function createProject(path, name) {
  // Task 23.
  return null;
}
