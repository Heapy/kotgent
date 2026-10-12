import { apiRequest } from "./api.ts";

export type DiffMode = "local" | "commit" | "branch";
export type DiffLocalScope = "all" | "staged" | "unstaged";
export interface DiffProblem { code: "invalid" | "unavailable" | "limited" | "stale" | "busy"; reason: string; message: string }
export interface DiffRepository { commonDir: string }
export interface DiffWorktree {
  id: string; path: string; headOid: string | null; branchRef: string | null;
  current: boolean; detached: boolean; bare: boolean; locked: boolean; prunable: boolean; unavailable: DiffProblem | null;
}
export interface DiffWorktrees { repository: DiffRepository; currentWorktreeId: string; worktrees: DiffWorktree[]; readAt: number }
export interface DiffRefs {
  repository: DiffRepository; worktreeId: string;
  refs: { name: string; oid: string; kind: "local" | "remote" }[];
  remoteDefaultRef: string | null; defaultBaseRef: string | null; readAt: number;
}
export interface DiffCommit { oid: string; parents: string[]; author: string; authoredAt: number; subject: string }
export interface DiffCommitCursor { headOid: string; baseOid: string | null; offset: number }
export interface DiffCommits {
  repository: DiffRepository; worktreeId: string; cursor: DiffCommitCursor | null;
  commits: DiffCommit[]; nextCursor: DiffCommitCursor | null; readAt: number;
}
export type DiffSelection = { mode: "local"; scope: DiffLocalScope } | { mode: "commit"; commitOid: string; parent: number | null } | { mode: "branch"; baseRef: string };
export interface DiffComparison {
  repository: DiffRepository; worktreeId: string; mode: DiffMode; scope: DiffLocalScope | null;
  headOid: string | null; baseRef: string | null; baseOid: string | null; mergeBaseOid: string | null;
  commitOid: string | null; parent: number | null; leftOid: string | null; rightOid: string | null;
}
export interface DiffSnapshot { id: string; comparison: DiffComparison; readAt: number }
export interface DiffFile {
  fileId: string; oldPath: string | null; newPath: string | null;
  change: "added" | "modified" | "deleted" | "renamed" | "copied" | "type_changed" | "untracked" | "conflicted";
  kind: "text" | "binary" | "symlink" | "submodule" | "conflict" | "unavailable" | "limited";
  oldMode: string | null; newMode: string | null; additions: number | null; deletions: number | null;
  indexStatus: string | null; worktreeStatus: string | null; problem: DiffProblem | null;
}
export interface DiffFiles { snapshot: DiffSnapshot; files: DiffFile[]; limited: DiffProblem | null }
export interface DiffLine { kind: "context" | "addition" | "deletion"; text: string; oldLine: number | null; newLine: number | null; noNewline: boolean }
export interface DiffHunk { oldStart: number; oldCount: number; newStart: number; newCount: number; heading: string; lines: DiffLine[] }
export interface DiffPatch { snapshot: DiffSnapshot; file: DiffFile; hunks: DiffHunk[]; limited: DiffProblem | null }
export type DiffResponse<T> = { data: T; error: null } | { data: null; error: DiffProblem };
export class DiffReadError extends Error {
  readonly problem: DiffProblem;
  constructor(problem: DiffProblem) { super(problem.message); this.problem = problem; }
}
export interface DiffPort {
  worktrees(session: string, signal: AbortSignal): Promise<DiffWorktrees>;
  refs(session: string, worktree: string, signal: AbortSignal): Promise<DiffRefs>;
  commits(session: string, worktree: string, baseRef: string | null, cursor: DiffCommitCursor | null, signal: AbortSignal): Promise<DiffCommits>;
  files(session: string, worktree: string, selection: DiffSelection, signal: AbortSignal): Promise<DiffFiles>;
  patch(session: string, worktree: string, snapshot: string, file: string, signal: AbortSignal): Promise<DiffPatch>;
}
async function read<T>(session: string, route: string, query: Record<string, string>, signal: AbortSignal): Promise<T> {
  try {
    const result = await apiRequest<DiffResponse<T>>(`/sessions/${encodeURIComponent(session)}/diff/${route}?${new URLSearchParams(query)}`, { signal });
    if (!result || typeof result !== "object" || !("data" in result)) throw new Error("Invalid Diff response");
    if (result.error) throw new DiffReadError(result.error);
    if (result.data === null) throw new Error("Missing Diff data");
    return result.data;
  } catch (error) {
    // apiRequest owns auth/timeouts and exposes non-2xx bodies as Error.message.
    if (error instanceof Error && !(error instanceof DiffReadError)) {
      let body: { error?: DiffProblem } | null = null;
      try { body = JSON.parse(error.message) as { error?: DiffProblem }; } catch { /* ordinary transport failure */ }
      if (body?.error) throw new DiffReadError(body.error);
    }
    throw error;
  }
}
export const diffApi: DiffPort = {
  worktrees: (s, a) => read(s, "worktrees", {}, a),
  refs: (s, w, a) => read(s, "refs", { worktreeId: w }, a),
  commits: (s, w, b, c, a) => read(s, "commits", { worktreeId: w, ...(c ? { cursor: JSON.stringify(c) } : b ? { baseRef: b } : {}) }, a),
  files: (s, w, selection, a) => read(s, "files", { worktreeId: w, ...Object.fromEntries(Object.entries(selection).filter(([, v]) => v !== null).map(([k, v]) => [k, String(v)])) }, a),
  patch: (s, w, snapshot, fileId, a) => read(s, "patch", { worktreeId: w, snapshot, fileId }, a),
};
export function comparisonKey(session: string, c: DiffComparison): string {
  return JSON.stringify([session, c.repository.commonDir, c.worktreeId, c.mode, c.scope, c.headOid, c.baseRef, c.baseOid, c.mergeBaseOid, c.commitOid, c.parent, c.leftOid, c.rightOid]);
}
export function filePath(f: DiffFile): string { return f.newPath ?? f.oldPath ?? "(unknown path)"; }
export function filterFiles(files: DiffFile[], query: string, viewed: ReadonlySet<string>, hideViewed: boolean): DiffFile[] {
  const needle = query.toLowerCase();
  return files.filter(f => (!hideViewed || !viewed.has(f.fileId)) && [f.oldPath, f.newPath].some(p => p?.toLowerCase().includes(needle)));
}
export function nextUnviewedFile(files: DiffFile[], viewed: ReadonlySet<string>, after: string | null): string | null {
  const start = files.findIndex(f => f.fileId === after);
  for (let i = 1; i <= files.length; i++) { const f = files[(start + i) % files.length]!; if (!viewed.has(f.fileId)) return f.fileId; }
  return null;
}
export interface SplitLine { left: DiffLine | null; right: DiffLine | null }
// Pair a contiguous replacement run; never align edits across a context line or hunk.
export function splitLines(lines: DiffLine[]): SplitLine[] {
  const rows: SplitLine[] = [];
  let removed: DiffLine[] = [], added: DiffLine[] = [];
  function flush() {
    for (let i = 0; i < Math.max(removed.length, added.length); i++) rows.push({ left: removed[i] ?? null, right: added[i] ?? null });
    removed = []; added = [];
  }
  for (const line of lines) {
    if (line.kind === "context") { flush(); rows.push({ left: line, right: line }); }
    else if (line.kind === "deletion") removed.push(line); else added.push(line);
  }
  flush(); return rows;
}
