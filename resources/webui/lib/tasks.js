/*
 * Task view state: the API calls the board and the detail view make, and the newest-rev-wins merge
 * helpers that keep a list of backlog entries consistent however frames and responses interleave.
 *
 * The merge rules mirror `lib/sessions.js` exactly, including the half that is easy to lose: a patch's
 * `rev` must be STAMPED ONTO the stored row. Without that, the next stale full row compares against the
 * old rev and wins, and the if-newer invariant self-destructs after the first patch.
 *
 * Every row-shaped payload here — a `tasks_snapshot` / `task_row` / `task_update` frame and every HTTP
 * response that carries an entry — is a `BacklogEntryDto` (`src/transport/TaskDtos.kt`), keyed by `ref`
 * and stamped with the task store's global monotonic `rev`. That is what lets a fetched row and a live
 * frame be merged in either arrival order.
 */

import { apiRequest } from "./api.js";

/** `/tasks/<ref>`, with the ref percent-encoded — a `TaskRef` always contains a `:` (`local:42`). */
function taskPath(ref, suffix) {
  return "/tasks/" + encodeURIComponent(ref) + (suffix || "");
}

/** A JSON request body plus its method; `apiRequest` supplies the `Content-Type` for a string body. */
function jsonBody(method, payload) {
  return { method: method, body: JSON.stringify(payload) };
}

// --- merge helpers (pure) --------------------------------------------------------------------------

/**
 * Replace the whole list from a `tasks_snapshot` frame. A connect/reconnect baseline, so it REPLACES
 * rather than merges — a reconnect must not resurrect a row deleted while the socket was down.
 *
 * `list` is unused for exactly that reason, and is kept only so all four appliers share the
 * `setTasks((current) => …)` shape their callers in `app.js` are written against. The rows are copied
 * rather than adopted, so the stored list is never the frame's own array.
 */
export function applyTasksSnapshot(list, rows) {
  return rows ? rows.slice() : [];
}

/**
 * Replace-or-append a full row (`task_row`, or a fetched/POSTed DTO), newest-rev-wins: every observation
 * of an entry carries the daemon-stamped `rev`, so a stale response that lands after a fresher frame
 * compares older and cannot roll the row back, whatever order the network delivered them in. Returns the
 * SAME array when nothing changed, so a `setTasks` caller keeps identity.
 */
export function upsertTaskIfNewer(list, row) {
  const index = list.findIndex((t) => t.ref === row.ref);
  if (index < 0) return list.concat([row]);
  if (!(row.rev > list[index].rev)) return list;
  const next = list.slice();
  next[index] = row;
  return next;
}

/**
 * Apply a `task_update`, newest-rev-wins; an unknown ref leaves the list untouched (the daemon patches
 * only refs the socket already carried as full rows, and a `task_row` is what adds one).
 *
 * The merge is what stamps the frame's `rev` onto the stored row — `rev` is one of the fields `msg`
 * carries, and losing it is how the if-newer invariant self-destructs: a later stale full row would then
 * compare against the row's OLD rev, win, and roll this patch back. Merging over `prev` rather than
 * replacing it also keeps anything the board hung on the row locally, at no cost: a `task_update` frame
 * carries the same complete entry a `task_row` does, so every server-owned field is overwritten anyway.
 */
export function patchTaskIfNewer(list, msg) {
  const index = list.findIndex((t) => t.ref === msg.ref);
  if (index < 0) return list;
  const prev = list[index];
  if (!(msg.rev > prev.rev)) return list;
  const next = list.slice();
  next[index] = Object.assign({}, prev, msg);
  return next;
}

/**
 * Drop a row (`task_removed`). Returns the SAME array when the ref was not there.
 *
 * A removal carries no `rev` and is deliberately unconditional: the row is gone on the daemon, so there
 * is no later observation of it that could be "newer", and holding it back for a rev comparison would
 * leave a deleted task on the board until a reload.
 */
export function removeTask(list, ref) {
  const index = list.findIndex((t) => t.ref === ref);
  if (index < 0) return list;
  const next = list.slice();
  next.splice(index, 1);
  return next;
}

// --- API calls -------------------------------------------------------------------------------------

/** `GET /tasks?project=<id>`. Answers `[]` rather than null so a caller can render it directly. */
export async function fetchTasks(projectId) {
  const query = projectId ? "?project=" + encodeURIComponent(projectId) : "";
  return (await apiRequest("/tasks" + query)) || [];
}

/** `GET /tasks/{ref}` — entry, deps, linked sessions, activity and the project path. */
export async function fetchTaskDetail(ref) {
  return apiRequest(taskPath(ref));
}

/** `POST /tasks` — the board always sends the SELECTED project id; it has no session to infer one from. */
export async function createTask(projectId, title, body) {
  return apiRequest("/tasks", jsonBody("POST", {
    project: projectId || null,
    title: title,
    body: body || "",
  }));
}

/**
 * `PATCH /tasks/{ref}` — title / body / state, with an optional message on a state change.
 *
 * The patch object is sent verbatim: a field it omits means "leave unchanged", never "clear", so a
 * caller changing only the column sends only `{ state }`. A board drop that changes both column and rank
 * is this call and then [moveTask] — `/move` takes no state and this takes no position.
 */
export async function patchTask(ref, patch) {
  return apiRequest(taskPath(ref), jsonBody("PATCH", patch || {}));
}

/** `POST /tasks/{ref}/move` — `{ before | after | top | bottom }`; never a state. */
export async function moveTask(ref, target) {
  return apiRequest(taskPath(ref, "/move"), jsonBody("POST", target || {}));
}

/** `POST /tasks/{ref}/deps` — `{ action: "add" | "remove", on }`. */
export async function editTaskDependency(ref, action, on) {
  return apiRequest(taskPath(ref, "/deps"), jsonBody("POST", { action: action, on: on }));
}

/**
 * `POST /tasks/{ref}/comment`.
 *
 * The browser sends no session identity — it has neither a pane header nor a session id — so the daemon
 * attributes or refuses the row on its own terms. An agent commenting from inside a pane goes through
 * `kotgent task comment`, which does carry one.
 */
export async function commentOnTask(ref, text) {
  return apiRequest(taskPath(ref, "/comment"), jsonBody("POST", { text: text }));
}

/** `DELETE /tasks/{ref}` — unlinks every holder, then removes the task, its deps and its feed. */
export async function deleteTask(ref) {
  return apiRequest(taskPath(ref), { method: "DELETE" });
}

/** `GET /projects` — the board selector's only source. */
export async function fetchProjects() {
  return (await apiRequest("/projects")) || [];
}

/** `POST /projects` — write `.kotgent.json` at an absolute path (an existing file always wins). */
export async function createProject(path, name) {
  return apiRequest("/projects", jsonBody("POST", { path: path, name: name || null }));
}
