// Keep revisioned daemon preferences separate from unversioned device-local terminal settings.

import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { createReadiness } from "../lib/readiness.ts";
import type { Preferences, ServerPreferences } from "../lib/prefs.ts";
import {
  loadPrefs,
  persistTerminalFontSize,
  persistTerminalUnicode,
  sanitizeServerPreferences,
} from "../lib/prefs.ts";

const prefsState = signal<Preferences>(loadPrefs());
export const prefs: ReadonlySignal<Preferences> = prefsState;

// Until the daemon answers, `adhdPaths` is a placeholder in which "no folder marks" and "marks not here
// yet" look the same, so anything that reports on marks waits for this.
export const prefsReadiness = createReadiness();

const serverPrefsState = signal<ServerPreferences>({
  basePath: prefs.value.basePath,
  groupingLevel: prefs.value.groupingLevel,
  revision: prefs.value.revision,
  adhdPaths: prefs.value.adhdPaths,
});
export const serverPrefs: ReadonlySignal<ServerPreferences> = serverPrefsState;

// Callers distinguish an unreadable response from one superseded by newer daemon state.
export const PREFS_APPLIED = "applied";
export const PREFS_SUPERSEDED = "superseded";
export const PREFS_UNREADABLE = "unreadable";

// Equal revisions describe the same save; only a strictly newer observation supersedes it.
export function applyServerPreferences(raw: unknown) {
  const next = sanitizeServerPreferences(raw);
  if (!next) return PREFS_UNREADABLE;
  if (next.revision < serverPrefs.value.revision) return PREFS_SUPERSEDED;
  serverPrefsState.value = next;
  prefsState.value = Object.assign({}, prefs.value, next);
  prefsReadiness.succeed();
  return PREFS_APPLIED;
}

// Persist device-local fields before publishing the same values to subscribers.
export function applyDevicePreferences(next: Pick<Preferences, "terminalFontSize" | "terminalUnicode">) {
  persistTerminalFontSize(next.terminalFontSize);
  persistTerminalUnicode(next.terminalUnicode);
  prefsState.value = Object.assign({}, prefs.value, {
    terminalFontSize: next.terminalFontSize,
    terminalUnicode: next.terminalUnicode,
  });
}
