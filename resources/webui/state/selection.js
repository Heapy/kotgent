// Which session the operator has selected, and how many times they have changed that. See the header of
// state/sessions.js for why signals-core is imported by relative path rather than by bare specifier.
//
// The generation counter used to be a loose `useRef` in app.js, sitting next to the selection it
// describes without belonging to it. It belongs here because it is not a general-purpose counter: it
// counts *selection events*, and the only writer that may advance it is the one that selects. Two rules
// follow from that, and neither is expressible where the counter is a free-floating ref:
//
//   * `pruneSelection` clears a selection whose row a reconnect snapshot no longer carries, and does not
//     advance the generation. The operator did not navigate; the row went away underneath them.
//   * `selectSessionId` advances it even when the same id is re-selected. That is the whole point. The
//     A→B→A hole is that an async flow which auto-selects on completion must be refused after the
//     operator has moved away and back, and a comparison of *ids* cannot see that they moved at all.
//
// It is also not the mutation lock and not the announcement counter. Neither of those can see a round
// trip through the sidebar: the lock is held for one flow at a time regardless of navigation, and an
// announcement is superseded by whatever speaks next, which navigating does not.

import { computed, signal } from "../vendor/signals-core.module.js";
import { findSession } from "./sessions.js";

export const activeSessionId = signal(null);

// Exported so a test can read it; app code should ask through `markSelection` rather than compare
// numbers of its own.
export const selectionGeneration = signal(0);

// The selected row is the join of the selection and the live list, and both move on their own. Deriving
// it once here keeps every caller from re-doing the lookup against a list it has to remember to re-read.
export const activeSession = computed(() => findSession(activeSessionId.value));

// A user selection event: it moves the selection and advances the generation, unconditionally.
export function selectSessionId(id) {
  const next = id || null;
  selectionGeneration.value += 1;
  activeSessionId.value = next;
  return next;
}

// Captures the generation and returns the question "is the selection still where it was". A flow holds
// this across its awaits and asks before it auto-selects anything.
export function markSelection() {
  const at = selectionGeneration.value;
  return () => selectionGeneration.value === at;
}

// A reconnect snapshot is authoritative, including deletions, so a selection it does not carry is gone.
// Not a selection event: the operator navigated nowhere, and a flow waiting to auto-select must not be
// refused because the daemon retired an unrelated row.
export function pruneSelection(liveIds) {
  const current = activeSessionId.value;
  if (!current || liveIds.has(current)) return false;
  activeSessionId.value = null;
  return true;
}
