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

// Answers whether this observation became the current one. Equal revision applies and reports true: a
// PUT's own response and the WebSocket echo of that same save carry the same revision, so treating equal
// as stale would leave the saved values unapplied whenever the echo won the race. Only a strictly newer
// revision means external state the operator has not seen yet, and only that is worth keeping a form
// open for. A payload the daemon's own contract cannot describe reports false as well, which is why the
// caller that must tell "unreadable" from "superseded" apart still asks `sanitizeServerPreferences`.
export function applyServerPreferences(raw) {
  const next = sanitizeServerPreferences(raw);
  if (!next || next.revision < serverPrefs.value.revision) return false;
  serverPrefs.value = next;
  prefs.value = Object.assign({}, prefs.value, next);
  return true;
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
