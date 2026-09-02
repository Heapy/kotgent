// One runner for every flow in the Web UI that changes daemon state, so that "is something already
// running" has one answer rather than one per flow. The command palette has to ask it about all seven at
// once, which a per-flow flag — or, for two of them, no flag at all — cannot answer.
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
//
// It owns no currency token, and deliberately so. One was tried here and could only ever answer yes:
// exclusivity means no newer mutation can start while an older one is running, so "has a newer mutation
// superseded mine" has no reachable no. What a late outcome actually has to ask is whether the sentence
// it is about to overwrite is still its own — an announcement is superseded by anything that speaks,
// including the socket's connection-lost warning and a clipboard copy, neither of which is a mutation.
// That question is answered by `announcementHolds` in state/status.js, which numbers the announcements.
//
// It is not a liveness guard and not a selection guard either. A dialog that unmounted mid-request, and
// a selection the operator moved during one, are different questions with their own answers.
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
// speaks: "interrupt", "resume", "stop", "done", "undone", "import", "start", "rename", "link-task",
// "preferences", "delete-project", "restore-project".
export const pendingMutation = signal(null);

export async function runMutation(name, fn) {
  if (pendingMutation.value !== null) throw new Error(MUTATION_BUSY_MESSAGE);
  pendingMutation.value = name;
  try {
    return await fn();
  } finally {
    pendingMutation.value = null;
  }
}
