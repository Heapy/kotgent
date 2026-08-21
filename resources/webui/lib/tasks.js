// HTTP and WebSocket task observations merge by the task store's monotonic revision.

import { apiRequest } from "./api.js";

/** Mirrors `io.kotgent.task.TaskState` in board order. */
export const TASK_STATES = ["todo", "in_progress", "review", "done"];

export const TASK_STATE_LABELS = {
  todo: "To do",
  in_progress: "In progress",
  review: "Review",
  done: "Done",
};

const TASK_STATE_ORDER = new Map(TASK_STATES.map((state, index) => [state, index]));

export function taskStateLabel(state) {
  return TASK_STATE_LABELS[state] || state || "unknown";
}

export function taskStateRank(state) {
  const rank = TASK_STATE_ORDER.get(state);
  return rank === undefined ? Number.MAX_SAFE_INTEGER : rank;
}

export function isOpenTaskState(state) {
  return state !== "done" && TASK_STATE_ORDER.has(state);
}

function compareFiniteNumbers(left, right) {
  const leftFinite = typeof left === "number" && Number.isFinite(left);
  const rightFinite = typeof right === "number" && Number.isFinite(right);
  if (leftFinite && rightFinite) return left < right ? -1 : left > right ? 1 : 0;
  if (leftFinite !== rightFinite) return leftFinite ? -1 : 1;
  return 0;
}

/** Project-local board order: rank first, then creation order, then a stable ref fallback. */
export function compareTasksByBoardOrder(left, right) {
  const position = compareFiniteNumbers(left && left.position, right && right.position);
  if (position !== 0) return position;
  const created = compareFiniteNumbers(left && left.createdAt, right && right.createdAt);
  if (created !== 0) return created;
  const leftRef = left && typeof left.ref === "string" ? left.ref : "";
  const rightRef = right && typeof right.ref === "string" ? right.ref : "";
  return leftRef < rightRef ? -1 : leftRef > rightRef ? 1 : 0;
}

function taskPath(ref, suffix) {
  return "/tasks/" + encodeURIComponent(ref) + (suffix || "");
}

function jsonBody(method, payload) {
  return { method: method, body: JSON.stringify(payload) };
}

// A reconnect snapshot replaces the list so rows deleted during the outage cannot reappear.
export function applyTasksSnapshot(list, rows) {
  return rows ? rows.slice() : [];
}

export function upsertTaskIfNewer(list, row) {
  const index = list.findIndex((t) => t.ref === row.ref);
  if (index < 0) return list.concat([row]);
  if (!(row.rev > list[index].rev)) return list;
  const next = list.slice();
  next[index] = row;
  return next;
}

// Preserve the patch's revision; otherwise a stale full row can win the next comparison.
export function patchTaskIfNewer(list, msg) {
  const index = list.findIndex((t) => t.ref === msg.ref);
  if (index < 0) return list;
  const prev = list[index];
  if (!(msg.rev > prev.rev)) return list;
  const next = list.slice();
  next[index] = Object.assign({}, prev, msg);
  return next;
}

// Removal frames carry no revision and are authoritative.
export function removeTask(list, ref) {
  const index = list.findIndex((t) => t.ref === ref);
  if (index < 0) return list;
  const next = list.slice();
  next.splice(index, 1);
  return next;
}

export async function fetchTasks(projectId) {
  const query = projectId ? "?project=" + encodeURIComponent(projectId) : "";
  return (await apiRequest("/tasks" + query)) || [];
}

export async function fetchTaskDetail(ref) {
  return apiRequest(taskPath(ref));
}

export async function createTask(projectId, title, body) {
  return apiRequest("/tasks", jsonBody("POST", {
    project: projectId || null,
    title: title,
    body: body || "",
  }));
}

export async function patchTask(ref, patch) {
  return apiRequest(taskPath(ref), jsonBody("PATCH", patch || {}));
}

export async function moveTask(ref, target) {
  return apiRequest(taskPath(ref, "/move"), jsonBody("POST", target || {}));
}

export async function linkTask(ref, sessionId) {
  return apiRequest(taskPath(ref, "/link"), jsonBody("POST", { sessionId: sessionId }));
}

export async function editTaskDependency(ref, action, on) {
  return apiRequest(taskPath(ref, "/deps"), jsonBody("POST", { action: action, on: on }));
}

export async function commentOnTask(ref, text) {
  return apiRequest(taskPath(ref, "/comment"), jsonBody("POST", { text: text }));
}

export async function deleteTask(ref) {
  return apiRequest(taskPath(ref), { method: "DELETE" });
}

export async function fetchProjects(archived = false) {
  return (await apiRequest("/projects" + (archived ? "?archived=true" : ""))) || [];
}

export async function createProject(path, name) {
  return apiRequest("/projects", jsonBody("POST", { path: path, name: name || null }));
}

function projectPath(id, suffix) {
  return "/projects/" + encodeURIComponent(id) + (suffix || "");
}

// Deletion only tombstones the project; tasks, links, and filesystem state survive.
export async function deleteProject(id) {
  return apiRequest(projectPath(id), { method: "DELETE" });
}

export async function restoreProject(id) {
  return apiRequest(projectPath(id, "/restore"), { method: "POST" });
}
