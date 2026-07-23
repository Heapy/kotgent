/*
 * Working-directory grouping: the pure path arithmetic behind the base-path preference.
 *
 * No DOM, no I/O — every function here is a plain value->value mapping, which is what makes the
 * grouping rules checkable outside a browser (the macosArm64 test binary cannot run this JS).
 */

/** Trim, collapse repeated slashes and drop the trailing slash, so paths compare and concatenate cleanly. */
export function normalizePath(path) {
  const trimmed = (path || "").trim().replace(/\/{2,}/g, "/");
  return trimmed.length > 1 ? trimmed.replace(/\/+$/, "") : trimmed;
}

/** The last segment of [path] ("/Users/me/dev" -> "dev"). */
export function basename(path) {
  const p = normalizePath(path);
  const cut = p.lastIndexOf("/");
  return cut >= 0 ? p.slice(cut + 1) : p;
}

/** Join [base] with already-normalized [segments], handling a "/" base. */
export function joinPath(base, segments) {
  if (segments.length === 0) return base;
  return (base === "/" ? "" : base) + "/" + segments.join("/");
}

/** The segments of [path] below [base], `[]` when they are the same path, or null when [path] is outside. */
export function segmentsUnder(base, path) {
  const b = normalizePath(base);
  const p = normalizePath(path);
  if (!b || !p) return null;
  if (p === b) return [];
  const prefix = b === "/" ? "/" : b + "/";
  if (p.indexOf(prefix) !== 0) return null;
  return p.slice(prefix.length).split("/").filter((segment) => segment.length > 0);
}

/**
 * The group a session's [cwd] belongs to: the base path plus at most [level] directories below it
 * (level 0 = a single group at the base). A cwd outside the base path is its own group, listed after
 * the in-base ones — the base path decides the shape of the tree, it does not hide anything.
 */
export function groupFor(cwd, basePath, level) {
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

/** Fold [list] into `[{path, label, inBase, sessions}]` — in-base groups first, each side path-sorted. */
export function groupSessions(list, basePath, level) {
  const groups = new Map();
  for (const s of list) {
    const g = groupFor(s.cwd, basePath, level);
    const existing = groups.get(g.path);
    if (existing) existing.sessions.push(s);
    else groups.set(g.path, { path: g.path, label: g.label, inBase: g.inBase, sessions: [s] });
  }
  return Array.from(groups.values()).sort((a, b) => {
    if (a.inBase !== b.inBase) return a.inBase ? -1 : 1;
    return a.path.localeCompare(b.path);
  });
}
