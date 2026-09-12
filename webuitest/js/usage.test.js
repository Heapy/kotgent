import { test } from "node:test";
import assert from "node:assert/strict";
import { applyUsageSnapshot, upsertUsageIfNewer } from "../../resources/webui/lib/usage.js";
import { mergeUsageWindow, replaceUsage, usage } from "../../resources/webui/state/usage.js";
import { effect } from "../../resources/webui/vendor/signals-core.module.js";

function window(overrides = {}) {
  return Object.freeze({
    provider: "claude", windowKey: "seven_day", usedPercent: 40,
    resetsAt: 1_800_604_800_000, windowSeconds: 604_800,
    observedAt: 100, changedAt: 100, ...overrides,
  });
}

test("usage is keyed by provider and native window, without mutating prior rows", () => {
  const first = window();
  const input = Object.freeze([first]);
  const otherProvider = window({ provider: "codex" });
  const otherWindow = window({ windowKey: "five_hour" });
  const result = upsertUsageIfNewer(upsertUsageIfNewer(input, otherProvider), otherWindow);
  assert.deepEqual(result, [first, otherProvider, otherWindow]);
  assert.deepEqual(input, [first]);
});

test("newer observations win and late or equal frames cannot reverse a meter", () => {
  const before = window();
  const original = Object.freeze([before]);
  const newer = window({ observedAt: 102, usedPercent: 5 });
  const merged = upsertUsageIfNewer(original, newer);
  assert.deepEqual(merged, [newer]);
  assert.deepEqual(original, [before]);
  assert.equal(upsertUsageIfNewer(merged, window({ observedAt: 101, usedPercent: 99 })), merged);
  assert.equal(upsertUsageIfNewer(merged, window({ observedAt: 102, usedPercent: 80 })), merged);
});

test("a matching heartbeat advances observation time while change time stays fixed", () => {
  const original = [window()];
  const heartbeat = window({ observedAt: 60_100 });
  assert.equal(upsertUsageIfNewer(original, heartbeat)[0], heartbeat);
  assert.equal(heartbeat.changedAt, original[0].changedAt);
});

test("snapshots are authoritative and take the newest duplicate within the snapshot", () => {
  const newer = window({ observedAt: 200 });
  const secondWindow = window({ windowKey: "five_hour" });
  const rows = Object.freeze([newer, window(), secondWindow]);
  assert.deepEqual(applyUsageSnapshot(rows), [newer, secondWindow]);
  assert.deepEqual(applyUsageSnapshot([]), []);
  assert.deepEqual(applyUsageSnapshot(null), []);
});

test("the signal writers share the reactive graph and publish only admitted updates", () => {
  replaceUsage([]);
  const observed = [];
  const dispose = effect(() => observed.push(usage.value.map((row) => row.observedAt)));
  try {
    replaceUsage([window()]);
    mergeUsageWindow(window({ observedAt: 101 }));
    mergeUsageWindow(window({ observedAt: 100, usedPercent: 99 }));
    mergeUsageWindow(window({ observedAt: 101, usedPercent: 99 }));
    assert.deepEqual(observed, [[], [100], [101]]);
    assert.equal(usage.value[0].usedPercent, 40);

    replaceUsage([window({ provider: "codex", observedAt: 50 })]);
    assert.equal(usage.value.length, 1);
    assert.equal(usage.value[0].provider, "codex");
    assert.deepEqual(observed.at(-1), [50], "reconnect replaces the previous account projection");
    replaceUsage([window({ provider: "codex", observedAt: 1 })]);
    assert.deepEqual(observed.at(-1), [1], "a restored daemon snapshot also replaces a newer cached row");
    replaceUsage([]);
    assert.deepEqual(usage.value, []);
  } finally {
    dispose();
    replaceUsage([]);
  }
});
