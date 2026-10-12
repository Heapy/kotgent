import { test } from "node:test";
import assert from "node:assert/strict";
import { diffFileTree, visibleDiffTree, revealDiffPaths } from "../../webui/src/lib/diff-tree.ts";
import { filterFiles } from "../../webui/src/lib/diff.ts";
import type { DiffFile } from "../../webui/src/lib/diff.ts";
const file = (id: string, path: string, oldPath: string | null = null): DiffFile => ({ fileId: id, newPath: path, oldPath, change: "modified", kind: "text", oldMode: "100644", newMode: "100644", additions: 1, deletions: 1, indexStatus: "M", worktreeStatus: " ", problem: null });

test("tree sorts folders before files, counts descendants, and preserves duplicate paths and file/directory collisions", () => {
  const files = [file("1", "z.txt"), file("2", "src/z.ts"), file("3", "src/a/a.ts"), file("4", "src/a/a.ts"), file("5", "src")];
  const tree = diffFileTree(files), rows = visibleDiffTree(tree, new Set());
  assert.deepEqual(rows.map(n => n.key), ["d:src", "d:src/a", "f:3", "f:4", "f:2", "f:5", "f:1"]);
  assert.equal(tree[0]!.kind === "folder" && tree[0]!.count, 3);
  assert.equal(rows[2]!.parent, "d:src/a");
  assert.equal(rows[2]!.depth, 2);
  assert.deepEqual(visibleDiffTree(tree, new Set(["src"])).map(n => n.key), ["d:src", "f:5", "f:1"]);
  assert.deepEqual(visibleDiffTree(tree, new Set(["src/a"])).map(n => n.key), ["d:src", "d:src/a", "f:2", "f:5", "f:1"]);
});
test("rename filtering keeps the new hierarchy, deletion uses the old hierarchy, unusual Git paths stay literal", () => {
  const renamed = file("r", "new/<script>\t\n.ts", "OLD/I.ts");
  const deleted = { ...file("d", "unused"), newPath: null, oldPath: "removed/gone.ts" };
  const filtered = filterFiles([renamed, deleted], "old/i", new Set(), false);
  const rows = visibleDiffTree(diffFileTree(filtered), new Set());
  assert.deepEqual(rows.map(n => n.path), ["new", "new/<script>\t\n.ts"]);
  assert.equal(rows[1]!.kind === "file" && rows[1]!.file.oldPath, "OLD/I.ts");
  assert.deepEqual(visibleDiffTree(diffFileTree([deleted]), new Set()).map(n => n.path), ["removed", "removed/gone.ts"]);
  assert.deepEqual(diffFileTree(filterFiles([renamed], "", new Set(["r"]), true)), []);
});
test("revealing a file expands only exact ancestor folders without changing the input", () => {
  const collapsed = new Set(["src", "src/a", "src/ab", "other"]);
  assert.deepEqual([...revealDiffPaths(collapsed, ["src/a/f.ts"])], ["src/ab", "other"]);
  assert.equal(collapsed.size, 4);
  assert.deepEqual(revealDiffPaths(collapsed, ["root.ts"]), collapsed);
});
test("maximum file inventory remains distinct", () => {
  const files = Array.from({length: 5000}, (_, i) => file(String(i), `src/f${i}.ts`));
  assert.equal(visibleDiffTree(diffFileTree(files), new Set()).length, 5001);
});
