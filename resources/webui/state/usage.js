import { signal } from "../vendor/signals-core.module.js";
import { applyUsageSnapshot, atUsageReceipt, upsertUsageIfNewer } from "../lib/usage.js";

export const usage = signal([]);

export function replaceUsage(windows, serverNow, receivedAt = performance.now()) {
  usage.value = applyUsageSnapshot(windows).map((window) => atUsageReceipt(window, serverNow, receivedAt));
}

export function mergeUsageWindow(window, serverNow, receivedAt = performance.now()) {
  const current = usage.value;
  const next = upsertUsageIfNewer(current, atUsageReceipt(window, serverNow, receivedAt));
  if (next !== current) usage.value = next;
}
