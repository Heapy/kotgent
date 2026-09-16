import { batch, signal } from "../vendor/signals-core.module.js";
import { applyUsageSnapshot, atUsageReceipt, upsertUsageIfNewer } from "../lib/usage.js";

export const usage = signal([]);
export const usageClockOffset = signal(null);

function publishUsage(windows, serverNow, clientReceivedAt) {
  batch(() => {
    usageClockOffset.value = Number.isFinite(serverNow) && Number.isFinite(clientReceivedAt)
      ? serverNow - clientReceivedAt : null;
    usage.value = windows;
  });
}

export function replaceUsage(windows, serverNow, receivedAt = performance.now()) {
  const next = applyUsageSnapshot(windows).map((window) => atUsageReceipt(window, serverNow, receivedAt));
  publishUsage(next, serverNow, receivedAt);
}

export function mergeUsageWindow(window, serverNow, receivedAt = performance.now()) {
  const current = usage.value;
  const next = upsertUsageIfNewer(current, atUsageReceipt(window, serverNow, receivedAt));
  if (next !== current) publishUsage(next, serverNow, receivedAt);
}
