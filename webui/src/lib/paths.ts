export interface GroupableSession {
  cwd: string;
  updatedAt?: number;
}

export type GroupEntry<T> = { session: T; group?: never; at: number }
  | { group: SessionGroup<T>; session?: never; at: number };

export interface SessionGroup<T> {
  path: string;
  label: string;
  inBase: boolean;
  sessions: T[];
  children: SessionGroup<T>[];
  sessionCount: number;
  entries?: GroupEntry<T>[];
  newestChange?: number;
}

interface GroupNode<T> {
  path: string;
  label: string;
  inBase: boolean;
  sessions: T[];
  children: Map<string, GroupNode<T>>;
}

export function normalizePath(path: unknown) {
  const value = path || "";
  if (typeof value !== "string") throw new TypeError("path.trim is not a function");
  const trimmed = value.trim().replace(/\/{2,}/g, "/");
  return trimmed.length > 1 ? trimmed.replace(/\/+$/, "") : trimmed;
}

export function basename(path: string | null | undefined) {
  const p = normalizePath(path);
  const cut = p.lastIndexOf("/");
  return cut >= 0 ? p.slice(cut + 1) : p;
}

export function joinPath(base: string, segments: readonly string[]) {
  if (segments.length === 0) return base;
  return (base === "/" ? "" : base) + "/" + segments.join("/");
}

export function segmentsUnder(base: string | null | undefined, path: string | null | undefined) {
  const b = normalizePath(base);
  const p = normalizePath(path);
  if (!b || !p) return null;
  if (p === b) return [];
  const prefix = b === "/" ? "/" : b + "/";
  if (p.indexOf(prefix) !== 0) return null;
  return p.slice(prefix.length).split("/").filter((segment) => segment.length > 0);
}

// Paths outside the configured base remain visible as standalone groups.
export function groupFor(cwd: string | null | undefined, basePath: string | null | undefined, level: number) {
  const path = normalizePath(cwd);
  const segments = segmentsUnder(basePath, path);
  if (segments === null) return { path: path, label: path || "(unknown)", inBase: false };
  const kept = segments.slice(0, Math.max(0, level));
  const base = normalizePath(basePath);
  return {
    path: joinPath(base, kept),
    label: kept.length > 0 ? kept.join("/") : (basename(base) || base),
    inBase: true,
  };
}

function newNode<T>(path: string, label: string, inBase: boolean): GroupNode<T> {
  return { path: path, label: label, inBase: inBase, sessions: [], children: new Map<string, GroupNode<T>>() };
}

function sortedNodes<T>(nodes: ReadonlyMap<string, GroupNode<T>>) {
  return Array.from(nodes.values()).sort((a, b) => a.path.localeCompare(b.path));
}

function finishNode<T>(node: GroupNode<T>): SessionGroup<T> {
  const children = sortedNodes(node.children).map(finishNode);
  return {
    path: node.path,
    label: node.label,
    inBase: node.inBase,
    sessions: node.sessions,
    children: children,
    sessionCount: node.sessions.length + children.reduce((total, child) => total + child.sessionCount, 0),
  };
}

function buildGroupEntries<T extends { updatedAt?: number }>(sessions: readonly T[], children: readonly SessionGroup<T>[]): GroupEntry<T>[] {
  return sessions.map<GroupEntry<T>>((session) => ({ session: session, at: session.updatedAt || 0 }))
    .concat(children.map((child) => ({ group: child, at: child.newestChange || 0 })));
}

// Only recency-ordered groups carry entries; live groups keep rows above subfolders.
export function groupEntries<T extends { updatedAt?: number }>(group: SessionGroup<T>) {
  return group.entries || buildGroupEntries(group.sessions, group.children);
}

// One list per folder so a directly-held session and a subfolder compete on the same recency, not by kind.
function orderedNode<T extends { updatedAt?: number }>(group: SessionGroup<T>): SessionGroup<T> & { newestChange: number } {
  const children = orderGroupsByRecentChange(group.children);
  const entries = buildGroupEntries(group.sessions, children)
    .sort((a, b) => b.at - a.at);
  return Object.assign({}, group, {
    children: children,
    entries: entries,
    newestChange: entries.length ? entries[0]!.at : 0,
  });
}

/** Path order is the archive's default; this re-reads the same tree newest-first at every level. */
export function orderGroupsByRecentChange<T extends { updatedAt?: number }>(groups: readonly SessionGroup<T>[]): (SessionGroup<T> & { newestChange: number })[] {
  return groups.map(orderedNode).sort((a, b) => b.newestChange - a.newestChange);
}

const OUTSIDE = "outside";
const BASE = "base";
const NESTED = "nested";

function groupingDepth(level: unknown) {
  return Math.max(0, Math.trunc(Number(level)) || 0);
}

function drawnHeads(cwd: string | null | undefined, base: string, depth: number): { kind: typeof OUTSIDE | typeof BASE | typeof NESTED; heads: { path: string; label: string }[] } {
  const path = normalizePath(cwd);
  const segments = segmentsUnder(base, path);
  if (segments === null) return { kind: OUTSIDE, heads: [{ path: path, label: path || "(unknown)" }] };
  const visible = segments.slice(0, depth);
  if (visible.length === 0) return { kind: BASE, heads: [{ path: base, label: basename(base) || base }] };
  return {
    kind: NESTED,
    heads: visible.map((segment, index) => ({
      path: joinPath(base, visible.slice(0, index + 1)),
      label: segment,
    })),
  };
}

/** The paths of the folder heads groupSessions draws above a session in this cwd, outermost first. */
export function headChain(cwd: string | null | undefined, basePath: string | null | undefined, level: unknown) {
  return drawnHeads(cwd, normalizePath(basePath), groupingDepth(level)).heads.map((head) => head.path);
}

export function groupSessions<T extends GroupableSession>(list: readonly T[], basePath: string | null | undefined, level: unknown): SessionGroup<T>[] {
  const base = normalizePath(basePath);
  const depth = groupingDepth(level);
  const tops = { [BASE]: new Map<string, GroupNode<T>>(), [NESTED]: new Map<string, GroupNode<T>>(), [OUTSIDE]: new Map<string, GroupNode<T>>() };

  for (const s of list) {
    const { kind, heads } = drawnHeads(s.cwd, base, depth);
    let siblings = tops[kind];
    let node: GroupNode<T> | null | undefined = null;
    for (const head of heads) {
      node = siblings.get(head.path);
      if (!node) {
        node = newNode<T>(head.path, head.label, kind !== OUTSIDE);
        siblings.set(head.path, node);
      }
      siblings = node.children;
    }
    node!.sessions.push(s);
  }

  return sortedNodes(tops[BASE])
    .concat(sortedNodes(tops[NESTED]), sortedNodes(tops[OUTSIDE]))
    .map(finishNode);
}
