// Grouping is daemon-wide; terminal rendering and shell state remain device-local.

import { normalizePath } from "./paths.js";
import { DEFAULT_TERMINAL_UNICODE, isTerminalUnicodeMode } from "./unicode.js";

export const LEGACY_PREFS_KEY = "kotgent.prefs.v1";
export const TERMINAL_FONT_SIZE_KEY = "kotgent.terminalFontSize.v1";
export const TERMINAL_UNICODE_KEY = "kotgent.terminalUnicode.v1";
export const SIDEBAR_COLLAPSED_KEY = "kotgent.sidebarCollapsed.v1";
export const MAX_GROUPING_LEVEL = 4;
export const TERMINAL_FONT_SIZES = [11, 13, 16];
export const DEFAULT_PREFS = {
  basePath: "",
  groupingLevel: 1,
  revision: 0,
  terminalFontSize: 13,
  terminalUnicode: DEFAULT_TERMINAL_UNICODE,
};

export function sanitizePrefs(raw) {
  const level = Number.parseInt(raw && raw.groupingLevel, 10);
  const revision = Number(raw && raw.revision);
  const fontSize = Number.parseInt(raw && raw.terminalFontSize, 10);
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
  };
}

export function sanitizeServerPreferences(raw) {
  if (!raw || typeof raw.basePath !== "string") return null;
  if (!Number.isInteger(raw.groupingLevel) ||
      raw.groupingLevel < 0 ||
      raw.groupingLevel > MAX_GROUPING_LEVEL) return null;
  if (!Number.isSafeInteger(raw.revision) || raw.revision < 0) return null;
  const basePath = normalizePath(raw.basePath);
  if (basePath.length > 0 && basePath.charAt(0) !== "/") return null;
  return {
    basePath: basePath,
    groupingLevel: raw.groupingLevel,
    revision: raw.revision,
  };
}

export function loadPrefs() {
  let terminalFontSize = DEFAULT_PREFS.terminalFontSize;
  let terminalUnicode = DEFAULT_PREFS.terminalUnicode;
  try {
    window.localStorage.removeItem(LEGACY_PREFS_KEY);
    terminalFontSize = window.localStorage.getItem(TERMINAL_FONT_SIZE_KEY);
    terminalUnicode = window.localStorage.getItem(TERMINAL_UNICODE_KEY);
  } catch (_) {
  }
  return sanitizePrefs({ terminalFontSize: terminalFontSize, terminalUnicode: terminalUnicode });
}

export function persistTerminalFontSize(value) {
  const fontSize = sanitizePrefs({ terminalFontSize: value }).terminalFontSize;
  try {
    window.localStorage.setItem(TERMINAL_FONT_SIZE_KEY, String(fontSize));
  } catch (_) { /* best effort */ }
}

export function persistTerminalUnicode(value) {
  const unicode = sanitizePrefs({ terminalUnicode: value }).terminalUnicode;
  try {
    window.localStorage.setItem(TERMINAL_UNICODE_KEY, unicode);
  } catch (_) { /* best effort */ }
}

export function groupingEnabled(prefs) {
  return prefs.basePath.length > 0;
}

// Keep collapsed paths outside the preferences object so saving preferences cannot reset them.
export const COLLAPSED_GROUPS_KEY = "kotgent.collapsedGroups.v1";

export function loadCollapsedGroups() {
  try {
    const raw = window.localStorage.getItem(COLLAPSED_GROUPS_KEY);
    const list = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(list) ? list.filter((p) => typeof p === "string") : []);
  } catch (_) {
    return new Set();
  }
}

export function persistCollapsedGroups(paths) {
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

export function persistSidebarCollapsed(value) {
  try {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, value === true ? "true" : "false");
  } catch (_) { /* best effort */ }
}
