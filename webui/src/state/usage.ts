import { batch, signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { applyUsageSnapshot, atUsageReceipt, upsertUsageIfNewer } from "../lib/usage.ts";
import type { ReceivedUsageWindow, UsageWindow } from "../lib/usage.ts";

const usageState = signal<readonly ReceivedUsageWindow[]>([]);
export const usage: ReadonlySignal<readonly ReceivedUsageWindow[]> = usageState;
const usageClockOffsetState = signal<number | null>(null);
export const usageClockOffset: ReadonlySignal<number | null> = usageClockOffsetState;

function publishUsage(windows: readonly ReceivedUsageWindow[], serverNow: unknown, clientReceivedAt: number) {
  batch(() => {
    usageClockOffsetState.value = typeof serverNow === "number" && Number.isFinite(serverNow) && Number.isFinite(clientReceivedAt)
      ? serverNow - clientReceivedAt : null;
    usageState.value = windows;
  });
}

export function replaceUsage(windows: readonly UsageWindow[] | null | undefined, serverNow?: unknown, receivedAt = performance.now()) {
  const next = applyUsageSnapshot(windows).map((window) => atUsageReceipt(window, serverNow, receivedAt));
  publishUsage(next, serverNow, receivedAt);
}

export function mergeUsageWindow(window: UsageWindow, serverNow?: unknown, receivedAt = performance.now()) {
  const current = usage.value;
  const next = upsertUsageIfNewer(current, atUsageReceipt(window, serverNow, receivedAt));
  if (next !== current) publishUsage(next, serverNow, receivedAt);
}
