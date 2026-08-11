export function normalizePath(path) {
  const trimmed = (path || "").trim().replace(/\/{2,}/g, "/");
  return trimmed.length > 1 ? trimmed.replace(/\/+$/, "") : trimmed;
}

export function basename(path) {
  const p = normalizePath(path);
  const cut = p.lastIndexOf("/");
  return cut >= 0 ? p.slice(cut + 1) : p;
}

export function joinPath(base, segments) {
  if (segments.length === 0) return base;
  return (base === "/" ? "" : base) + "/" + segments.join("/");
}

export function segmentsUnder(base, path) {
  const b = normalizePath(base);
  const p = normalizePath(path);
  if (!b || !p) return null;
  if (p === b) return [];
  const prefix = b === "/" ? "/" : b + "/";
  if (p.indexOf(prefix) !== 0) return null;
  return p.slice(prefix.length).split("/").filter((segment) => segment.length > 0);
}

// Paths outside the configured base remain visible as standalone groups.
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

function newNode(path, label, inBase) {
  return { path: path, label: label, inBase: inBase, sessions: [], children: new Map() };
}

function sortedNodes(nodes) {
  return Array.from(nodes.values()).sort((a, b) => a.path.localeCompare(b.path));
}

function finishNode(node) {
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

export function groupSessions(list, basePath, level) {
  const base = normalizePath(basePath);
  const depth = Math.max(0, Math.trunc(Number(level)) || 0);
  const roots = new Map();
  const outside = new Map();
  let baseNode = null;

  for (const s of list) {
    const path = normalizePath(s.cwd);
    const segments = segmentsUnder(base, path);
    if (segments === null) {
      let node = outside.get(path);
      if (!node) {
        node = newNode(path, path || "(unknown)", false);
        outside.set(path, node);
      }
      node.sessions.push(s);
      continue;
    }

    const visible = segments.slice(0, depth);
    if (visible.length === 0) {
      if (!baseNode) baseNode = newNode(base, basename(base) || base, true);
      baseNode.sessions.push(s);
      continue;
    }

    let siblings = roots;
    const pathSegments = [];
    let node = null;
    for (const segment of visible) {
      pathSegments.push(segment);
      const nodePath = joinPath(base, pathSegments);
      node = siblings.get(nodePath);
      if (!node) {
        node = newNode(nodePath, segment, true);
        siblings.set(nodePath, node);
      }
      siblings = node.children;
    }
    node.sessions.push(s);
  }

  const inBase = sortedNodes(roots).map(finishNode);
  if (baseNode) inBase.unshift(finishNode(baseNode));
  return inBase.concat(sortedNodes(outside).map(finishNode));
}
