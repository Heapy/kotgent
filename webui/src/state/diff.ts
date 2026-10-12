import { signal } from "@preact/signals-core";
import type { ReadonlySignal, Signal } from "@preact/signals-core";
import { diffApi, comparisonKey, DiffReadError, filterFiles, nextUnviewedFile } from "../lib/diff.ts";
import type { DiffPort, DiffMode, DiffLocalScope, DiffSelection, DiffWorktrees, DiffRefs, DiffCommits, DiffFiles, DiffPatch, DiffProblem, DiffCommit } from "../lib/diff.ts";

export interface DiffRead<T> { data: T | null; state: "idle" | "loading" | "ready" | "failed"; loading: boolean; error: string | null; problem: DiffProblem | null; stale: boolean }
const empty = <T>(): DiffRead<T> => ({ data: null, state: "idle", loading: false, error: null, problem: null, stale: false });
export interface DiffState {
  worktree: string | null; mode: DiffMode; scope: DiffLocalScope; baseRef: string; commitOid: string; parent: number; commit: DiffCommit | null;
  inventory: DiffRead<DiffWorktrees>; refs: DiffRead<DiffRefs>; history: DiffRead<DiffCommits>;
  files: DiffRead<DiffFiles>; patch: DiffRead<DiffPatch>;
  selected: string | null; query: string; hideViewed: boolean; viewed: ReadonlySet<string>;
  page: "files" | "patch"; layout: "unified" | "split"; wrap: boolean; scrollTop: number; filesScrollTop: number;
}
function initial(): DiffState {
  return { worktree: null, mode: "local", scope: "all", baseRef: "", commitOid: "", parent: 1, commit: null,
    inventory: empty(), refs: empty(), history: empty(), files: empty(), patch: empty(), selected: null,
    query: "", hideViewed: false, viewed: new Set(), page: "files", layout: "unified", wrap: false, scrollTop: 0, filesScrollTop: 0 };
}
type Resource = "inventory" | "refs" | "history" | "files" | "patch";
export const MAX_DIFF_SESSIONS = 8;
export const MAX_DIFF_COMMITS = 2000;

/** A store is a page-memory owner. Components retain it; they never own requests or patch bytes. */
export function createDiffStore(port: DiffPort = diffApi, capacity = MAX_DIFF_SESSIONS) {
  interface Entry { state: Signal<DiffState>; generation: number; controllers: Map<Resource, AbortController>; retained: number; dirty: boolean; resume: Set<Resource>; bases: Map<string, string> }
  const entries = new Map<string, Entry>();
  let visible = true;
  function entry(session: string): Entry {
    const found = entries.get(session);
    if (found) { entries.delete(session); entries.set(session, found); return found; }
    // Never evict mounted consumers. At most one selected patch per retained session.
    while (entries.size >= capacity) {
      const victim = [...entries].find(([, e]) => !e.retained);
      if (!victim) break;
      cancel(victim[1]); entries.delete(victim[0]);
    }
    const e: Entry = { state: signal(initial()), generation: 0, controllers: new Map(), retained: 0, dirty: false, resume: new Set(), bases: new Map() };
    entries.set(session, e); return e;
  }
  function update(e: Entry, change: Partial<DiffState>) { e.state.value = { ...e.state.value, ...change }; }
  function cancel(e: Entry) {
    e.generation++;
    for (const c of e.controllers.values()) c.abort();
    const changes: Partial<DiffState> = {};
    for (const key of e.controllers.keys()) {
      const resource = e.state.value[key];
      Object.assign(changes, { [key]: { ...resource, loading: false, state: resource.data ? "ready" : "idle" } });
    }
    e.controllers.clear(); update(e, changes);
  }
  function selection(s: DiffState): DiffSelection | null {
    if (s.mode === "local") return { mode: s.mode, scope: s.scope };
    if (s.mode === "branch") return s.baseRef ? { mode: s.mode, baseRef: s.baseRef } : null;
    return s.commitOid ? { mode: s.mode, commitOid: s.commitOid, parent: s.commit?.parents.length === 0 ? null : s.parent } : null;
  }
  function assertSource(e: Entry, result: { repository: { commonDir: string }; worktreeId: string }) {
    if (result.worktreeId !== e.state.value.worktree || result.repository.commonDir !== e.state.value.inventory.data?.repository.commonDir) throw new Error("Diff source changed. Refresh to reload.");
  }
  async function read<T>(e: Entry, key: Resource, load: (signal: AbortSignal) => Promise<T>, accept: (data: T) => void): Promise<boolean> {
    e.controllers.get(key)?.abort();
    const controller = new AbortController(), generation = e.generation;
    e.controllers.set(key, controller);
    const old = e.state.value[key];
    update(e, { [key]: { ...old, state: old.data ? "ready" : "loading", loading: true } });
    const current = () => e.generation === generation && e.controllers.get(key) === controller && !controller.signal.aborted;
    try {
      const data = await load(controller.signal);
      if (!current()) return false;
      accept(data);
      return true;
    } catch (error) {
      if (!current()) return false;
      const problem = error instanceof DiffReadError ? error.problem : null;
      const previous = e.state.value[key];
      update(e, { [key]: { ...previous, state: previous.data ? "ready" : "failed", loading: false, stale: !!previous.data, error: error instanceof Error ? error.message : "Diff read failed", problem } });
      if (problem?.code === "stale") {
        update(e, { viewed: new Set(), files: { ...e.state.value.files, stale: true }, patch: { ...e.state.value.patch, stale: true } });
      }
      return false;
    } finally { if (current()) e.controllers.delete(key); }
  }
  const ready = <T>(data: T): DiffRead<T> => ({ data, state: "ready", loading: false, error: null, problem: null, stale: false });
  async function loadPatch(session: string, e: Entry) {
    const s = e.state.value, snapshot = s.files.data?.snapshot, id = s.selected;
    if (!snapshot || !id || !s.worktree || s.files.stale) return;
    await read(e, "patch", a => port.patch(session, s.worktree!, snapshot.id, id, a), data => {
      assertSource(e, data.snapshot.comparison);
      if (data.snapshot.id !== snapshot.id || comparisonKey(session, data.snapshot.comparison) !== comparisonKey(session, snapshot.comparison) || data.file.fileId !== id) throw new Error("Patch does not match the selected comparison");
      update(e, { patch: ready(data) });
    });
  }
  async function loadFiles(session: string, e: Entry) {
    const s = e.state.value, choice = selection(s);
    if (!s.worktree || !choice) return;
    const ok = await read(e, "files", a => port.files(session, s.worktree!, choice, a), data => {
      assertSource(e, data.snapshot.comparison);
      const c = data.snapshot.comparison;
      if (c.mode !== choice.mode || (choice.mode === "local" && c.scope !== choice.scope) || (choice.mode === "branch" && c.baseRef !== choice.baseRef) || (choice.mode === "commit" && (c.commitOid !== choice.commitOid || c.parent !== choice.parent))) throw new Error("Comparison does not match the selection");
      const prior = e.state.value;
      // file IDs belong to a snapshot; retain selection by path only. Prior patch bytes remain explicitly stale until replaced.
      const oldFile = prior.files.data?.files.find(f => f.fileId === prior.selected);
      const chosen = data.files.find(f => oldFile && f.oldPath === oldFile.oldPath && f.newPath === oldFile.newPath) ?? data.files[0];
      const keepPatch = prior.patch.data && chosen && oldFile && chosen.oldPath === oldFile.oldPath && chosen.newPath === oldFile.newPath;
      update(e, { files: ready(data), patch: keepPatch ? { ...prior.patch, stale: true } : empty(), selected: chosen?.fileId ?? null, viewed: new Set() });
    });
    if (ok) await loadPatch(session, e);
  }
  async function loadHistory(session: string, e: Entry, more = false) {
    const s = e.state.value;
    if (!s.worktree || s.mode === "local" || (s.mode === "branch" && !s.baseRef) || s.history.loading) return;
    const prior = more ? s.history.data : null, cursor = prior?.nextCursor ?? null;
    if (more && (!cursor || prior!.commits.length >= MAX_DIFF_COMMITS)) return;
    await read(e, "history", a => port.commits(session, s.worktree!, s.mode === "branch" ? s.baseRef : null, cursor, a), data => {
      assertSource(e, data);
      if (cursor && (data.cursor?.headOid !== cursor.headOid || data.cursor?.baseOid !== cursor.baseOid || data.cursor?.offset !== cursor.offset)) throw new Error("History page changed its pinned comparison");
      if (data.nextCursor && (!data.cursor || data.nextCursor.headOid !== data.cursor.headOid || data.nextCursor.baseOid !== data.cursor.baseOid || data.nextCursor.offset <= data.cursor.offset)) throw new Error("History continuation changed its pinned comparison");
      const commits = prior ? [...prior.commits, ...data.commits.filter(c => !prior.commits.some(p => p.oid === c.oid))] : data.commits;
      update(e, { history: ready({ ...data, commits: commits.slice(0, MAX_DIFF_COMMITS) }) });
    });
  }
  async function loadSource(session: string, e: Entry) {
    const s = e.state.value;
    if (!s.worktree) return;
    const ok = await read(e, "refs", a => port.refs(session, s.worktree!, a), data => {
      assertSource(e, data);
      const key = JSON.stringify([data.repository.commonDir, data.worktreeId]);
      const saved = e.bases.get(key) ?? e.state.value.baseRef;
      const baseRef = data.refs.some(r => r.name === saved) ? saved : data.defaultBaseRef ?? "";
      update(e, { refs: ready(data), baseRef });
    });
    if (!ok) return;
    await Promise.all([loadFiles(session, e), loadHistory(session, e)]);
  }
  async function refresh(session: string) {
    const e = entry(session); cancel(e); e.dirty = false; e.resume.clear();
    update(e, { viewed: new Set(), files: { ...e.state.value.files, stale: !!e.state.value.files.data }, patch: { ...e.state.value.patch, stale: !!e.state.value.patch.data } });
    const ok = await read(e, "inventory", a => port.worktrees(session, a), data => {
      const s = e.state.value;
      const repositoryChanged = s.inventory.data && s.inventory.data.repository.commonDir !== data.repository.commonDir;
      const worktree = !repositoryChanged && data.worktrees.some(w => w.id === s.worktree) ? s.worktree : data.currentWorktreeId;
      if (repositoryChanged || worktree !== s.worktree) update(e, { refs: empty(), history: empty(), files: empty(), patch: empty(), selected: null, baseRef: "", commitOid: "", commit: null, scrollTop: 0, page: "files" });
      update(e, { inventory: ready(data), worktree });
    });
    if (ok) await loadSource(session, e);
  }
  function choose(session: string, change: Partial<Pick<DiffState, "worktree" | "mode" | "scope" | "baseRef" | "commitOid" | "parent">>) {
    const e = entry(session), before = e.state.value;
    if (change.commitOid && !before.history.data?.commits.some(c => c.oid === change.commitOid)) return;
    cancel(e);
    const historyChanged = change.worktree !== undefined || change.mode !== undefined || change.baseRef !== undefined;
    update(e, { ...change, ...(change.commitOid ? { commit: before.history.data!.commits.find(c => c.oid === change.commitOid)! } : {}), files: empty(), patch: empty(), selected: null, viewed: new Set(), page: "files", scrollTop: 0, filesScrollTop: 0,
      ...(historyChanged ? { history: empty(), commitOid: "", commit: null, parent: 1 } : {}),
      ...(change.worktree !== undefined ? { refs: empty(), baseRef: "" } : {}) });
    const s = e.state.value;
    if (s.worktree && s.inventory.data && s.baseRef) {
      const key = JSON.stringify([s.inventory.data.repository.commonDir, s.worktree]);
      e.bases.delete(key); e.bases.set(key, s.baseRef);
      if (e.bases.size > 32) e.bases.delete(e.bases.keys().next().value!);
    }
    if (!s.inventory.data) void refresh(session);
    else if (change.worktree !== undefined || !s.refs.data) void loadSource(session, e);
    else { void loadFiles(session, e); if (historyChanged) void loadHistory(session, e); }
  }
  function selectFile(session: string, id: string) {
    const e = entry(session);
    if (!e.state.value.files.data?.files.some(f => f.fileId === id)) return;
    const same = id === e.state.value.selected;
    if (same && e.state.value.patch.loading) { update(e, { page: "patch" }); return; }
    e.controllers.get("patch")?.abort(); e.controllers.delete("patch");
    update(e, { selected: id, page: "patch", ...(same ? {} : { patch: empty(), scrollTop: 0 }) });
    if (!same || !e.state.value.patch.data || e.state.value.patch.stale) void loadPatch(session, e);
  }
  function reading(session: string, change: Partial<Pick<DiffState, "query" | "hideViewed" | "page" | "layout" | "wrap" | "scrollTop" | "filesScrollTop">>) {
    const e = entry(session);
    // Scroll is a restoration bookmark, not rendered data: do not rebuild 20,000 code lines per scroll event.
    const { scrollTop, filesScrollTop, ...rendered } = change;
    if (scrollTop !== undefined) e.state.peek().scrollTop = scrollTop;
    if (filesScrollTop !== undefined) e.state.peek().filesScrollTop = filesScrollTop;
    if (Object.keys(rendered).length) update(e, rendered);
  }
  function markViewed(session: string, id: string, value: boolean) {
    const e = entry(session), s = e.state.value;
    if (s.files.stale || s.patch.stale || !s.files.data?.files.some(f => f.fileId === id)) return;
    const viewed = new Set(s.viewed); if (value) viewed.add(id); else viewed.delete(id); update(e, { viewed });
  }
  function next(session: string) {
    const s = entry(session).state.value;
    const id = nextUnviewedFile(filterFiles(s.files.data?.files ?? [], s.query, s.viewed, s.hideViewed), s.viewed, s.selected);
    if (id) selectFile(session, id);
  }
  function retain(session: string) {
    const e = entry(session); e.retained++;
    if (visible && (e.dirty || e.state.value.inventory.state === "idle" || [...e.resume].some(k => k === "inventory" || k === "refs" || k === "files"))) void refresh(session);
    else if (visible) {
      if (e.resume.has("patch")) void loadPatch(session, e);
      if (e.resume.has("history")) void loadHistory(session, e, !!e.state.value.history.data);
    }
    e.resume.clear();
    let released = false;
    return () => { if (released) return; released = true; e.retained--; if (!e.retained && e.controllers.size) { e.resume = new Set(e.controllers.keys()); cancel(e); } };
  }
  function recover() { for (const [session, e] of [...entries]) { if (visible && e.retained) void refresh(session); else e.dirty = true; } }
  function setVisible(value: boolean) {
    if (visible === value) return; visible = value;
    if (value) recover(); else for (const e of entries.values()) { e.dirty = true; cancel(e); }
  }
  return { state: (session: string): ReadonlySignal<DiffState> => entry(session).state, retain, refresh, choose, selectFile, reading, markViewed, next,
    more: (session: string) => loadHistory(session, entry(session), true), recover, setVisible,
    size: () => entries.size };
}
export const diffStore = createDiffStore();
export const retainDiff = diffStore.retain;
export const refreshDiff = diffStore.refresh;
export const recoverDiffs = diffStore.recover;
export const setDiffVisibility = diffStore.setVisible;
