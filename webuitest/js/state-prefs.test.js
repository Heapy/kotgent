// Preference readiness from resources/webui/state/prefs.js. Until the daemon answers, adhdPaths is a
// placeholder, so the sidebar must tell "no folder marks" from "marks not here yet". This file stands
// alone because readiness is sticky once ready, and node:test gives each file a fresh process.

import { test } from "node:test";
import assert from "node:assert/strict";

import { READY } from "../../resources/webui/lib/readiness.js";
import {
  PREFS_APPLIED,
  PREFS_SUPERSEDED,
  PREFS_UNREADABLE,
  applyServerPreferences,
  prefs,
  prefsReadiness,
} from "../../resources/webui/state/prefs.js";

const known = () => prefsReadiness.status.value.state === READY;

test("the seeded placeholder is not an answer", () => {
  assert.deepEqual(prefs.value.adhdPaths, []);
  assert.equal(known(), false);
});

test("an unreadable answer still says nothing about the marks", () => {
  assert.equal(applyServerPreferences({ basePath: 7 }), PREFS_UNREADABLE);
  assert.equal(known(), false);
});

test("the first applied answer makes the marks known", () => {
  const answer = { basePath: "/", groupingLevel: 1, revision: 3, adhdPaths: ["/a"] };

  assert.equal(applyServerPreferences(answer), PREFS_APPLIED);

  assert.equal(known(), true);
  assert.deepEqual(prefs.value.adhdPaths, ["/a"]);
});

test("a later superseded answer leaves the marks known", () => {
  const older = { basePath: "/", groupingLevel: 1, revision: 2 };

  assert.equal(applyServerPreferences(older), PREFS_SUPERSEDED);

  assert.equal(known(), true);
});
