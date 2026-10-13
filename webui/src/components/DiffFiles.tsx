import { useMemo, useRef, useState } from "preact/hooks";
import type { RefObject } from "preact";
import { filePath } from "../lib/diff.ts";
import type { DiffFile } from "../lib/diff.ts";
import { diffFileTree, visibleDiffTree } from "../lib/diff-tree.ts";

export function DiffStats({ file }: { file: DiffFile }) {
  if (file.additions === null && file.deletions === null) return <span class="diff-stats" title="Line counts unavailable" aria-label="Line counts unavailable">—</span>;
  return <span class="diff-stats"><span class="diff-plus">+{file.additions ?? "?"}</span><span class="diff-minus">−{file.deletions ?? "?"}</span></span>;
}
export function DiffFileStatus({ file }: { file: DiffFile }) {
  const symbol = { added: "A", modified: "M", deleted: "D", renamed: "R", copied: "C", type_changed: "T", untracked: "?", conflicted: "!" }[file.change];
  return <span class={`diff-file-status diff-change-${file.change}`} title={file.change} aria-hidden="true">{symbol}</span>;
}
function fileDescription(file: DiffFile) {
  return [file.change, file.kind, file.indexStatus !== null ? `index ${file.indexStatus === " " ? "unchanged" : file.indexStatus}` : "",
    file.worktreeStatus !== null ? `worktree ${file.worktreeStatus === " " ? "unchanged" : file.worktreeStatus}` : ""].filter(Boolean).join(" · ");
}
function FileLabel({ file, tree, viewed }: { file: DiffFile; tree: boolean; viewed: boolean }) {
  const path = filePath(file), slash = path.lastIndexOf("/");
  const renamed = file.oldPath && file.newPath && file.oldPath !== file.newPath;
  return <>{tree && <span class="diff-chevron" aria-hidden="true"/>}<DiffFileStatus file={file}/><span class="diff-file-label">
    <span class="diff-file-name" aria-hidden="true">{path.slice(slash + 1)}</span>
    <span class="diff-file-path diff-sr-only">{path}</span>
    {!tree && <small>{renamed ? `${file.oldPath} → ${file.newPath}` : slash < 0 ? fileDescription(file) : path.slice(0, slash)}</small>}
  </span>{viewed && <span class="diff-viewed-mark" aria-hidden="true">✓</span>}<DiffStats file={file}/></>;
}

export function DiffFiles({ files, tree, collapsed, selected, viewed, disabled, scrollRef, onScroll, onToggle, onSelect }: {
  files: DiffFile[]; tree: boolean; collapsed: ReadonlySet<string>; selected: string | null; viewed: ReadonlySet<string>; disabled: boolean;
  scrollRef: RefObject<HTMLUListElement>; onScroll: (top: number) => void; onToggle: (path: string) => void; onSelect: (id: string) => void;
}) {
  const roots = useMemo(() => diffFileTree(files), [files]);
  const rows = useMemo(() => visibleDiffTree(roots, collapsed), [roots, collapsed]);
  const [focused, setFocused] = useState<string | null>(null);
  const buttons = useRef(new Map<string, HTMLButtonElement>());
  const active = rows.find(n => n.key === focused)?.key ?? rows.find(n => n.kind === "file" && n.file.fileId === selected)?.key ?? rows[0]?.key;
  const positions = useMemo(() => {
    const result = new Map<string, { position: number; size: number }>(), stack = [roots];
    while (stack.length) {
      const siblings = stack.pop()!;
      siblings.forEach((n, i) => {
        result.set(n.key, { position: i + 1, size: siblings.length });
        if (n.kind === "folder") stack.push(n.children);
      });
    }
    return result;
  }, [roots]);
  function focus(key: string | undefined) { if (key) { setFocused(key); buttons.current.get(key)?.focus(); } }
  return <ul class={`diff-file-list ${tree ? "diff-file-tree" : ""}`} ref={scrollRef} role={tree ? "tree" : "list"} aria-label="Changed files" onScroll={e => onScroll(e.currentTarget.scrollTop)}>
    {tree ? rows.map((node, index) => <li key={node.key} role="none">
      <button type="button" role="treeitem" ref={el => { if (el) buttons.current.set(node.key, el); else buttons.current.delete(node.key); }}
        class={`diff-file-row ${node.kind === "folder" ? "diff-folder-row" : ""}`} style={{ paddingLeft: `${8 + node.depth * 16}px` }}
        tabIndex={active === node.key ? 0 : -1} aria-level={node.depth + 1}
        aria-posinset={positions.get(node.key)!.position} aria-setsize={positions.get(node.key)!.size}
        aria-expanded={node.kind === "folder" ? !collapsed.has(node.path) : undefined}
        aria-selected={node.kind === "file" && node.file.fileId === selected}
        aria-current={node.kind === "file" && node.file.fileId === selected ? "true" : undefined}
        aria-label={node.kind === "file" ? `${node.path} · ${fileDescription(node.file)}${viewed.has(node.file.fileId) ? " · Viewed" : ""}` : undefined}
        aria-disabled={node.kind === "file" && disabled ? "true" : undefined}
        title={node.kind === "folder" ? node.path : `${node.path}\n${node.file.oldPath && node.file.oldPath !== node.path ? `From ${node.file.oldPath}\n` : ""}${fileDescription(node.file)}`}
        onFocus={() => setFocused(node.key)}
        onClick={() => node.kind === "folder" ? onToggle(node.path) : !disabled && onSelect(node.file.fileId)}
        onKeyDown={e => {
          if (e.altKey || e.ctrlKey || e.metaKey) return;
          const open = node.kind === "folder" && !collapsed.has(node.path);
          switch (e.key) {
            case "ArrowDown": focus(rows[Math.min(index + 1, rows.length - 1)]?.key); break;
            case "ArrowUp": focus(rows[Math.max(0, index - 1)]?.key); break;
            case "Home": focus(rows[0]?.key); break;
            case "End": focus(rows.at(-1)?.key); break;
            case "ArrowRight": if (node.kind === "folder") { if (open) focus(rows[index + 1]?.key); else onToggle(node.path); } break;
            case "ArrowLeft": if (open) onToggle(node.path); else focus(node.parent ?? undefined); break;
            default: return;
          }
          e.preventDefault(); e.stopPropagation();
        }}>
        {node.kind === "folder" ? <><span class="diff-chevron" aria-hidden="true">{collapsed.has(node.path) ? "›" : "⌄"}</span><svg class="diff-folder-icon" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M2 3h4l2 2h6v8H2Z" fill="currentColor" fill-opacity=".15" stroke="currentColor" stroke-linejoin="round"/></svg><span class="diff-folder-name">{node.name}</span><span class="diff-folder-count">{node.count}</span></>
          : <FileLabel file={node.file} tree viewed={viewed.has(node.file.fileId)}/>}
      </button>
    </li>) : files.map(file => <li key={file.fileId}><button type="button" class="diff-file-row" disabled={disabled}
      aria-label={`${filePath(file)} · ${fileDescription(file)}${viewed.has(file.fileId) ? " · Viewed" : ""}`}
      title={`${filePath(file)}\n${fileDescription(file)}`} aria-current={selected === file.fileId ? "true" : undefined} onClick={() => onSelect(file.fileId)}>
      <FileLabel file={file} tree={false} viewed={viewed.has(file.fileId)}/>
    </button></li>)}
  </ul>;
}
