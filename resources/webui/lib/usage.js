// Receipt timestamps are assigned monotonically per provider/window by the daemon.
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
