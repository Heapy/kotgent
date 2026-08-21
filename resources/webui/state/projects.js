// The live project list, held once, on the same terms as state/sessions.js. See that module's header
// for why signals-core is imported by relative path rather than by bare specifier.
//
// Projects have no revision and no event frame: the daemon never pushes one, so the list is whatever the
// last completed refresh said, plus any row a mutation response has already confirmed. There is nothing
// to arbitrate here, which is why these writers apply verbatim instead of merging by rank. Serializing
// the refreshes themselves — discarding a response once a later read has been requested — stays with the
// caller that owns the requests.

import { computed, signal } from "../vendor/signals-core.module.js";
import { READY, createReadiness } from "../lib/readiness.js";

export const projects = signal([]);

// The list is read over HTTP and nothing pushes it, so "not read yet", "no projects", and "the read
// failed" are three different answers the link picker must not confuse. The owner of the request
// registers itself as the loader, which is what makes the picker's retry control reach the network.
export const projectsReadiness = createReadiness();

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
  // A completed read is authoritative for the whole source, including over a later one still in flight.
  const first = projectsReadiness.status.value.state !== READY;
  projectsReadiness.succeed();
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
