import { useMemo, useRef, useState } from "preact/hooks";
import type { JSX } from "preact";
import type { DiffState } from "../state/diff.ts";
import { MAX_DIFF_COMMITS } from "../state/diff.ts";
import { branchName, filterBranches, filterCommits } from "../lib/diff.ts";

function navigate(event: JSX.TargetedKeyboardEvent<HTMLButtonElement>, list: HTMLElement | null) {
  if (event.altKey || event.ctrlKey || event.metaKey || !list) return;
  const rows = [...list.querySelectorAll<HTMLButtonElement>("button[data-history-row]")];
  const index = rows.indexOf(event.currentTarget);
  const next = event.key === "ArrowDown" ? Math.min(index + 1, rows.length - 1)
    : event.key === "ArrowUp" ? Math.max(index - 1, 0)
    : event.key === "Home" ? 0 : event.key === "End" ? rows.length - 1 : null;
  if (next === null) return;
  event.preventDefault(); event.stopPropagation(); rows[next]?.focus();
}

export function DiffBranches({ id, state, onSelect }: { id: string; state: DiffState; onSelect: (ref: string) => void }) {
  const [query, setQuery] = useState("");
  const list = useRef<HTMLDivElement>(null);
  const refs = state.refs.data?.refs ?? [];
  const visible = filterBranches(refs, query);
  const current = state.inventory.data?.worktrees.find(w => w.id === state.worktree)?.branchRef;
  return <aside id={id} class="diff-branches" aria-label="Branches" hidden={!state.branchesVisible}>
    <div class="diff-files-heading"><strong>Branches</strong><span class="diff-branch-count">{refs.length}</span></div>
    <label class="diff-filter"><span class="diff-sr-only">Filter branches</span><input type="search" value={query} placeholder="Filter branches…" spellcheck={false} autoCorrect="off" autocapitalize="off" onInput={e => setQuery(e.currentTarget.value)}/></label>
    <div class="diff-branch-list" ref={list}>
      <button type="button" data-history-row class="diff-branch-row" aria-pressed={state.mode === "commit" && !state.historyRef} onClick={() => onSelect("")} onKeyDown={e => navigate(e, list.current)}><span aria-hidden="true">◉</span><span>Checkout HEAD</span></button>
      {(["local", "remote"] as const).map(kind => <section key={kind} aria-label={kind === "local" ? "Local branches" : "Remote branches"}>
        <h3>{kind === "local" ? "Local" : "Remote"}</h3>
        <ul>{visible.filter(r => r.kind === kind).map(r => <li key={r.name}><button type="button" data-history-row class="diff-branch-row" title={`${r.name}\n${r.oid}${r.name === current ? "\nChecked out here" : ""}`} aria-label={`${branchName(r.name)}${r.name === current ? " (checked out)" : ""}`} aria-pressed={state.mode === "commit" && state.historyRef === r.name} onClick={() => onSelect(r.name)} onKeyDown={e => navigate(e, list.current)}>
          <span aria-hidden="true">⑂</span><span>{branchName(r.name)}</span>{r.name === current && <span class="diff-branch-current" aria-hidden="true">✓</span>}
        </button></li>)}</ul>
        {!visible.some(r => r.kind === kind) && <p>{query ? "No matches" : kind === "remote" ? "No remote branches" : "No local branches"}</p>}
      </section>)}
    </div>
  </aside>;
}

export function DiffHistory({ state, onSelect, onMore }: { state: DiffState; onSelect: (oid: string) => void; onMore: () => void }) {
  const [query, setQuery] = useState("");
  const list = useRef<HTMLOListElement>(null);
  const commits = state.history.data?.commits ?? [];
  const filtered = useMemo(() => filterCommits(commits, query), [commits, query]);
  const labels = useMemo(() => {
    const byOid = new Map<string, string[]>();
    for (const ref of state.refs.data?.refs ?? []) {
      const names = byOid.get(ref.oid) ?? [];
      if (names.length < 3) names.push(branchName(ref.name));
      byOid.set(ref.oid, names);
    }
    return byOid;
  }, [state.refs.data]);
  const title = state.mode === "branch" ? `HEAD excluding ${branchName(state.baseRef)}` : state.historyRef ? branchName(state.historyRef) : "Checkout HEAD";
  return <section class="diff-history" aria-label="Commit log">
    <div class="diff-history-toolbar"><strong title={title}>Log · {title}</strong><label><span class="diff-sr-only">Filter loaded commits</span><input type="search" value={query} placeholder="Filter loaded commits…" spellcheck={false} autoCorrect="off" autocapitalize="off" onInput={e => setQuery(e.currentTarget.value)}/></label><span>{filtered.length}{query ? ` / ${commits.length}` : ""} commits</span></div>
    <div class="diff-log-columns" aria-hidden="true"><span>Commit</span><span>Author</span><span>Date</span><span>Hash</span></div>
    <ol ref={list} aria-label="Commits">{filtered.map(c => <li key={c.oid}><button type="button" class="diff-log-row" data-history-row aria-pressed={state.mode === "commit" && state.commitOid === c.oid} onClick={() => onSelect(c.oid)} onKeyDown={e => navigate(e, list.current)} title={`${c.subject}\n${c.author} · ${new Date(c.authoredAt).toLocaleString()}\n${c.oid}`}>
      <span class="diff-log-subject"><span class="diff-commit-dot" aria-hidden="true"/><span class="diff-ref-labels">{labels.get(c.oid)?.map((name, index) => <span key={index} class="diff-ref-badge">{name}</span>)}</span><span class="diff-log-message">{c.subject}</span>{c.parents.length > 1 && <span class="diff-merge-badge">Merge</span>}</span>
      <span class="diff-log-author">{c.author}</span><time dateTime={new Date(c.authoredAt).toISOString()}>{new Date(c.authoredAt).toLocaleDateString()}</time><code>{c.oid.slice(0, 8)}</code>
    </button></li>)}</ol>
    {!filtered.length && <p>{state.history.loading ? "Loading commits…" : state.history.error ? state.history.error : query ? "No loaded commits match this filter." : "No commits in this history."}</p>}
    <div class="diff-history-footer">
      {state.history.data?.nextCursor && <button type="button" disabled={state.inventory.loading || state.refs.loading || state.history.loading || commits.length >= MAX_DIFF_COMMITS} onClick={onMore}>Load more commits</button>}
      {commits.length >= MAX_DIFF_COMMITS ? <span>History limited to {MAX_DIFF_COMMITS} commits.</span> : <span>{state.history.loading && commits.length ? "Loading commits…" : state.mode === "branch" ? "Commits absent from base" : "Select a commit to view its changed files"}</span>}
    </div>
  </section>;
}
