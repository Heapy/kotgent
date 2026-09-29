// Projects have no revision or event frame. Refresh ordering lives in lib/refresh.ts; confirmed rows apply
// verbatim here.

import { computed, signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { READY, createReadiness } from "../lib/readiness.ts";
import type { Project } from "../lib/tasks.ts";

const projectsState = signal<readonly Project[]>([]);
export const projects: ReadonlySignal<readonly Project[]> = projectsState;

export const projectsReadiness = createReadiness();

const projectIds = computed<ReadonlySet<string>>(() => new Set(projects.value.map((project) => project.id)));

export function findProject(id: string | null | undefined) {
  if (!id) return null;
  return projects.value.find((project) => project.id === id) || null;
}

export function isLiveProject(id: string | null | undefined) {
  return !!id && projectIds.value.has(id);
}

export function replaceProjects(rows: readonly Project[] | null | undefined) {
  const previous = projects.value;
  projectsState.value = rows ? rows.slice() : [];
  // A completed read is authoritative for the whole source, including over a later one still in flight.
  const first = projectsReadiness.status.value.state !== READY;
  projectsReadiness.succeed();
  return { previous: previous, first: first };
}

// Apply a mutation response before revalidation so a failed read cannot restore interactive stale state.
export function applyProjectRow(row: Project) {
  const current = projects.value;
  const index = current.findIndex((project) => project.id === row.id);
  const next = current.slice();
  if (index < 0) next.push(row);
  else next[index] = row;
  projectsState.value = next;
}

export function removeProjectRow(id: string) {
  const current = projects.value;
  const index = current.findIndex((project) => project.id === id);
  if (index < 0) return;
  const next = current.slice();
  next.splice(index, 1);
  projectsState.value = next;
}
