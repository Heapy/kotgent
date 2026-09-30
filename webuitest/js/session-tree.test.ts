// Parent/child sessions in the sidebar: placement from webui/src/lib/tree.ts, ADHD membership through the
// parent from webui/src/lib/adhd.ts, and the device-local collapse state in webui/src/state/tree.ts.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { isSessionInAdhd, adhdCoverOf, listedInAdhd } from "../../webui/src/lib/adhd.ts";
import { groupSessions, treeCwd } from "../../webui/src/lib/paths.ts";
import type { SessionGroup } from "../../webui/src/lib/paths.ts";
import type { Preferences } from "../../webui/src/lib/prefs.ts";
import { liveParentOf, sessionIndex } from "../../webui/src/lib/sessions.ts";
import type { Session } from "../../webui/src/lib/sessions.ts";
import {
  MAX_EXPANDED_TREES,
  ORPHAN_LABEL,
  attentionRows,
  detachedParentLabel,
  sanitizeExpandedIds,
  sessionForest,
  toggleExpandedId,
  workersLabel,
} from "../../webui/src/lib/tree.ts";
import type { SessionNode } from "../../webui/src/lib/tree.ts";
import { sessionRow } from "./fixtures.ts";

const prefsFor = (basePath: string, groupingLevel: number, adhdPaths: string[] = []): Preferences => ({
  basePath,
  groupingLevel,
  adhdPaths,
  revision: 0,
  terminalFontSize: 13,
  terminalUnicode: "default",
});

const row = (id: string, overrides?: Partial<Session>) =>
  sessionRow({ id: id, name: id, tmuxSession: "kt-" + id, ...overrides });

function outline(nodes: readonly SessionNode[], depth = 0): string[] {
  return nodes.flatMap((node) => [
    "  ".repeat(depth) + node.session.id + (node.attention > 0 ? " !" + node.attention : ""),
    ...outline(node.children, depth + 1),
  ]);
}

function groupOutline(groups: readonly SessionGroup<SessionNode>[], depth = 0): string[] {
  return groups.flatMap((group) => [
    "  ".repeat(depth) + "[" + group.path + "] (" + group.sessionCount + ")",
    ...outline(group.sessions, depth + 1),
    ...groupOutline(group.children, depth + 1),
  ]);
}

const listedIds = (
  live: readonly Session[],
  prefs: Preferences,
  activeId: string | null = null,
  ready = true,
) => listedInAdhd(live, sessionIndex(live), prefs, activeId, ready).map((s) => s.id);

describe("the session forest", () => {
  test("nests children under their parent in list order, depth first", () => {
    const rows = [
      row("orch"),
      row("w1", { parentSessionId: "orch" }),
      row("solo"),
      row("w2", { parentSessionId: "orch" }),
      row("inv", { parentSessionId: "w1" }),
    ];

    assert.deepEqual(outline(sessionForest(rows, sessionIndex(rows))), [
      "orch",
      "  w1",
      "    inv",
      "  w2",
      "solo",
    ]);
  });

  test("keeps children under their parent regardless of folder grouping", () => {
    const rows = [
      row("orch", { cwd: "/work/api" }),
      row("w1", { cwd: "/tmp/worktrees/w1", parentSessionId: "orch" }),
      row("inv", { cwd: "/elsewhere/deep/x", parentSessionId: "w1" }),
      row("web", { cwd: "/work/web" }),
    ];
    const forest = sessionForest(rows, sessionIndex(rows));

    assert.deepEqual(groupOutline(groupSessions(forest, "/work", 1)), [
      "[/work/api] (1)",
      "  orch",
      "    w1",
      "      inv",
      "[/work/web] (1)",
      "  web",
    ]);
  });

  test("places a child by its parent's folder, through every ancestor", () => {
    const rows = [
      row("orch", { cwd: "/work/api" }),
      row("w1", { cwd: "/tmp/w1", parentSessionId: "orch" }),
      row("inv", { cwd: "/tmp/inv", parentSessionId: "w1" }),
    ];
    const index = sessionIndex(rows);

    assert.equal(treeCwd(rows[2]!, index), "/work/api");
    assert.equal(treeCwd(rows[0]!, index), "/work/api");
    for (const node of sessionForest(rows, index)) assert.equal(node.cwd, "/work/api");
  });

  test("an unknown parent leaves the child in its own folder", () => {
    const child = row("w1", { cwd: "/tmp/w1", parentSessionId: "gone" });

    assert.equal(treeCwd(child, sessionIndex([child])), "/tmp/w1");
  });

  test("a parent missing from the drawn rows leaves the child at the top level", () => {
    const orch = row("orch", { cwd: "/work/api" });
    const child = row("w1", { cwd: "/tmp/w1", parentSessionId: "orch" });
    const forest = sessionForest([child], sessionIndex([orch, child]));

    assert.deepEqual(outline(forest), ["w1"]);
    assert.equal(forest[0]!.cwd, "/work/api", "a detached child still sits in its parent's folder");
  });

  test("a parent cycle cannot make rows disappear", () => {
    const rows = [row("a", { parentSessionId: "b" }), row("b", { parentSessionId: "a" }), row("c", { parentSessionId: "c" })];
    const forest = sessionForest(rows, sessionIndex(rows));

    assert.deepEqual(outline(forest).map((line) => line.trim()).sort(), ["a", "b", "c"]);
    assert.equal(typeof treeCwd(rows[0]!, sessionIndex(rows)), "string");
  });

  test("aggregates attention over every descendant but not the node itself", () => {
    const rows = [
      row("orch", { state: "needs_answer" }),
      row("w1", { parentSessionId: "orch", state: "needs_approval" }),
      row("w2", { parentSessionId: "orch", state: "running" }),
      row("inv", { parentSessionId: "w2", state: "needs_answer" }),
    ];

    assert.deepEqual(outline(sessionForest(rows, sessionIndex(rows))), [
      "orch !2",
      "  w1",
      "  w2 !1",
      "    inv",
    ]);
  });
});

describe("the attention section", () => {
  test("lists only top-level rows, so a nested child shows through its parent's badge", () => {
    const rows = [
      row("orch"),
      row("w1", { parentSessionId: "orch", state: "needs_approval" }),
      row("solo", { state: "needs_answer" }),
    ];
    const forest = sessionForest(rows, sessionIndex(rows));

    assert.deepEqual(attentionRows(forest).map((s) => s.id), ["solo"]);
    assert.equal(forest[0]!.attention, 1);
  });

  test("lists a child whose parent is not drawn", () => {
    const orch = row("orch", { archived: true });
    const child = row("w1", { parentSessionId: "orch", state: "needs_answer" });
    const live = [child];

    assert.deepEqual(attentionRows(sessionForest(live, sessionIndex([orch, child]))).map((s) => s.id), ["w1"]);
  });
});

describe("the label of a detached child", () => {
  test("names a live parent that is not drawn above it", () => {
    const orch = row("orch", { name: "planner" });
    const child = row("w1", { parentSessionId: "orch" });

    assert.equal(detachedParentLabel(child, sessionIndex([orch, child])), "under planner");
  });

  test("says the orchestrator finished when the parent is archived or gone", () => {
    const orch = row("orch", { archived: true });
    const child = row("w1", { parentSessionId: "orch" });

    assert.equal(detachedParentLabel(child, sessionIndex([orch, child])), ORPHAN_LABEL);
    assert.equal(detachedParentLabel(child, sessionIndex([child])), ORPHAN_LABEL);
    assert.equal(ORPHAN_LABEL, "orchestrator finished");
  });

  test("is absent for a session without a parent", () => {
    assert.equal(detachedParentLabel(row("solo"), sessionIndex([])), null);
  });
});

describe("the Done section", () => {
  test("keeps the tree among archived rows", () => {
    const rows = [
      row("orch", { archived: true }),
      row("w1", { parentSessionId: "orch", archived: true }),
      row("w2", { parentSessionId: "orch", archived: true }),
    ];

    assert.deepEqual(outline(sessionForest(rows, sessionIndex(rows))), ["orch", "  w1", "  w2"]);
  });

  test("draws an archived child of a live parent at the top level, naming the parent", () => {
    const orch = row("orch", { name: "planner" });
    const child = row("w1", { parentSessionId: "orch", archived: true, cwd: "/tmp/w1" });
    const all = sessionIndex([orch, child]);
    const forest = sessionForest([child], all);

    assert.deepEqual(outline(forest), ["w1"]);
    assert.equal(detachedParentLabel(child, all), "under planner");
  });
});

describe("ADHD membership of a child", () => {
  test("comes through its parent's folder, never its own", () => {
    const orch = row("orch", { cwd: "/work/api" });
    const child = row("w1", { cwd: "/tmp/w1", parentSessionId: "orch" });
    const index = sessionIndex([orch, child]);

    assert.equal(isSessionInAdhd(child, prefsFor("/work", 1, ["/work/api"]), index), true);
    assert.deepEqual(adhdCoverOf(child, prefsFor("/work", 1, ["/work/api"]), index), { parent: orch });
    assert.equal(isSessionInAdhd(child, prefsFor("/", 1, ["/tmp"]), index), false);
  });

  test("comes through a parent's own mark", () => {
    const orch = row("orch", { adhd: true });
    const child = row("w1", { parentSessionId: "orch" });

    assert.deepEqual(adhdCoverOf(child, prefsFor("", 1), sessionIndex([orch, child])), { parent: orch });
  });

  test("of an orphan comes through the finished parent's folder, where it is drawn", () => {
    const orch = row("orch", { cwd: "/work/api", archived: true });
    const child = row("w1", { cwd: "/tmp/w1", parentSessionId: "orch" });
    const index = sessionIndex([orch, child]);

    assert.equal(liveParentOf(child, index), null);
    assert.deepEqual(adhdCoverOf(child, prefsFor("/work", 1, ["/work/api"]), index), { folder: "/work/api" });
  });
});

describe("ADHD visibility", () => {
  const rows = [
    row("orch", { cwd: "/work/api" }),
    row("w1", { cwd: "/tmp/w1", parentSessionId: "orch" }),
    row("w2", { cwd: "/tmp/w2", parentSessionId: "orch" }),
    row("inv", { cwd: "/tmp/inv", parentSessionId: "w1" }),
    row("solo", { cwd: "/work/web" }),
  ];

  test("lists a child on its own flag", () => {
    const marked = rows.map((s) => (s.id === "w2" ? { ...s, adhd: true } : s));

    assert.deepEqual(listedIds(marked, prefsFor("/work", 1)), ["w2"]);
  });

  test("lists every descendant of a listed parent", () => {
    assert.deepEqual(listedIds(rows, prefsFor("/work", 1, ["/work/api"])), ["orch", "w1", "w2", "inv"]);
  });

  test("lists the selected session and, through it, its children", () => {
    assert.deepEqual(listedIds(rows, prefsFor("/work", 1), "w1"), ["w1", "inv"]);
    assert.deepEqual(listedIds(rows, prefsFor("/work", 1), "inv"), ["inv"]);
  });

  test("lists only the selection until preferences load", () => {
    const marked = rows.map((s) => (s.id === "solo" ? { ...s, adhd: true } : s));

    assert.deepEqual(listedIds(marked, prefsFor("/work", 1, ["/work/api"]), "w2", false), ["w2"]);
  });

  test("draws a listed child of a hidden parent at the top level with the parent's name", () => {
    const marked = rows.map((s) => (s.id === "w1" ? { ...s, adhd: true } : s));
    const index = sessionIndex(marked);
    const listed = listedInAdhd(marked, index, prefsFor("/work", 1), null, true);
    const forest = sessionForest(listed, index);

    assert.deepEqual(outline(forest), ["w1", "  inv"]);
    assert.equal(detachedParentLabel(forest[0]!.session, index), "under orch");
    assert.equal(forest[0]!.cwd, "/work/api");
  });

  test("an attention child of a hidden parent is its own top-level row", () => {
    const marked = rows.map((s) => (s.id === "w2" ? { ...s, adhd: true, state: "needs_answer" } : s));
    const index = sessionIndex(marked);
    const forest = sessionForest(listedInAdhd(marked, index, prefsFor("/work", 1), null, true), index);

    assert.deepEqual(attentionRows(forest).map((s) => s.id), ["w2"]);
  });
});

describe("the collapse state", () => {
  test("a toggle expands an id and a second toggle collapses it again", () => {
    const once = toggleExpandedId([], "orch");
    assert.deepEqual(once, ["orch"]);
    assert.deepEqual(toggleExpandedId(once, "orch"), []);
  });

  test("keeps only the most recently expanded ids", () => {
    let ids: readonly string[] = [];
    for (let i = 0; i < MAX_EXPANDED_TREES + 5; i += 1) ids = toggleExpandedId(ids, "s" + i);

    assert.equal(ids.length, MAX_EXPANDED_TREES);
    assert.equal(ids[0], "s5");
    assert.equal(ids[ids.length - 1], "s" + (MAX_EXPANDED_TREES + 4));
  });

  test("reads back only unique strings from storage", () => {
    assert.deepEqual(sanitizeExpandedIds(["a", 1, "b", "a", null]), ["a", "b"]);
    assert.deepEqual(sanitizeExpandedIds({ a: 1 }), []);
    assert.deepEqual(sanitizeExpandedIds(null), []);
  });

  test("the toggle names how many workers it holds", () => {
    assert.equal(workersLabel(1), "1 worker");
    assert.equal(workersLabel(3), "3 workers");
  });
});

class MemoryStorage {
  values = new Map<string, string>();
  failing = false;

  getItem(key: string) {
    if (this.failing) throw new Error("storage refused");
    return this.values.has(key) ? this.values.get(key)! : null;
  }

  setItem(key: string, value: string) {
    if (this.failing) throw new Error("storage refused");
    this.values.set(key, value);
  }
}

async function freshTreeState(storage: MemoryStorage, tag: string) {
  globalThis.window = { localStorage: storage } as unknown as typeof globalThis.window;
  return import("../../webui/src/state/tree.ts?" + tag) as Promise<typeof import("../../webui/src/state/tree.ts")>;
}

describe("state/tree.ts", () => {
  test("starts collapsed, remembers an expanded parent on this device and reads it back", async () => {
    const storage = new MemoryStorage();
    const first = await freshTreeState(storage, "first");

    assert.equal(first.expandedTrees.value.has("orch"), false);
    first.toggleTree("orch");
    assert.equal(first.expandedTrees.value.has("orch"), true);
    assert.deepEqual(JSON.parse(storage.getItem(first.EXPANDED_TREES_KEY)!), ["orch"]);

    const second = await freshTreeState(storage, "second");
    assert.equal(second.expandedTrees.value.has("orch"), true);
  });

  test("keeps working in memory when storage throws", async () => {
    const storage = new MemoryStorage();
    storage.failing = true;
    const state = await freshTreeState(storage, "failing");

    assert.equal(state.expandedTrees.value.size, 0);
    state.toggleTree("orch");
    assert.equal(state.expandedTrees.value.has("orch"), true);
  });

  test("tolerates a corrupt stored value", async () => {
    const storage = new MemoryStorage();
    storage.values.set("kotgent.expandedTrees.v1", "{not json");
    const state = await freshTreeState(storage, "corrupt");

    assert.equal(state.expandedTrees.value.size, 0);
  });
});
