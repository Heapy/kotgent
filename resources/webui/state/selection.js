// Selection generation records user navigation even across an A→B→A round trip.

import { computed, signal } from "../vendor/signals-core.module.js";
import { findSession } from "./sessions.js";

export const activeSessionId = signal(null);

export const selectionGeneration = signal(0);

export const activeSession = computed(() => findSession(activeSessionId.value));

// Re-selecting the same id is still a user selection event.
export function selectSessionId(id) {
  const next = id || null;
  selectionGeneration.value += 1;
  activeSessionId.value = next;
  return next;
}

// Capture across an await before conditionally auto-selecting.
export function markSelection() {
  const at = selectionGeneration.value;
  return () => selectionGeneration.value === at;
}

// Snapshot pruning is not user navigation and therefore does not advance the generation.
export function pruneSelection(liveIds) {
  const current = activeSessionId.value;
  if (!current || liveIds.has(current)) return false;
  activeSessionId.value = null;
  return true;
}
