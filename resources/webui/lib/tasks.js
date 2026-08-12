// HTTP and WebSocket task observations merge by the task store's monotonic revision.

import { apiRequest } from "./api.js";

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

export async function editTaskDependency(ref, action, on) {
  return apiRequest(taskPath(ref, "/deps"), jsonBody("POST", { action: action, on: on }));
}

export async function commentOnTask(ref, text) {
  return apiRequest(taskPath(ref, "/comment"), jsonBody("POST", { text: text }));
}

export async function deleteTask(ref) {
  return apiRequest(taskPath(ref), { method: "DELETE" });
}

// The two sides of the delete tombstone are one list each, never a flag on a merged one: every
// selector wants the live projects and only the restore dialog wants exactly the deleted ones.
export async function fetchProjects(archived = false) {
  return (await apiRequest("/projects" + (archived ? "?archived=true" : ""))) || [];
}

export async function createProject(path, name) {
  return apiRequest("/projects", jsonBody("POST", { path: path, name: name || null }));
}

function projectPath(id, suffix) {
  return "/projects/" + encodeURIComponent(id) + (suffix || "");
}

// A tombstone, not a cascade: the tasks, the sessions' links and the project's `.kotgent.json` all
// survive it. Both calls are idempotent, so a repeated click answers the same row rather than a 404.
export async function deleteProject(id) {
  return apiRequest(projectPath(id), { method: "DELETE" });
}

export async function restoreProject(id) {
  return apiRequest(projectPath(id, "/restore"), { method: "POST" });
}
