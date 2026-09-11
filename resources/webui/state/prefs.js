// Keep revisioned daemon preferences separate from unversioned device-local terminal settings.

import { signal } from "../vendor/signals-core.module.js";
import {
  loadPrefs,
  persistTerminalFontSize,
  persistTerminalUnicode,
  sanitizeServerPreferences,
} from "../lib/prefs.js";

export const prefs = signal(loadPrefs());

export const serverPrefs = signal({
  basePath: prefs.value.basePath,
  groupingLevel: prefs.value.groupingLevel,
  revision: prefs.value.revision,
});

// Callers distinguish an unreadable response from one superseded by newer daemon state.
export const PREFS_APPLIED = "applied";
export const PREFS_SUPERSEDED = "superseded";
export const PREFS_UNREADABLE = "unreadable";

// Equal revisions describe the same save; only a strictly newer observation supersedes it.
export function applyServerPreferences(raw) {
  const next = sanitizeServerPreferences(raw);
  if (!next) return PREFS_UNREADABLE;
  if (next.revision < serverPrefs.value.revision) return PREFS_SUPERSEDED;
  serverPrefs.value = next;
  prefs.value = Object.assign({}, prefs.value, next);
  return PREFS_APPLIED;
}

// Persist device-local fields before publishing the same values to subscribers.
export function applyDevicePreferences(next) {
  persistTerminalFontSize(next.terminalFontSize);
  persistTerminalUnicode(next.terminalUnicode);
  prefs.value = Object.assign({}, prefs.value, {
    terminalFontSize: next.terminalFontSize,
    terminalUnicode: next.terminalUnicode,
  });
}
