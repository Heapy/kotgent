// ADHD-mode membership is decided when the list renders, so a folder mark covers every session under
// it without any write fan-out, including sessions started long after the mark.

import { normalizePath, segmentsUnder } from "./paths.js";

// The nearest marked folder holding this cwd, or null. The row pin names it when it, not the session's own
// mark, is what keeps the session listed.
export function adhdFolderOf(cwd, paths) {
  if (!cwd || !Array.isArray(paths)) return null;
  let nearest = null;
  for (const path of paths) {
    // segmentsUnder answers [] for an exact match, so only a null answer means "not under".
    const below = segmentsUnder(path, cwd);
    if (below === null) continue;
    if (nearest === null || below.length < nearest.depth) nearest = { path: path, depth: below.length };
  }
  return nearest === null ? null : nearest.path;
}

// Whether this exact folder carries a mark, which is what its button shows. A folder under a marked
// ancestor is listed by the rule below but is not itself marked.
export function isPathAdhd(path, paths) {
  if (!path || !Array.isArray(paths)) return false;
  const target = normalizePath(path);
  return paths.some((marked) => normalizePath(marked) === target);
}

export function isSessionInAdhd(session, paths) {
  if (!session) return false;
  return session.adhd === true || adhdFolderOf(session.cwd, paths) !== null;
}
