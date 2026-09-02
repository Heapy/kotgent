// Revision merge rules from resources/webui/lib/sessions.js. These decide which observation a browser
// keeps when an HTTP response and a WebSocket frame describe the same session, so they are proven here
// rather than in the browser tier: nothing in them touches the DOM, the network, or a timer.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  byRecentChange,
  displayName,
  patchIfNewer,
  upsertIfNewer,
} from "../../resources/webui/lib/sessions.js";
import { listOf, patchFrame, sessionRow } from "./fixtures.js";

describe("upsertIfNewer", () => {
  test("a session the list has never seen is appended", () => {
    const empty = listOf();
    const row = sessionRow({});

    assert.deepEqual(upsertIfNewer(empty, row), [row]);
    assert.equal(empty.length, 0, "the caller's list must not be mutated");
  });

  test("a newer revision replaces the row without moving it", () => {
    const list = listOf(sessionRow({ id: "s0", rev: 1 }), sessionRow({ rev: 2 }), sessionRow({ id: "s2", rev: 1 }));
    const newer = sessionRow({ rev: 3, state: "stopped" });

    const merged = upsertIfNewer(list, newer);

    assert.deepEqual(merged.map((s) => s.id), ["s0", "s1", "s2"]);
    assert.strictEqual(merged[1], newer);
  });

  test("an equal revision is a no-op that returns the very same list", () => {
    const list = listOf(sessionRow({ rev: 2 }));

    // Identity, not deep equality: the app re-renders on a changed list, so a no-op must stay the same value.
    assert.strictEqual(upsertIfNewer(list, sessionRow({ rev: 2, state: "crashed" })), list);
  });

  test("an older revision is a no-op that returns the very same list", () => {
    const list = listOf(sessionRow({ rev: 5 }));

    assert.strictEqual(upsertIfNewer(list, sessionRow({ rev: 4, state: "crashed" })), list);
  });

  test("a row carrying no revision never displaces a stored row", () => {
    const list = listOf(sessionRow({ rev: 2 }));
    const unstamped = sessionRow({ rev: undefined, state: "crashed" });

    assert.strictEqual(upsertIfNewer(list, unstamped), list);
  });

  test("a first observation is appended even when it carries no revision", () => {
    // Documented behavior, not an oversight: a row with no counterpart has nothing to lose a comparison to.
    const unstamped = sessionRow({ rev: undefined });

    assert.deepEqual(upsertIfNewer(listOf(), unstamped), [unstamped]);
  });
});

describe("patchIfNewer", () => {
  test("a patch for a session the list does not hold is a no-op", () => {
    const list = listOf(sessionRow({}));

    assert.strictEqual(patchIfNewer(list, patchFrame({ sessionId: "missing", rev: 99 })), list);
  });

  test("a patch on an empty collection is a no-op", () => {
    const empty = listOf();

    assert.strictEqual(patchIfNewer(empty, patchFrame({ rev: 99 })), empty);
  });

  test("a newer patch stamps its own revision and recomputes liveness", () => {
    const list = listOf(sessionRow({ rev: 2, state: "running", alive: true }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4, state: "stopped" }));

    assert.equal(merged[0].rev, 4);
    assert.equal(merged[0].state, "stopped");
    assert.equal(merged[0].alive, false, "liveness is derived from the patched state, never carried over");
  });

  test("null clears a field authoritatively", () => {
    const list = listOf(sessionRow({ rev: 2, taskRef: "local:12", projectId: "p1" }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4, taskRef: null }));

    assert.equal(merged[0].taskRef, null);
  });

  test("fields the patch does not carry survive from the stored row", () => {
    const list = listOf(sessionRow({ rev: 2, name: "one", cwd: "/work/one" }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4 }));

    assert.equal(merged[0].name, "one");
    assert.equal(merged[0].cwd, "/work/one");
  });

  test("a patch carrying a new name renames the row", () => {
    const list = listOf(sessionRow({ rev: 2, name: "one" }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4, name: "two" }));

    assert.equal(merged[0].name, "two");
  });

  test("a patch from a daemon that omits name keeps the previous name", () => {
    const list = listOf(sessionRow({ rev: 2, name: "one" }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4, name: undefined }));

    assert.equal(merged[0].name, "one");
  });

  test("a patch carrying an empty name clears it back to the automatic label", () => {
    const list = listOf(sessionRow({ rev: 2, name: "one", tmuxSession: "kotgent-one" }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4, name: "" }));

    assert.equal(merged[0].name, "");
    assert.equal(displayName(merged[0]), "kotgent-one");
  });

  test("a stale patch is ignored, the name it carries included", () => {
    const list = listOf(sessionRow({ rev: 4, name: "one" }));

    assert.strictEqual(patchIfNewer(list, patchFrame({ rev: 2, name: "two" })), list);
    assert.equal(list[0].name, "one");
  });

  test("a patch from a daemon that omits updatedAt keeps the snapshot's stamp", () => {
    const list = listOf(sessionRow({ rev: 2, updatedAt: 100 }));

    const merged = patchIfNewer(list, patchFrame({ rev: 4, updatedAt: undefined }));

    assert.equal(merged[0].updatedAt, 100);
  });

  test("an equal revision is a no-op that returns the very same list", () => {
    const list = listOf(sessionRow({ rev: 3 }));

    assert.strictEqual(patchIfNewer(list, patchFrame({ rev: 3, state: "crashed" })), list);
  });

  test("an older revision is a no-op that returns the very same list", () => {
    const list = listOf(sessionRow({ rev: 5 }));

    assert.strictEqual(patchIfNewer(list, patchFrame({ rev: 4, state: "crashed" })), list);
  });

  test("a patch carrying no revision is a no-op", () => {
    const list = listOf(sessionRow({ rev: 2 }));

    assert.strictEqual(patchIfNewer(list, patchFrame({ rev: undefined, state: "crashed" })), list);
  });
});

describe("out-of-order arrival", () => {
  // Arrival timing is not an ordering guarantee: the daemon's revision is. Every delivery order of the
  // same frames must therefore land on the highest-revision snapshot.
  const FIRST = sessionRow({ rev: 2, state: "ready", unread: 0, updatedAt: 200 });
  const NEWEST = sessionRow({
    rev: 5,
    state: "resumable",
    alive: false,
    needsAttention: false,
    lastSeq: 31,
    unread: 4,
    taskRef: "local:12",
    updatedAt: 500,
  });
  const FRAMES = [
    { kind: "upsert", frame: FIRST },
    { kind: "patch", frame: patchFrame({ rev: 3, state: "needs_approval", needsAttention: true, updatedAt: 300 }) },
    { kind: "patch", frame: patchFrame({ rev: 4, state: "stopped", updatedAt: 400 }) },
    { kind: "upsert", frame: NEWEST },
  ];

  function permutations(items) {
    if (items.length <= 1) return [items.slice()];
    const out = [];
    for (let index = 0; index < items.length; index += 1) {
      const rest = items.slice(0, index).concat(items.slice(index + 1));
      for (const tail of permutations(rest)) out.push([items[index], ...tail]);
    }
    return out;
  }

  function applyFrame(list, entry) {
    return entry.kind === "upsert" ? upsertIfNewer(list, entry.frame) : patchIfNewer(list, entry.frame);
  }

  test("every delivery order of the same frames converges on the newest revision", () => {
    assert.notDeepEqual(NEWEST, FIRST, "the frames must differ, or convergence would be vacuous");

    const orders = permutations(FRAMES);
    assert.equal(orders.length, 24, "all 4! orders must be exercised, or the rule below is partly untested");

    for (const order of orders) {
      const settled = order.reduce(applyFrame, listOf());
      assert.deepEqual(
        settled,
        [NEWEST],
        `arrival order ${order.map((entry) => `${entry.kind}@${entry.frame.rev}`).join(" -> ")} did not converge`,
      );
    }
  });

  // The case above settles on a full row, so it holds however the patches merge — none of their field
  // values reaches the result. Here the newest frame is a patch, so convergence is a claim about what a
  // patch merge produces. The upsert is pinned first rather than permuted because a patch for a row the
  // list has never seen is dropped by design (the case above it), which is a different rule.
  const LAST_PATCH = patchFrame({
    rev: 6,
    state: "needs_approval",
    needsAttention: true,
    lastSeq: 44,
    unread: 7,
    taskRef: "local:12",
    updatedAt: 600,
  });

  test("with the newest frame a patch, every arrival order still lands on that patch's fields", () => {
    const tail = FRAMES.slice(1).concat([{ kind: "patch", frame: LAST_PATCH }]);
    const orders = permutations(tail);
    assert.equal(orders.length, 24, "all 4! orders of the frames that follow the snapshot");

    for (const order of orders) {
      const settled = [FRAMES[0], ...order].reduce(applyFrame, listOf());
      assert.equal(settled.length, 1);
      const row = settled[0];
      assert.equal(row.rev, 6, `order ${order.map((e) => e.frame.rev).join(" -> ")} did not converge`);
      assert.equal(row.state, "needs_approval");
      assert.equal(row.needsAttention, true);
      assert.equal(row.alive, true, "aliveness is derived from the patched state, not carried over");
      assert.equal(row.lastSeq, 44);
      assert.equal(row.unread, 7);
      assert.equal(row.taskRef, "local:12");
      assert.equal(row.updatedAt, 600);
      // A patch carries no identity fields, so the snapshot's are the only ones the row can have.
      assert.equal(row.cwd, FIRST.cwd);
      assert.equal(row.agent, FIRST.agent);
      assert.equal(row.name, FIRST.name);
    }
  });
});

// The done list sorts by `updatedAt`, and a rename is metadata rather than session activity: the daemon
// leaves `updated_at` alone, so the frame it emits must not reorder the list either.
describe("a rename and the done-list ordering", () => {
  test("a renamed done session keeps its place in the list", () => {
    const list = listOf(
      sessionRow({ id: "older", rev: 2, name: "older", archived: true, updatedAt: 100 }),
      sessionRow({ id: "newer", rev: 2, name: "newer", archived: true, updatedAt: 200 }),
    );

    const merged = patchIfNewer(
      list,
      patchFrame({ sessionId: "older", rev: 4, archived: true, name: "renamed", updatedAt: 100 }),
    );
    const ordered = byRecentChange(merged);

    assert.deepEqual(ordered.map((s) => s.id), ["newer", "older"]);
    assert.equal(ordered[1].name, "renamed", "the row moved nowhere, but it did take the new name");
  });
});
