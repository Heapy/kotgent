export const STALE_BUILD_RELOAD_KEY = "kotgent.staleBuildReload.v1";

export function isStaleBuild(servedEntrySrc, runningEntryUrl, origin) {
  if (!servedEntrySrc?.trim()) return false;
  try {
    return new URL(servedEntrySrc, origin).href !== runningEntryUrl;
  } catch (_) {
    return false;
  }
}

export function claimStaleBuildReload(storage, runningEntryUrl, servedEntryUrl) {
  if (!storage) return false;
  try {
    const pair = JSON.stringify([runningEntryUrl, servedEntryUrl]);
    if (storage.getItem(STALE_BUILD_RELOAD_KEY) === pair) return false;
    storage.setItem(STALE_BUILD_RELOAD_KEY, pair);
    return true;
  } catch (_) {
    return false;
  }
}

export function sessionStorageOrNull() {
  try {
    return window.sessionStorage;
  } catch (_) {
    return null;
  }
}
