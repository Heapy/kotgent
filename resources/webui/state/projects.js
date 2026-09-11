// Projects have no revision or event frame. Refresh ordering lives in lib/refresh.js; confirmed rows apply
// verbatim here.

import { computed, signal } from "../vendor/signals-core.module.js";
import { READY, createReadiness } from "../lib/readiness.js";

export const projects = signal([]);

export const projectsReadiness = createReadiness();

const projectIds = computed(() => new Set(projects.value.map((project) => project.id)));

export function findProject(id) {
  if (!id) return null;
  return projects.value.find((project) => project.id === id) || null;
}

export function isLiveProject(id) {
  return !!id && projectIds.value.has(id);
}

export function replaceProjects(rows) {
  const previous = projects.value;
  projects.value = rows ? rows.slice() : [];
  // A completed read is authoritative for the whole source, including over a later one still in flight.
  const first = projectsReadiness.status.value.state !== READY;
  projectsReadiness.succeed();
  return { previous: previous, first: first };
}

// Apply a mutation response before revalidation so a failed read cannot restore interactive stale state.
export function applyProjectRow(row) {
  const current = projects.value;
  const index = current.findIndex((project) => project.id === row.id);
  const next = current.slice();
  if (index < 0) next.push(row);
  else next[index] = row;
  projects.value = next;
}

export function removeProjectRow(id) {
  const current = projects.value;
  const index = current.findIndex((project) => project.id === id);
  if (index < 0) return;
  const next = current.slice();
  next.splice(index, 1);
  projects.value = next;
}
