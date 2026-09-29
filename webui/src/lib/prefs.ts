// Grouping is daemon-wide; terminal rendering and shell state remain device-local.

import { normalizePath } from "./paths.ts";
import type { TerminalUnicodeModeValue } from "./unicode.ts";
import { DEFAULT_TERMINAL_UNICODE, isTerminalUnicodeMode } from "./unicode.ts";

export interface ServerPreferences {
  basePath: string;
  groupingLevel: number;
  revision: number;
  adhdPaths: string[];
}

export interface Preferences extends ServerPreferences {
  terminalFontSize: number;
  terminalUnicode: TerminalUnicodeModeValue;
}

export type PreferenceDraft = Partial<Record<keyof Preferences, unknown>> & {
  basePath?: string | null | undefined;
};

export const LEGACY_PREFS_KEY = "kotgent.prefs.v1";
export const TERMINAL_FONT_SIZE_KEY = "kotgent.terminalFontSize.v1";
export const TERMINAL_UNICODE_KEY = "kotgent.terminalUnicode.v1";
export const SIDEBAR_COLLAPSED_KEY = "kotgent.sidebarCollapsed.v1";
export const ADHD_MODE_KEY = "kotgent.adhdMode.v1";
export const ATTENTION_COLLAPSED_KEY = "kotgent.attentionCollapsed.v1";
export const MAX_GROUPING_LEVEL = 4;
export const TERMINAL_FONT_SIZES = [11, 13, 16];
export const DEFAULT_PREFS: Preferences = {
  basePath: "",
  groupingLevel: 1,
  revision: 0,
  adhdPaths: [],
  terminalFontSize: 13,
  terminalUnicode: DEFAULT_TERMINAL_UNICODE,
};

export function sanitizePrefs(raw: PreferenceDraft | null | undefined): Preferences {
  const level = Number.parseInt(String(raw && raw.groupingLevel), 10);
  const revision = Number(raw && raw.revision);
  const fontSize = Number.parseInt(String(raw && raw.terminalFontSize), 10);
  const unicode = raw && raw.terminalUnicode;
  return {
    basePath: normalizePath(raw && raw.basePath),
    groupingLevel: Number.isFinite(level)
      ? Math.min(MAX_GROUPING_LEVEL, Math.max(0, level))
      : DEFAULT_PREFS.groupingLevel,
    revision: Number.isSafeInteger(revision) && revision >= 0
      ? revision
      : DEFAULT_PREFS.revision,
    terminalFontSize: TERMINAL_FONT_SIZES.includes(fontSize)
      ? fontSize
      : DEFAULT_PREFS.terminalFontSize,
    terminalUnicode: isTerminalUnicodeMode(unicode) ? unicode : DEFAULT_PREFS.terminalUnicode,
    // Seeded before the first server read, so the sidebar's membership rule never sees undefined. Only
    // the daemon supplies real paths, through sanitizeServerPreferences.
    adhdPaths: [],
  };
}

export function sanitizeServerPreferences(raw: unknown): ServerPreferences | null {
  if (!raw || typeof raw !== "object" || !("basePath" in raw) || typeof raw.basePath !== "string") return null;
  if (!("groupingLevel" in raw) || typeof raw.groupingLevel !== "number" || !Number.isInteger(raw.groupingLevel) ||
      raw.groupingLevel < 0 ||
      raw.groupingLevel > MAX_GROUPING_LEVEL) return null;
  if (!("revision" in raw) || typeof raw.revision !== "number" || !Number.isSafeInteger(raw.revision) || raw.revision < 0) return null;
  // A daemon that predates ADHD mode omits the field; anything else must be a list of strings.
  const adhdPaths: unknown = !("adhdPaths" in raw) || raw.adhdPaths === undefined ? [] : raw.adhdPaths;
  if (!Array.isArray(adhdPaths)) return null;
  if (!adhdPaths.every((path: unknown): path is string => typeof path === "string")) return null;
  const basePath = normalizePath(raw.basePath);
  if (basePath.length > 0 && basePath.charAt(0) !== "/") return null;
  return {
    basePath: basePath,
    groupingLevel: raw.groupingLevel,
    revision: raw.revision,
    adhdPaths: adhdPaths.slice(),
  };
}

export function loadPrefs() {
  let terminalFontSize: unknown = DEFAULT_PREFS.terminalFontSize;
  let terminalUnicode: unknown = DEFAULT_PREFS.terminalUnicode;
  try {
    window.localStorage.removeItem(LEGACY_PREFS_KEY);
    terminalFontSize = window.localStorage.getItem(TERMINAL_FONT_SIZE_KEY);
    terminalUnicode = window.localStorage.getItem(TERMINAL_UNICODE_KEY);
  } catch (_) {
  }
  return sanitizePrefs({ terminalFontSize: terminalFontSize, terminalUnicode: terminalUnicode });
}

export function persistTerminalFontSize(value: unknown) {
  const fontSize = sanitizePrefs({ terminalFontSize: value }).terminalFontSize;
  try {
    window.localStorage.setItem(TERMINAL_FONT_SIZE_KEY, String(fontSize));
  } catch (_) { /* best effort */ }
}

export function persistTerminalUnicode(value: unknown) {
  const unicode = sanitizePrefs({ terminalUnicode: value }).terminalUnicode;
  try {
    window.localStorage.setItem(TERMINAL_UNICODE_KEY, unicode);
  } catch (_) { /* best effort */ }
}

export function groupingEnabled(prefs: Pick<Preferences, "basePath">) {
  return prefs.basePath.length > 0;
}

// Keep collapsed paths outside the preferences object so saving preferences cannot reset them.
export const COLLAPSED_GROUPS_KEY = "kotgent.collapsedGroups.v1";

export function loadCollapsedGroups(): Set<string> {
  try {
    const raw = window.localStorage.getItem(COLLAPSED_GROUPS_KEY);
    const list: unknown = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(list) ? list.filter((p: unknown): p is string => typeof p === "string") : []);
  } catch (_) {
    return new Set<string>();
  }
}

export function persistCollapsedGroups(paths: Iterable<string>) {
  try {
    window.localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify(Array.from(paths)));
  } catch (_) { /* best effort */ }
}

export function loadSidebarCollapsed() {
  try {
    return window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "true";
  } catch (_) {
    return false;
  }
}

export function persistSidebarCollapsed(value: unknown) {
  try {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, value === true ? "true" : "false");
  } catch (_) { /* best effort */ }
}

// Which sessions are marked is daemon-wide; whether this screen is reduced right now is not.
export function loadAdhdMode() {
  try {
    return window.localStorage.getItem(ADHD_MODE_KEY) === "true";
  } catch (_) {
    return false;
  }
}

export function persistAdhdMode(value: unknown) {
  try {
    window.localStorage.setItem(ADHD_MODE_KEY, value === true ? "true" : "false");
  } catch (_) { /* best effort */ }
}

export function loadAttentionCollapsed() {
  try {
    return window.localStorage.getItem(ATTENTION_COLLAPSED_KEY) === "true";
  } catch (_) {
    return false;
  }
}

export function persistAttentionCollapsed(value: unknown) {
  try {
    window.localStorage.setItem(ATTENTION_COLLAPSED_KEY, value === true ? "true" : "false");
  } catch (_) { /* best effort */ }
}
