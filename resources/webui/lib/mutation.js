// One runner for every flow in the Web UI that changes daemon state. Before this module each of the six
// mutating flows in app.js re-invented "is something already running": a boolean save-in-flight ref for
// preferences, a pending-action mirror ref for import/control/link, a status-sentence comparison for the
// link's follow-up read, and nothing at all for session start and project archive. Four idioms, four
// scopes, and no single answer to the question the command palette has to ask.
//
// The runner owns three things and nothing else:
//
//   * the lock. It is taken synchronously at the call, before the callback's first await, so two flows
//     entered in the same turn cannot interleave; and it is released only when the whole callback has
//     settled, so a mutation's own follow-up read still holds it. That second half is deliberate and
//     costs something: an unrelated control can wait up to one transport timeout (API_REQUEST_TIMEOUT_MS,
//     60 s, lib/api.js:4). The alternative — releasing after the request and reading unlocked — let a
//     second run start while the first was still settling and overwrite its result.
//   * the name of the flow holding the lock, published as a signal so the palette's disabled reasons and
//     the link picker's guard read one value rather than a copy each.
//   * a monotonic generation token. `isCurrent()` answers "has a newer mutation started since mine",
//     which is what a late outcome must ask before it writes anything the operator can see. A refused
//     attempt never took the lock and never advances it, so it supersedes nobody.
//
// It is not a liveness guard and not a selection guard. A dialog that unmounted mid-request, and a
// selection the operator moved during one, are different questions with their own answers.
//
// signals-core is imported by relative path rather than through the "@preact/signals-core" bare
// specifier the import map wires; see the comment at state/sessions.js for why the two resolve to one
// module instance. Nothing here touches the DOM, the network, or a timer, so the rules are proven at the
// node tier in webuitest/js/mutation.test.js.

import { signal } from "../vendor/signals-core.module.js";

// The refusal an operator reads. Pinned by the browser tier: a new-session form submitted while another
// flow is running shows exactly this sentence.
export const MUTATION_BUSY_MESSAGE = "Another action is still in progress — try again in a moment.";

// The name of the flow holding the lock, or null. Names are the vocabulary lib/commands.js already
// speaks: "interrupt", "resume", "stop", "done", "undone", "import", "start", "link-task",
// "preferences", "delete-project", "restore-project".
export const pendingMutation = signal(null);

let generation = 0;

export function isMutating() {
  return pendingMutation.value !== null;
}

export async function runMutation(name, fn) {
  if (pendingMutation.value !== null) throw new Error(MUTATION_BUSY_MESSAGE);
  const token = ++generation;
  const isCurrent = () => generation === token;
  pendingMutation.value = name;
  try {
    return await fn({ isCurrent: isCurrent, name: name });
  } finally {
    pendingMutation.value = null;
  }
}
