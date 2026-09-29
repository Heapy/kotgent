// This module is the sole owner of live session state; lib/sessions.ts owns revision arithmetic.

import { computed, signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { READY, createReadiness } from "../lib/readiness.ts";
import { patchIfNewer, upsertIfNewer } from "../lib/sessions.ts";
import type { Session, SessionUpdate } from "../lib/sessions.ts";

const sessionsState = signal<readonly Session[]>([]);
export const sessions: ReadonlySignal<readonly Session[]> = sessionsState;

// A snapshot distinguishes an unloaded list from a loaded empty list.
export const sessionsReadiness = createReadiness();

const sessionById = computed<ReadonlyMap<string, Session>>(() => {
  const index = new Map<string, Session>();
  for (const row of sessions.value) index.set(row.id, row);
  return index;
});

export function findSession(id: string | null | undefined) {
  if (!id) return null;
  return sessionById.value.get(id) || null;
}

// Return the replaced list so callers can detect attention edges across reconnect.
export function replaceSessions(rows: readonly Session[] | null | undefined) {
  const previous = sessions.value;
  sessionsState.value = rows ? rows.slice() : [];
  const first = sessionsReadiness.status.value.state !== READY;
  sessionsReadiness.succeed();
  return { previous: previous, first: first };
}

// Even a declined observation reports the current winner for caller follow-up work.
export function mergeSessionRow(row: Session) {
  const current = sessions.value;
  const previous = current.find((s) => s.id === row.id) || null;
  const next = upsertIfNewer(current, row);
  const changed = next !== current;
  if (changed) sessionsState.value = next;
  return { changed: changed, previous: previous, winner: findSession(row.id) };
}

// A patch frame carries no cwd or agent, so a patch for a row the list has never seen is dropped
// rather than published as a half-row.
export function mergeSessionPatch(msg: SessionUpdate) {
  const current = sessions.value;
  const previous = current.find((s) => s.id === msg.sessionId) || null;
  if (!previous) return { changed: false, previous: null, winner: null };
  const next = patchIfNewer(current, msg);
  const changed = next !== current;
  if (changed) sessionsState.value = next;
  return { changed: changed, previous: previous, winner: findSession(msg.sessionId) };
}
