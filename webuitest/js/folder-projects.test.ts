import { test } from "node:test";
import assert from "node:assert/strict";
import { createFolderProjectStore } from "../../webui/src/state/folder-projects.ts";
import type { FolderLookup } from "../../webui/src/state/folder-projects.ts";
import { deferred, flush } from "./fixtures.ts";

const found = (path: string, id = path): FolderLookup => ({ path, status: "found", projectId: id });
function fixture() {
  const reads: { paths: readonly string[]; signal: AbortSignal; response: ReturnType<typeof deferred<readonly FolderLookup[]>> }[] = [];
  const store = createFolderProjectStore((paths, signal) => {
    const response = deferred<readonly FolderLookup[]>();
    reads.push({ paths, signal, response });
    return response.promise; // Ignores abort deliberately to test obsolete answers.
  });
  const observer = store.observe();
  return { store: { ...store, setPaths: observer.setPaths }, reads };
}

test("regrouping preserves known identities and reads newly observed paths", async () => {
  const { store, reads } = fixture();
  store.setPaths(["/alpha"]);
  reads[0]!.response.resolve([found("/alpha")]);
  await flush();
  store.setPaths(["/alpha", "/beta"]);
  assert.deepEqual([...store.projects.value], [["/alpha", "/alpha"]]);
  assert.deepEqual(reads[1]!.paths, ["/beta"]);
  reads[1]!.response.resolve([found("/beta")]);
  await flush();
  store.setPaths([]);
  store.setPaths(["/alpha"]);
  assert.deepEqual([...store.projects.value], [["/alpha", "/alpha"]], "returning immediately displays the cached identity");
  assert.deepEqual(reads[2]!.paths, ["/alpha"], "returning observes the path again without consulting a clock");
  reads[2]!.response.resolve([found("/alpha", "updated")]);
  await flush();
  assert.equal(store.projects.value.get("/alpha"), "updated");
});

test("one failed lookup retains its identity while successes and confirmed absences apply", async () => {
  const { store, reads } = fixture();
  store.setPaths(["/a", "/b", "/c"]);
  reads[0]!.response.resolve([found("/a"), found("/b"), found("/c")]);
  await flush();
  const refresh = store.refresh();
  reads[1]!.response.resolve([
    { path: "/a", status: "failed" }, found("/b", "renamed-identity"), { path: "/c", status: "absent" },
  ]);
  await refresh;
  assert.deepEqual([...store.projects.value], [["/a", "/a"], ["/b", "renamed-identity"]]);
  await flush();
  assert.equal(reads.length, 2, "errors do not schedule automatic retries");
  const retry = store.refresh();
  assert.deepEqual(reads[2]!.paths, ["/a", "/b", "/c"]);
  reads[2]!.response.resolve([{ path: "/a", status: "absent" }, found("/b", "renamed-identity"), { path: "/c", status: "absent" }]);
  await retry;
  assert.deepEqual([...store.projects.value], [["/b", "renamed-identity"]]);
});

test("transport failure keeps known identities and cannot turn missing rows into absence", async () => {
  const { store, reads } = fixture();
  store.setPaths(["/a"]);
  reads[0]!.response.resolve([found("/a")]);
  await flush();
  const refresh = store.refresh();
  reads[1]!.response.reject(new Error("offline"));
  await refresh;
  assert.equal(store.projects.value.get("/a"), "/a");
  const retry = store.refresh();
  reads[2]!.response.resolve([]);
  await retry;
  assert.equal(store.projects.value.get("/a"), "/a", "an incomplete response is a failure, not authoritative absence");
});

test("every foreground refresh rereads found and absent identities and coalesces in-flight work", async () => {
  const { store, reads } = fixture();
  store.setPaths(["/a", "/none"]);
  const first = store.refresh();
  assert.equal(store.refresh(), first);
  assert.equal(reads[0]!.signal.aborted, false);
  reads[0]!.response.resolve([found("/a"), { path: "/none", status: "absent" }]);
  await first;
  await flush();
  assert.equal(reads.length, 1);
  const next = store.refresh();
  assert.deepEqual(reads[1]!.paths, ["/a", "/none"]);
  reads[1]!.response.resolve([found("/a"), found("/none")]);
  await next;
  assert.equal(store.projects.value.size, 2);
});

test("obsolete responses cannot publish after regrouping, invalidation or leaving", async () => {
  const { store, reads } = fixture();
  store.setPaths(["/a"]);
  store.setPaths(["/b"]);
  assert.equal(reads[0]!.signal.aborted, true);
  reads[0]!.response.resolve([found("/a")]);
  await flush();
  assert.equal(store.projects.value.size, 0);
  reads[1]!.response.resolve([found("/b")]);
  await flush();
  store.invalidate();
  store.setPaths([]);
  reads[2]!.response.resolve([found("/b", "stale")]);
  await flush();
  assert.equal(store.projects.value.size, 0);
  store.setPaths(["/b"]);
  assert.equal(store.projects.value.get("/b"), "/b");
  const refresh = store.refresh();
  reads[3]!.response.resolve([found("/b", "fresh")]);
  await refresh;
  assert.equal(store.projects.value.get("/b"), "fresh");
});

test("large folder sets use bounded serial batches and keep successes when another batch fails", async () => {
  const { store, reads } = fixture();
  const paths = Array.from({ length: 65 }, (_, i) => `/folder-${i}`);
  store.setPaths(paths);
  assert.equal(reads[0]!.paths.length, 64);
  reads[0]!.response.resolve(reads[0]!.paths.map((path) => found(path)));
  await flush();
  assert.equal(reads[1]!.paths.length, 1);
  reads[1]!.response.reject(new Error("temporary failure"));
  await flush();
  assert.equal(store.projects.value.size, 64);
});


test("membership invalidation discards inactive cache entries and supersedes an in-flight read", async () => {
  const { store, reads } = fixture();
  store.setPaths(["/a", "/b"]);
  reads[0]!.response.resolve([found("/a"), found("/b")]);
  await flush();
  store.setPaths(["/a"]);
  const old = store.refresh();
  store.invalidate();
  assert.equal(reads[1]!.signal.aborted, true);
  reads[1]!.response.resolve([found("/a", "obsolete")]);
  await flush();
  assert.equal(store.projects.value.get("/a"), "/a");
  reads[2]!.response.resolve([{ path: "/a", status: "absent" }]);
  await old;
  await flush();
  assert.equal(store.projects.value.size, 0);
  store.setPaths(["/b"]);
  assert.equal(store.projects.value.size, 0, "inactive identity was invalidated too");
  reads[3]!.response.resolve([found("/b", "restored")]);
  await flush();
  assert.equal(store.projects.value.get("/b"), "restored");
});

test("bounded LRU keeps recently used identities and evicts unused ones", async () => {
  const source = createFolderProjectStore(async (paths) => paths.map((path) => found(path)));
  const store = { ...source, setPaths: source.observe().setPaths };
  const paths = Array.from({ length: 512 }, (_, i) => `/folder-${i}`);
  store.setPaths(paths);
  await store.refresh();
  store.setPaths([paths[0]!]);
  store.setPaths(["/new"]);
  await store.refresh();
  store.setPaths([paths[0]!, paths[1]!]);
  assert.equal(store.projects.value.get(paths[0]!), paths[0], "the touched identity survived");
  assert.equal(store.projects.value.has(paths[1]!), false, "the least recently used identity was evicted");
  await store.refresh();
  assert.equal(store.projects.value.size, 2);
});

test("independent observers retain shared paths and release only their own paths", async () => {
  const { store, reads } = fixture();
  const first = store.observe();
  const second = store.observe();
  first.setPaths(["/shared", "/first"]);
  reads[0]!.response.resolve([found("/shared"), found("/first")]);
  await flush();
  second.setPaths(["/shared", "/second"]);
  assert.deepEqual(reads[1]!.paths, ["/second"]);
  reads[1]!.response.resolve([found("/second")]);
  await flush();
  first.dispose();
  first.dispose();
  first.setPaths(["/released"]);
  assert.deepEqual([...store.projects.value.keys()], ["/second", "/shared"]);
  const refresh = store.refresh();
  assert.deepEqual(reads[2]!.paths, ["/second", "/shared"]);
  second.setPaths(["/shared"]);
  reads[2]!.response.resolve([found("/shared", "obsolete")]);
  await flush();
  assert.deepEqual(reads[3]!.paths, ["/shared"], "remaining observer keeps its pending refresh");
  reads[3]!.response.resolve([found("/shared", "updated")]);
  await refresh;
  assert.equal(store.projects.value.get("/shared"), "updated");
  second.dispose();
  assert.equal(store.projects.value.size, 0);
});

test("releasing an overlapping observer leaves another observer's request running", async () => {
  const { store, reads } = fixture();
  const first = store.observe();
  const second = store.observe();
  first.setPaths(["/shared"]);
  second.setPaths(["/shared"]);
  first.dispose();
  assert.equal(reads.length, 1);
  assert.equal(reads[0]!.signal.aborted, false);
  reads[0]!.response.resolve([found("/shared")]);
  await flush();
  assert.equal(store.projects.value.get("/shared"), "/shared");
  second.dispose();
});
