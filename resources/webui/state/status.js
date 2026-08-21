// The one sentence the aria-live status region is showing, and whether it is an error. See the header of
// state/sessions.js for why signals-core is imported by relative path rather than by bare specifier.
//
// This state had a mirror ref in app.js that no reader ever consulted — `say` wrote both copies and only
// the rendered one was ever read. A write-only mirror is worse than a redundant one: it looks like a
// source that async code may consult, so the next flow that needs the current sentence reaches for it
// and gets whatever the last render happened to leave there. Holding the value here is what makes the
// absence honest. There is exactly one value and exactly one writer.

import { signal } from "../vendor/signals-core.module.js";

export const EMPTY_STATUS = Object.freeze({ text: "", error: false });

export const status = signal(EMPTY_STATUS);

// Announces one sentence. A fresh object every time, including for a repeated sentence: the announcement
// is the event, not the text, and a status line that stopped re-rendering for a repeat would stop
// reporting a retry that says the same thing twice.
export function say(text, error) {
  status.value = { text: text, error: !!error };
}
