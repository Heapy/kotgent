import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { applyMutexSnapshot, mergeMutexUpdate } from "../lib/mutexes.ts";
import type { ReceivedMutexListing } from "../lib/mutexes.ts";

// Null until the first snapshot, so the screen can tell "not loaded" from "nothing held".
const mutexesState = signal<ReceivedMutexListing | null>(null);
export const mutexes: ReadonlySignal<ReceivedMutexListing | null> = mutexesState;

export function replaceMutexes(listing: unknown, receivedAt = performance.now()) {
  const next = applyMutexSnapshot(listing, receivedAt);
  if (next) mutexesState.value = next;
}

export function mergeMutexes(listing: unknown, receivedAt = performance.now()) {
  const current = mutexesState.value;
  const next = mergeMutexUpdate(current, listing, receivedAt);
  if (next !== current) mutexesState.value = next;
}
