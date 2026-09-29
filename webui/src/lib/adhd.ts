// ADHD-mode membership is decided when the list renders, so a folder mark is never written to the sessions
// under it. A mark covers the sessions drawn under its head, and nothing while no such head is drawn.

import type { Preferences } from "./prefs.ts";
import type { Session } from "./sessions.ts";

import { headChain, normalizePath } from "./paths.ts";
import { groupingEnabled } from "./prefs.ts";

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
export function isPathAdhd(path: unknown, paths: unknown) {
  if (!path || !Array.isArray(paths)) return false;
  const target = normalizePath(path);
  return paths.some((marked: unknown) => normalizePath(marked) === target);
}

export function isSessionInAdhd(session: Session | null | undefined, prefs: Preferences) {
  if (!session) return false;
  return session.adhd === true || adhdFolderOf(session.cwd, prefs) !== null;
}
