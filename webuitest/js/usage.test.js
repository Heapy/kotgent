import { test } from "node:test";
import assert from "node:assert/strict";
import { applyUsageSnapshot, providerUsageStaleAt, upsertUsageIfNewer } from "../../resources/webui/lib/usage.js";
import { mergeUsageWindow, replaceUsage, usage } from "../../resources/webui/state/usage.js";
import { effect } from "../../resources/webui/vendor/signals-core.module.js";

function window(overrides = {}) {
  return Object.freeze({
    provider: "claude", windowKey: "seven_day", usedPercent: 40,
    resetsAt: 1_800_604_800_000, windowSeconds: 604_800,
    observedAt: 100, changedAt: 100, receivedAt: overrides.observedAt ?? 100, ...overrides,
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

test("snapshot age is independent of a phone clock hours ahead or behind the daemon", (t) => {
  const serverNow = 1_800_000_000_000;
  let phoneNow = serverNow + 12 * 60 * 60 * 1000;
  t.mock.method(Date, "now", () => phoneNow);
  t.mock.method(performance, "now", () => 1000);
  try {
    for (const skew of [12, -12]) {
      phoneNow = serverNow + skew * 60 * 60 * 1000;
      replaceUsage([
        window({ observedAt: serverNow - 599_999 }),
        window({ provider: "codex", observedAt: serverNow - 600_001 }),
      ], serverNow);
      assert.equal(usage.value[0].staleAt, 1002, "a nearly ten-minute-old reading retains only two milliseconds");
      assert.equal(usage.value[1].staleAt, 1000, "an old snapshot is already stale when it arrives");
    }
  } finally {
    replaceUsage([]);
  }
});

test("an admitted heartbeat restores freshness without replacing usage history, but replay cannot extend it", () => {
  try {
    replaceUsage([window()], 100, 1000);
    assert.equal(usage.value[0].staleAt, 601_001);
    mergeUsageWindow(window({ observedAt: 900_100 }), 900_100, 901_000);
    const refreshed = usage.value;
    assert.deepEqual(refreshed[0], {
      provider: "claude", windowKey: "seven_day", usedPercent: 40,
      resetsAt: 1_800_604_800_000, windowSeconds: 604_800,
      observedAt: 900_100, changedAt: 100, receivedAt: 900_100, staleAt: 1_501_001,
    });
    mergeUsageWindow(window({ observedAt: 900_100, usedPercent: 99 }), 1_500_100, 1_501_000);
    mergeUsageWindow(window({ observedAt: 800_100, usedPercent: 98 }), 1_500_100, 1_501_000);
    assert.equal(usage.value, refreshed, "equal and late revisions cannot revive stale data or change its value");
  } finally {
    replaceUsage([]);
  }
});

test("reconnecting ages the authoritative snapshot instead of granting old values another ten minutes", () => {
  try {
    replaceUsage([window()], 100, 0);
    replaceUsage([window()], 600_200, 20);
    assert.ok(usage.value[0].staleAt < 20);
    assert.equal(usage.value[0].usedPercent, 40);
    replaceUsage([window({ observedAt: 600_100 })], 600_200, 30);
    assert.equal(usage.value[0].staleAt, 599_931, "a recent persisted observation keeps its existing age");
  } finally {
    replaceUsage([]);
  }
});

test("missing receipt or server time is stale and a future receipt cannot extend the freshness budget", () => {
  try {
    for (const serverNow of [undefined, null, NaN]) {
      replaceUsage([window()], serverNow, 10);
      assert.equal(usage.value[0].staleAt, 10);
    }
    replaceUsage([window({ receivedAt: undefined })], 100, 10);
    assert.equal(usage.value[0].staleAt, 10);
    replaceUsage([window({ observedAt: 999_999 })], 100, 20);
    assert.equal(usage.value[0].staleAt, 600_021);
  } finally {
    replaceUsage([]);
  }
});

test("receipt age ignores future ordering revisions and a fresh sibling keeps its provider fresh", () => {
  try {
    replaceUsage([
      window({ observedAt: 86_400_000, receivedAt: 100 }),
      window({ windowKey: "five_hour", observedAt: 700_100, receivedAt: 700_100 }),
    ], 700_100, 1000);
    assert.ok(usage.value[0].staleAt < 1000, "a future logical revision cannot make its old receipt fresh");
    assert.equal(providerUsageStaleAt(usage.value), 601_001, "the fresh sibling has the lower ordering revision");
    mergeUsageWindow(window({ observedAt: 86_400_001, receivedAt: 800_100 }), 800_100, 101_000);
    assert.equal(providerUsageStaleAt(usage.value), 701_001);
    assert.equal(usage.value[0].receivedAt, 800_100);
  } finally {
    replaceUsage([]);
  }
});
