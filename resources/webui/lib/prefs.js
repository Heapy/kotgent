/*
 * Preferences: base path + grouping level.
 *
 * These are a per-browser VIEW setting, not daemon state — they change how this page draws the session
 * list and what it pre-fills, nothing about the sessions themselves. So they live in localStorage and
 * never round-trip to the server.
 */

import { normalizePath } from "./paths.js";

export const PREFS_KEY = "kotgent.prefs.v1";
export const MAX_GROUPING_LEVEL = 4;
export const DEFAULT_PREFS = { basePath: "", groupingLevel: 1 };

/** Coerce anything read back from localStorage into a valid prefs shape. */
export function sanitizePrefs(raw) {
  const level = Number.parseInt(raw && raw.groupingLevel, 10);
  return {
    basePath: normalizePath(raw && raw.basePath),
    groupingLevel: Number.isFinite(level)
      ? Math.min(MAX_GROUPING_LEVEL, Math.max(0, level))
      : DEFAULT_PREFS.groupingLevel,
  };
}

export function loadPrefs() {
  try {
    const raw = window.localStorage.getItem(PREFS_KEY);
    return sanitizePrefs(raw ? JSON.parse(raw) : DEFAULT_PREFS);
  } catch (_) {
    return sanitizePrefs(DEFAULT_PREFS); // unreadable / disabled storage — fall back to the defaults
  }
}

export function persistPrefs(next) {
  try {
    window.localStorage.setItem(PREFS_KEY, JSON.stringify(next));
  } catch (_) { /* private mode / quota — the prefs still apply to this page load */ }
}

/** Grouping is what the base path buys; without one the sidebar stays a single flat list. */
export function groupingEnabled(prefs) {
  return prefs.basePath.length > 0;
}

/*
 * Which directory groups are collapsed, by group path. Deliberately NOT part of the prefs object: it
 * changes on a click in the sidebar, while prefs are rebuilt wholesale by the Preferences dialog — one
 * save there would drop it. A path that no longer has sessions keeps its entry, so a group the user
 * collapsed comes back collapsed when a session reappears under it.
 */
export const COLLAPSED_GROUPS_KEY = "kotgent.collapsedGroups.v1";

export function loadCollapsedGroups() {
  try {
    const raw = window.localStorage.getItem(COLLAPSED_GROUPS_KEY);
    const list = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(list) ? list.filter((p) => typeof p === "string") : []);
  } catch (_) {
    return new Set(); // unreadable / disabled storage — every group starts expanded
  }
}

export function persistCollapsedGroups(paths) {
  try {
    window.localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify(Array.from(paths)));
  } catch (_) { /* private mode / quota — the collapse still applies to this page load */ }
}
