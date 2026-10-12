import { test } from "node:test";
import assert from "node:assert/strict";
import { createDiffStore, MAX_DIFF_COMMITS } from "../../webui/src/state/diff.ts";
import { DiffReadError } from "../../webui/src/lib/diff.ts";
import type { DiffPort, DiffSelection, DiffComparison, DiffFiles, DiffPatch, DiffWorktrees, DiffRefs, DiffCommits, DiffCommitCursor } from "../../webui/src/lib/diff.ts";
const tick = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const repo = {commonDir: "/repo/.git"};
function inventory(): DiffWorktrees { return {repository: repo, currentWorktreeId: "/a", readAt: 1, worktrees: ["/a", "/b"].map(id => ({id, path: id, headOid: "H", branchRef: "refs/heads/main", current: id === "/a", detached: false, bare: false, locked: false, prunable: false, unavailable: null}))}; }
function refs(w: string): DiffRefs { return {repository: repo, worktreeId: w, readAt: 1, refs: ["main", "other"].map(n => ({name: "refs/heads/" + n, oid: "B", kind: "local"})), remoteDefaultRef: null, defaultBaseRef: "refs/heads/main"}; }
function history(w: string, cursor: DiffCommitCursor | null = null): DiffCommits { return {repository: repo, worktreeId: w, readAt: 1, cursor: cursor ?? {headOid: "H", baseOid: null, offset: 0}, nextCursor: {headOid: cursor?.headOid ?? "H", baseOid: cursor?.baseOid ?? null, offset: (cursor?.offset ?? 0) + 100}, commits: ["C", "D"].map(oid => ({oid, parents: ["P"], author: "Ada", authoredAt: 0, subject: oid}))}; }
function files(w = "/a", selection: DiffSelection = {mode: "local", scope: "all"}, id = "snap"): DiffFiles {
  const comparison: DiffComparison = {repository: repo, worktreeId: w, scope: null, headOid: "H", baseRef: null, baseOid: null, mergeBaseOid: null, commitOid: null, parent: null, leftOid: "P", rightOid: "H", ...selection};
  return {snapshot: {id, comparison, readAt: 1}, limited: null, files: ["f1", "f2"].map(fileId => ({fileId, oldPath: fileId, newPath: fileId, change: "modified", kind: "text", oldMode: "100644", newMode: "100644", additions: 1, deletions: 1, indexStatus: "M", worktreeStatus: " ", problem: null}))};
}
function patch(f: DiffFiles, id = "f1"): DiffPatch { return {snapshot: f.snapshot, file: f.files.find(f => f.fileId === id)!, hunks: [], limited: null}; }
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => {resolve = a; reject = b;}); return {promise, resolve, reject}; }
function fixture(overrides: Partial<DiffPort> = {}) {
  let latest = files();
  const patches: string[] = [];
  const port: DiffPort = {worktrees: async () => inventory(), refs: async (_, w) => refs(w), commits: async (_, w, _b, c) => history(w, c), files: async (_, w, s) => { latest = files(w, s); return latest; }, patch: async (_, _w, _s, id) => { patches.push(id); return patch(latest, id); }, ...overrides};
  const store = createDiffStore(port);
  return {store, port, patches, state: () => store.state("s").value};
}
test("first load requests only selected patch; remount keeps Viewed, filter, page and scroll", async () => {
  const f = fixture(); const release = f.store.retain("s"); await tick();
  assert.equal(f.state().files.state, "ready"); assert.deepEqual(f.patches, ["f1"]);
  f.store.selectFile("s", "f2"); await tick(); f.store.markViewed("s", "f2", true);
  f.store.reading("s", {query: "f", scrollTop: 123, filesScrollTop: 44, layout: "split", wrap: true});
  release(); const again = f.store.retain("s"); await tick();
  assert.equal(f.state().selected, "f2"); assert.equal(f.state().scrollTop, 123); assert.equal(f.state().filesScrollTop, 44);
  assert.equal(f.state().page, "patch"); assert.equal(f.state().query, "f"); assert.equal(f.state().viewed.has("f2"), true);
  assert.deepEqual(f.patches, ["f1", "f2", "f2"]); again();
});
test("old worktree file response cannot overwrite new source even if transport ignores abort", async () => {
  const old = deferred<DiffFiles>(); let oldSignal: AbortSignal | null = null;
  const f = fixture({files: async (_, w, s, a) => { if (w === "/a") { oldSignal = a; return old.promise; } return files(w, s); }, patch: async (_, w, _s, id) => patch(files(w), id)});
  f.store.retain("s"); await tick(); f.store.choose("s", {worktree: "/b"}); await tick();
  assert.equal((oldSignal as AbortSignal | null)?.aborted, true);
  old.resolve(files()); await tick();
  assert.equal(f.state().files.data?.snapshot.comparison.worktreeId, "/b");
});
test("base and commit changes reject late comparison responses", async () => {
  for (const mode of ["branch", "commit"] as const) {
    const pending = deferred<DiffFiles>();
    const f = fixture({ files: async (_, w, s) => (s.mode === "branch" && s.baseRef.endsWith("main")) || (s.mode === "commit" && s.commitOid === "C") ? pending.promise : files(w, s), patch: async () => {throw new Error("unused");} });
    f.store.retain("s"); await tick(); f.store.choose("s", {mode}); await tick();
    if (mode === "commit") f.store.choose("s", {commitOid: "C", parent: 1});
    await tick();
    f.store.choose("s", mode === "branch" ? {baseRef: "refs/heads/other"} : {commitOid: "D", parent: 1}); await tick();
    pending.resolve(files("/a", mode === "branch" ? {mode, baseRef: "refs/heads/main"} : {mode, commitOid: "C", parent: 1})); await tick();
    assert.equal(mode === "branch" ? f.state().files.data?.snapshot.comparison.baseRef : f.state().files.data?.snapshot.comparison.commitOid, mode === "branch" ? "refs/heads/other" : "D");
  }
});
test("refresh while pending aborts old read and only new snapshot becomes ready", async () => {
  const first = deferred<DiffFiles>(), second = deferred<DiffFiles>(); let reads = 0;
  const f = fixture({files: async () => ++reads === 1 ? first.promise : second.promise, patch: async () => patch(files("/a", {mode: "local", scope: "all"}, "new"))});
  f.store.retain("s"); await tick(); const refresh = f.store.refresh("s"); await tick();
  first.resolve(files("/a", {mode: "local", scope: "all"}, "old")); await tick();
  assert.equal(f.state().files.data, null); assert.equal(f.state().files.loading, true);
  second.resolve(files("/a", {mode: "local", scope: "all"}, "new")); await refresh;
  assert.equal(f.state().files.data?.snapshot.id, "new");
});
test("initial failures are failed; failed revalidation keeps marked stale last-good data", async () => {
  let fail = true;
  const f = fixture({worktrees: async () => {if (fail) throw new Error("Offline"); return inventory();}});
  f.store.retain("s"); await tick(); assert.equal(f.state().inventory.state, "failed"); assert.equal(f.state().files.data, null);
  fail = false; await f.store.refresh("s"); f.store.markViewed("s", "f1", true);
  const previous = f.state().patch.data; fail = true; await f.store.refresh("s");
  assert.equal(f.state().inventory.state, "ready"); assert.equal(f.state().inventory.error, "Offline");
  assert.equal(f.state().patch.data, previous); assert.equal(f.state().patch.stale, true); assert.equal(f.state().viewed.size, 0);
});
test("stale snapshot clears Viewed and requires explicit Refresh", async () => {
  let fail = false;
  const f = fixture({patch: async (_, w, _s, id) => { if (fail) throw new DiffReadError({code: "stale", reason: "snapshot_changed", message: "Refresh"}); return patch(files(w), id); }});
  f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true); fail = true;
  f.store.selectFile("s", "f2"); await tick();
  assert.equal(f.state().viewed.size, 0); assert.equal(f.state().files.stale, true); assert.equal(f.state().patch.problem?.reason, "snapshot_changed");
  f.store.markViewed("s", "f2", true); assert.equal(f.state().viewed.size, 0);
  fail = false; await f.store.refresh("s"); assert.equal(f.state().files.stale, false);
});
test("selected patch rejects late replies and wrong resolved identities", async () => {
  const old = deferred<DiffPatch>();
  const f = fixture({patch: async (_, w, _s, id) => id === "f1" ? old.promise : patch(files(w), id)});
  f.store.retain("s"); await tick(); f.store.selectFile("s", "f2"); await tick(); old.resolve(patch(files())); await tick();
  assert.equal(f.state().patch.data?.file.fileId, "f2");
  const bad = fixture({patch: async () => patch(files("/b"))}); bad.store.retain("s"); await tick();
  assert.equal(bad.state().patch.state, "failed"); assert.equal(bad.state().patch.data, null);
});
test("hidden pending reads cannot publish and visibility/reconnect explicitly revalidate", async () => {
  const old = deferred<DiffFiles>(); let count = 0;
  const f = fixture({files: async (_, w, s) => ++count === 1 ? old.promise : files(w, s)});
  const release = f.store.retain("s"); await tick(); release(); old.resolve(files()); await tick();
  assert.equal(f.state().files.data, null);
  f.store.retain("s"); await tick(); assert.equal(count, 2);
  f.store.setVisible(false); f.store.recover(); await tick(); assert.equal(count, 2);
  f.store.setVisible(true); await tick(); assert.equal(count, 3);
  f.store.recover(); await tick(); assert.equal(count, 4);
});
test("history pagination retains fixed cursor and omits base from API transport; capped history", async () => {
  const calls: (DiffCommitCursor | null)[] = [];
  const f = fixture({commits: async (_, w, _b, c) => {calls.push(c); return {...history(w, c), commits: c ? [{oid: "E", parents: [], author: "a", authoredAt: 0, subject: "root"}] : history(w).commits};}});
  f.store.retain("s"); await tick(); f.store.choose("s", {mode: "commit"}); await tick(); await f.store.more("s");
  assert.deepEqual(calls, [null, {headOid: "H", baseOid: null, offset: 100}]);
  assert.deepEqual(f.state().history.data?.commits.map(c => c.oid), ["C", "D", "E"]);
  f.store.choose("s", {commitOid: "E", parent: 1}); await tick(); assert.equal(f.state().files.data?.snapshot.comparison.parent, null);
  const capped = fixture({commits: async (_, w) => ({...history(w), commits: Array.from({length: MAX_DIFF_COMMITS + 2}, (_, i) => ({oid: String(i), parents: [], author: "a", authoredAt: 0, subject: "s"}))})});
  capped.store.retain("s"); await tick(); capped.store.choose("s", {mode: "commit"}); await tick();
  assert.equal(capped.state().history.data?.commits.length, MAX_DIFF_COMMITS);
});
test("comparison changes reset Viewed and next respects filters; unmounted cache is bounded", async () => {
  const f = fixture(); f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true);
  f.store.reading("s", {query: "f2"}); f.store.next("s"); await tick(); assert.equal(f.state().selected, "f2");
  f.store.choose("s", {scope: "staged"}); await tick(); assert.equal(f.state().viewed.size, 0);
  const cache = createDiffStore(f.port, 2); cache.state("a"); cache.state("b"); cache.state("c"); assert.equal(cache.size(), 2);
});
test("failed patch revalidation retains last-good patch under a stale label", async () => {
  let fail = false;
  const f = fixture({patch: async () => { if (fail) throw new Error("Patch offline"); return patch(files()); }});
  f.store.retain("s"); await tick(); const last = f.state().patch.data;
  fail = true; await f.store.refresh("s");
  assert.equal(f.state().patch.state, "ready"); assert.equal(f.state().patch.data, last);
  assert.equal(f.state().patch.stale, true); assert.equal(f.state().patch.error, "Patch offline");
});
test("remount during selected patch request preserves prior Viewed marks", async () => {
  const pending = deferred<DiffPatch>(); let attempts = 0;
  const f = fixture({patch: async (_, w, _s, id) => id === "f2" && ++attempts === 1 ? pending.promise : patch(files(w), id)});
  const release = f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true);
  f.store.selectFile("s", "f2"); await tick(); release(); f.store.retain("s"); await tick();
  assert.equal(f.state().viewed.has("f1"), true); assert.equal(f.state().patch.data?.file.fileId, "f2");
  pending.resolve(patch(files(), "f2")); await tick(); assert.equal(f.state().viewed.has("f1"), true);
});
test("same worktree path in another repository discards old commit and patch", async () => {
  let changed = false;
  const f = fixture({worktrees: async () => ({...inventory(), repository: changed ? {commonDir: "/new/.git"} : repo})});
  f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true); changed = true; await f.store.refresh("s");
  assert.equal(f.state().patch.data, null); assert.equal(f.state().viewed.size, 0); assert.equal(f.state().refs.state, "failed");
});
test("late response from another session cannot affect the active session state", async () => {
  const old = deferred<DiffWorktrees>();
  const f = fixture({worktrees: async s => s === "old" ? old.promise : inventory()});
  f.store.retain("old"); f.store.retain("s"); await tick(); const current = f.state();
  old.resolve({...inventory(), repository: {commonDir: "/elsewhere"}}); await tick(); assert.equal(f.state(), current);
});
test("wrong pinned history page fails without discarding already loaded history", async () => {
  const f = fixture({commits: async (_, w, _b, c) => history(w, c ? {...c, headOid: "moved"} : null)});
  f.store.retain("s"); await tick(); f.store.choose("s", {mode: "commit"}); await tick(); const first = f.state().history.data;
  await f.store.more("s"); assert.equal(f.state().history.data, first); assert.equal(f.state().history.stale, true);
});
test("changing mode before inventory resolves restarts discovery for that choice", async () => {
  const pending = deferred<DiffWorktrees>(); let count = 0;
  const f = fixture({worktrees: async () => ++count === 1 ? pending.promise : inventory()});
  f.store.retain("s"); f.store.choose("s", {mode: "branch"}); await tick();
  assert.equal(f.state().files.data?.snapshot.comparison.mode, "branch");
  pending.resolve(inventory()); await tick(); assert.equal(f.state().mode, "branch");
});
test("root metadata survives a history refresh which no longer lists the selected commit", async () => {
  let moved = false;
  const f = fixture({commits: async (_, w) => ({...history(w), commits: moved ? history(w).commits : [{oid: "root", parents: [], author: "Ada", authoredAt: 0, subject: "Root"}]})});
  f.store.retain("s"); await tick(); f.store.choose("s", {mode: "commit"}); await tick();
  f.store.choose("s", {commitOid: "root", parent: 1}); await tick(); moved = true;
  await f.store.refresh("s"); await f.store.refresh("s");
  assert.equal(f.state().commit?.oid, "root"); assert.equal(f.state().files.data?.snapshot.comparison.parent, null);
});
test("selecting the same file during revalidation does not abort the replacement patch", async () => {
  const pending = deferred<DiffPatch>(); let count = 0;
  const f = fixture({patch: async () => ++count === 1 ? patch(files()) : pending.promise});
  f.store.retain("s"); await tick(); const refresh = f.store.refresh("s"); await tick();
  f.store.selectFile("s", "f1"); assert.equal(f.state().patch.loading, true);
  pending.resolve(patch(files())); await refresh; assert.equal(f.state().patch.stale, false);
});
test("branch-history activation atomically opens root and merge commits", async () => {
  for (const parents of [[], ["P1", "P2"]]) {
    const requests: DiffSelection[] = [];
    const f = fixture({commits: async (_, w) => ({...history(w), commits: [{oid: "chosen", parents, author: "Ada", authoredAt: 0, subject: "Selected"}]}), files: async (_, w, s) => {requests.push(s); return files(w, s);}});
    f.store.retain("s"); await tick(); f.store.choose("s", {mode: "branch"}); await tick();
    f.store.choose("s", {mode: "commit", commitOid: "chosen", parent: 1}); await tick();
    assert.equal(f.state().mode, "commit"); assert.equal(f.state().commitOid, "chosen");
    assert.equal(f.state().commit?.author, "Ada");
    assert.deepEqual(requests.at(-1), {mode: "commit", commitOid: "chosen", parent: parents.length ? 1 : null});
    if (parents.length) { f.store.choose("s", {parent: 2}); await tick(); assert.deepEqual(requests.at(-1), {mode: "commit", commitOid: "chosen", parent: 2}); }
  }
});
test("refresh gates old pagination while discovery changes the default base", async () => {
  const discovery = deferred<DiffWorktrees>(), oldPage = deferred<DiffCommits>();
  const calls: {base: string | null; cursor: DiffCommitCursor | null}[] = [];
  let refreshing = false;
  const f = fixture({worktrees: async () => refreshing ? discovery.promise : inventory(), refs: async (_, w) => refreshing ? {...refs(w), refs: refs(w).refs.filter(r => r.name.endsWith("other")), defaultBaseRef: "refs/heads/other"} : refs(w), commits: async (_, w, base, cursor) => {calls.push({base, cursor}); return cursor ? oldPage.promise : {...history(w), cursor: {headOid: "H", baseOid: base, offset: 0}, nextCursor: {headOid: "H", baseOid: base, offset: 100}};}});
  f.store.retain("s"); await tick(); f.store.choose("s", {mode: "branch"}); await tick();
  const more = f.store.more("s"); await tick(); refreshing = true; const refresh = f.store.refresh("s"); await tick();
  await f.store.more("s"); assert.equal(calls.length, 2);
  discovery.resolve(inventory()); await refresh;
  oldPage.resolve(history("/a", {headOid: "H", baseOid: "refs/heads/main", offset: 100})); await more;
  assert.deepEqual(calls.at(-1), {base: "refs/heads/other", cursor: null});
  assert.equal(f.state().baseRef, "refs/heads/other"); assert.equal(f.state().history.data?.cursor?.baseOid, "refs/heads/other");
  assert.equal(f.state().history.stale, false);
});
test("remount restarts an interrupted first-page refresh instead of paging old history", async () => {
  const pending = deferred<DiffCommits>(), calls: (DiffCommitCursor | null)[] = [];
  let head = "H";
  const f = fixture({worktrees: async () => ({...inventory(), worktrees: inventory().worktrees.map(w => ({...w, headOid: head}))}), commits: async (_, w, _b, cursor) => {
    calls.push(cursor); if (calls.length === 2) return pending.promise;
    return {...history(w), cursor: {headOid: head, baseOid: null, offset: 0}, nextCursor: {headOid: head, baseOid: null, offset: 100}};
  }});
  const release = f.store.retain("s"); await tick(); f.store.choose("s", {mode: "commit"}); await tick();
  head = "H2"; const refresh = f.store.refresh("s"); await tick();
  assert.equal(f.state().history.loading, true); assert.equal(f.state().inventory.loading, false); assert.equal(f.state().refs.loading, false);
  release(); f.store.retain("s"); await tick();
  assert.deepEqual(calls, [null, null, null]); assert.equal(f.state().history.data?.cursor?.headOid, "H2");
  pending.resolve(history("/a")); await refresh; assert.equal(f.state().history.data?.cursor?.headOid, "H2");
});
test("remount resumes interrupted pagination when its pinned history remains current", async () => {
  const pending = deferred<DiffCommits>(), calls: (DiffCommitCursor | null)[] = [];
  const f = fixture({commits: async (_, w, _b, cursor) => {calls.push(cursor); return calls.length === 2 ? pending.promise : history(w, cursor);}});
  const release = f.store.retain("s"); await tick(); f.store.choose("s", {mode: "commit"}); await tick();
  const more = f.store.more("s"); await tick(); release(); f.store.retain("s"); await tick();
  assert.deepEqual(calls, [null, {headOid: "H", baseOid: null, offset: 100}, {headOid: "H", baseOid: null, offset: 100}]);
  pending.resolve(history("/a", {headOid: "H", baseOid: null, offset: 100})); await more;
});
test("settled remount detects changed local bytes and clears Viewed even with identical metadata", async () => {
  let changed = false;
  const f = fixture({files: async () => files("/a", {mode: "local", scope: "all"}, changed ? "new" : "snap"), patch: async (_, _w, snapshot, id) => {
    if (changed && snapshot === "snap") throw new DiffReadError({code: "stale", reason: "snapshot_changed", message: "Bytes changed"});
    return patch(files("/a", {mode: "local", scope: "all"}, snapshot), id);
  }});
  const release = f.store.retain("s"); await tick(); f.store.selectFile("s", "f2"); await tick();
  f.store.markViewed("s", "f2", true); f.store.reading("s", {query: "f", scrollTop: 42}); release(); changed = true;
  f.store.retain("s"); await tick();
  assert.equal(f.state().files.data?.snapshot.id, "new"); assert.equal(f.state().patch.data?.snapshot.id, "new");
  assert.equal(f.state().viewed.size, 0); assert.equal(f.state().query, "f"); assert.equal(f.state().selected, "f2"); assert.equal(f.state().scrollTop, 42);
  assert.equal(f.state().files.stale, false);
});
test("explicit refresh resets Viewed even when content is unchanged", async () => {
  const f = fixture(); f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true);
  await f.store.refresh("s"); assert.equal(f.state().viewed.size, 0);
});
test("stale patch endpoint labels remain on its snapshot after file-list HEAD advances", async () => {
  const {comparisonLabels} = await import("../../webui/src/lib/diff.ts");
  let advanced = false;
  const f = fixture({files: async () => {const data = files(); if (advanced) data.snapshot.comparison.headOid = "H2"; return data;}, patch: async () => {if (advanced) throw new Error("Patch offline"); return patch(files());}});
  f.store.retain("s"); await tick(); advanced = true; await f.store.refresh("s");
  assert.equal(f.state().patch.stale, true);
  assert.deepEqual(comparisonLabels(f.state().patch.data!.snapshot.comparison)[0], ["HEAD", "H"]);
  assert.deepEqual(comparisonLabels(f.state().files.data!.snapshot.comparison)[0], ["HEAD", "H2"]);
});
test("file activation during snapshot revalidation does not abandon the validation read", async () => {
  const pending = deferred<DiffPatch>(); let reads = 0;
  const f = fixture({patch: async () => ++reads === 1 ? patch(files()) : pending.promise});
  const release = f.store.retain("s"); await tick(); release(); f.store.retain("s"); await tick();
  f.store.selectFile("s", "f2"); assert.equal(f.state().selected, "f1");
  pending.resolve(patch(files())); await tick(); assert.equal(f.state().files.stale, false);
});
test("repeated remount preserves Viewed while inventory or refs revalidation is pending", async () => {
  for (const resource of ["inventory", "refs"] as const) {
    const pendingInventory = deferred<DiffWorktrees>(), pendingRefs = deferred<DiffRefs>();
    let inventoryReads = 0, refsReads = 0;
    const f = fixture({worktrees: async () => ++inventoryReads === 2 && resource === "inventory" ? pendingInventory.promise : inventory(), refs: async (_, w) => ++refsReads === 2 && resource === "refs" ? pendingRefs.promise : refs(w)});
    const first = f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true);
    f.store.reading("s", {query: "f", scrollTop: 31}); first();
    const second = f.store.retain("s"); await tick(); assert.equal(f.state()[resource].loading, true);
    assert.equal(f.state().viewed.has("f1"), true); second();
    f.store.retain("s"); assert.equal(f.state().viewed.has("f1"), true); await tick();
    assert.equal(f.state().viewed.has("f1"), true); assert.equal(f.state().scrollTop, 31); assert.equal(f.state().query, "f");
    pendingInventory.resolve(inventory()); pendingRefs.resolve(refs("/a")); await tick();
    assert.equal(f.state().viewed.has("f1"), true); assert.equal(f.state().files.stale, false);
  }
});
test("repeated remount does not turn an explicit resetting Refresh into preserving validation", async () => {
  const pending = deferred<DiffWorktrees>(); let reads = 0, fileReads = 0;
  const f = fixture({worktrees: async () => ++reads === 2 ? pending.promise : inventory(), files: async (_, w, s) => {fileReads++; return files(w, s);}});
  const release = f.store.retain("s"); await tick(); f.store.markViewed("s", "f1", true);
  const refresh = f.store.refresh("s"); await tick(); assert.equal(f.state().viewed.size, 0);
  release(); f.store.retain("s"); await tick(); assert.equal(fileReads, 2); assert.equal(f.state().viewed.size, 0);
  pending.resolve(inventory()); await refresh; assert.equal(f.state().viewed.size, 0);
});
