// This module is the sole owner of live session state; lib/sessions.js owns revision arithmetic.

import { computed, signal } from "../vendor/signals-core.module.js";
import { READY, createReadiness } from "../lib/readiness.js";
import { patchIfNewer, upsertIfNewer } from "../lib/sessions.js";

export const sessions = signal([]);

// A snapshot distinguishes an unloaded list from a loaded empty list.
export const sessionsReadiness = createReadiness();

const sessionById = computed(() => {
  const index = new Map();
  for (const row of sessions.value) index.set(row.id, row);
  return index;
});

export function findSession(id) {
  if (!id) return null;
  return sessionById.value.get(id) || null;
}

// Return the replaced list so callers can detect attention edges across reconnect.
export function replaceSessions(rows) {
  const previous = sessions.value;
  sessions.value = rows ? rows.slice() : [];
  const first = sessionsReadiness.status.value.state !== READY;
  sessionsReadiness.succeed();
  return { previous: previous, first: first };
}

// Even a declined observation reports the current winner for caller follow-up work.
export function mergeSessionRow(row) {
  const current = sessions.value;
  const previous = current.find((s) => s.id === row.id) || null;
  const next = upsertIfNewer(current, row);
  const changed = next !== current;
  if (changed) sessions.value = next;
  return { changed: changed, previous: previous, winner: findSession(row.id) };
}

// A patch frame carries no cwd or agent, so a patch for a row the list has never seen is dropped
// rather than published as a half-row.
export function mergeSessionPatch(msg) {
  const current = sessions.value;
  const previous = current.find((s) => s.id === msg.sessionId) || null;
  if (!previous) return { changed: false, previous: null, winner: null };
  const next = patchIfNewer(current, msg);
  const changed = next !== current;
  if (changed) sessions.value = next;
  return { changed: changed, previous: previous, winner: findSession(msg.sessionId) };
}
