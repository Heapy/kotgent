// HTTP and WebSocket task observations merge by the task store's monotonic revision.

import { apiRequest } from "./api.ts";

export interface Task {
  ref: string;
  project: string;
  title: string;
  body: string;
  url: string | null;
  position: number;
  state: string;
  blocked: boolean;
  dependsOn: string[];
  createdAt: number;
  updatedAt: number;
  rev: number;
}

export interface TaskActivity {
  id: number;
  ref: string;
  ts: number;
  kind: string;
  author: string;
  text: string | null;
  fromState: string | null;
  toState: string | null;
}

export interface LinkedSession {
  id: string;
  name: string;
  agent: string;
  state: string;
  needsAttention: boolean;
  alive: boolean;
  archived: boolean;
}

export interface TaskDetail {
  task: Task;
  projectName: string | null;
  projectPath: string | null;
  dependsOn: string[];
  dependents: string[];
  sessions: LinkedSession[];
  activity: TaskActivity[];
}

export interface Project {
  id: string;
  name: string;
  path: string | null;
  updatedAt: number;
  archived: boolean;
}

export interface TaskPatch {
  title?: string | null;
  body?: string | null;
  state?: string | null;
  message?: string | null;
  sessionId?: string | null;
}

export interface TaskMove {
  before?: string | null;
  after?: string | null;
  top?: boolean;
  bottom?: boolean;
}

export type TaskState = "todo" | "in_progress" | "review" | "done";

/** Mirrors `io.kotgent.task.TaskState` in board order. */
export const TASK_STATES: TaskState[] = ["todo", "in_progress", "review", "done"];

export const TASK_STATE_LABELS: Record<string, string> = {
  todo: "To do",
  in_progress: "In progress",
  review: "Review",
  done: "Done",
};

const TASK_STATE_ORDER = new Map<string, number>(TASK_STATES.map((state, index) => [state, index]));

export function taskStateLabel(state: string) {
  return TASK_STATE_LABELS[state] || state || "unknown";
}

export function taskStateRank(state: string) {
  const rank = TASK_STATE_ORDER.get(state);
  return rank === undefined ? Number.MAX_SAFE_INTEGER : rank;
}

export function isOpenTaskState(state: string) {
  return state !== "done" && TASK_STATE_ORDER.has(state);
}

function compareFiniteNumbers(left: unknown, right: unknown) {
  const leftFinite = typeof left === "number" && Number.isFinite(left);
  const rightFinite = typeof right === "number" && Number.isFinite(right);
  if (leftFinite && rightFinite) return left < right ? -1 : left > right ? 1 : 0;
  if (leftFinite !== rightFinite) return leftFinite ? -1 : 1;
  return 0;
}

/** Project-local board order: rank first, then creation order, then a stable ref fallback. */
export function compareTasksByBoardOrder(left: Task | null | undefined, right: Task | null | undefined) {
  const position = compareFiniteNumbers(left && left.position, right && right.position);
  if (position !== 0) return position;
  const created = compareFiniteNumbers(left && left.createdAt, right && right.createdAt);
  if (created !== 0) return created;
  const leftRef = left && typeof left.ref === "string" ? left.ref : "";
  const rightRef = right && typeof right.ref === "string" ? right.ref : "";
  return leftRef < rightRef ? -1 : leftRef > rightRef ? 1 : 0;
}

function taskPath(ref: string, suffix?: string) {
  return "/tasks/" + encodeURIComponent(ref) + (suffix || "");
}

function jsonBody(method: string, payload: unknown) {
  return { method: method, body: JSON.stringify(payload) };
}

// A reconnect snapshot replaces the list so rows deleted during the outage cannot reappear, which is why
// it reads nothing of the list it replaces.
export function applyTasksSnapshot<T>(rows: readonly T[] | null | undefined): T[] {
  return rows ? rows.slice() : [];
}

export function upsertTaskIfNewer<T extends Pick<Task, "ref" | "rev">>(list: readonly T[], row: T): readonly T[] {
  const index = list.findIndex((t) => t.ref === row.ref);
  if (index < 0) return list.concat([row]);
  if (!(row.rev > list[index]!.rev)) return list;
  const next = list.slice();
  next[index] = row;
  return next;
}

// Preserve the patch's revision; otherwise a stale full row can win the next comparison.
export function patchTaskIfNewer<T extends Pick<Task, "ref" | "rev">>(list: readonly T[], msg: Partial<T> & Pick<T, "ref" | "rev">): readonly T[] {
  const index = list.findIndex((t) => t.ref === msg.ref);
  if (index < 0) return list;
  const prev = list[index]!;
  if (!(msg.rev > prev.rev)) return list;
  const next = list.slice();
  next[index] = Object.assign({}, prev, msg);
  return next;
}

// Removal frames carry no revision and are authoritative.
export function removeTask<T extends { ref: string }>(list: readonly T[], ref: string): readonly T[] {
  const index = list.findIndex((t) => t.ref === ref);
  if (index < 0) return list;
  const next = list.slice();
  next.splice(index, 1);
  return next;
}

export async function fetchTasks(projectId?: string | null) {
  const query = projectId ? "?project=" + encodeURIComponent(projectId) : "";
  return (await apiRequest<Task[]>("/tasks" + query)) || [];
}

export async function fetchTaskDetail(ref: string) {
  return apiRequest<TaskDetail>(taskPath(ref));
}

export async function createTask(projectId: string | null, title: string, body?: string) {
  return apiRequest<Task>("/tasks", jsonBody("POST", {
    project: projectId || null,
    title: title,
    body: body || "",
  }));
}

export async function patchTask(ref: string, patch?: TaskPatch | null) {
  return apiRequest<Task>(taskPath(ref), jsonBody("PATCH", patch || {}));
}

export async function moveTask(ref: string, target?: TaskMove | null) {
  return apiRequest<Task>(taskPath(ref, "/move"), jsonBody("POST", target || {}));
}

export async function linkTask(ref: string, sessionId: string) {
  return apiRequest(taskPath(ref, "/link"), jsonBody("POST", { sessionId: sessionId }));
}

export async function editTaskDependency(ref: string, action: "add" | "remove", on: string) {
  return apiRequest<Task>(taskPath(ref, "/deps"), jsonBody("POST", { action: action, on: on }));
}

export async function commentOnTask(ref: string, text: string) {
  return apiRequest(taskPath(ref, "/comment"), jsonBody("POST", { text: text }));
}

export async function deleteTask(ref: string) {
  return apiRequest(taskPath(ref), { method: "DELETE" });
}

export async function fetchProjects(archived = false) {
  return (await apiRequest<Project[] | { projects?: Project[] }>("/projects" + (archived ? "?archived=true" : ""))) || [];
}

export async function createProject(path: string, name?: string | null) {
  return apiRequest<Project>("/projects", jsonBody("POST", { path: path, name: name || null }));
}

function projectPath(id: string, suffix?: string) {
  return "/projects/" + encodeURIComponent(id) + (suffix || "");
}

// Deletion only tombstones the project; tasks, links, and filesystem state survive.
export async function deleteProject(id: string) {
  return apiRequest<Project>(projectPath(id), { method: "DELETE" });
}

export async function restoreProject(id: string) {
  return apiRequest<Project>(projectPath(id, "/restore"), { method: "POST" });
}
