// The live project list, held once, on the same terms as state/sessions.js. See that module's header
// for why signals-core is imported by relative path rather than by bare specifier.
//
// Projects have no revision and no event frame: the daemon never pushes one, so the list is whatever the
// last completed refresh said, plus any row a mutation response has already confirmed. There is nothing
// to arbitrate here, which is why these writers apply verbatim instead of merging by rank. Serializing
// the refreshes themselves — discarding a response once a later read has been requested — stays with the
// caller that owns the requests.

import { computed, signal } from "../vendor/signals-core.module.js";

export const projects = signal([]);

// Distinguishes "not read yet" from "no projects", which the link picker must not confuse.
export const projectsReady = signal(false);

const projectIds = computed(() => new Set(projects.value.map((project) => project.id)));

export function findProject(id) {
  if (!id) return null;
  return projects.value.find((project) => project.id === id) || null;
}

// The question every link guard asks: is this id still a live project, rather than a stale selection or
// a tombstone the operator is looking at.
export function isLiveProject(id) {
  return !!id && projectIds.value.has(id);
}

export function replaceProjects(rows) {
  const previous = projects.value;
  projects.value = rows ? rows.slice() : [];
  const first = !projectsReady.value;
  if (first) projectsReady.value = true;
  return { previous: previous, first: first };
}

// A mutation response is the daemon's own confirmation of one row, so it is applied before the refetch:
// a re-read that fails must not leave a deleted project selected and interactive against its tombstone.
export function applyProjectRow(row) {
  const current = projects.value;
  const index = current.findIndex((project) => project.id === row.id);
  const next = current.slice();
  if (index < 0) next.push(row);
  else next[index] = row;
  projects.value = next;
  return { changed: true, previous: index < 0 ? null : current[index], winner: row };
}

export function removeProjectRow(id) {
  const current = projects.value;
  const index = current.findIndex((project) => project.id === id);
  if (index < 0) return { changed: false, previous: null, winner: null };
  const next = current.slice();
  next.splice(index, 1);
  projects.value = next;
  return { changed: true, previous: current[index], winner: null };
}
