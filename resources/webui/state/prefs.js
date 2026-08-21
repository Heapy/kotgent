// The preferences the page is running under. `lib/prefs.js` owns the sanitizing and the localStorage
// keys; this module owns the current value and is its only writer. See the header of state/sessions.js
// for why signals-core is imported by relative path rather than by bare specifier.
//
// One object, two sources. `basePath`, `groupingLevel` and `revision` are daemon-wide and arrive both as
// the response to a PUT and as a pushed `preferences_update`, in either order; `terminalFontSize` and
// `terminalUnicode` are device-local and never leave this browser. Merging them into one value is what
// every reader wants — and is also why the daemon's own triple is kept separately in `serverPrefs`.
// Comparing the merged object against a response would let a device-local field decide a revision
// question it has no part in, and the closing announcement of a save must quote what the daemon
// committed rather than what the form asked for.
//
// app.js used to hold this as `useState` plus three refs: a `prefsRef` mirror, a
// `preferencesRevisionRef`, and a `serverPreferencesRef` whose `.revision` was by construction always
// equal to the second. Three hand-maintained copies of two facts.

import { signal } from "../vendor/signals-core.module.js";
import {
  loadPrefs,
  persistTerminalFontSize,
  persistTerminalUnicode,
  sanitizeServerPreferences,
} from "../lib/prefs.js";

export const prefs = signal(loadPrefs());

// The last daemon-committed triple, and the rank every later observation is judged against.
export const serverPrefs = signal({
  basePath: prefs.value.basePath,
  groupingLevel: prefs.value.groupingLevel,
  revision: prefs.value.revision,
});

// What became of one observation. The two ways of not applying it are different facts and the save flow
// acts on each differently — an unreadable body means the daemon never told us what it committed and the
// save is reported as failed, while a superseded one means the save landed and something newer has
// arrived since. A single boolean conflated them, so the caller sanitized the payload a second time just
// to tell them apart.
export const PREFS_APPLIED = "applied";
export const PREFS_SUPERSEDED = "superseded";
export const PREFS_UNREADABLE = "unreadable";

// Equal revision applies: a PUT's own response and the WebSocket echo of that same save carry the same
// revision, so treating equal as stale would leave the saved values unapplied whenever the echo won the
// race. Only a strictly newer revision means external state the operator has not seen yet, and only that
// is worth keeping a form open for.
export function applyServerPreferences(raw) {
  const next = sanitizeServerPreferences(raw);
  if (!next) return PREFS_UNREADABLE;
  if (next.revision < serverPrefs.value.revision) return PREFS_SUPERSEDED;
  serverPrefs.value = next;
  prefs.value = Object.assign({}, prefs.value, next);
  return PREFS_APPLIED;
}

// Device-local fields, applied and persisted together so the stored value and the rendered one cannot
// disagree. They carry no revision and race nothing: this browser is their only author.
export function applyDevicePreferences(next) {
  persistTerminalFontSize(next.terminalFontSize);
  persistTerminalUnicode(next.terminalUnicode);
  prefs.value = Object.assign({}, prefs.value, {
    terminalFontSize: next.terminalFontSize,
    terminalUnicode: next.terminalUnicode,
  });
}
