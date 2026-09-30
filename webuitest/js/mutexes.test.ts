import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MUTEX_HELD_LONG_MS, applyMutexSnapshot, formatMutexElapsed, isHeldLong, mergeMutexUpdate, mutexElapsed,
  sessionMutexPills,
} from "../../webui/src/lib/mutexes.ts";
import type { MutexEntry, MutexListing } from "../../webui/src/lib/mutexes.ts";
import { mergeMutexes, mutexes, replaceMutexes } from "../../webui/src/state/mutexes.ts";
import { effect } from "./signals.ts";

const SERVER_NOW = 1_800_000_000_000;

function entry(key: string, holder: string | null, ...waiters: [string, boolean?][]): MutexEntry {
  return {
    key,
    holder: holder ? { sessionId: holder, acquiredAt: SERVER_NOW - 60_000 } : null,
    waiters: waiters.map(([sessionId, granted = false], index) => ({
      sessionId, since: SERVER_NOW - 10_000 + index, granted,
    })),
  };
}

function listing(rev: number, ...entries: MutexEntry[]): MutexListing {
  return { rev, mutexes: entries, serverNow: SERVER_NOW };
}

test("updates apply only with a newer revision; late and equal frames are dropped", () => {
  const base = applyMutexSnapshot(listing(5, entry("kotlin-build", "s-a")), 100)!;
  const newer = mergeMutexUpdate(base, listing(6), 200);
  assert.deepEqual(newer?.mutexes, []);
  assert.equal(newer?.receivedAt, 200);
  assert.equal(mergeMutexUpdate(newer, listing(6, entry("x", "s-b")), 300), newer);
  assert.equal(mergeMutexUpdate(newer, listing(4, entry("x", "s-b")), 300), newer);
  assert.equal(mergeMutexUpdate(newer, { rev: "7" }, 300), newer, "an unreadable frame changes nothing");
  assert.equal(mergeMutexUpdate(null, listing(1), 300)?.rev, 1, "an update before any snapshot is admitted");
});

test("a snapshot is authoritative even at an equal or lower revision and re-bases elapsed time", () => {
  const current = applyMutexSnapshot(listing(9, entry("a", "s-a")), 100)!;
  const same = applyMutexSnapshot({ ...listing(9, entry("a", "s-a")), serverNow: SERVER_NOW + 5_000 }, 7_000)!;
  assert.equal(mutexElapsed(current, SERVER_NOW - 60_000, 7_000), 60_000 + 6_900);
  assert.equal(mutexElapsed(same, SERVER_NOW - 60_000, 7_000), 65_000, "the daemon clock supplies the age");
  const lower = applyMutexSnapshot(listing(2), 8_000)!;
  assert.equal(lower.rev, 2);
  assert.equal(applyMutexSnapshot(null, 1), null);
  assert.equal(applyMutexSnapshot({ rev: 1, mutexes: "nope" }, 1), null);
});

test("elapsed time grows with the local monotonic clock and ignores the wall clock", () => {
  const received = applyMutexSnapshot(listing(1, entry("a", "s-a")), 1_000)!;
  const acquiredAt = SERVER_NOW - 60_000;
  assert.equal(mutexElapsed(received, acquiredAt, 1_000), 60_000);
  assert.equal(mutexElapsed(received, acquiredAt, 31_000), 90_000);
  assert.equal(mutexElapsed(received, SERVER_NOW + 500, 1_000), 0, "a stamp ahead of the daemon clamps to zero");
  assert.equal(mutexElapsed({ ...received, serverNow: NaN }, acquiredAt, 1_000), null);
});

test("holdings beyond fifteen minutes are long and durations read in the largest two units", () => {
  assert.equal(isHeldLong(MUTEX_HELD_LONG_MS), false);
  assert.equal(isHeldLong(MUTEX_HELD_LONG_MS + 1), true);
  assert.equal(isHeldLong(null), false);
  assert.equal(formatMutexElapsed(0), "0s");
  assert.equal(formatMutexElapsed(59_999), "59s");
  assert.equal(formatMutexElapsed(61_000), "1m 01s");
  assert.equal(formatMutexElapsed(3_600_000 + 5 * 60_000), "1h 05m");
  assert.equal(formatMutexElapsed(null), "unknown");
});

test("pills name what the open session holds and where it waits in each queue", () => {
  const received = applyMutexSnapshot(listing(
    3,
    entry("build", "s-a", ["s-b"], ["s-c"]),
    entry("deploy", "s-c", ["s-a", true], ["s-b"]),
    entry("idle", null),
  ), 0);
  assert.deepEqual(sessionMutexPills(received, "s-a"), [
    { key: "build", kind: "held", position: null },
    { key: "deploy", kind: "waiting", position: 1 },
  ]);
  assert.deepEqual(sessionMutexPills(received, "s-b"), [
    { key: "build", kind: "waiting", position: 1 },
    { key: "deploy", kind: "waiting", position: 2 },
  ]);
  assert.deepEqual(sessionMutexPills(received, "s-z"), []);
  assert.deepEqual(sessionMutexPills(null, "s-a"), []);
  assert.deepEqual(sessionMutexPills(received, null), []);
});

test("the state writers publish snapshots and only newer updates", () => {
  replaceMutexes(listing(1), 0);
  const observed: (number | undefined)[] = [];
  const dispose = effect(() => { observed.push(mutexes.value?.rev); });
  try {
    mergeMutexes(listing(3, entry("a", "s-a")), 10);
    mergeMutexes(listing(2), 20);
    mergeMutexes(listing(3), 30);
    replaceMutexes(listing(3, entry("a", "s-a")), 40);
    replaceMutexes({ rev: "broken" }, 50);
    assert.deepEqual(observed, [1, 3, 3]);
    assert.equal(mutexes.value?.receivedAt, 40);
  } finally {
    dispose();
  }
});
