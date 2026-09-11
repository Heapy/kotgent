// One synchronous lock serializes daemon mutations through each flow's final follow-up read: releasing
// after the request alone would let a second flow start while the first is still settling and overwrite
// its result. The holder name drives UI guards; announcement, selection, and component-lifetime guards
// remain separate.

import { signal } from "../vendor/signals-core.module.js";

export const MUTATION_BUSY_MESSAGE = "Another action is still in progress — try again in a moment.";

export const pendingMutation = signal(null);

export async function runMutation(name, fn) {
  if (pendingMutation.value !== null) throw new Error(MUTATION_BUSY_MESSAGE);
  pendingMutation.value = name;
  try {
    return await fn();
  } finally {
    pendingMutation.value = null;
  }
}
