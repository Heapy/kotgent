// The live session list, held once. `lib/sessions.js` still owns the revision arithmetic; this module
// owns the current value and is its only writer, so the seven session writers cannot disagree about what
// "current" means. They used to: three of them maintained a mirror ref and four wrote only Preact state,
// so a functional-style write was invisible to whichever writer ran next and got rebuilt away.
//
// signals-core is imported by relative path rather than through the "@preact/signals-core" bare
// specifier the import map wires. index.html loads /_v/<rev>/app.js, so this module is served as
// /_v/<rev>/state/sessions.js and "../vendor/signals-core.module.js" normalizes to exactly the import
// map's target: one URL, one module instance, one reactive graph shared with the @preact/signals
// adapter. The bare specifier would resolve in a browser and nowhere else — node has no resolver for it
// — and these rules are proven at the node tier, in webuitest/js/state-sessions.test.js.

import { computed, signal } from "../vendor/signals-core.module.js";
import { READY, createReadiness } from "../lib/readiness.js";
import { patchIfNewer, upsertIfNewer } from "../lib/sessions.js";

export const sessions = signal([]);

// A snapshot distinguishes an unloaded list from an empty one; only the first one is an announcement.
// The same vocabulary as the task and project lists, so the three answer one question rather than three:
// like the task list this one arrives on the events socket and never reaches `failed`, because the socket
// retries forever on its own and announces the outage itself.
export const sessionsReadiness = createReadiness();

// Derived, not reconciled: an index rebuilt by a caller after each write is a mirror by another name.
const sessionById = computed(() => {
  const index = new Map();
  for (const row of sessions.value) index.set(row.id, row);
  return index;
});

export function findSession(id) {
  if (!id) return null;
  return sessionById.value.get(id) || null;
}

// Reconnect snapshots are authoritative, including deletions. The replaced list comes back so the
// caller can judge attention transitions — the only carrier for changes made while the socket was down.
export function replaceSessions(rows) {
  const previous = sessions.value;
  sessions.value = rows ? rows.slice() : [];
  const first = sessionsReadiness.status.value.state !== READY;
  sessionsReadiness.succeed();
  return { previous: previous, first: first };
}

// `changed` is whether the merge produced a new list, `previous` the row this observation supersedes,
// and `winner` the row that is current afterwards. A declined observation still names a winner: the
// caller's follow-up work — retrying a stalled read, in particular — is about the row, not the frame.
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
