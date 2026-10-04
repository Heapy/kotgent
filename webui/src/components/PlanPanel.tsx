import type { JSX, Ref } from "preact";
import { useCallback, useEffect, useId, useRef, useState } from "preact/hooks";
import { errorMessage } from "../lib/api.ts";
import { pendingMutation } from "../lib/mutation.ts";
import { isPlanViewed, nextUnviewed, planBlocks } from "../lib/plans.ts";
import type { DisplayPlanBlock, PlanDocument, PlanThread } from "../lib/plans.ts";
import { changePlan, planEntry, refreshPlan, retainPlan } from "../state/plans.ts";
import type { PlanFindingFocus } from "../state/layout.ts";
import { navigate, sessionPath } from "../lib/router.ts";
import { isEditingTarget, registerElement } from "../lib/dom.ts";
import { FindingReview } from "./FindingCard.tsx";
import { Markdown } from "./Markdown.tsx";

type Change = import("../lib/plans.ts").PlanChange;

export function PlanPanel({ taskRef, onClose = null, findingFocus }: {
  taskRef: string; onClose?: (() => void) | null; findingFocus?: PlanFindingFocus | undefined;
}) {
  const entry = planEntry(taskRef);
  const document = entry.document;
  const [active, setActive] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmRound, setConfirmRound] = useState<number | null>(null);
  const blockElements = useRef(new Map<string, HTMLElement>());
  const alive = useRef(true);
  const titleId = useId();
  const handledFocus = useRef<PlanFindingFocus | undefined>(undefined);
  const onFindingFocused = useCallback((request: PlanFindingFocus) => {
    handledFocus.current = request;
  }, []);
  const pendingFocus = findingFocus?.taskRef === taskRef && handledFocus.current !== findingFocus
    ? findingFocus : undefined;
  useEffect(() => {
    alive.current = true;
    const release = retainPlan(taskRef);
    return () => { alive.current = false; release(); };
  }, [taskRef]);
  const busy = pendingMutation.value !== null;
  const blocks = document ? planBlocks(document.plan) : [];
  const selected = blocks.find(block => block.id === active) ?? blocks[0];
  const round = document?.review.rounds.at(-1);
  const open = round && round.verdict === null;
  const viewed = document ? blocks.filter(block => isPlanViewed(document, block)).length : 0;
  const unresolved = document?.review.threads.filter(thread => thread.status !== "resolved").length ?? 0;

  const change: Change = async (path, method, body) => {
    setError(null);
    try {
      await changePlan(taskRef, path, method, body);
      return true;
    } catch (failure) {
      if (alive.current) setError((failure as { status?: number }).status === 409
        ? "This plan changed. The latest version is shown; your unsaved text is kept."
        : errorMessage(failure));
      return false;
    }
  };
  const jump = (id: string | null) => {
    if (!id) return;
    setActive(id);
    const target = blockElements.current.get(id);
    target?.scrollIntoView({ block: "nearest" });
    target?.focus({ preventScroll: true });
  };
  const toggleViewed = (block: DisplayPlanBlock) => {
    if (!document) return;
    const marked = isPlanViewed(document, block);
    void change(`/blocks/${block.id}/viewed`, marked ? "DELETE" : "PUT", marked ? undefined : { rev: block.rev });
  };
  const shortcut: JSX.KeyboardEventHandler<HTMLElement> = event => {
    if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || busy || !document) return;
    if (isEditingTarget(event.target)) return;
    if (event.key === "v" && selected) { event.preventDefault(); toggleViewed(selected); }
    if (event.key === "n") { event.preventDefault(); jump(nextUnviewed(document, selected?.id ?? null)); }
  };
  const approve = async (n: number) => {
    await change("/review/approve", "POST", { round: n });
    if (alive.current) setConfirmRound(null);
  };
  return <section class={"plan-panel" + (onClose ? " plan-overlay" : "")}
                  aria-labelledby={titleId} tabIndex={0} onKeyDown={shortcut}>
    <header class="plan-head">
      <div><p class="field-hint">{taskRef} · {document?.plan.status.replaceAll("_", " ") ?? "Plan"}</p>
        <h2 id={titleId}>{document?.plan.title ?? "Plan"}</h2></div>
      {onClose && <button class="icon-button" type="button" aria-label="Back to task" onClick={onClose}>×</button>}
    </header>
    {(error || entry.error) && <p class="form-error" role="alert">{error || entry.error}
      {entry.error && <button type="button" onClick={() => void refreshPlan(taskRef)}>Retry</button>}</p>}
    {!entry.ready && !entry.error && <p role="status">Loading plan…</p>}
    {entry.ready && !document && <p class="field-hint">This task has no plan yet.</p>}
    {document && <>
      <div class="plan-round" aria-label="Plan review">
        <span class="plan-progress" aria-live="polite">{viewed}/{blocks.length} viewed</span>
        <button class="button button-small" type="button" disabled={viewed === blocks.length}
                onClick={() => jump(nextUnviewed(document, selected?.id ?? null))}>Next unviewed</button>
        <span>{round ? `Round ${round.n}${round.verdict ? " · " + round.verdict : " · open"}` : "No review requested"}</span>
        <button class="button button-small plan-submit" type="button" disabled={!open || busy}
                onClick={() => void change("/review/submit", "POST", { round: round!.n })}>Submit review</button>
        <button class="button button-primary button-small plan-approve" type="button" disabled={!open || busy}
                onClick={() => unresolved ? setConfirmRound(round!.n) : void approve(round!.n)}>Approve</button>
      </div>
      <div class="plan-settings">
        <label>Execution mode <select aria-label="Execution mode" value={document.plan.mode} disabled={busy || document.plan.status === "done"}
          onChange={event => void change("/settings", "PATCH", { mode: event.currentTarget.value })}>
          <option value="supervised">Supervised</option><option value="autonomous">Autonomous</option>
        </select></label>
        <span class="field-hint">Mode changes apply to the next task review. Up to {document.plan.concurrency} tasks at once.</span>
        {document.plan.featureBranch && <code>{document.plan.featureBranch}</code>}
      </div>
      <div class="plan-body">
        <nav class="plan-toc" aria-label="Plan contents">
          <label class="plan-toc-select">Jump to block
            <select value={selected?.id} onChange={event => jump(event.currentTarget.value)}>
              {blocks.map(block => <option value={block.id}>{isPlanViewed(document, block) ? "✓ " : "○ "}{block.label}</option>)}
            </select>
          </label>
          <ol>{blocks.map(block => {
            const count = document.review.threads.filter(thread => thread.blockId === block.id && thread.status !== "resolved").length;
            const marked = isPlanViewed(document, block);
            const changed = !marked && document.review.viewMarks.some(mark => mark.blockId === block.id);
            return <li key={block.id}>
              <input type="checkbox" aria-label={`Viewed: ${block.label}`} checked={marked} disabled={busy}
                     onChange={() => toggleViewed(block)} />
              <button type="button" aria-current={selected?.id === block.id ? "location" : undefined}
                      onClick={() => jump(block.id)}>{block.label}{changed && <span title="Changed since viewed"> ●</span>}
                {count > 0 && <span class="plan-thread-count"> {count} open</span>}</button>
            </li>;
          })}</ol>
        </nav>
        <div class="plan-blocks">{blocks.map(block => <PlanBlockView key={block.id} block={block}
          document={document} change={change} busy={busy} onActive={() => setActive(block.id)}
          elementRef={element => registerElement(blockElements.current, block.id, element)}
          findingFocus={pendingFocus} onFindingFocused={onFindingFocused}
          onViewed={() => toggleViewed(block)} />)}</div>
      </div>
    </>}
    {confirmRound !== null && <ApproveDialog count={unresolved} busy={busy}
      onCancel={() => setConfirmRound(null)} onApprove={() => void approve(confirmRound)} />}
  </section>;
}

function PlanBlockView({ block, document, change, busy, onActive, onViewed, elementRef, findingFocus, onFindingFocused }: {
  block: DisplayPlanBlock; document: PlanDocument; change: Change; busy: boolean; onActive: () => void; onViewed: () => void;
  elementRef: Ref<HTMLElement>; findingFocus?: PlanFindingFocus | undefined;
  onFindingFocused: (request: PlanFindingFocus) => void;
}) {
  const [edit, setEdit] = useState<{ rev: number; text: string } | null>(null);
  const [asking, setAsking] = useState(false);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);
  const marked = isPlanViewed(document, block);
  const stale = edit !== null && edit.rev !== block.rev;
  const save: JSX.SubmitEventHandler<HTMLFormElement> = async event => {
    event.preventDefault();
    if (edit && await change(`/blocks/${block.id}`, "PATCH", { rev: edit.rev, body: edit.text })) {
      if (alive.current) setEdit(null);
    }
  };
  return <article ref={elementRef} class="plan-block" data-block={block.id} tabIndex={-1} onFocusIn={onActive} onClick={onActive}>
    <header><h3>{block.label}</h3><span class="field-hint">rev {block.rev}</span></header>
    <div class="plan-block-actions">
      <label><input type="checkbox" checked={marked} disabled={busy} onChange={onViewed} /> Viewed</label>
      <button class="button button-small" type="button" onClick={() => setAsking(!asking)}>Ask</button>
      <button class="button button-small" type="button" disabled={edit !== null}
              onClick={() => setEdit({ rev: block.rev, text: block.body })}>Edit</button>
    </div>
    {edit ? <form class="plan-editor" onSubmit={save}>
      <label>Your edit<textarea aria-label={`Edit ${block.label}`} rows={6} value={edit.text}
        spellcheck={false} autoCorrect="off" autocapitalize="off"
        onInput={event => setEdit({ ...edit, text: event.currentTarget.value })} /></label>
      {stale && <div class="plan-conflict" role="status"><strong>Changed while you were editing. Current version:</strong>
        <Markdown text={block.body} />
        <button class="button button-small" type="button" onClick={() => setEdit({ ...edit, rev: block.rev })}>
          Keep my edit against revision {block.rev}</button>
      </div>}
      <div class="plan-block-actions">
        <button class="button button-primary button-small" type="submit" disabled={busy || stale}>Save edit</button>
        <button class="button button-small" type="button" disabled={busy} onClick={() => setEdit(null)}>Cancel edit</button>
      </div>
    </form> : <Markdown text={block.body} />}
    {block.task && <div class="plan-task-meta">
      <span class="plan-task-status">{block.task.status.replaceAll("_", " ")} · {block.task.agent}</span>
      {block.task.worker && <p>Worker <a href={sessionPath(block.task.worker.sessionId)} onClick={event => {
        if (event.button || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
        event.preventDefault(); navigate(sessionPath(block.task!.worker!.sessionId));
      }}>{block.task.worker.sessionId}</a> · <code>{block.task.worker.branch}</code></p>}
      {block.task.dependsOn.length > 0 && <p>Depends on {block.task.dependsOn.join(", ")}</p>}
      {block.task.files.length > 0 && <ul>{block.task.files.map(file => <li>{file.action}: <code>{file.path}</code></li>)}</ul>}
    </div>}
    {block.task && <FindingReview document={document} task={block.task} change={change} busy={busy} findingFocus={findingFocus} onFindingFocused={onFindingFocused} />}
    {asking && <MessageForm label="Question" action="Ask question" busy={busy} send={async body => {
      const ok = await change("/threads", "POST", { blockId: block.id, kind: "question", body });
      if (ok && alive.current) setAsking(false);
      return ok;
    }} />}
    {document.review.threads.filter(thread => thread.blockId === block.id).map(thread =>
      <ThreadView key={thread.id} thread={thread} change={change} busy={busy} />)}
  </article>;
}

function ThreadView({ thread, change, busy }: { thread: PlanThread; change: Change; busy: boolean }) {
  return <section class="plan-thread" aria-label={`${thread.kind} · ${thread.status}`}>
    <header><strong>{thread.kind} · {thread.status}</strong>
      {thread.status !== "resolved" && <button class="button button-small" type="button" disabled={busy}
        onClick={() => void change(`/threads/${thread.id}/resolve`, "POST")}>Resolve</button>}</header>
    {thread.messages.map((message, index) => <div class="plan-message" key={index}>
      <small>{message.author.type === "operator" ? "You" : `Agent ${message.author.sessionId}`}</small>
      <Markdown text={message.body} />
    </div>)}
    {thread.status !== "resolved" && <MessageForm label="Reply" action="Send reply" busy={busy}
      send={body => change(`/threads/${thread.id}/messages`, "POST", { body })} />}
  </section>;
}

function MessageForm({ label, action, busy, send }: {
  label: string; action: string; busy: boolean; send: (body: string) => Promise<boolean>;
}) {
  const [body, setBody] = useState("");
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);
  return <form class="plan-message-form" onSubmit={async event => {
    event.preventDefault();
    if (await send(body) && alive.current) setBody("");
  }}>
    <label>{label}<textarea rows={2} value={body} onInput={event => setBody(event.currentTarget.value)} /></label>
    <button class="button button-small" type="submit" disabled={busy || !body.trim()}>{action}</button>
  </form>;
}

function ApproveDialog({ count, busy, onCancel, onApprove }: {
  count: number; busy: boolean; onCancel: () => void; onApprove: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => { dialog.current?.showModal(); }, []);
  return <dialog ref={dialog} class="plan-confirm" aria-labelledby={id} onCancel={onCancel}>
    <h3 id={id}>Approve with open threads?</h3>
    <p>{count} thread{count === 1 ? " remains" : "s remain"} unresolved.</p>
    <div class="plan-block-actions">
      <button class="button" type="button" disabled={busy} onClick={onCancel}>Keep reviewing</button>
      <button class="button button-primary" type="button" disabled={busy} onClick={onApprove}>Approve anyway</button>
    </div>
  </dialog>;
}
