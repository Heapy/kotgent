// This module is the sole owner of live task state; lib/tasks.ts owns revision arithmetic.

import { computed, signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { READY, createReadiness } from "../lib/readiness.ts";
import type { Task } from "../lib/tasks.ts";
import {
  applyTasksSnapshot,
  patchTaskIfNewer,
  removeTask,
  upsertTaskIfNewer,
} from "../lib/tasks.ts";

const tasksState = signal<readonly Task[]>([]);
export const tasks: ReadonlySignal<readonly Task[]> = tasksState;

// Destructive flows must distinguish an unloaded snapshot from a loaded empty backlog.
export const tasksReadiness = createReadiness();

const taskByRef = computed<ReadonlyMap<string, Task>>(() => {
  const index = new Map<string, Task>();
  for (const row of tasks.value) index.set(row.ref, row);
  return index;
});

export function findTask(ref: string | null | undefined) {
  if (!ref) return null;
  return taskByRef.value.get(ref) || null;
}

// A reconnect snapshot replaces the list so rows deleted during the outage cannot reappear.
export function replaceTasks(rows: readonly Task[] | null | undefined) {
  const previous = tasks.value;
  tasksState.value = applyTasksSnapshot(rows);
  const first = tasksReadiness.status.value.state !== READY;
  tasksReadiness.succeed();
  return { previous: previous, first: first };
}

export function mergeTaskRow(row: Task) {
  const current = tasks.value;
  const previous = current.find((t) => t.ref === row.ref) || null;
  const next = upsertTaskIfNewer(current, row);
  const changed = next !== current;
  if (changed) tasksState.value = next;
  return { changed: changed, previous: previous, winner: findTask(row.ref) };
}

export function mergeTaskPatch(msg: Partial<Task> & Pick<Task, "ref" | "rev">) {
  const current = tasks.value;
  const previous = current.find((t) => t.ref === msg.ref) || null;
  if (!previous) return { changed: false, previous: null, winner: null };
  const next = patchTaskIfNewer(current, msg);
  const changed = next !== current;
  if (changed) tasksState.value = next;
  return { changed: changed, previous: previous, winner: findTask(msg.ref) };
}

// Removal frames carry no revision and are authoritative.
export function dropTask(ref: string) {
  const current = tasks.value;
  const previous = current.find((t) => t.ref === ref) || null;
  const next = removeTask(current, ref);
  const changed = next !== current;
  if (changed) tasksState.value = next;
  return { changed: changed, previous: previous, winner: null };
}
