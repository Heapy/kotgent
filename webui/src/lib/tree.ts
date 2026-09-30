// A child nests under its parent only while that parent is drawn in the same list; otherwise it is drawn at
// the top level and says why. Folder grouping sees only the top-level nodes, each in its tree's folder.

import type { Session } from "./sessions.ts";

import { treeCwd } from "./paths.ts";
import { displayName, isNeedsAttention } from "./sessions.ts";

export interface SessionNode {
  session: Session;
  children: SessionNode[];
  cwd: string;
  updatedAt: number;
  attention: number;
}

export const ORPHAN_LABEL = "orchestrator finished";

export const MAX_EXPANDED_TREES = 200;

function nestsUnder(session: Session, drawn: ReadonlyMap<string, SessionNode>) {
  const parentId = session.parentSessionId;
  if (!parentId || !drawn.has(parentId)) return false;
  const seen = new Set<string>([session.id]);
  let current: string | null = parentId;
  while (current) {
    if (seen.has(current)) return false;
    seen.add(current);
    const node = drawn.get(current);
    if (!node) return true;
    current = node.session.parentSessionId;
  }
  return true;
}

function countAttention(node: SessionNode): number {
  let total = 0;
  for (const child of node.children) {
    total += countAttention(child) + (isNeedsAttention(child.session.state) ? 1 : 0);
  }
  node.attention = total;
  return total;
}

export function sessionForest(rows: readonly Session[], index: ReadonlyMap<string, Session>): SessionNode[] {
  const drawn = new Map<string, SessionNode>();
  for (const session of rows) {
    drawn.set(session.id, {
      session: session,
      children: [],
      cwd: treeCwd(session, index),
      updatedAt: session.updatedAt || 0,
      attention: 0,
    });
  }
  const roots: SessionNode[] = [];
  for (const session of rows) {
    const node = drawn.get(session.id)!;
    if (nestsUnder(session, drawn)) drawn.get(session.parentSessionId!)!.children.push(node);
    else roots.push(node);
  }
  for (const root of roots) countAttention(root);
  return roots;
}

/** Nested children reach the attention section only through their parent's aggregated badge. */
export function attentionRows(roots: readonly SessionNode[]): Session[] {
  return roots.filter((node) => isNeedsAttention(node.session.state)).map((node) => node.session);
}

/** Only for a child drawn at the top level: its parent is either elsewhere or no longer live. */
export function detachedParentLabel(session: Session, index: ReadonlyMap<string, Session>): string | null {
  if (!session.parentSessionId) return null;
  const parent = index.get(session.parentSessionId);
  return parent && parent.id !== session.id && !parent.archived ? "under " + displayName(parent) : ORPHAN_LABEL;
}

export function workersLabel(count: number) {
  return count === 1 ? "1 worker" : count + " workers";
}

export function sanitizeExpandedIds(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const ids = Array.from(new Set(raw.filter((id: unknown): id is string => typeof id === "string")));
  return ids.slice(-MAX_EXPANDED_TREES);
}

export function toggleExpandedId(ids: readonly string[], id: string): string[] {
  if (ids.includes(id)) return ids.filter((known) => known !== id);
  return ids.concat([id]).slice(-MAX_EXPANDED_TREES);
}
