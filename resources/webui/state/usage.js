import { signal } from "../vendor/signals-core.module.js";
import { applyUsageSnapshot, upsertUsageIfNewer } from "../lib/usage.js";

export const usage = signal([]);

export function replaceUsage(windows) {
  usage.value = applyUsageSnapshot(windows);
}

export function mergeUsageWindow(window) {
  const current = usage.value;
  const next = upsertUsageIfNewer(current, window);
  if (next !== current) usage.value = next;
}
