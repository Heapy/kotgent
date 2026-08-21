// The selection rules behind the shared typeahead-listbox primitive
// (resources/webui/lib/typeahead.js, wired to Preact in resources/webui/components/Typeahead.js).
//
// Four sites spelled these rules four times — the command palette, the two directory-path pickers, and
// the session/task link picker — and disagreed in ways that only a browser could show:
//
//   * the link picker reconciled its active row in a post-paint effect, so a query typed and an Enter
//     pressed in the same frame linked nothing, or the wrong row. Every rule here is a pure function of
//     the current keys and the last explicit choice, so "what is active" has an answer during render and
//     again at event time, with no frame in between. `commitsTheRowNavigationJustChose` is that case.
//   * three sites carried an index with a `-1` sentinel and one carried a ref. An index is a name for a
//     position, and positions move underneath a live list. Keys are the single representation here; the
//     `-1` sentinel becomes a `null` active key, which is a value the rules return rather than a number
//     the callers agree to read as "nothing".
//   * an IME candidate commit dispatches keydown with `key === "Enter"`, `isComposing === true` and
//     `keyCode === 229`. The link picker's Enter is the only one in the app that commits a mutating
//     POST, so `typeaheadIntent` answers "composing" before it answers "commit".
//
// The last block covers lib/commands.js, whose `toLocaleLowerCase()` fold made a command containing "I"
// unfindable under a tr/az browser locale. Node's default locale cannot be changed per test, so the
// fold itself is replaced for the duration of the check by ./turkish-fold.js: that proves the palette no
// longer *calls* the locale-sensitive method, which is the actual claim.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  COMMIT,
  COMPOSING,
  DISMISS,
  NEXT,
  PREVIOUS,
  chooseKey,
  resolveActiveKey,
  stepActiveKey,
  typeaheadIntent,
} from "../../resources/webui/lib/typeahead.js";
import { filterCommands } from "../../resources/webui/lib/commands.js";
import { underTurkishFold } from "./turkish-fold.js";

const KEYS = ["local:1", "local:2", "local:3"];

describe("resolveActiveKey", () => {
  test("an empty list has no active row, whether or not the caller auto-activates", () => {
    for (const autoFirst of [true, false]) {
      assert.equal(resolveActiveKey([], null, { autoFirst: autoFirst }), null);
      assert.equal(resolveActiveKey(null, null, { autoFirst: autoFirst }), null);
    }
  });

  test("a caller that auto-activates starts on the first row with nothing chosen", () => {
    assert.equal(resolveActiveKey(KEYS, null, { autoFirst: true }), "local:1");
  });

  test("a path picker starts on no row at all, so Enter still belongs to the form", () => {
    assert.equal(resolveActiveKey(KEYS, null, { autoFirst: false }), null);
  });

  test("an explicit choice survives a re-render of the same list under the same token", () => {
    const chosen = chooseKey("local:3", "loc");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: true, token: "loc" }), "local:3");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: false, token: "loc" }), "local:3");
  });

  test("a choice the list no longer offers is dropped, not carried as a stale key", () => {
    const chosen = chooseKey("local:9", "loc");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: true, token: "loc" }), "local:1");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: false, token: "loc" }), null);
  });

  test("a new query discards the previous choice even when its row is still listed", () => {
    const chosen = chooseKey("local:3", "loc");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: true, token: "local" }), "local:1");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: false, token: "local" }), null);
  });

  test("no token and an absent token are the same token, so nothing resets on the empty query", () => {
    const chosen = chooseKey("local:2");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: true }), "local:2");
    assert.equal(resolveActiveKey(KEYS, chosen, { autoFirst: true, token: null }), "local:2");
  });

  test("the defaults are the majority caller: auto-activate, no token", () => {
    assert.equal(resolveActiveKey(KEYS, null), "local:1");
  });

  test("a list that shrinks under a live update re-lands on the first row", () => {
    const chosen = chooseKey("local:3", "");
    assert.equal(resolveActiveKey(["local:1"], chosen, { token: "" }), "local:1");
  });
});

describe("stepActiveKey", () => {
  test("an empty list cannot be navigated", () => {
    assert.equal(stepActiveKey([], null, 1), null);
    assert.equal(stepActiveKey(null, null, -1), null);
  });

  test("from nothing, down opens on the first row and up opens on the last", () => {
    assert.equal(stepActiveKey(KEYS, null, 1), "local:1");
    assert.equal(stepActiveKey(KEYS, null, -1), "local:3");
  });

  test("a key the list dropped is treated as nothing rather than as a position", () => {
    assert.equal(stepActiveKey(KEYS, "local:9", 1), "local:1");
    assert.equal(stepActiveKey(KEYS, "local:9", -1), "local:3");
  });

  test("movement wraps in both directions", () => {
    assert.equal(stepActiveKey(KEYS, "local:1", 1), "local:2");
    assert.equal(stepActiveKey(KEYS, "local:3", 1), "local:1");
    assert.equal(stepActiveKey(KEYS, "local:1", -1), "local:3");
    assert.equal(stepActiveKey(KEYS, "local:2", -1), "local:1");
  });

  test("one row is its own neighbour in both directions", () => {
    assert.equal(stepActiveKey(["only"], "only", 1), "only");
    assert.equal(stepActiveKey(["only"], "only", -1), "only");
  });
});

describe("typeaheadIntent", () => {
  test("the four navigation keys are named, and nothing else is", () => {
    assert.equal(typeaheadIntent({ key: "ArrowDown" }), NEXT);
    assert.equal(typeaheadIntent({ key: "ArrowUp" }), PREVIOUS);
    assert.equal(typeaheadIntent({ key: "Enter" }), COMMIT);
    assert.equal(typeaheadIntent({ key: "Escape" }), DISMISS);
    for (const key of ["a", "Tab", "Home", "PageDown", " "]) {
      assert.equal(typeaheadIntent({ key: key }), null);
    }
    assert.equal(typeaheadIntent(null), null);
  });

  test("an IME candidate commit is composition, not a selection", () => {
    assert.equal(typeaheadIntent({ key: "Enter", isComposing: true }), COMPOSING);
    assert.equal(typeaheadIntent({ key: "Enter", keyCode: 229 }), COMPOSING);
    assert.equal(typeaheadIntent({ key: "Enter", isComposing: true, keyCode: 229 }), COMPOSING);
  });

  test("composition outranks every key, so arrows move the candidate list and not the listbox", () => {
    for (const key of ["ArrowDown", "ArrowUp", "Escape", "Enter"]) {
      assert.equal(typeaheadIntent({ key: key, keyCode: 229 }), COMPOSING);
    }
  });

  test("a settled composition commits normally: the guard is not a blanket refusal of Enter", () => {
    assert.equal(typeaheadIntent({ key: "Enter", isComposing: false, keyCode: 13 }), COMMIT);
  });
});

describe("navigating and committing", () => {
  // The defect this primitive exists to remove: ArrowUp and Enter dispatched into the same task, with
  // no render between them. A row derived at render time is one navigation behind here; a row derived
  // from the last explicit choice is not.
  test("commitsTheRowNavigationJustChose", () => {
    const options = { autoFirst: true, token: "" };
    let chosen = null;
    const active = () => resolveActiveKey(KEYS, chosen, options);
    assert.equal(active(), "local:1");

    chosen = chooseKey(stepActiveKey(KEYS, active(), 1), options.token);
    chosen = chooseKey(stepActiveKey(KEYS, active(), -1), options.token);
    assert.equal(active(), "local:1", "two moves in one task settle where the second one landed");

    chosen = chooseKey(stepActiveKey(KEYS, active(), -1), options.token);
    assert.equal(active(), "local:3", "and Enter reads that, not the row the last paint drew");
  });

  test("a choice made by pointer is read back by the very next keystroke", () => {
    const options = { autoFirst: true, token: "q" };
    let chosen = chooseKey("local:2", "q");
    assert.equal(resolveActiveKey(KEYS, chosen, options), "local:2");
    chosen = chooseKey(stepActiveKey(KEYS, resolveActiveKey(KEYS, chosen, options), 1), "q");
    assert.equal(resolveActiveKey(KEYS, chosen, options), "local:3");
  });

  test("a path picker that never navigated has nothing to commit", () => {
    const options = { autoFirst: false, token: "/a/" };
    assert.equal(resolveActiveKey(["/a/b", "/a/c"], null, options), null);
  });
});

describe("filterCommands folds case without a locale", () => {
  const COMMANDS = [
    { id: "index", title: "Index the API", subtitle: null, group: "general", disabled: null },
    { id: "other", title: "Restart the daemon", subtitle: null, group: "general", disabled: null },
  ];

  test("a command containing I is findable under a Turkish fold", () => {
    const found = underTurkishFold(() => filterCommands(COMMANDS, "index"));
    assert.deepEqual(found.map((item) => item.id), ["index"]);
  });

  test("a query typed with an uppercase I is folded the same way", () => {
    const found = underTurkishFold(() => filterCommands(COMMANDS, "Index"));
    assert.deepEqual(found.map((item) => item.id), ["index"]);
  });

  test("the substitution is real, and it is undone", () => {
    assert.equal("Index the API".toLocaleLowerCase(), "index the api");
    const folded = underTurkishFold(() => "Index the API".toLocaleLowerCase());
    assert.equal(folded, "ındex the apı", "a locale fold would have hidden this title from 'index'");
    assert.equal("Index the API".toLocaleLowerCase(), "index the api");
  });

  test("a word-start match still outranks one inside a word, with both titles reachable", () => {
    const items = [
      { id: "inside", title: "Reindex everything", group: "general", disabled: null },
      { id: "start", title: "Index the API", group: "general", disabled: null },
    ];
    const found = underTurkishFold(() => filterCommands(items, "index"));
    assert.deepEqual(found.map((item) => item.id), ["start", "inside"]);
  });
});
