// The one sentence the aria-live status region is showing, and whether it is an error. See the header of
// state/sessions.js for why signals-core is imported by relative path rather than by bare specifier.
//
// There is exactly one value and exactly one writer, and nothing may hold a copy beside it. A write-only
// mirror is worse than a redundant one: it looks like a source that async code may consult, so the next
// flow needing the current sentence reaches for it and gets whatever the last render left there.

import { signal } from "../vendor/signals-core.module.js";

export const EMPTY_STATUS = Object.freeze({ text: "", error: false });

export const status = signal(EMPTY_STATUS);

// Announcements are numbered. The number is not shown anywhere; it exists so that a flow which announces
// something, awaits, and then wants to replace its own sentence with the outcome can ask whether the
// sentence it is about to overwrite is still the one it wrote. Anything the operator was told in between
// — the socket's "Daemon connection lost", the result of a command they started meanwhile — is newer
// news than a result they have already been told is coming.
//
// This replaces two earlier answers to that one question. The first compared the status *text* to the
// sentence the flow had written, which a repeated sentence defeats. The second was a generation counter
// in lib/mutation.js, which could not answer it at all: the mutation lock is exclusive, so no newer
// mutation can start while an older one runs and "am I still the newest mutation" is always yes. The
// announcement is what gets superseded, so the counter belongs here.
let announcements = 0;

// Announces one sentence and returns its number. A fresh object every time, including for a repeated
// sentence: the announcement is the event, not the text, and a status line that stopped re-rendering for
// a repeat would stop reporting a retry that says the same thing twice.
export function say(text, error) {
  announcements += 1;
  status.value = { text: text, error: !!error };
  return announcements;
}

// Whether the announcement `token` names — the value `say` returned — is still the newest one.
export function announcementHolds(token) {
  return announcements === token;
}
