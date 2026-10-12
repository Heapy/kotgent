import { useEffect, useLayoutEffect, useRef } from "preact/hooks";
import { diffStore, MAX_DIFF_COMMITS } from "../state/diff.ts";
import { filePath, filterFiles } from "../lib/diff.ts";
import type { DiffMode, DiffLocalScope } from "../lib/diff.ts";
import { DiffPatch } from "./DiffPatch.tsx";
import "../diff.css";

/** Integration owns workspace/session selection; this panel only chooses a Git source. */
export function DiffPanel({ sessionId }: { sessionId: string }) {
  const state = diffStore.state(sessionId).value;
  const patchScroll = useRef<HTMLDivElement>(null), filesScroll = useRef<HTMLUListElement>(null);
  const heading = useRef<HTMLHeadingElement>(null), filter = useRef<HTMLInputElement>(null);
  const previousPage = useRef(state.page);
  useEffect(() => diffStore.retain(sessionId), [sessionId]);
  useLayoutEffect(() => {
    if (patchScroll.current) patchScroll.current.scrollTop = state.scrollTop;
    if (filesScroll.current) filesScroll.current.scrollTop = state.filesScrollTop;
  }, [sessionId, state.selected, state.patch.data, state.page]);
  useEffect(() => {
    if (previousPage.current !== state.page) {
      if (state.page === "patch") heading.current?.focus(); else filter.current?.focus();
      previousPage.current = state.page;
    }
  }, [state.page]);
  const choose = (change: Parameters<typeof diffStore.choose>[1]) => diffStore.choose(sessionId, change);
  const reading = (change: Parameters<typeof diffStore.reading>[1]) => diffStore.reading(sessionId, change);
  const worktree = state.inventory.data?.worktrees.find(w => w.id === state.worktree);
  const files = filterFiles(state.files.data?.files ?? [], state.query, state.viewed, state.hideViewed);
  const selected = state.files.data?.files.find(f => f.fileId === state.selected);
  const commit = state.commit;
  const resources = [state.inventory, state.refs, state.history, state.files, state.patch];
  const loading = resources.some(r => r.loading);
  const errors = [...new Set(resources.flatMap(r => r.error ? [r.error] : []))];
  const stale = resources.some(r => r.stale);
  return <section class="diff-panel" aria-label="Git changes">
    <div class="diff-controls">
      <label class="diff-worktree">Worktree<select value={state.worktree ?? ""} onChange={e => choose({ worktree: e.currentTarget.value })}>
        {!state.inventory.data && <option value="">Loading worktrees…</option>}
        {state.inventory.data?.worktrees.map(w => <option key={w.id} value={w.id}>{w.branchRef?.replace(/^refs\/heads\//, "") ?? (w.detached ? "Detached HEAD" : w.bare ? "Bare" : "Unborn")} · {w.path}{w.current ? " · current" : ""}{w.locked ? " · locked" : ""}{w.unavailable || w.prunable ? " · unavailable" : ""}</option>)}
      </select></label>
      <button type="button" onClick={() => void diffStore.refresh(sessionId)}>Refresh</button>
      <label>Compare<select value={state.mode} onChange={e => choose({ mode: e.currentTarget.value as DiffMode })}><option value="local">Local changes</option><option value="commit">Commits</option><option value="branch">Branch changes</option></select></label>
      {state.mode === "local" && <label>Scope<select value={state.scope} onChange={e => choose({ scope: e.currentTarget.value as DiffLocalScope })}><option value="all">All</option><option value="staged">Staged</option><option value="unstaged">Unstaged</option></select></label>}
      {state.mode === "branch" && <label>Base branch<select value={state.baseRef} onChange={e => choose({ baseRef: e.currentTarget.value })}><option value="">Choose a base…</option>{state.refs.data?.refs.map(r => <option key={r.name} value={r.name}>{r.name.replace(/^refs\/(heads|remotes)\//, "")} · {r.kind}</option>)}</select></label>}
    </div>
    {worktree && <p class="diff-source">{worktree.path} · {worktree.headOid?.slice(0, 12) ?? "No HEAD"}{worktree.locked ? " · Locked (readable)" : ""}</p>}
    {worktree?.unavailable && <p role="status">Worktree unavailable: {worktree.unavailable.message}</p>}
    {worktree?.bare && state.mode === "local" && <p role="status">Bare repositories have no local working copy.</p>}
    {(loading || stale || errors.length > 0) && <div class="diff-status" role="status">{loading && <span>Loading changes… </span>}{stale && <strong>Showing stale content. </strong>}{errors.map(error => <p key={error}>{error}</p>)}</div>}
    {state.mode !== "local" && <details class="diff-history" open={state.mode === "commit" && !state.commitOid}>
      <summary>{state.mode === "branch" ? "Commits absent from base" : "Choose a commit"} ({state.history.data?.commits.length ?? 0})</summary>
      <ol>{state.history.data?.commits.map(c => <li key={c.oid}>{state.mode === "commit" ? <button type="button" aria-pressed={state.commitOid === c.oid} onClick={() => choose({ commitOid: c.oid, parent: 1 })}>{c.subject} · {c.oid.slice(0, 12)}</button> : <span>{c.subject} · {c.oid.slice(0, 12)}</span>}<small>{c.author} · <time dateTime={new Date(c.authoredAt).toISOString()}>{new Date(c.authoredAt).toLocaleString()}</time> · {c.parents.length} parent{c.parents.length === 1 ? "" : "s"}</small></li>)}</ol>
      {state.history.data && !state.history.data.commits.length && <p>No commits in this history.</p>}
      {state.history.data?.nextCursor && <button type="button" disabled={state.history.loading || state.history.data.commits.length >= MAX_DIFF_COMMITS} onClick={() => void diffStore.more(sessionId)}>Load more commits</button>}
      {(state.history.data?.commits.length ?? 0) >= MAX_DIFF_COMMITS && <p>History limited to {MAX_DIFF_COMMITS} commits. Refresh to start again.</p>}
    </details>}
    {commit && <div class="diff-commit"><p>{commit.subject} · {commit.author} · {new Date(commit.authoredAt).toLocaleString()}<br/><code>{commit.oid}</code></p>{commit.parents.length ? <label>Parent<select value={state.parent} onChange={e => choose({ parent: Number(e.currentTarget.value) })}>{commit.parents.map((p, i) => <option key={p} value={i + 1}>Parent {i + 1} · {p.slice(0, 12)}</option>)}</select></label> : <p>Root commit · compared with the empty tree</p>}</div>}
    {state.mode === "commit" && !state.commitOid && <p>Select a commit to view its changes.</p>}
    {state.mode === "branch" && !state.baseRef && <p>Choose a base branch to compare.</p>}
    <div class={`diff-body diff-page-${state.page}`}>
      <div class="diff-files">
        <label>Filter changed files<input ref={filter} value={state.query} type="search" spellcheck={false} autoCorrect="off" autocapitalize="off" onInput={e => reading({ query: e.currentTarget.value })}/></label>
        <label class="diff-check"><input type="checkbox" checked={state.hideViewed} onChange={e => reading({ hideViewed: e.currentTarget.checked })}/> Hide viewed</label>
        {state.files.data && <p>{files.length} of {state.files.data.files.length} files · {state.viewed.size} viewed</p>}
        {state.files.data?.limited && <p role="status">Partial file list: {state.files.data.limited.message}</p>}
        <ul ref={filesScroll} onScroll={e => reading({ filesScrollTop: e.currentTarget.scrollTop })}>{files.map(f => <li key={f.fileId}><button type="button" aria-current={state.selected === f.fileId ? "true" : undefined} onClick={() => diffStore.selectFile(sessionId, f.fileId)}>
          <span class="diff-file-path">{f.oldPath && f.newPath && f.oldPath !== f.newPath ? `${f.oldPath} → ${f.newPath}` : filePath(f)}</span>
          <small>{f.change} · {f.kind}{f.indexStatus !== null ? ` · index ${f.indexStatus === " " ? "unchanged" : f.indexStatus}` : ""}{f.worktreeStatus !== null ? ` · worktree ${f.worktreeStatus === " " ? "unchanged" : f.worktreeStatus}` : ""} · +{f.additions ?? "?"} −{f.deletions ?? "?"}{state.viewed.has(f.fileId) ? " · Viewed" : ""}</small>
        </button></li>)}</ul>
        {state.files.data && !files.length && <p>{state.files.data.files.length ? "No files match this filter." : state.files.data.limited ? "No files available within the reading limit." : "No changes in this comparison."}</p>}
      </div>
      <div class="diff-patch-pane">
        <div class="diff-patch-tools"><button class="diff-back" type="button" onClick={() => reading({ page: "files" })}>Back to files</button>
          <label>Layout<select value={state.layout} onChange={e => reading({ layout: e.currentTarget.value as "unified" | "split" })}><option value="unified">Unified</option><option value="split">Split</option></select></label>
          <label class="diff-check"><input type="checkbox" checked={state.wrap} onChange={e => reading({ wrap: e.currentTarget.checked })}/> Wrap</label>
          <label class="diff-check"><input type="checkbox" disabled={!selected || stale || !state.patch.data} checked={!!selected && state.viewed.has(selected.fileId)} onChange={e => selected && diffStore.markViewed(sessionId, selected.fileId, e.currentTarget.checked)}/> Viewed</label>
          <button type="button" disabled={!files.some(f => !state.viewed.has(f.fileId))} onClick={() => diffStore.next(sessionId)}>Next unviewed</button>
        </div>
        <h3 ref={heading} tabIndex={-1}>{selected ? filePath(selected) : "Select a changed file"}</h3>
        <div class="diff-patch-scroll" tabIndex={0} role="region" aria-label="Selected patch" ref={patchScroll} onScroll={e => reading({ scrollTop: e.currentTarget.scrollTop })}>
          {state.patch.data && <DiffPatch patch={state.patch.data} layout={state.layout} wrap={state.wrap}/>}
        </div>
      </div>
    </div>
  </section>;
}
