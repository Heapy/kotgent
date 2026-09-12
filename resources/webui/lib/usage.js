// Ordering revisions are assigned monotonically per provider/window by the daemon.
export function upsertUsageIfNewer(windows, incoming) {
  const index = windows.findIndex((window) =>
    window.provider === incoming.provider && window.windowKey === incoming.windowKey);
  if (index < 0) return windows.concat([incoming]);
  if (!(incoming.observedAt > windows[index].observedAt)) return windows;
  const next = windows.slice();
  next[index] = incoming;
  return next;
}

export function applyUsageSnapshot(windows) {
  return (windows || []).reduce(upsertUsageIfNewer, []);
}
export const USAGE_STALE_MS = 600_000;

// Age comes from the daemon's clock; subsequent expiry uses only the client's elapsed time.
export function atUsageReceipt(window, serverNow, clientReceivedAt) {
  const age = Number.isFinite(serverNow) && Number.isFinite(window.receivedAt)
    ? Math.max(0, serverNow - window.receivedAt) : USAGE_STALE_MS + 1;
  return { ...window, staleAt: clientReceivedAt + USAGE_STALE_MS + 1 - age };
}

export function providerUsageStaleAt(windows) {
  return Math.max(...windows.map((window) => window.staleAt));
}
