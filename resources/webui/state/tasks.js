// This module is the sole owner of live task state; lib/tasks.js owns revision arithmetic.

import { computed, signal } from "../vendor/signals-core.module.js";
import { READY, createReadiness } from "../lib/readiness.js";
import {
  applyTasksSnapshot,
  patchTaskIfNewer,
  removeTask,
  upsertTaskIfNewer,
} from "../lib/tasks.js";

export const tasks = signal([]);

// Destructive flows must distinguish an unloaded snapshot from a loaded empty backlog.
export const tasksReadiness = createReadiness();

const taskByRef = computed(() => {
  const index = new Map();
  for (const row of tasks.value) index.set(row.ref, row);
  return index;
});

export function findTask(ref) {
  if (!ref) return null;
  return taskByRef.value.get(ref) || null;
}

// A reconnect snapshot replaces the list so rows deleted during the outage cannot reappear.
export function replaceTasks(rows) {
  const previous = tasks.value;
  tasks.value = applyTasksSnapshot(rows);
  const first = tasksReadiness.status.value.state !== READY;
  tasksReadiness.succeed();
  return { previous: previous, first: first };
}

export function mergeTaskRow(row) {
  const current = tasks.value;
  const previous = current.find((t) => t.ref === row.ref) || null;
  const next = upsertTaskIfNewer(current, row);
  const changed = next !== current;
  if (changed) tasks.value = next;
  return { changed: changed, previous: previous, winner: findTask(row.ref) };
}

export function mergeTaskPatch(msg) {
  const current = tasks.value;
  const previous = current.find((t) => t.ref === msg.ref) || null;
  if (!previous) return { changed: false, previous: null, winner: null };
  const next = patchTaskIfNewer(current, msg);
  const changed = next !== current;
  if (changed) tasks.value = next;
  return { changed: changed, previous: previous, winner: findTask(msg.ref) };
}

// Removal frames carry no revision and are authoritative.
export function dropTask(ref) {
  const current = tasks.value;
  const previous = current.find((t) => t.ref === ref) || null;
  const next = removeTask(current, ref);
  const changed = next !== current;
  if (changed) tasks.value = next;
  return { changed: changed, previous: previous, winner: null };
}
