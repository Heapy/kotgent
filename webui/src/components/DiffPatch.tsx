import { splitLines } from "../lib/diff.ts";
import type { DiffPatch as Patch, DiffLine } from "../lib/diff.ts";
import { DiffImages } from "./DiffImages.tsx";

function Code({ line }: { line: DiffLine | null }) {
  return <code>{line?.text ?? ""}{line?.noNewline && <span class="diff-no-newline"> ⏎ No newline at end of file</span>}</code>;
}
export function DiffPatch({ sessionId, patch, layout, wrap }: { sessionId: string; patch: Patch; layout: "unified" | "split"; wrap: boolean }) {
  const file = patch.file;
  return <div class={`diff-code ${wrap ? "diff-wrap" : ""}`}>
    {(file.oldMode !== file.newMode) && <p>Mode: {file.oldMode ?? "absent"} → {file.newMode ?? "absent"}</p>}
    {patch.limited && <p role="status">Partial patch: {patch.limited.message}</p>}
    {file.problem && !patch.images && <p role="status">{file.problem.message}</p>}
    {patch.images ? <DiffImages key={`${patch.snapshot.id}:${file.fileId}`} sessionId={sessionId} patch={patch}/>
      : file.kind !== "text" ? <p class="diff-special">{{ binary: "Binary file — no text preview", conflict: "Unresolved conflict — resolve in your checkout", symlink: "Symbolic link — target is not followed", submodule: "Submodule — nested content is not loaded", unavailable: "File content unavailable", limited: "File preview exceeds the reading limit" }[file.kind]}</p>
      : !patch.hunks.length ? <p>{file.oldMode !== file.newMode ? "Mode-only change; no text hunks." : "No text hunks in this change."}</p>
      : <table class={`diff-lines diff-${layout}`} aria-label={`${layout === "split" ? "Split" : "Unified"} patch`}>
        <colgroup>{layout === "split" ? <><col style="width:5ch"/><col/><col style="width:5ch"/><col/></> : <><col style="width:5ch"/><col style="width:5ch"/><col style="width:3ch"/><col/></>}</colgroup>
        <thead><tr>{layout === "split" ? <><th colSpan={2}>Before</th><th colSpan={2}>After</th></> : <><th>Old</th><th>New</th><th aria-label="Change">±</th><th>Code</th></>}</tr></thead>
        <tbody>{patch.hunks.map((hunk, index) => <FragmentHunk key={index} hunk={hunk} layout={layout} />)}</tbody>
      </table>}
  </div>;
}
function FragmentHunk({ hunk, layout }: { hunk: Patch["hunks"][number]; layout: "unified" | "split" }) {
  return <>
    <tr class="diff-hunk"><th colSpan={4}>@@ -{hunk.oldStart},{hunk.oldCount} +{hunk.newStart},{hunk.newCount} @@ {hunk.heading}</th></tr>
    {layout === "split" ? splitLines(hunk.lines).map((row, i) => <tr key={i}>
      <td class={`diff-number diff-${row.left?.kind ?? "empty"}`}>{row.left?.oldLine}</td><td class={`diff-cell diff-${row.left?.kind ?? "empty"}`}><Code line={row.left} /></td>
      <td class={`diff-number diff-${row.right?.kind ?? "empty"}`}>{row.right?.newLine}</td><td class={`diff-cell diff-${row.right?.kind ?? "empty"}`}><Code line={row.right} /></td>
    </tr>) : hunk.lines.map((line, i) => <tr key={i} class={`diff-${line.kind}`}>
      <td class="diff-number">{line.oldLine}</td><td class="diff-number">{line.newLine}</td><td class="diff-sign">{line.kind === "addition" ? "+" : line.kind === "deletion" ? "−" : " "}</td><td class="diff-cell"><Code line={line} /></td>
    </tr>)}
  </>;
}
