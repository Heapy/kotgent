import { test } from "node:test";
import assert from "node:assert/strict";
import { comparisonKey, diffApi, DiffReadError, filterFiles, nextUnviewedFile, splitLines } from "../../webui/src/lib/diff.ts";
import type { DiffFile, DiffLine, DiffComparison } from "../../webui/src/lib/diff.ts";

const file = (fileId: string, oldPath: string | null, newPath: string | null): DiffFile => ({ fileId, oldPath, newPath, change: "renamed", kind: "text", oldMode: "100644", newMode: "100644", additions: null, deletions: null, indexStatus: null, worktreeStatus: null, problem: null });
test("file filtering includes both rename paths, unusual names and stable case folding", () => {
  const files = [file("a", "OLD/FILE.ts", "new/<script>\t\n.ts"), file("b", null, "other")];
  assert.deepEqual(filterFiles(files, "old/file", new Set(), false), [files[0]]);
  assert.deepEqual(filterFiles(files, "\t\n", new Set(), false), [files[0]]);
  assert.deepEqual(filterFiles(files, "", new Set(["a"]), true), [files[1]]);
  assert.equal(nextUnviewedFile(files, new Set(["b"]), "b"), "a");
  assert.equal(nextUnviewedFile(files, new Set(["a", "b"]), "a"), null);
  assert.equal(nextUnviewedFile([], new Set(), null), null);
});
const line = (kind: DiffLine["kind"], text: string, oldLine: number | null, newLine: number | null): DiffLine => ({ kind, text, oldLine, newLine, noNewline: text === "last" });
test("split pairs replacement runs with empty cells without shifting across context", () => {
  const lines = [line("context", "a", 1, 1), line("deletion", "b", 2, null), line("deletion", "c", 3, null), line("addition", "d", null, 2), line("context", "e", 4, 3), line("addition", "last", null, 4)];
  const rows = splitLines(lines);
  assert.deepEqual(rows, [{left: lines[0], right: lines[0]}, {left: lines[1], right: lines[3]}, {left: lines[2], right: null}, {left: lines[4], right: lines[4]}, {left: null, right: lines[5]}]);
  assert.equal(rows[4]!.right!.noNewline, true);
  assert.deepEqual(splitLines([]), []);
});
test("resolved identity includes session, clone, worktree and all comparison endpoints", () => {
  const c: DiffComparison = { repository: {commonDir: "/a"}, worktreeId: "/w", mode: "local", scope: "all", headOid: "H", baseRef: null, baseOid: null, mergeBaseOid: null, commitOid: null, parent: null, leftOid: "H", rightOid: null };
  assert.notEqual(comparisonKey("s", c), comparisonKey("other", c));
  for (const change of [{repository: {commonDir: "/b"}}, {worktreeId: "/x"}, {scope: "staged" as const}, {headOid: "N"}, {rightOid: "X"}, {baseOid: "B"}, {parent: 2}]) assert.notEqual(comparisonKey("s", c), comparisonKey("s", {...c, ...change}));
});
test("authenticated API encodes source, pins cursor without baseRef and omits root parent", async () => {
  const original = globalThis.fetch;
  const requests: {url: string; options: RequestInit | undefined}[] = [];
  globalThis.fetch = async (url, options) => { requests.push({url: String(url), options}); return Response.json({data: {}, error: null}); };
  try {
    const a = new AbortController();
    const cursor = {headOid: "h".repeat(40), baseOid: "b".repeat(40), offset: 100};
    await diffApi.commits("s/a", "/a b&c", "refs/heads/main", cursor, a.signal);
    const url = new URL(requests[0]!.url, "http://localhost");
    assert.equal(url.pathname, "/api/v1/sessions/s%2Fa/diff/commits");
    assert.equal(url.searchParams.get("worktreeId"), "/a b&c");
    assert.deepEqual(JSON.parse(url.searchParams.get("cursor")!), cursor);
    assert.equal(url.searchParams.has("baseRef"), false);
    assert.equal(requests[0]!.options?.credentials, "same-origin");
    await diffApi.files("s", "/w", {mode: "commit", commitOid: "c".repeat(40), parent: null}, a.signal);
    assert.equal(new URL(requests[1]!.url, "http://localhost").searchParams.has("parent"), false);
    await diffApi.patch("s", "/w", "snap&", "f/?", a.signal);
    assert.equal(new URL(requests[2]!.url, "http://localhost").searchParams.get("fileId"), "f/?");
  } finally { globalThis.fetch = original; }
});
test("typed stale errors survive the common request helper's non-2xx handling", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => Response.json({data: null, error: {code: "stale", reason: "snapshot_expired", message: "Refresh"}}, {status: 409});
  try { await assert.rejects(diffApi.patch("s", "/w", "snap", "f", new AbortController().signal), e => e instanceof DiffReadError && e.problem.reason === "snapshot_expired"); }
  finally { globalThis.fetch = original; }
});
test("snapshot labels identify resolved branch endpoints independently of inventory", async () => {
  const {comparisonLabels} = await import("../../webui/src/lib/diff.ts");
  const c: DiffComparison = {repository: {commonDir: "/repo/.git"}, worktreeId: "/repo", mode: "branch", scope: null, headOid: "resolved-H", baseRef: "refs/heads/main", baseOid: "resolved-B", mergeBaseOid: "resolved-M", commitOid: null, parent: null, leftOid: "resolved-M", rightOid: "resolved-H"};
  assert.deepEqual(comparisonLabels(c), [["HEAD", "resolved-H"], ["Base ref", "refs/heads/main"], ["Base OID", "resolved-B"], ["Merge-base OID", "resolved-M"], ["Before", "resolved-M"], ["After", "resolved-H"]]);
});
