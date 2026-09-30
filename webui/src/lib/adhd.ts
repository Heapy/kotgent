// ADHD-mode membership is decided when the list renders, so a folder mark is never written to the sessions
// under it. A mark covers the sessions drawn under its head, and nothing while no such head is drawn. A child
// drawn under its parent is covered only through that parent, never through a folder of its own.

import type { Preferences } from "./prefs.ts";
import type { Session } from "./sessions.ts";

import { headChain, normalizePath, treeCwd } from "./paths.ts";
import { groupingEnabled } from "./prefs.ts";
import { liveParentOf } from "./sessions.ts";

export type AdhdCover = { folder: string; parent?: never } | { parent: Session; folder?: never };

const NO_SESSIONS: ReadonlyMap<string, Session> = new Map<string, Session>();

export function adhdFolderOf(cwd: string | null | undefined, prefs: Preferences) {
  if (!cwd || !groupingEnabled(prefs)) return null;
  const chain = headChain(cwd, prefs.basePath, prefs.groupingLevel);
  for (let index = chain.length - 1; index >= 0; index -= 1) {
    if (isPathAdhd(chain[index], prefs.adhdPaths)) return chain[index]!;
  }
  return null;
}

// Whether this exact folder carries a mark, which is what its button shows. A folder under a marked
// ancestor is listed by the rule below but is not itself marked.
export function isPathAdhd(path: string | null | undefined, paths: readonly (string | null | undefined)[] | null | undefined) {
  if (!path || !Array.isArray(paths)) return false;
  const target = normalizePath(path);
  return paths.some((marked: string | null | undefined) => normalizePath(marked) === target);
}

function coverOf(
  session: Session,
  prefs: Preferences,
  index: ReadonlyMap<string, Session>,
  seen: Set<string>,
): AdhdCover | null {
  seen.add(session.id);
  const parent = liveParentOf(session, index);
  if (!parent) {
    const folder = adhdFolderOf(treeCwd(session, index), prefs);
    return folder !== null ? { folder: folder } : null;
  }
  if (seen.has(parent.id)) return null;
  return parent.adhd === true || coverOf(parent, prefs, index, seen) !== null ? { parent: parent } : null;
}

/** What lists a session in ADHD mode besides its own mark. */
export function adhdCoverOf(
  session: Session,
  prefs: Preferences,
  index: ReadonlyMap<string, Session> = NO_SESSIONS,
): AdhdCover | null {
  return coverOf(session, prefs, index, new Set<string>());
}

export function isSessionInAdhd(
  session: Session | null | undefined,
  prefs: Preferences,
  index: ReadonlyMap<string, Session> = NO_SESSIONS,
) {
  if (!session) return false;
  return session.adhd === true || adhdCoverOf(session, prefs, index) !== null;
}

// A session is listed on its own mark, through a listed parent, or because it is selected: the selection
// keeps its row, or unmarking it would strand the terminal with no row to return to. Until preferences
// load, marks are placeholders and only the selection and its descendants are listed.
export function listedInAdhd(
  live: readonly Session[],
  index: ReadonlyMap<string, Session>,
  prefs: Preferences,
  activeId: string | null,
  prefsReady: boolean,
): Session[] {
  const memo = new Map<string, boolean>();
  const listed = (session: Session): boolean => {
    const known = memo.get(session.id);
    if (known !== undefined) return known;
    memo.set(session.id, false);
    const parent = liveParentOf(session, index);
    const result = session.id === activeId ||
      (prefsReady && isSessionInAdhd(session, prefs, index)) ||
      (parent !== null && listed(parent));
    memo.set(session.id, result);
    return result;
  };
  return live.filter(listed);
}
