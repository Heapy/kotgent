// The per-session workspace layout from webui/src/lib/workspace.ts and its owner webui/src/state/layout.ts.
// The model is pure, so tab and column rules are proven here; moving the xterm between slots is a browser test.

import { beforeEach, describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  AVAILABLE_COLUMN_TYPES,
  MAX_WORKSPACES,
  activeTabOf,
  addColumn,
  addTab,
  capWorkspaces,
  closeColumn,
  closeTab,
  defaultWorkspace,
  focusColumn,
  moveDivider,
  normalizeFractions,
  parseWorkspaces,
  pruneWorkspaces,
  selectTab,
  serializeWorkspaces,
  setColumnType,
  tabLabel,
  visibleColumns,
} from "../../webui/src/lib/workspace.ts";
import type { ColumnType, SessionWorkspace, Tab } from "../../webui/src/lib/workspace.ts";

const STORAGE_KEY = "kotgent.workspaces.v1";

class MemoryStorage {
  readonly items = new Map<string, string>();
  failing = false;
  getItem(key: string) {
    if (this.failing) throw new Error("storage is unavailable");
    return this.items.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    if (this.failing) throw new Error("storage is unavailable");
    this.items.set(key, value);
  }
  removeItem(key: string) {
    this.items.delete(key);
  }
}

const storage = new MemoryStorage();
storage.items.set(STORAGE_KEY, JSON.stringify({
  seeded: {
    tabs: [{ id: "t1", columns: [{ type: "task" }], fractions: [1], focus: 0 }],
    activeTab: "t1",
    touchedAt: 5,
  },
}));
// The state module reads storage when it loads, so the fake must exist before the import.
Object.assign(globalThis, { window: { localStorage: storage } });
const layout = await import("../../webui/src/state/layout.ts");

const ALL_TYPES: readonly ColumnType[] = ["terminal", "task", "plan", "files", "diff"];

function types(tab: Tab) {
  return tab.columns.map((column) => column.type);
}

function only(ws: SessionWorkspace) {
  return activeTabOf(ws);
}

function sum(values: readonly number[]) {
  return values.reduce((total, value) => total + value, 0);
}

function close(actual: readonly number[], expected: readonly number[]) {
  assert.equal(actual.length, expected.length, "fraction count");
  actual.forEach((value, i) => assert.ok(Math.abs(value - expected[i]!) < 1e-9, actual.join(", ")));
}

function twoColumns(now = 1) {
  return addColumn(defaultWorkspace(now), "t1", 0, AVAILABLE_COLUMN_TYPES, now);
}

describe("the default layout", () => {
  test("is one tab holding the terminal alone", () => {
    const ws = defaultWorkspace(42);

    assert.equal(ws.tabs.length, 1);
    assert.equal(ws.activeTab, "t1");
    assert.equal(ws.touchedAt, 42);
    assert.deepEqual(types(only(ws)), ["terminal"]);
    assert.deepEqual(only(ws).fractions, [1]);
  });

  test("offers terminal, task and plan until files and diffs exist", () => {
    assert.deepEqual([...AVAILABLE_COLUMN_TYPES], ["terminal", "task", "plan"]);
  });
});

describe("tabs", () => {
  test("a new tab holds a terminal and becomes active", () => {
    const ws = addTab(defaultWorkspace(1), AVAILABLE_COLUMN_TYPES, 2);

    assert.deepEqual(ws.tabs.map((tab) => tab.id), ["t1", "t2"]);
    assert.equal(ws.activeTab, "t2");
    assert.deepEqual(types(only(ws)), ["terminal"]);
    assert.equal(ws.touchedAt, 2);
  });

  test("an id is never reused while a later tab still holds it", () => {
    let ws = addTab(addTab(defaultWorkspace(1), AVAILABLE_COLUMN_TYPES, 2), AVAILABLE_COLUMN_TYPES, 3);
    ws = closeTab(ws, "t2", 4);
    ws = addTab(ws, AVAILABLE_COLUMN_TYPES, 5);

    assert.deepEqual(ws.tabs.map((tab) => tab.id), ["t1", "t3", "t4"]);
  });

  test("the last tab cannot be closed", () => {
    const ws = defaultWorkspace(1);

    assert.equal(closeTab(ws, "t1", 2), ws);
  });

  test("closing the active tab activates its left neighbour, or the right one for the first tab", () => {
    const three = addTab(addTab(defaultWorkspace(1), AVAILABLE_COLUMN_TYPES, 2), AVAILABLE_COLUMN_TYPES, 3);

    assert.equal(closeTab(three, "t3", 4).activeTab, "t2");
    assert.equal(closeTab(selectTab(three, "t1", 4), "t1", 5).activeTab, "t2");
  });

  test("closing an inactive tab keeps the active one", () => {
    const three = addTab(addTab(defaultWorkspace(1), AVAILABLE_COLUMN_TYPES, 2), AVAILABLE_COLUMN_TYPES, 3);

    assert.equal(closeTab(three, "t1", 4).activeTab, "t3");
  });

  test("selecting an unknown or the active tab changes nothing", () => {
    const ws = addTab(defaultWorkspace(1), AVAILABLE_COLUMN_TYPES, 2);

    assert.equal(selectTab(ws, "t9", 3), ws);
    assert.equal(selectTab(ws, "t2", 3), ws);
    assert.equal(selectTab(ws, "t1", 3).activeTab, "t1");
  });

  test("are labelled by their columns", () => {
    assert.equal(tabLabel(only(twoColumns())), "Terminal · Task");
    assert.equal(tabLabel(only(defaultWorkspace(1))), "Terminal");
  });
});

describe("columns", () => {
  test("adding picks the first available type the tab does not hold, right of the pressed column", () => {
    const ws = twoColumns(3);

    assert.deepEqual(types(only(ws)), ["terminal", "task"]);
    close(only(ws).fractions, [0.5, 0.5]);
    assert.equal(only(ws).focus, 1);
    assert.equal(ws.touchedAt, 3);

    const taskFirst = setColumnType(defaultWorkspace(1), "t1", 0, "task", 2);
    assert.deepEqual(types(only(addColumn(taskFirst, "t1", 0, AVAILABLE_COLUMN_TYPES, 3))), ["task", "terminal"]);
  });

  test("adding does nothing once every available type is in the tab", () => {
    const ws = addColumn(twoColumns(), "t1", 1, AVAILABLE_COLUMN_TYPES, 2);

    assert.equal(addColumn(ws, "t1", 1, AVAILABLE_COLUMN_TYPES, 9), ws);
  });

  test("adding takes a newly available type in declaration order", () => {
    const ws = addColumn(twoColumns(), "t1", 1, ALL_TYPES, 2);

    assert.deepEqual(types(only(ws)), ["terminal", "task", "plan"]);
    close(only(ws).fractions, [1 / 3, 1 / 3, 1 / 3]);
  });

  test("a new column takes an equal share and the others keep their proportions", () => {
    let ws = twoColumns();
    ws = moveDivider(ws, "t1", 0, 1, 0.1, 0.1, 2);
    ws = addColumn(ws, "t1", 1, ALL_TYPES, 3);

    close(only(ws).fractions, [0.6 * 2 / 3, 0.4 * 2 / 3, 1 / 3]);
  });

  test("the last column cannot be closed", () => {
    const ws = defaultWorkspace(1);

    assert.equal(closeColumn(ws, "t1", 0, 2), ws);
  });

  test("closing a column gives its share to the rest and keeps focus on a column that exists", () => {
    let ws = addColumn(twoColumns(), "t1", 1, ALL_TYPES, 2);
    ws = moveDivider(ws, "t1", 0, 1, 1 / 6, 0.05, 3);
    ws = closeColumn(ws, "t1", 2, 4);

    assert.deepEqual(types(only(ws)), ["terminal", "task"]);
    close(only(ws).fractions, [0.75, 0.25]);
    assert.equal(only(ws).focus, 1);

    ws = closeColumn(focusColumn(ws, "t1", 0, 5), "t1", 0, 6);
    assert.deepEqual(types(only(ws)), ["task"]);
    assert.equal(only(ws).focus, 0);
  });

  test("choosing a type the tab already holds swaps the two columns", () => {
    const ws = setColumnType(twoColumns(), "t1", 1, "terminal", 2);

    assert.deepEqual(types(only(ws)), ["task", "terminal"]);
    assert.equal(ws.touchedAt, 2);
  });

  test("choosing an absent type replaces the column in place", () => {
    const ws = setColumnType(twoColumns(), "t1", 1, "plan", 2);

    assert.deepEqual(types(only(ws)), ["terminal", "plan"]);
  });

  test("choosing the type a column already has changes nothing", () => {
    const ws = twoColumns();

    assert.equal(setColumnType(ws, "t1", 0, "terminal", 2), ws);
  });

  test("focus follows a valid index only", () => {
    const ws = twoColumns();

    assert.equal(only(focusColumn(ws, "t1", 0, 2)).focus, 0);
    assert.equal(focusColumn(ws, "t1", 5, 2), ws);
  });
});

describe("fractions", () => {
  test("normalize to a positive distribution of the column count", () => {
    close(normalizeFractions([2, 2], 2), [0.5, 0.5]);
    close(normalizeFractions([1, 3], 2), [0.25, 0.75]);
    close(normalizeFractions([1], 2), [0.5, 0.5]);
    close(normalizeFractions([1, -1], 2), [0.5, 0.5]);
    close(normalizeFractions([1, Number.NaN], 2), [0.5, 0.5]);
    close(normalizeFractions([0, 1], 2), [0.5, 0.5]);
    close(normalizeFractions("wide", 3), [1 / 3, 1 / 3, 1 / 3]);
  });

  test("a divider moves share between its two columns only, within the minimum", () => {
    const three = addColumn(twoColumns(), "t1", 1, ALL_TYPES, 2);

    const moved = only(moveDivider(three, "t1", 0, 1, 0.1, 0.1, 3)).fractions;
    close(moved, [1 / 3 + 0.1, 1 / 3 - 0.1, 1 / 3]);

    const clamped = only(moveDivider(three, "t1", 1, 2, 0.5, 0.1, 3)).fractions;
    close(clamped, [1 / 3, 2 / 3 - 0.1, 0.1]);
    assert.ok(Math.abs(sum(clamped) - 1) < 1e-9);
  });

  test("a divider that cannot move changes nothing", () => {
    const ws = twoColumns();
    const pinned = moveDivider(ws, "t1", 0, 1, -0.4, 0.5, 2);

    assert.equal(pinned, ws);
    assert.equal(moveDivider(ws, "t1", 0, 5, 0.1, 0.1, 2), ws);
  });
});

describe("visible columns", () => {
  const options = (width: number, narrow = false, available: readonly ColumnType[] = AVAILABLE_COLUMN_TYPES) => ({
    width: width,
    minWidth: 320,
    narrow: narrow,
    available: available,
  });

  test("show every column when there is room", () => {
    const tab = only(twoColumns());

    assert.deepEqual(visibleColumns(tab, options(1000)).map((c) => c.type), ["terminal", "task"]);
    assert.deepEqual(visibleColumns(tab, options(0)).map((c) => c.index), [0, 1]);
  });

  test("hide by minimum width, keeping the focused column, without touching the stored layout", () => {
    const ws = focusColumn(twoColumns(), "t1", 0, 2);
    const snapshot = JSON.stringify(ws);
    const tab = only(ws);

    const shown = visibleColumns(tab, options(500));

    assert.deepEqual(shown.map((c) => c.type), ["terminal"]);
    assert.deepEqual(shown.map((c) => c.fraction), [1]);
    assert.equal(JSON.stringify(ws), snapshot);
    assert.deepEqual(visibleColumns(only(focusColumn(ws, "t1", 1, 3)), options(500)).map((c) => c.type), ["task"]);
  });

  test("hide a column whose share is narrower than the minimum", () => {
    const tab = only(moveDivider(twoColumns(), "t1", 0, 1, 0.4, 0.05, 2));

    const shown = visibleColumns(tab, options(1000));
    assert.deepEqual(shown.map((c) => c.type), ["terminal"]);
    assert.deepEqual(shown.map((c) => c.fraction), [1]);
  });

  test("show one column on a phone", () => {
    const tab = only(twoColumns());

    assert.deepEqual(visibleColumns(tab, options(2000, true)).map((c) => c.type), ["task"]);
  });

  test("hide unavailable types and share their width among the rest", () => {
    const tab = only(setColumnType(addColumn(twoColumns(), "t1", 1, ALL_TYPES, 2), "t1", 2, "files", 3));

    const shown = visibleColumns(tab, options(2000));
    assert.deepEqual(shown.map((c) => c.type), ["terminal", "task"]);
    close(shown.map((c) => c.fraction), [0.5, 0.5]);
    assert.deepEqual(types(tab), ["terminal", "task", "files"]);
  });

  test("a tab holding nothing available shows nothing", () => {
    const tab = only(setColumnType(defaultWorkspace(1), "t1", 0, "diff", 2));

    assert.deepEqual(visibleColumns(tab, options(1000)), []);
  });
});

describe("storage format", () => {
  test("round-trips through its serialized form", () => {
    const ws = addTab(twoColumns(), AVAILABLE_COLUMN_TYPES, 2);
    const map = new Map([["s1", ws]]);

    assert.deepEqual(parseWorkspaces(serializeWorkspaces(map)), map);
  });

  test("drops what it cannot read instead of failing", () => {
    assert.equal(parseWorkspaces(null).size, 0);
    assert.equal(parseWorkspaces("{not json").size, 0);
    assert.equal(parseWorkspaces("[1, 2]").size, 0);

    const parsed = parseWorkspaces(JSON.stringify({
      empty: { tabs: [], activeTab: "t1", touchedAt: 1 },
      junk: 7,
      mixed: {
        tabs: [
          { id: "t1", columns: [{ type: "terminal" }, { type: "bogus" }, { type: "terminal" }, { type: "task" }],
            fractions: [3, 1, 1, 1], focus: 9 },
          { id: "t1", columns: [{ type: "task" }], fractions: [1] },
          { id: "t2", columns: [], fractions: [] },
          { id: "t3", columns: [{ type: "plan" }], fractions: "x" },
        ],
        activeTab: "t7",
        touchedAt: "yesterday",
      },
    }));

    assert.deepEqual([...parsed.keys()], ["mixed"]);
    const mixed = parsed.get("mixed")!;
    assert.deepEqual(mixed.tabs.map((tab) => tab.id), ["t1", "t3"]);
    assert.deepEqual(types(mixed.tabs[0]!), ["terminal", "task"]);
    close(mixed.tabs[0]!.fractions, [0.5, 0.5]);
    assert.equal(mixed.tabs[0]!.focus, 0);
    assert.deepEqual(types(mixed.tabs[1]!), ["plan"]);
    assert.equal(mixed.activeTab, "t1");
    assert.equal(mixed.touchedAt, 0);
  });

  test("pruning after a snapshot keeps only live sessions", () => {
    const map = new Map([["a", defaultWorkspace(1)], ["b", defaultWorkspace(2)]]);

    assert.deepEqual([...pruneWorkspaces(map, new Set(["b", "c"])).keys()], ["b"]);
    assert.equal(pruneWorkspaces(map, new Set(["a", "b"])), map);
  });

  test("keeps the most recently touched layouts up to the cap", () => {
    const map = new Map<string, SessionWorkspace>();
    for (let i = 0; i <= MAX_WORKSPACES; i++) map.set("s" + i, defaultWorkspace(1000 - i));

    const capped = capWorkspaces(map, MAX_WORKSPACES);

    assert.equal(MAX_WORKSPACES, 200);
    assert.equal(capped.size, MAX_WORKSPACES);
    assert.equal(capped.has("s" + MAX_WORKSPACES), false);
    assert.equal(capped.has("s0"), true);
    assert.equal(capWorkspaces(capped, MAX_WORKSPACES), capped);
  });
});

describe("the layout owner", () => {
  beforeEach(() => {
    storage.failing = false;
  });

  test("loads stored layouts when the page starts", () => {
    assert.deepEqual(types(activeTabOf(layout.workspaceOf("seeded"))), ["task"]);
  });

  test("answers the default for an unknown session without storing it", () => {
    const ws = layout.workspaceOf("fresh");

    assert.deepEqual(types(activeTabOf(ws)), ["terminal"]);
    assert.equal(layout.workspaces.value.has("fresh"), false);
  });

  test("persists every change under one key", () => {
    layout.addColumn("s-one", "t1", 0);
    layout.addTab("s-one");

    const stored = parseWorkspaces(storage.items.get(STORAGE_KEY) ?? null);
    assert.deepEqual(stored.get("s-one")!.tabs.map((tab) => tab.id), ["t1", "t2"]);
    assert.deepEqual(types(stored.get("s-one")!.tabs[0]!), ["terminal", "task"]);
    assert.equal(layout.workspaceOf("s-one").activeTab, "t2");
  });

  test("keeps working in memory when storage throws", () => {
    storage.failing = true;

    layout.addTab("s-two");
    layout.selectTab("s-two", "t1");
    layout.closeTab("s-two", "t2");
    layout.addColumn("s-two", "t1", 0);
    layout.setColumnType("s-two", "t1", 1, "terminal");
    layout.focusColumn("s-two", "t1", 0);
    layout.moveDivider("s-two", "t1", 0, 1, 0.1, 0.1);
    layout.closeColumn("s-two", "t1", 1);

    assert.deepEqual(types(activeTabOf(layout.workspaceOf("s-two"))), ["task"]);
  });

  test("forgets the sessions a full snapshot no longer lists", () => {
    layout.addTab("s-gone");
    layout.pruneWorkspaces(new Set(["s-one"]));

    assert.deepEqual([...layout.workspaces.value.keys()], ["s-one"]);
    assert.deepEqual([...parseWorkspaces(storage.items.get(STORAGE_KEY) ?? null).keys()], ["s-one"]);
  });
});


test("opening an investigator targets its finding in a reused Terminal · Plan workspace", () => {
  layout.openFinding("investigator", "local:1", "f_first");
  const first = layout.findingFocusFor("investigator");
  assert.deepEqual(first, { taskRef: "local:1", findingId: "f_first" });
  assert.deepEqual(activeTabOf(layout.workspaceOf("investigator")).columns, [{ type: "terminal" }, { type: "plan" }]);
  assert.equal(activeTabOf(layout.workspaceOf("investigator")).focus, 1);
  const count = layout.workspaceOf("investigator").tabs.length;
  layout.openFinding("investigator", "local:1", "f_second");
  assert.equal(layout.workspaceOf("investigator").tabs.length, count);
  assert.deepEqual(layout.findingFocusFor("investigator"), { taskRef: "local:1", findingId: "f_second" });
  layout.pruneWorkspaces(new Set());
  assert.equal(layout.findingFocusFor("investigator"), undefined);
});
