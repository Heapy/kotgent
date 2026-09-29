export interface UsageWindow {
  provider: string;
  windowKey: string;
  usedPercent: number;
  resetsAt: number | null;
  windowSeconds: number | null;
  observedAt: number;
  changedAt: number;
  receivedAt: number;
}

export interface ReceivedUsageWindow extends UsageWindow {
  staleAt: number;
}

export function isFiniteNumber(value: unknown): value is number {
  return Number.isFinite(value);
}

// Ordering revisions are assigned monotonically per provider/window by the daemon.
export function upsertUsageIfNewer<T extends UsageWindow>(windows: readonly T[], incoming: T): readonly T[] {
  const index = windows.findIndex((window) =>
    window.provider === incoming.provider && window.windowKey === incoming.windowKey);
  if (index < 0) return windows.concat([incoming]);
  if (!(incoming.observedAt > windows[index]!.observedAt)) return windows;
  const next = windows.slice();
  next[index] = incoming;
  return next;
}

export function applyUsageSnapshot<T extends UsageWindow>(windows: readonly T[] | null | undefined): readonly T[] {
  return (windows || []).reduce<readonly T[]>(upsertUsageIfNewer, []);
}
export const USAGE_STALE_MS = 600_000;

// Age comes from the daemon's clock; subsequent expiry uses only the client's elapsed time.
export function atUsageReceipt<T extends UsageWindow>(window: T, serverNow: unknown, clientReceivedAt: number): T & { staleAt: number } {
  const age = isFiniteNumber(serverNow) && Number.isFinite(window.receivedAt)
    ? Math.max(0, serverNow - window.receivedAt) : USAGE_STALE_MS + 1;
  return { ...window, staleAt: clientReceivedAt + USAGE_STALE_MS + 1 - age };
}

export function providerUsageStaleAt(windows: readonly ReceivedUsageWindow[]) {
  return Math.max(...windows.map((window) => window.staleAt));
}

export function usageWindowSeconds(window: Pick<UsageWindow, "provider" | "windowKey"> & { windowSeconds?: unknown }) {
  if (window.windowSeconds != null) {
    return isFiniteNumber(window.windowSeconds) && window.windowSeconds > 0
      ? window.windowSeconds : null;
  }
  if (window.provider === "claude" && window.windowKey === "five_hour") return 18000;
  if (window.provider === "claude" && window.windowKey === "seven_day") return 604800;
  return null;
}

export function usageWindowTime(window: UsageWindow, clientNow: number, serverTimeOffset: unknown) {
  const now = isFiniteNumber(serverTimeOffset) && Number.isFinite(clientNow)
    ? serverTimeOffset + clientNow : null;
  const remainingMs = now != null && isFiniteNumber(window.resetsAt) ? window.resetsAt - now : null;
  const seconds = usageWindowSeconds(window);
  const elapsedPercent = remainingMs != null && seconds != null
    ? Math.max(0, Math.min(100, 100 - remainingMs / (seconds * 1000) * 100)) : null;
  // Repaint on the next clock minute, or exactly at a reset between minute ticks.
  const nextUpdateAt = now == null ? Infinity : clientNow + Math.min(
    60_000 - now % 60_000,
    remainingMs !== null && remainingMs > 0 ? remainingMs : Infinity,
  );
  return { now, remainingMs, elapsedPercent, nextUpdateAt };
}

export function usageTimeLeft(remainingMs: unknown) {
  if (!isFiniteNumber(remainingMs)) return "unknown";
  if (remainingMs <= 0) return "Waiting for update";
  const minutes = Math.ceil(remainingMs / 60_000);
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor(minutes % 1440 / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes % 60}m`;
  return `${minutes}m`;
}
