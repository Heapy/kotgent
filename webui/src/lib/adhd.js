// ADHD-mode membership is decided when the list renders, so a folder mark is never written to the sessions
// under it. A mark covers the sessions drawn under its head, and nothing while no such head is drawn.

import { headChain, normalizePath } from "./paths.js";
import { groupingEnabled } from "./prefs.js";

export function adhdFolderOf(cwd, prefs) {
  if (!cwd || !groupingEnabled(prefs)) return null;
  const chain = headChain(cwd, prefs.basePath, prefs.groupingLevel);
  for (let index = chain.length - 1; index >= 0; index -= 1) {
    if (isPathAdhd(chain[index], prefs.adhdPaths)) return chain[index];
  }
  return null;
}

// Whether this exact folder carries a mark, which is what its button shows. A folder under a marked
// ancestor is listed by the rule below but is not itself marked.
export function isPathAdhd(path, paths) {
  if (!path || !Array.isArray(paths)) return false;
  const target = normalizePath(path);
  return paths.some((marked) => normalizePath(marked) === target);
}

export function isSessionInAdhd(session, prefs) {
  if (!session) return false;
  return session.adhd === true || adhdFolderOf(session.cwd, prefs) !== null;
}
