import { useEffect, useLayoutEffect, useMemo, useRef } from "preact/hooks";
import { diffStore, MAX_DIFF_COMMITS } from "../state/diff.ts";
import { filePath, filterFiles, comparisonLabels } from "../lib/diff.ts";
import type { DiffMode, DiffLocalScope, DiffComparison } from "../lib/diff.ts";
import { DiffFiles, DiffFileStatus, DiffStats } from "./DiffFiles.tsx";
import { DiffPatch } from "./DiffPatch.tsx";
import "../diff.css";

/** Integration owns workspace/session selection; this panel only chooses a Git source. */
export function DiffPanel({ sessionId }: { sessionId: string }) {
  const state = diffStore.state(sessionId).value;
  const patchScroll = useRef<HTMLDivElement>(null), filesScroll = useRef<HTMLUListElement>(null);
  const heading = useRef<HTMLHeadingElement>(null), filter = useRef<HTMLInputElement>(null);
  const previousPage = useRef(state.page);
  // Start revalidation before paint: a remounted ready frame must not become disabled after a keypress.
  useLayoutEffect(() => diffStore.retain(sessionId), [sessionId]);
  useLayoutEffect(() => {
    if (patchScroll.current) patchScroll.current.scrollTop = state.scrollTop;
    if (filesScroll.current) filesScroll.current.scrollTop = state.filesScrollTop;
  }, [sessionId, state.selected, state.patch.data, state.page]);
  useEffect(() => {
    if (previousPage.current !== state.page) {
      if (state.page === "patch") {
        if (!filesScroll.current?.getClientRects().length) heading.current?.focus();
      } else filter.current?.focus();
      previousPage.current = state.page;
    }
  }, [state.page]);
  const choose = (change: Parameters<typeof diffStore.choose>[1]) => diffStore.choose(sessionId, change);
  const reading = (change: Parameters<typeof diffStore.reading>[1]) => diffStore.reading(sessionId, change);
  const worktree = state.inventory.data?.worktrees.find(w => w.id === state.worktree);
  const files = useMemo(() => filterFiles(state.files.data?.files ?? [], state.query, state.viewed, state.hideViewed), [state.files.data, state.query, state.viewed, state.hideViewed]);
  const selected = state.files.data?.files.find(f => f.fileId === state.selected);
  const commit = state.commit;
  const resources = [state.inventory, state.refs, state.history, state.files, state.patch];
  const loading = resources.some(r => r.loading);
  const errors = [...new Set(resources.flatMap(r => r.error ? [r.error] : []))];
  const stale = resources.some(r => r.stale);
  const toggleFolder = (path: string) => {
    const collapsedFolders = new Set(state.collapsedFolders);
    if (collapsedFolders.has(path)) collapsedFolders.delete(path); else collapsedFolders.add(path);
    reading({ collapsedFolders });
  };
  return <section class="diff-panel" aria-label="Git changes">
    <div class="diff-controls diff-checkout">
      <label class="diff-worktree">Worktree<select value={state.worktree ?? ""} onChange={e => choose({ worktree: e.currentTarget.value })}>
        {!state.inventory.data && <option value="">Loading worktrees…</option>}
        {state.inventory.data?.worktrees.map(w => <option key={w.id} value={w.id}>{w.branchRef?.replace(/^refs\/heads\//, "") ?? (w.detached ? "Detached HEAD" : w.bare ? "Bare" : "Unborn")} · {w.path.split("/").at(-1)}{w.current ? " · current" : ""}{w.locked ? " · locked" : ""}{w.unavailable || w.prunable ? " · unavailable" : ""}</option>)}
      </select></label>
      <button class="diff-refresh" type="button" aria-label="Refresh" disabled={loading} onClick={() => void diffStore.refresh(sessionId)}><span class={loading ? "diff-spinning" : ""} aria-hidden="true">↻</span> Refresh</button>
    </div>
    <p class="diff-source" title={worktree?.path}>{worktree ? <>{worktree.path} · Checkout HEAD {worktree.headOid?.slice(0, 12) ?? "unborn"}{worktree.locked ? " · Locked (readable)" : ""}</> : "Discovering worktrees…"}</p>
    <div class="diff-controls diff-compare-controls">
      <label>Compare<select value={state.mode} onChange={e => choose({ mode: e.currentTarget.value as DiffMode })}><option value="local">Local changes</option><option value="commit">Commits</option><option value="branch">Branch changes</option></select></label>
      {state.mode === "local" && <label>Scope<select value={state.scope} onChange={e => choose({ scope: e.currentTarget.value as DiffLocalScope })}><option value="all">All</option><option value="staged">Staged</option><option value="unstaged">Unstaged</option></select></label>}
      {state.mode === "branch" && <label>Base branch<select value={state.baseRef} onChange={e => choose({ baseRef: e.currentTarget.value })}><option value="">Choose a base…</option>{state.refs.data?.refs.map(r => <option key={r.name} value={r.name}>{r.name.replace(/^refs\/(heads|remotes)\//, "")} · {r.kind}</option>)}</select></label>}
      <div class="diff-display-controls">
        <label>Layout<select value={state.layout} onChange={e => reading({ layout: e.currentTarget.value as "unified" | "split" })}><option value="unified">Unified</option><option value="split">Split</option></select></label>
        <label class="diff-check"><input type="checkbox" checked={state.wrap} onChange={e => reading({ wrap: e.currentTarget.checked })}/> Wrap</label>
      </div>
    </div>
    {worktree?.unavailable && <p role="status">Worktree unavailable: {worktree.unavailable.message}</p>}
    {worktree?.bare && state.mode === "local" && <p role="status">Bare repositories have no local working copy.</p>}
    {state.mode !== "local" && <details class="diff-history" open={state.mode === "commit" && !state.commitOid}>
      <summary>{state.mode === "branch" ? "Commits absent from base" : "Choose a commit"} ({state.history.data?.commits.length ?? 0})</summary>
      <ol>{state.history.data?.commits.map(c => <li key={c.oid}><button type="button" aria-pressed={state.mode === "commit" && state.commitOid === c.oid} onClick={() => choose({ mode: "commit", commitOid: c.oid, parent: 1 })}>{c.subject} · {c.oid.slice(0, 12)}</button><small>{c.author} · <time dateTime={new Date(c.authoredAt).toISOString()}>{new Date(c.authoredAt).toLocaleString()}</time> · {c.parents.length} parent{c.parents.length === 1 ? "" : "s"}</small></li>)}</ol>
      {state.history.data && !state.history.data.commits.length && <p>No commits in this history.</p>}
      {state.history.data?.nextCursor && <button type="button" disabled={state.inventory.loading || state.refs.loading || state.history.loading || state.history.data.commits.length >= MAX_DIFF_COMMITS} onClick={() => void diffStore.more(sessionId)}>Load more commits</button>}
      {(state.history.data?.commits.length ?? 0) >= MAX_DIFF_COMMITS && <p>History limited to {MAX_DIFF_COMMITS} commits. Refresh to start again.</p>}
    </details>}
    {commit && <div class="diff-commit"><p>{commit.subject} · {commit.author} · {new Date(commit.authoredAt).toLocaleString()}<br/><code>{commit.oid}</code></p>{commit.parents.length ? <label>Parent<select value={state.parent} onChange={e => choose({ parent: Number(e.currentTarget.value) })}>{commit.parents.map((p, i) => <option key={p} value={i + 1}>Parent {i + 1} · {p.slice(0, 12)}</option>)}</select></label> : <p>Root commit · compared with the empty tree</p>}</div>}
    {state.mode === "commit" && !state.commitOid && <p>Select a commit to view its changes.</p>}
    {state.mode === "branch" && !state.baseRef && <p>Choose a base branch to compare.</p>}
    <div class={`diff-body diff-page-${state.page}`}>
      <div class="diff-files">
        <div class="diff-files-heading"><strong>Changed files</strong><div class="diff-segments" role="group" aria-label="File view">
          <button type="button" aria-pressed={state.fileView === "list"} onClick={() => reading({ fileView: "list" })}>List</button>
          <button type="button" aria-pressed={state.fileView === "tree"} onClick={() => reading({ fileView: "tree" })}>Tree</button>
        </div></div>
        <label class="diff-filter"><span class="diff-sr-only">Filter changed files</span><input ref={filter} value={state.query} type="search" placeholder="Filter files…" spellcheck={false} autoCorrect="off" autocapitalize="off" onInput={e => reading({ query: e.currentTarget.value })}/></label>
        <div class="diff-files-options"><label class="diff-check"><input type="checkbox" checked={state.hideViewed} onChange={e => reading({ hideViewed: e.currentTarget.checked })}/> Hide viewed</label>
          {state.fileView === "tree" && <button type="button" class="diff-text-button" onClick={() => reading({ collapsedFolders: new Set() })}>Expand all</button>}
        </div>
        {state.files.data?.limited && <p role="status">Partial file list: {state.files.data.limited.message}</p>}
        <DiffFiles files={files} tree={state.fileView === "tree"} collapsed={state.collapsedFolders} selected={state.selected} viewed={state.viewed}
          disabled={state.files.stale && [state.inventory, state.refs, state.files, state.patch].some(r => r.loading)}
          scrollRef={filesScroll} onScroll={filesScrollTop => reading({ filesScrollTop })} onToggle={toggleFolder} onSelect={id => diffStore.selectFile(sessionId, id)}/>
        {state.files.data && !files.length && <p>{state.files.data.files.length ? "No files match this filter." : state.files.data.limited ? "No files available within the reading limit." : "No changes in this comparison."}</p>}
        <div class="diff-files-footer">{state.files.data ? `${files.length} of ${state.files.data.files.length} files · ${state.viewed.size} viewed` : "Changed files"}</div>
      </div>
      <div class="diff-patch-pane">
        <div class="diff-patch-tools"><button class="diff-back" type="button" onClick={() => reading({ page: "files" })}>Back to files</button>
          <div class="diff-patch-heading">{selected && <DiffFileStatus file={selected}/>}<h3 ref={heading} tabIndex={-1} title={selected ? filePath(selected) : undefined}>{selected ? filePath(selected) : "Select a changed file"}</h3></div>
          {selected && <DiffStats file={selected}/>}
          <label class="diff-check"><input type="checkbox" disabled={!selected || stale || !state.patch.data} checked={!!selected && state.viewed.has(selected.fileId)} onChange={e => selected && diffStore.markViewed(sessionId, selected.fileId, e.currentTarget.checked)}/> Viewed</label>
          <button type="button" class="diff-next" title="Next unviewed file" disabled={!files.some(f => !state.viewed.has(f.fileId))} onClick={() => diffStore.next(sessionId)}>Next unviewed <span aria-hidden="true">↓</span></button>
        </div>
        <div class="diff-patch-scroll" tabIndex={0} role="region" aria-label="Selected patch" ref={patchScroll} onScroll={e => reading({ scrollTop: e.currentTarget.scrollTop })}>
          {!state.patch.data && <div class="diff-empty-state"><span aria-hidden="true">{loading ? "⋯" : "±"}</span><p>{loading ? "Reading changes…" : selected ? "No patch available. Check the comparison status below." : "Select a file to review its changes"}</p></div>}
          {state.patch.data && <DiffPatch patch={state.patch.data} layout={state.layout} wrap={state.wrap}/>}
        </div>
      </div>
    </div>
    <footer class="diff-footer">
      <div class="diff-identities">
        {state.files.data && <ComparisonIdentity comparison={state.files.data.snapshot.comparison} label="File list comparison"/>}
        {state.patch.data && <ComparisonIdentity comparison={state.patch.data.snapshot.comparison} label={state.patch.stale ? "Stale patch comparison" : "Patch comparison"}/>}
      </div>
      <div class={`diff-read-status ${stale || errors.length ? "diff-status" : ""}`} role="status" title={errors.join("\n")}>
        <span class={`diff-activity ${loading ? "is-loading" : ""}`} aria-hidden="true"/>
        <span>{stale ? "Showing stale content" : loading ? "Reading changes…" : "Up to date"}{errors.length ? ` · ${errors.join(" · ")}` : ""}</span>
      </div>
      {errors.length > 0 && <details class="diff-errors"><summary>Details</summary><div>{errors.map(error => <p key={error}>{error}</p>)}</div></details>}
    </footer>
  </section>;
}

function ComparisonIdentity({ comparison, label }: { comparison: DiffComparison; label: string }) {
  return <details class="diff-comparison"><summary title={`${label} · HEAD ${comparison.headOid ?? "unborn"}`}>{label.replace(" comparison", "")} <span>{comparison.mode === "branch" ? "· merge-base " : "· "}{(comparison.mode === "branch" ? comparison.mergeBaseOid : comparison.headOid)?.slice(0, 7) ?? "unborn"}</span></summary>
    <dl>{comparisonLabels(comparison).map(([name, value]) => <div key={name}><dt>{name}</dt><dd><code>{value}</code></dd></div>)}</dl>
  </details>;
}
