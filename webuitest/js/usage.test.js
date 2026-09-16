import { test } from "node:test";
import assert from "node:assert/strict";
import {
  applyUsageSnapshot, atUsageReceipt, providerUsageStaleAt, upsertUsageIfNewer,
  usageTimeLeft, usageWindowSeconds, usageWindowTime,
} from "../../resources/webui/lib/usage.js";
import { mergeUsageWindow, replaceUsage, usage, usageClockOffset } from "../../resources/webui/state/usage.js";
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
      replaceUsage([window()], 100, 10);
      replaceUsage([window()], serverNow, 10);
      assert.equal(usage.value[0].staleAt, 10);
      assert.equal(usageWindowTime(usage.value[0], 10, usageClockOffset.value).now, null,
        "an authoritative snapshot with unknown time clears the previous clock");
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

test("the time marker uses the daemon clock and advances independently of quota and phone time", (t) => {
  const serverNow = 1_800_000_000_000;
  t.mock.method(Date, "now", () => serverNow + 12 * 60 * 60 * 1000);
  const row = atUsageReceipt(window({
    windowKey: "five_hour", windowSeconds: 18000, resetsAt: serverNow + 2 * 60 * 60 * 1000,
    observedAt: serverNow + 86_400_000, receivedAt: serverNow - 300_000,
  }), serverNow, 1000);
  const offset = serverNow - 1000;
  const initial = usageWindowTime(row, 1000, offset);
  assert.equal(initial.now, serverNow);
  assert.equal(initial.elapsedPercent, 60);
  assert.equal(initial.remainingMs, 7_200_000);
  assert.equal(initial.nextUpdateAt, 61_000);

  const later = usageWindowTime(row, 1_801_000, offset);
  assert.equal(later.now, serverNow + 1_800_000);
  assert.equal(later.elapsedPercent, 70);
  assert.equal(later.remainingMs, 5_400_000);
  assert.equal(row.usedPercent, 40);
});

test("a marker stays at the edges before the window and after its deadline without resetting usage", () => {
  const row = window({ windowSeconds: 60, resetsAt: 100_500 });
  const at = (now) => usageWindowTime(row, now, 99_000);
  assert.equal(at(-60_000).elapsedPercent, 0);
  assert.equal(at(1000).nextUpdateAt, 1500, "wake at a reset between minute ticks");
  assert.equal(at(1500).elapsedPercent, 100);
  assert.equal(at(1501).remainingMs, -1);
  assert.equal(at(1501).elapsedPercent, 100);
  assert.equal(at(1501).nextUpdateAt, 21_000, "an expired reset cannot cause a timer loop");
  assert.equal(row.usedPercent, 40);
});

test("native durations take precedence and only known Claude windows have a duration fallback", () => {
  assert.equal(usageWindowSeconds(window({ windowKey: "five_hour", windowSeconds: null })), 18000);
  assert.equal(usageWindowSeconds(window({ windowSeconds: undefined })), 604800);
  assert.equal(usageWindowSeconds(window({ provider: "codex", windowKey: "primary", windowSeconds: 604800 })), 604800);
  assert.equal(usageWindowSeconds(window({ windowKey: "five_hour", windowSeconds: 3600 })), 3600);
  for (const windowSeconds of [0, -1, NaN, Infinity, "18000"]) {
    assert.equal(usageWindowSeconds(window({ windowSeconds })), null);
  }
  assert.equal(usageWindowSeconds(window({ provider: "codex", windowSeconds: null })), null);
  assert.equal(usageWindowSeconds(window({ windowKey: "other", windowSeconds: null })), null);
});

test("missing reset, duration or server time never fabricates a marker", () => {
  const timed = (overrides) => usageWindowTime(window(overrides), 20, 90);
  for (const resetsAt of [null, undefined, NaN, Infinity]) {
    assert.equal(timed({ resetsAt }).elapsedPercent, null);
    assert.equal(timed({ resetsAt }).remainingMs, null);
  }
  const withoutDuration = timed({ provider: "codex", windowSeconds: null, resetsAt: 200 });
  assert.equal(withoutDuration.elapsedPercent, null);
  assert.equal(withoutDuration.remainingMs, 90, "a known reset still supports a countdown");
  for (const offset of [undefined, null, NaN, Infinity]) {
    const time = usageWindowTime(window(), 20, offset);
    assert.equal(time.now, null);
    assert.equal(time.elapsedPercent, null);
    assert.equal(time.remainingMs, null);
    assert.equal(time.nextUpdateAt, Infinity);
  }
});

test("replayed frames cannot move the clock anchor, while a snapshot reanchors every window", () => {
  try {
    replaceUsage([window({ resetsAt: 10_000 })], 1000, 100);
    mergeUsageWindow(window({ resetsAt: 20_000 }), 9000, 200);
    mergeUsageWindow(window({ observedAt: 99, resetsAt: 30_000 }), 9500, 200);
    assert.equal(usageWindowTime(usage.value[0], 200, usageClockOffset.value).now, 1100);
    assert.equal(usageWindowTime(usage.value[0], 200, usageClockOffset.value).remainingMs, 8900);
    replaceUsage([window({ observedAt: 50, resetsAt: 10_000 })], 5000, 200);
    assert.equal(usageWindowTime(usage.value[0], 200, usageClockOffset.value).remainingMs, 5000);
  } finally {
    replaceUsage([]);
  }
});

for (const correction of [-3_600_000, 3_600_000]) {
  test(`a ${correction < 0 ? "backward" : "forward"} server clock correction reaches silent windows without refreshing their data`, () => {
    const serverNow = 1_800_000_000_000;
    const resetsAt = serverNow + 7_200_000;
    const claude = window({
      windowKey: "five_hour", windowSeconds: 18000, usedPercent: 36, resetsAt,
      observedAt: serverNow, receivedAt: serverNow, changedAt: serverNow - 60_000,
    });
    const codex = window({ ...claude, provider: "codex", windowKey: "primary", usedPercent: 58 });
    try {
      replaceUsage([claude, codex], serverNow, 1000);
      const silent = usage.value[1];
      const correctedNow = serverNow + 60_000 + correction;
      mergeUsageWindow({
        ...claude, observedAt: Math.max(serverNow + 1, correctedNow), receivedAt: correctedNow,
      }, correctedNow, 61_000);

      for (const row of usage.value) {
        const time = usageWindowTime(row, 61_000, usageClockOffset.value);
        assert.equal(time.now, correctedNow, `${row.provider} must use the latest admitted server clock`);
        assert.equal(time.remainingMs, resetsAt - correctedNow);
        assert.equal(time.elapsedPercent, 100 - (resetsAt - correctedNow) / 18_000_000 * 100);
      }
      assert.equal(usage.value[0].usedPercent, 36, "a clock correction cannot change usage");
      assert.equal(usage.value[1], silent, "the silent window keeps its quota, revision, receipt and freshness deadline");
    } finally {
      replaceUsage([]);
    }
  });
}

test("accepted quota and clock changes reach observers as one consistent state", () => {
  replaceUsage([window()], 1000, 100);
  const observed = [];
  const dispose = effect(() => {
    const row = usage.value[0];
    observed.push({
      usedPercent: row.usedPercent,
      now: usageWindowTime(row, 100, usageClockOffset.value).now,
    });
  });
  try {
    mergeUsageWindow(window({ observedAt: 101, usedPercent: 50 }), 5000, 100);
    mergeUsageWindow(window({ observedAt: 101, usedPercent: 99 }), 9000, 100);
    assert.deepEqual(observed, [
      { usedPercent: 40, now: 1000 },
      { usedPercent: 50, now: 5000 },
    ]);
  } finally {
    dispose();
    replaceUsage([]);
  }
});

test("countdowns distinguish unknown and elapsed resets and round the remaining minute upward", () => {
  assert.equal(usageTimeLeft(null), "unknown");
  assert.equal(usageTimeLeft(0), "Waiting for update");
  assert.equal(usageTimeLeft(-1), "Waiting for update");
  assert.equal(usageTimeLeft(1), "1m");
  assert.equal(usageTimeLeft(60_001), "2m");
  assert.equal(usageTimeLeft(3_600_000), "1h 0m");
  assert.equal(usageTimeLeft(6_840_000), "1h 54m");
  assert.equal(usageTimeLeft(345_600_000), "4d 0h");
});
