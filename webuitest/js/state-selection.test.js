// The four state groups that moved out of app.js last: the selection (with its generation counter), the
// open dialog, the announced status sentence, and the preferences. They were `useState` plus a hand-kept
// `useRef` mirror each, so none of their rules were reachable below a browser. They are reachable here
// because these modules import signals-core by relative path and touch no DOM, no network and no timer —
// `lib/prefs.js` reads localStorage, but behind its own try/catch, so it answers defaults under node.
//
// The headline case is A→B→A. `selectionGenRef` existed because an async flow that conditionally
// auto-selects on completion must be refused once the operator has navigated during it — and comparing
// *ids* cannot see a round trip that ends where it started. Nothing else in the page can answer that
// question: `runMutation`'s `isCurrent()` stays true across any amount of navigation.

import { describe, test, beforeEach } from "node:test";
import assert from "node:assert/strict";

import { effect } from "../../resources/webui/vendor/signals-core.module.js";
import { DEFAULT_PREFS } from "../../resources/webui/lib/prefs.js";
import { mergeSessionRow, replaceSessions } from "../../resources/webui/state/sessions.js";
import {
  activeSession,
  activeSessionId,
  markSelection,
  pruneSelection,
  selectSessionId,
  selectionGeneration,
} from "../../resources/webui/state/selection.js";
import {
  closeDialog,
  closeDialogFrom,
  dialog,
  openDialog,
} from "../../resources/webui/state/dialog.js";
import { EMPTY_STATUS, say, status } from "../../resources/webui/state/status.js";
import {
  applyDevicePreferences,
  applyServerPreferences,
  prefs,
  serverPrefs,
} from "../../resources/webui/state/prefs.js";

// Frozen inputs turn an accidental in-place write into a TypeError; ES modules are always strict mode.
function sessionRow(overrides) {
  return Object.freeze({
    id: "a",
    name: "one",
    state: "running",
    needsAttention: false,
    alive: true,
    lastSeq: 1,
    unread: 0,
    rev: 1,
    ...overrides,
  });
}

// Module state is a singleton and node isolates per file, not per test.
beforeEach(() => {
  replaceSessions([]);
  activeSessionId.value = null;
  selectionGeneration.value = 0;
  dialog.value = null;
  status.value = EMPTY_STATUS;
  prefs.value = { ...DEFAULT_PREFS };
  serverPrefs.value = {
    basePath: DEFAULT_PREFS.basePath,
    groupingLevel: DEFAULT_PREFS.groupingLevel,
    revision: DEFAULT_PREFS.revision,
  };
});

describe("state/selection.js", () => {
  test("selecting moves the selection and advances the generation", () => {
    assert.equal(selectionGeneration.value, 0);
    selectSessionId("a");
    assert.equal(activeSessionId.value, "a");
    assert.equal(selectionGeneration.value, 1);
  });

  test("re-selecting the same id is still a selection event", () => {
    selectSessionId("a");
    selectSessionId("a");
    assert.equal(
      selectionGeneration.value,
      2,
      "the return leg of an A→B→A round trip re-selects an id that is already current",
    );
  });

  test("an empty id clears the selection rather than storing a falsy value", () => {
    selectSessionId("a");
    selectSessionId("");
    assert.equal(activeSessionId.value, null);
  });

  test("a flow whose selection never moved may auto-select", async () => {
    replaceSessions([sessionRow({ id: "a" })]);
    selectSessionId("a");
    const selectionUnmoved = markSelection();
    const settled = (async () => {
      await Promise.resolve();
      return selectionUnmoved();
    })();
    assert.equal(await settled, true);
  });

  test("a flow is refused after the operator navigates A → B → A during it", async () => {
    replaceSessions([sessionRow({ id: "a" }), sessionRow({ id: "b", name: "two" })]);
    selectSessionId("a");

    // The flow captures the selection it was submitted under, then awaits.
    const selectionUnmoved = markSelection();
    const settled = (async () => {
      await Promise.resolve();
      return selectionUnmoved();
    })();

    // Meanwhile the operator goes to B and comes back to A.
    selectSessionId("b");
    selectSessionId("a");

    assert.equal(
      activeSessionId.value,
      "a",
      "the selected id is exactly what it was at submit — an id comparison would auto-select here",
    );
    assert.equal(
      await settled,
      false,
      "the operator navigated during the flow, so its late auto-select must be refused",
    );
  });

  test("two marks taken at different points answer independently", () => {
    selectSessionId("a");
    const first = markSelection();
    selectSessionId("b");
    const second = markSelection();
    assert.equal(first(), false);
    assert.equal(second(), true);
  });

  test("pruning clears a selection the snapshot no longer carries", () => {
    selectSessionId("a");
    assert.equal(pruneSelection(new Set(["b"])), true);
    assert.equal(activeSessionId.value, null);
  });

  test("pruning leaves a selection the snapshot still carries", () => {
    selectSessionId("a");
    assert.equal(pruneSelection(new Set(["a", "b"])), false);
    assert.equal(activeSessionId.value, "a");
  });

  test("pruning is not a selection event, so a waiting flow is not refused by it", () => {
    selectSessionId("a");
    const selectionUnmoved = markSelection();
    pruneSelection(new Set(["b"]));
    assert.equal(activeSessionId.value, null);
    assert.equal(
      selectionUnmoved(),
      true,
      "the operator navigated nowhere; the daemon retired an unrelated row",
    );
    assert.equal(selectionGeneration.value, 1);
  });

  test("pruning an empty selection reports no change", () => {
    assert.equal(pruneSelection(new Set(["a"])), false);
    assert.equal(selectionGeneration.value, 0);
  });

  test("the derived active session follows both the selection and the list", () => {
    assert.equal(activeSession.value, null, "nothing is selected");
    selectSessionId("a");
    assert.equal(activeSession.value, null, "the selected row has not arrived yet");
    replaceSessions([sessionRow({ id: "a" }), sessionRow({ id: "b", name: "two" })]);
    assert.equal(activeSession.value.name, "one");
    selectSessionId("b");
    assert.equal(activeSession.value.name, "two");
  });

  test("the derived active session costs a render only when its own row changes", () => {
    replaceSessions([sessionRow({ id: "a" }), sessionRow({ id: "b", name: "two" })]);
    selectSessionId("a");

    const seen = [];
    const stop = effect(() => { seen.push(activeSession.value); });
    assert.equal(seen.length, 1);

    mergeSessionRow(sessionRow({ id: "b", name: "two renamed", rev: 2 }));
    assert.equal(seen.length, 1, "a merge into an unrelated row is not news for the selected one");

    mergeSessionRow(sessionRow({ id: "a", name: "one renamed", rev: 2 }));
    assert.equal(seen.length, 2);
    assert.equal(seen[1].name, "one renamed");
    stop();
  });
});

describe("state/dialog.js", () => {
  test("opening publishes the descriptor and closing clears it", () => {
    const form = { kind: "prefs" };
    openDialog(form);
    assert.equal(dialog.value, form);
    closeDialog();
    assert.equal(dialog.value, null);
  });

  test("opening nothing clears rather than storing a falsy descriptor", () => {
    openDialog({ kind: "help" });
    openDialog(null);
    assert.equal(dialog.value, null);
  });

  test("a late completion closes the instance that submitted it", () => {
    const form = { kind: "new" };
    openDialog(form);
    assert.equal(closeDialogFrom(form), true);
    assert.equal(dialog.value, null);
  });

  test("a late completion cannot close a dialog that replaced its own", () => {
    const submitted = { kind: "new" };
    openDialog(submitted);
    const replacement = { kind: "link-task" };
    openDialog(replacement);
    assert.equal(closeDialogFrom(submitted), false);
    assert.equal(dialog.value, replacement, "the operator's current form stays open");
  });

  test("a completion submitted with no dialog open cannot close a later one", () => {
    const submitted = dialog.value;
    const later = { kind: "help" };
    openDialog(later);
    assert.equal(closeDialogFrom(submitted), false);
    assert.equal(dialog.value, later);
  });
});

describe("state/status.js", () => {
  test("the initial sentence is empty and is not an error", () => {
    assert.deepEqual(status.value, { text: "", error: false });
  });

  test("an announcement publishes its sentence, errors coerced to a boolean", () => {
    say("Started one.");
    assert.deepEqual(status.value, { text: "Started one.", error: false });
    say("Stop failed: timed out", "the daemon said so");
    assert.deepEqual(status.value, { text: "Stop failed: timed out", error: true });
  });

  test("repeating a sentence is a new announcement", () => {
    say("Resume in progress…");
    const first = status.value;
    say("Resume in progress…");
    assert.notEqual(
      status.value,
      first,
      "a retry that says the same thing twice is still something that happened",
    );
    assert.deepEqual(status.value, first);
  });
});

describe("state/prefs.js", () => {
  const committed = { basePath: "/work", groupingLevel: 2, revision: 5 };

  test("a newer revision applies and merges over the device fields", () => {
    prefs.value = { ...prefs.value, terminalFontSize: 16 };
    assert.equal(applyServerPreferences(committed), true);
    assert.equal(prefs.value.basePath, "/work");
    assert.equal(prefs.value.groupingLevel, 2);
    assert.equal(prefs.value.revision, 5);
    assert.equal(prefs.value.terminalFontSize, 16, "a daemon-wide update owns no device field");
  });

  test("an equal revision applies, because a save's response and its own echo share one", () => {
    applyServerPreferences(committed);
    assert.equal(
      applyServerPreferences({ basePath: "/echo", groupingLevel: 3, revision: 5 }),
      true,
    );
    assert.equal(prefs.value.basePath, "/echo");
    assert.equal(prefs.value.groupingLevel, 3);
  });

  test("an older revision is declined and changes nothing", () => {
    applyServerPreferences(committed);
    const before = prefs.value;
    assert.equal(
      applyServerPreferences({ basePath: "/stale", groupingLevel: 0, revision: 4 }),
      false,
    );
    assert.equal(prefs.value, before, "a declined observation must not even cost a render");
  });

  test("a payload the daemon's contract cannot describe is declined", () => {
    const before = prefs.value;
    assert.equal(applyServerPreferences(null), false);
    assert.equal(applyServerPreferences({ basePath: 5, groupingLevel: 1, revision: 1 }), false);
    assert.equal(applyServerPreferences({ basePath: "/w", groupingLevel: 9, revision: 1 }), false);
    assert.equal(prefs.value, before);
  });

  test("the daemon's own triple is kept apart from the merged view", () => {
    prefs.value = { ...prefs.value, terminalFontSize: 16 };
    applyServerPreferences(committed);
    assert.deepEqual(serverPrefs.value, { basePath: "/work", groupingLevel: 2, revision: 5 });
  });

  test("device preferences change only the device fields", () => {
    applyServerPreferences(committed);
    applyDevicePreferences({ terminalFontSize: 16, terminalUnicode: "15-graphemes" });
    assert.equal(prefs.value.terminalFontSize, 16);
    assert.equal(prefs.value.terminalUnicode, "15-graphemes");
    assert.equal(prefs.value.basePath, "/work", "they carry no revision and arbitrate nothing");
    assert.equal(prefs.value.groupingLevel, 2);
    assert.equal(prefs.value.revision, 5);
    assert.deepEqual(
      serverPrefs.value,
      { basePath: "/work", groupingLevel: 2, revision: 5 },
      "a device field must never be echoed back as daemon-committed state",
    );
  });
});
