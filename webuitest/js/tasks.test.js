// Pure rules from resources/webui/lib/tasks.js: the board's revision merges and its state vocabulary.
// The module imports apiRequest, but only calls it inside request functions, so importing it here never
// reaches window or fetch. Nothing exercised below performs I/O.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  TASK_STATES,
  applyTasksSnapshot,
  compareTasksByBoardOrder,
  isOpenTaskState,
  patchTaskIfNewer,
  removeTask,
  taskStateLabel,
  taskStateRank,
  upsertTaskIfNewer,
} from "../../resources/webui/lib/tasks.js";

// Frozen inputs turn an accidental in-place write into a TypeError; ES modules are always strict mode.
function taskRow(overrides) {
  return Object.freeze({
    ref: "kotgent#12",
    project: "p1",
    title: "wire the board",
    state: "todo",
    position: 100,
    createdAt: 10,
    rev: 2,
    ...overrides,
  });
}

function listOf(...rows) {
  return Object.freeze(rows);
}

describe("upsertTaskIfNewer", () => {
  test("a task the list has never seen is appended", () => {
    const empty = listOf();
    const row = taskRow({});

    assert.deepEqual(upsertTaskIfNewer(empty, row), [row]);
    assert.equal(empty.length, 0, "the caller's list must not be mutated");
  });

  test("a newer revision replaces the row without moving it", () => {
    const list = listOf(taskRow({ ref: "a", rev: 1 }), taskRow({ rev: 2 }), taskRow({ ref: "c", rev: 1 }));
    const newer = taskRow({ rev: 3, state: "review" });

    const merged = upsertTaskIfNewer(list, newer);

    assert.deepEqual(merged.map((t) => t.ref), ["a", "kotgent#12", "c"]);
    assert.strictEqual(merged[1], newer);
  });

  test("an equal revision is a no-op that returns the very same list", () => {
    const list = listOf(taskRow({ rev: 2 }));

    // Identity, not deep equality: the board re-renders on a changed list, so a no-op must stay the same value.
    assert.strictEqual(upsertTaskIfNewer(list, taskRow({ rev: 2, state: "done" })), list);
  });

  test("an older revision is a no-op that returns the very same list", () => {
    const list = listOf(taskRow({ rev: 5 }));

    assert.strictEqual(upsertTaskIfNewer(list, taskRow({ rev: 4, state: "done" })), list);
  });

  test("a row carrying no revision never displaces a stored row", () => {
    const list = listOf(taskRow({ rev: 2 }));

    assert.strictEqual(upsertTaskIfNewer(list, taskRow({ rev: undefined, state: "done" })), list);
  });
});

describe("patchTaskIfNewer", () => {
  test("a patch for an unknown ref is a no-op", () => {
    const list = listOf(taskRow({}));

    assert.strictEqual(patchTaskIfNewer(list, { ref: "kotgent#99", rev: 99, state: "done" }), list);
  });

  test("a patch on an empty collection is a no-op", () => {
    const empty = listOf();

    assert.strictEqual(patchTaskIfNewer(empty, { ref: "kotgent#12", rev: 99, state: "done" }), empty);
  });

  test("a newer patch merges its fields and keeps its own revision", () => {
    const list = listOf(taskRow({ rev: 2, state: "todo", position: 100 }));

    const merged = patchTaskIfNewer(list, { ref: "kotgent#12", rev: 4, state: "in_progress", position: 250 });

    assert.equal(merged[0].state, "in_progress");
    assert.equal(merged[0].position, 250);
    // A stale full row must lose the next comparison, which it only does if the patch's revision survives.
    assert.equal(merged[0].rev, 4);
  });

  test("fields the patch does not carry survive from the stored row", () => {
    const list = listOf(taskRow({ rev: 2, title: "wire the board", createdAt: 10 }));

    const merged = patchTaskIfNewer(list, { ref: "kotgent#12", rev: 4, state: "review" });

    assert.equal(merged[0].title, "wire the board");
    assert.equal(merged[0].createdAt, 10);
  });

  test("an equal revision is a no-op that returns the very same list", () => {
    const list = listOf(taskRow({ rev: 3 }));

    assert.strictEqual(patchTaskIfNewer(list, { ref: "kotgent#12", rev: 3, state: "done" }), list);
  });

  test("an older revision is a no-op that returns the very same list", () => {
    const list = listOf(taskRow({ rev: 5 }));

    assert.strictEqual(patchTaskIfNewer(list, { ref: "kotgent#12", rev: 4, state: "done" }), list);
  });

  test("a patch carrying no revision is a no-op", () => {
    const list = listOf(taskRow({ rev: 2 }));

    assert.strictEqual(patchTaskIfNewer(list, { ref: "kotgent#12", state: "done" }), list);
  });
});

describe("removeTask", () => {
  test("the named task goes and the rest keep their order", () => {
    const list = listOf(taskRow({ ref: "a" }), taskRow({ ref: "b" }), taskRow({ ref: "c" }));

    assert.deepEqual(removeTask(list, "b").map((t) => t.ref), ["a", "c"]);
    assert.deepEqual(list.map((t) => t.ref), ["a", "b", "c"], "the caller's list must not be mutated");
  });

  test("removal carries no revision and outranks the stored one", () => {
    // The frame is authoritative: a high stored revision is not a reason to keep a deleted row.
    const list = listOf(taskRow({ ref: "a", rev: 99 }));

    assert.deepEqual(removeTask(list, "a"), []);
  });

  test("an unknown ref is a no-op that returns the very same list", () => {
    const list = listOf(taskRow({ ref: "a" }));

    assert.strictEqual(removeTask(list, "kotgent#404"), list);
  });

  test("an empty collection is a no-op that returns the very same list", () => {
    const empty = listOf();

    assert.strictEqual(removeTask(empty, "a"), empty);
  });
});

describe("applyTasksSnapshot", () => {
  test("a reconnect snapshot replaces the list so a deleted row cannot reappear", () => {
    const stale = listOf(taskRow({ ref: "deleted-during-the-outage", rev: 99 }));
    const rows = [taskRow({ ref: "a" })];

    assert.deepEqual(applyTasksSnapshot(stale, rows).map((t) => t.ref), ["a"]);
  });

  test("an absent snapshot empties the list", () => {
    const stale = listOf(taskRow({ ref: "a" }));

    assert.deepEqual(applyTasksSnapshot(stale, null), []);
    assert.deepEqual(applyTasksSnapshot(stale, undefined), []);
  });

  test("the snapshot is copied, so the caller's array cannot alias board state", () => {
    const rows = [taskRow({ ref: "a" })];

    assert.notStrictEqual(applyTasksSnapshot(listOf(), rows), rows);
  });
});

describe("open-state classification", () => {
  test("every board state except done is open", () => {
    const open = TASK_STATES.filter(isOpenTaskState);

    assert.deepEqual(open, ["todo", "in_progress", "review"]);
    assert.equal(isOpenTaskState("done"), false);
  });

  test("a state the board does not know is not open", () => {
    // A newer daemon's state must not be counted as open work by an older page.
    assert.equal(isOpenTaskState("archived"), false);
    assert.equal(isOpenTaskState(""), false);
    assert.equal(isOpenTaskState(undefined), false);
  });

  test("ranks follow board order and an unknown state sorts last", () => {
    const ranks = TASK_STATES.map(taskStateRank);

    assert.deepEqual(ranks, [0, 1, 2, 3]);
    assert.equal(taskStateRank("archived"), Number.MAX_SAFE_INTEGER);
    assert.equal(taskStateRank(undefined), Number.MAX_SAFE_INTEGER);
  });

  test("an unlabelled state falls back to the raw value, then to unknown", () => {
    assert.equal(taskStateLabel("in_progress"), "In progress");
    assert.equal(taskStateLabel("archived"), "archived");
    assert.equal(taskStateLabel(undefined), "unknown");
  });
});

// The board's ordering rule. It is a pure comparator the tier already imports, and until now the only
// thing proving it was a five-row assertion in the browser tier — one level above where it can be
// answered. Zeroing the createdAt tiebreak passed the whole node tier before these cases existed.
describe("compareTasksByBoardOrder", () => {
  const sorted = (...rows) => rows.slice().sort(compareTasksByBoardOrder).map((row) => row.ref);

  test("position comes first", () => {
    const low = taskRow({ ref: "a#1", position: 10, createdAt: 99 });
    const high = taskRow({ ref: "a#2", position: 20, createdAt: 1 });

    assert.equal(compareTasksByBoardOrder(low, high), -1);
    assert.equal(compareTasksByBoardOrder(high, low), 1);
    assert.deepEqual(sorted(high, low), ["a#1", "a#2"]);
  });

  test("equal positions fall back to creation order", () => {
    const older = taskRow({ ref: "b#2", position: 10, createdAt: 1 });
    const newer = taskRow({ ref: "b#1", position: 10, createdAt: 2 });

    assert.equal(compareTasksByBoardOrder(older, newer), -1);
    assert.deepEqual(
      sorted(newer, older),
      ["b#2", "b#1"],
      "the older row first, which the ref fallback alone would have reversed",
    );
  });

  test("rows agreeing on both fall back to the ref, so the order is total", () => {
    const first = taskRow({ ref: "c#1", position: 10, createdAt: 5 });
    const second = taskRow({ ref: "c#2", position: 10, createdAt: 5 });

    assert.equal(compareTasksByBoardOrder(first, second), -1);
    assert.equal(compareTasksByBoardOrder(first, first), 0);
    assert.deepEqual(sorted(second, first), ["c#1", "c#2"]);
  });

  test("a row missing a field sorts after one that has it, and never throws", () => {
    const placed = taskRow({ ref: "d#1", position: 10 });
    const unplaced = taskRow({ ref: "d#0", position: null });
    const created = taskRow({ ref: "e#1", position: null, createdAt: 3 });
    const uncreated = taskRow({ ref: "e#0", position: null, createdAt: undefined });

    assert.deepEqual(sorted(unplaced, placed), ["d#1", "d#0"]);
    assert.deepEqual(sorted(uncreated, created), ["e#1", "e#0"]);
    assert.equal(compareTasksByBoardOrder(null, null), 0);
    assert.equal(compareTasksByBoardOrder(placed, null), -1);
  });

  test("a non-finite position is not a position", () => {
    const numbered = taskRow({ ref: "f#1", position: 10 });
    const nan = taskRow({ ref: "f#0", position: Number.NaN });

    assert.deepEqual(sorted(nan, numbered), ["f#1", "f#0"]);
  });
});
