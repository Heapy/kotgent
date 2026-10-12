import { filePath } from "./diff.ts";
import type { DiffFile } from "./diff.ts";

export type DiffTreeNode = { key: string; name: string; path: string; parent: string | null; depth: number } &
  ({ kind: "folder"; children: DiffTreeNode[]; count: number } | { kind: "file"; file: DiffFile });

/** Keep Git paths verbatim and leaves keyed by snapshot identity: two entries may share a path. */
export function diffFileTree(files: DiffFile[]): DiffTreeNode[] {
  const roots: DiffTreeNode[] = [], folders = new Map<string, Extract<DiffTreeNode, { kind: "folder" }>>();
  for (const file of files) {
    const path = filePath(file), parts = path.split("/");
    let children = roots, parent: string | null = null;
    for (let i = 0; i < parts.length - 1; i++) {
      const path = parts.slice(0, i + 1).join("/");
      let folder = folders.get(path);
      if (!folder) {
        folder = { kind: "folder", key: `d:${path}`, path, name: parts[i]!, parent, depth: i, children: [], count: 0 };
        folders.set(path, folder); children.push(folder);
      }
      folder.count++; children = folder.children; parent = folder.key;
    }
    children.push({ kind: "file", key: `f:${file.fileId}`, path, name: parts.at(-1)!, parent, depth: parts.length - 1, file });
  }
  const compare = (a: DiffTreeNode, b: DiffTreeNode) => {
    if (a.kind !== b.kind) return a.kind === "folder" ? -1 : 1;
    return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
  };
  roots.sort(compare);
  for (const folder of folders.values()) folder.children.sort(compare);
  return roots;
}

export function visibleDiffTree(roots: DiffTreeNode[], collapsed: ReadonlySet<string>): DiffTreeNode[] {
  const rows: DiffTreeNode[] = [], stack = [...roots].reverse();
  while (stack.length) {
    const node = stack.pop()!; rows.push(node);
    if (node.kind === "folder" && !collapsed.has(node.path)) {
      for (let i = node.children.length - 1; i >= 0; i--) stack.push(node.children[i]!);
    }
  }
  return rows;
}

export function revealDiffPaths(collapsed: ReadonlySet<string>, paths: Iterable<string>): ReadonlySet<string> {
  const next = new Set(collapsed);
  for (const path of paths) {
    for (let i = path.indexOf("/"); i !== -1; i = path.indexOf("/", i + 1)) next.delete(path.slice(0, i));
    if (!next.size) break;
  }
  return next;
}
