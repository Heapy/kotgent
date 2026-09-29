// Selection generation records user navigation even across an A→B→A round trip.

import { computed, signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import type { Session } from "../lib/sessions.ts";
import { findSession } from "./sessions.ts";

const activeSessionIdState = signal<string | null>(null);
export const activeSessionId: ReadonlySignal<string | null> = activeSessionIdState;

const selectionGenerationState = signal<number>(0);
export const selectionGeneration: ReadonlySignal<number> = selectionGenerationState;

export const activeSession = computed<Session | null>(() => findSession(activeSessionId.value));

// Re-selecting the same id is still a user selection event.
export function selectSessionId(id: string | null | undefined) {
  const next = id || null;
  selectionGenerationState.value += 1;
  activeSessionIdState.value = next;
  return next;
}

// Capture across an await before conditionally auto-selecting.
export function markSelection() {
  const at = selectionGeneration.value;
  return () => selectionGeneration.value === at;
}

// Snapshot pruning is not user navigation and therefore does not advance the generation.
export function pruneSelection(liveIds: ReadonlySet<string>) {
  const current = activeSessionId.value;
  if (!current || liveIds.has(current)) return false;
  activeSessionIdState.value = null;
  return true;
}
