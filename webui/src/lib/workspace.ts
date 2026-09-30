// Per-session workspace layout: tabs of columns. Every operation returns its input unchanged when it has
// nothing to do, so the owner can skip a storage write by identity.

export const COLUMN_TYPES = ["terminal", "task", "plan", "files", "diff"] as const;

export type ColumnType = (typeof COLUMN_TYPES)[number];

// Stored layouts may name a type this build cannot render; it stays stored and is hidden.
export const AVAILABLE_COLUMN_TYPES: readonly ColumnType[] = ["terminal", "task"];

export const COLUMN_LABELS: Readonly<Record<ColumnType, string>> = {
  terminal: "Terminal",
  task: "Task",
  plan: "Plan",
  files: "Files",
  diff: "Diff",
};

export const MAX_WORKSPACES = 200;

export interface Column {
  readonly type: ColumnType;
}

export interface Tab {
  readonly id: string;
  readonly columns: readonly Column[];
  readonly fractions: readonly number[];
  // The column a phone shows, and the one width hiding keeps.
  readonly focus: number;
}

export interface SessionWorkspace {
  readonly tabs: readonly Tab[];
  readonly activeTab: string;
  readonly touchedAt: number;
}

export interface VisibleColumn {
  readonly index: number;
  readonly type: ColumnType;
  readonly fraction: number;
}

export interface VisibilityOptions {
  readonly width: number;
  readonly minWidth: number;
  readonly narrow: boolean;
  readonly available: readonly ColumnType[];
}

export function isColumnType(value: unknown): value is ColumnType {
  return typeof value === "string" && (COLUMN_TYPES as readonly string[]).includes(value);
}

function equalShares(count: number) {
  return Array.from({ length: count }, () => 1 / count);
}

export function normalizeFractions(raw: unknown, count: number): number[] {
  if (count <= 0) return [];
  if (!Array.isArray(raw) || raw.length !== count) return equalShares(count);
  const values: unknown[] = raw;
  if (!values.every((value) => typeof value === "number" && Number.isFinite(value) && value > 0)) {
    return equalShares(count);
  }
  const numbers = values as number[];
  const total = numbers.reduce((sum, value) => sum + value, 0);
  return numbers.map((value) => value / total);
}

function newTab(id: string, type: ColumnType): Tab {
  return { id: id, columns: [{ type: type }], fractions: [1], focus: 0 };
}

export function defaultWorkspace(now: number): SessionWorkspace {
  return { tabs: [newTab("t1", "terminal")], activeTab: "t1", touchedAt: now };
}

export function activeTabOf(ws: SessionWorkspace): Tab {
  return ws.tabs.find((tab) => tab.id === ws.activeTab) || ws.tabs[0]!;
}

export function tabLabel(tab: Tab) {
  return tab.columns.map((column) => COLUMN_LABELS[column.type]).join(" · ");
}

function nextTabId(ws: SessionWorkspace) {
  let highest = 0;
  for (const tab of ws.tabs) {
    const match = /^t(\d+)$/.exec(tab.id);
    if (match) highest = Math.max(highest, Number(match[1]));
  }
  return "t" + (highest + 1);
}

function withTab(ws: SessionWorkspace, tabId: string, change: (tab: Tab) => Tab, now: number): SessionWorkspace {
  const index = ws.tabs.findIndex((tab) => tab.id === tabId);
  if (index < 0) return ws;
  const current = ws.tabs[index]!;
  const next = change(current);
  if (next === current) return ws;
  const tabs = ws.tabs.slice();
  tabs[index] = next;
  return { tabs: tabs, activeTab: ws.activeTab, touchedAt: now };
}

export function addTab(ws: SessionWorkspace, available: readonly ColumnType[], now: number): SessionWorkspace {
  const first = available[0];
  if (!first) return ws;
  const id = nextTabId(ws);
  return { tabs: ws.tabs.concat(newTab(id, first)), activeTab: id, touchedAt: now };
}

export function closeTab(ws: SessionWorkspace, tabId: string, now: number): SessionWorkspace {
  const index = ws.tabs.findIndex((tab) => tab.id === tabId);
  if (index < 0 || ws.tabs.length <= 1) return ws;
  const tabs = ws.tabs.filter((tab) => tab.id !== tabId);
  const activeTab = ws.activeTab === tabId ? tabs[Math.max(0, index - 1)]!.id : ws.activeTab;
  return { tabs: tabs, activeTab: activeTab, touchedAt: now };
}

export function selectTab(ws: SessionWorkspace, tabId: string, now: number): SessionWorkspace {
  if (ws.activeTab === tabId || !ws.tabs.some((tab) => tab.id === tabId)) return ws;
  return { tabs: ws.tabs, activeTab: tabId, touchedAt: now };
}

export function addColumn(
  ws: SessionWorkspace,
  tabId: string,
  after: number,
  available: readonly ColumnType[],
  now: number,
): SessionWorkspace {
  return withTab(ws, tabId, (tab) => {
    const held = new Set(tab.columns.map((column) => column.type));
    const type = COLUMN_TYPES.find((candidate) => available.includes(candidate) && !held.has(candidate));
    if (!type) return tab;
    const at = Math.min(Math.max(after + 1, 0), tab.columns.length);
    const count = tab.columns.length + 1;
    const scale = (count - 1) / count;
    const columns = tab.columns.slice();
    columns.splice(at, 0, { type: type });
    const fractions = tab.fractions.map((fraction) => fraction * scale);
    fractions.splice(at, 0, 1 / count);
    return { id: tab.id, columns: columns, fractions: fractions, focus: at };
  }, now);
}

export function closeColumn(ws: SessionWorkspace, tabId: string, index: number, now: number): SessionWorkspace {
  return withTab(ws, tabId, (tab) => {
    if (tab.columns.length <= 1 || index < 0 || index >= tab.columns.length) return tab;
    const columns = tab.columns.filter((_, i) => i !== index);
    const fractions = normalizeFractions(tab.fractions.filter((_, i) => i !== index), columns.length);
    const focus = tab.focus > index ? tab.focus - 1 : Math.min(tab.focus, columns.length - 1);
    return { id: tab.id, columns: columns, fractions: fractions, focus: focus };
  }, now);
}

export function setColumnType(
  ws: SessionWorkspace,
  tabId: string,
  index: number,
  type: ColumnType,
  now: number,
): SessionWorkspace {
  return withTab(ws, tabId, (tab) => {
    const current = tab.columns[index];
    if (!current || current.type === type || !isColumnType(type)) return tab;
    const columns = tab.columns.slice();
    const holder = tab.columns.findIndex((column) => column.type === type);
    if (holder >= 0) columns[holder] = current;
    columns[index] = { type: type };
    return { id: tab.id, columns: columns, fractions: tab.fractions, focus: tab.focus };
  }, now);
}

export function focusColumn(ws: SessionWorkspace, tabId: string, index: number, now: number): SessionWorkspace {
  return withTab(ws, tabId, (tab) => {
    if (tab.focus === index || index < 0 || index >= tab.columns.length) return tab;
    return { id: tab.id, columns: tab.columns, fractions: tab.fractions, focus: index };
  }, now);
}

// Shares are in stored units; the caller converts pixels using the visible columns' share of the tab.
export function moveDivider(
  ws: SessionWorkspace,
  tabId: string,
  left: number,
  right: number,
  delta: number,
  minFraction: number,
  now: number,
): SessionWorkspace {
  return withTab(ws, tabId, (tab) => {
    const leftShare = tab.fractions[left];
    const rightShare = tab.fractions[right];
    if (leftShare === undefined || rightShare === undefined || left === right || !Number.isFinite(delta)) return tab;
    const pair = leftShare + rightShare;
    if (pair < 2 * minFraction) return tab;
    const nextLeft = Math.min(Math.max(leftShare + delta, minFraction), pair - minFraction);
    if (Math.abs(nextLeft - leftShare) < 1e-12) return tab;
    const fractions = tab.fractions.slice();
    fractions[left] = nextLeft;
    fractions[right] = pair - nextLeft;
    return { id: tab.id, columns: tab.columns, fractions: fractions, focus: tab.focus };
  }, now);
}

// Decides what fits on screen without changing the stored layout.
export function visibleColumns(tab: Tab, options: VisibilityOptions): VisibleColumn[] {
  let kept = tab.columns
    .map((column, index) => ({ index: index, type: column.type, share: tab.fractions[index] || 0 }))
    .filter((column) => options.available.includes(column.type));
  if (kept.length === 0) return [];

  const measured = !options.narrow && options.width > 0;
  const limit = options.narrow ? 1 : measured ? Math.max(1, Math.floor(options.width / options.minWidth)) : kept.length;
  if (kept.length > limit) {
    const focused = kept.find((column) => column.index === tab.focus) || kept[0]!;
    const others = kept.filter((column) => column !== focused).slice(0, limit - 1);
    kept = kept.filter((column) => column === focused || others.includes(column));
  }

  if (measured) {
    while (kept.length > 1) {
      const total = kept.reduce((sum, column) => sum + column.share, 0);
      let narrowest = kept[0]!;
      for (const column of kept) if (column.share <= narrowest.share) narrowest = column;
      if (narrowest.share / total * options.width >= options.minWidth) break;
      kept = kept.filter((column) => column !== narrowest);
    }
  }

  const fractions = normalizeFractions(kept.map((column) => column.share), kept.length);
  return kept.map((column, i) => ({ index: column.index, type: column.type, fraction: fractions[i]! }));
}

function parseTab(raw: unknown): Tab | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const id = record["id"];
  if (typeof id !== "string" || id.length === 0) return null;
  const rawColumns = record["columns"];
  if (!Array.isArray(rawColumns)) return null;
  const columns: Column[] = [];
  for (const entry of rawColumns as unknown[]) {
    const type = entry && typeof entry === "object" ? (entry as Record<string, unknown>)["type"] : null;
    if (isColumnType(type) && !columns.some((column) => column.type === type)) columns.push({ type: type });
  }
  if (columns.length === 0) return null;
  const focus = record["focus"];
  return {
    id: id,
    columns: columns,
    fractions: normalizeFractions(record["fractions"], columns.length),
    focus: typeof focus === "number" && Number.isInteger(focus) && focus >= 0 && focus < columns.length ? focus : 0,
  };
}

function parseWorkspace(raw: unknown): SessionWorkspace | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const rawTabs = record["tabs"];
  if (!Array.isArray(rawTabs)) return null;
  const tabs: Tab[] = [];
  for (const entry of rawTabs as unknown[]) {
    const tab = parseTab(entry);
    if (tab && !tabs.some((held) => held.id === tab.id)) tabs.push(tab);
  }
  if (tabs.length === 0) return null;
  const activeTab = record["activeTab"];
  const touchedAt = record["touchedAt"];
  return {
    tabs: tabs,
    activeTab: typeof activeTab === "string" && tabs.some((tab) => tab.id === activeTab) ? activeTab : tabs[0]!.id,
    touchedAt: typeof touchedAt === "number" && Number.isFinite(touchedAt) && touchedAt >= 0 ? touchedAt : 0,
  };
}

export function parseWorkspaces(text: string | null): Map<string, SessionWorkspace> {
  const parsed = new Map<string, SessionWorkspace>();
  if (!text) return parsed;
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (_) {
    return parsed;
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return parsed;
  for (const [id, entry] of Object.entries(raw as Record<string, unknown>)) {
    const ws = parseWorkspace(entry);
    if (ws) parsed.set(id, ws);
  }
  return parsed;
}

export function serializeWorkspaces(workspaces: ReadonlyMap<string, SessionWorkspace>) {
  return JSON.stringify(Object.fromEntries(workspaces));
}

export function pruneWorkspaces(
  workspaces: ReadonlyMap<string, SessionWorkspace>,
  liveIds: ReadonlySet<string>,
): ReadonlyMap<string, SessionWorkspace> {
  if ([...workspaces.keys()].every((id) => liveIds.has(id))) return workspaces;
  return new Map([...workspaces].filter(([id]) => liveIds.has(id)));
}

export function capWorkspaces(
  workspaces: ReadonlyMap<string, SessionWorkspace>,
  max: number,
): ReadonlyMap<string, SessionWorkspace> {
  if (workspaces.size <= max) return workspaces;
  const newest = [...workspaces].sort((a, b) => b[1].touchedAt - a[1].touchedAt).slice(0, max);
  const keep = new Set(newest.map(([id]) => id));
  return new Map([...workspaces].filter(([id]) => keep.has(id)));
}
