// The daemon owns filesystem identity; the project catalogue owns names and availability.
import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { apiRequest } from "../lib/api.ts";
import { createSerialRefresh } from "../lib/refresh.ts";

export type FolderLookup = { path: string; status: "found" | "absent" | "failed"; projectId?: string | null };
const BATCH_SIZE = 64; // PROJECT_LOOKUP_MAX_PATHS on the daemon.
const RETAINED_PATHS = 512;

export function createFolderProjectStore(
  read: (paths: readonly string[], signal: AbortSignal) => Promise<readonly FolderLookup[]>,
) {
  const state = signal<ReadonlyMap<string, string>>(new Map());
  const cache = new Map<string, string | null>();
  const observers = new Map<symbol, readonly string[]>();
  let paths: readonly string[] = [];
  let request: { paths: readonly string[]; abort: AbortController } | null = null;
  let pending: Promise<unknown> | null = null;
  const samePaths = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((path, i) => path === b[i]);

  function publish() {
    const rows = new Map<string, string>();
    for (const path of paths) {
      if (!cache.has(path)) continue;
      const id = cache.get(path)!;
      cache.delete(path);
      cache.set(path, id); // Touch active entries so unused entries are evicted oldest first.
      if (id) rows.set(path, id);
    }
    state.value = rows;
    const active = new Set(paths);
    for (const path of cache.keys()) {
      if (cache.size <= Math.max(RETAINED_PATHS, active.size)) break;
      if (!active.has(path)) cache.delete(path);
    }
  }

  const queue = createSerialRefresh({
    read: async () => {
      const current = request;
      if (!current) return null;
      const rows: FolderLookup[] = [];
      for (let start = 0; start < current.paths.length; start += BATCH_SIZE) {
        current.abort.signal.throwIfAborted();
        try {
          rows.push(...await read(current.paths.slice(start, start + BATCH_SIZE), current.abort.signal));
        } catch (_) { current.abort.signal.throwIfAborted(); } // A failed batch preserves its cached identities.
      }
      current.abort.signal.throwIfAborted();
      return { current, rows };
    },
    begin: () => undefined,
    succeed: (result) => {
      if (!result || result.current.abort.signal.aborted) return;
      for (const path of result.current.paths) {
        const row = result.rows.find((row) => row?.path === path);
        if (row?.status === "absent") cache.set(path, null);
        else if (row?.status === "found" && row.projectId) cache.set(path, row.projectId);
      }
      publish();
    },
    fail: () => {},
    report: () => {},
  });

  function load(requested: readonly string[], force = false): Promise<unknown> {
    if (!force && pending && request && samePaths(request.paths, requested)) return pending;
    request?.abort.abort();
    request = null;
    pending = null;
    if (requested.length === 0) return Promise.resolve();
    const current = { paths: requested, abort: new AbortController() };
    request = current;
    const result = queue(false);
    pending = result;
    void result.finally(() => { if (request === current) { request = null; pending = null; } });
    return result;
  }

  function updatePaths() {
    const next = [...new Set([...observers.values()].flat())].sort();
    if (samePaths(next, paths)) return;
    const added = next.filter((path) => !paths.includes(path) || !cache.has(path) || request?.paths.includes(path));
    paths = next;
    publish();
    void load(added);
  }

  function observe() {
    const owner = Symbol();
    let disposed = false;
    return {
      setPaths(observed: readonly string[]) {
        if (disposed) return;
        observers.set(owner, [...observed]);
        updatePaths();
      },
      dispose() {
        if (disposed) return;
        disposed = true;
        observers.delete(owner);
        updatePaths();
      },
    };
  }

  function invalidate() {
    // Membership changes can register/restore absent projects. Retain only the visible fallback values.
    for (const path of cache.keys()) if (!paths.includes(path)) cache.delete(path);
    void load(paths, true);
  }

  return { projects: state as ReadonlySignal<ReadonlyMap<string, string>>, observe, refresh: () => load(paths), invalidate };
}

export const folderProjectStore = createFolderProjectStore(async (paths, signal) => {
  const result = await apiRequest<FolderLookup[]>("/projects/resolve", {
    method: "POST", body: JSON.stringify({ paths }), signal,
  });
  if (!Array.isArray(result)) throw new Error("Invalid folder project response");
  return result;
});
export const folderProjects = folderProjectStore.projects;
