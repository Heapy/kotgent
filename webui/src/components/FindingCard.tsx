import { useEffect, useId, useRef, useState } from "preact/hooks";
import type { Finding, FindingDecisionKind, PlanActor, PlanChange, PlanDocument, PlanTask } from "../lib/plans.ts";
import { canDecideFindings, canSendFindings, currentFindings, findingDecisionPayload, taskReview } from "../lib/plans.ts";
import { navigate, sessionPath } from "../lib/router.ts";
import { openFinding } from "../state/layout.ts";
import { planEntry } from "../state/plans.ts";
import { Markdown } from "./Markdown.tsx";

export function FindingReview({ document, task, change, busy }: {
  document: PlanDocument; task: PlanTask; change: PlanChange; busy: boolean;
}) {
  const review = taskReview(document, task.id);
  if (!review) return null;
  const findings = currentFindings(document, task.id);
  const history = document.execution.findings.filter(f => f.taskId === task.id && f.iteration !== review.iteration);
  const controls = canDecideFindings(document, task);
  return <section class="finding-review" aria-label={`Review of ${task.title}`}>
    <header><h4>Review · iteration {review.iteration}</h4><span class="finding-mode">{review.mode}</span></header>
    {review.nextMode !== review.mode && <p class="field-hint">Next review: {review.nextMode}. This review stays {review.mode}.</p>}
    {findings.length === 0 && <p class="field-hint">No findings in this review.</p>}
    {findings.map(finding => <FindingCard key={finding.id} taskRef={document.plan.taskRef} finding={finding} editable={controls} change={change} busy={busy} />)}
    {controls && findings.length > 0 && <button type="button" class="button button-primary finding-send"
      disabled={busy || !canSendFindings(document, task)} onClick={() => void change("/findings/send", "POST", { taskId: task.id })}>
      Send to worker</button>}
    {!controls && findings.length > 0 && <p class="field-hint">{review.mode === "autonomous"
      ? "The assigned worker records each decision and its reason." : "Decisions have been sent to the worker."}</p>}
    {history.length > 0 && <details class="finding-history"><summary>Earlier findings ({history.length})</summary>
      {history.map(finding => <FindingCard key={finding.id} taskRef={document.plan.taskRef} finding={finding} editable={false} change={change} busy={busy} />)}
    </details>}
  </section>;
}

function actorName(actor: PlanActor | null): string { return actor?.type === "session" ? actor.sessionId : actor ? "You" : "Agent"; }
interface DecisionDraft { rev: number; kind: FindingDecisionKind; option: number; note: string }

export function FindingCard({ taskRef, finding, editable, change, busy }: {
  taskRef: string; finding: Finding; editable: boolean; change: PlanChange; busy: boolean;
}) {
  const [draft, setDraft] = useState<DecisionDraft | null>(null);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);
  const titleId = useId();
  const formId = useId();
  const current = draft ?? { rev: finding.rev, kind: finding.decision?.kind ?? "fix_now",
    option: finding.decision?.optionIndex ?? finding.recommended, note: finding.decision?.note ?? "" };
  const stale = draft !== null && draft.rev !== finding.rev;
  const verifier = finding.verifier;
  return <article class="finding-card" data-finding={finding.id} tabIndex={-1} aria-labelledby={titleId}>
    <header><h5 id={titleId}>{finding.id}{finding.location && <> · <code>{finding.location}</code></>}</h5>
      <span class="field-hint">rev {finding.rev}</span></header>
    <div class="finding-assessment"><strong>Condition</strong><Markdown text={finding.condition} />
      <strong>Impact</strong><Markdown text={finding.impact} /></div>
    <p class="finding-scores">Reviewer ({actorName(finding.author)}): danger <strong>{finding.danger}</strong> · likelihood <strong>{finding.likelihood}</strong></p>
    {verifier ? <div class="finding-verifier"><strong>Verifier: {verifier.verdict}</strong>
      <p class="finding-scores">{actorName(finding.verifiedBy)}: danger <strong>{verifier.danger}</strong> · likelihood <strong>{verifier.likelihood}</strong></p>
      <Markdown text={verifier.reason} /></div> : <p class="field-hint">Awaiting independent verification.</p>}
    <ol class="finding-options">{finding.options.map((option, index) => <li key={index} class={index === finding.recommended ? "recommended" : ""}>
      <strong>Option {index + 1}{index === finding.recommended ? " · Recommended" : ""}</strong>
      <Markdown text={option.fix} /><p>Outcome</p><Markdown text={option.outcome} />
      <p class="finding-scores">Reviewer: cost {option.cost} · fit {option.fit}
        {verifier?.options[index] && <> · Verifier: cost {verifier.options[index]!.cost} · fit {verifier.options[index]!.fit}</>}</p>
    </li>)}</ol>
    {finding.decision && <div class="finding-decision-summary"><strong>Decision: {decisionLabel(finding.decision.kind)}
      {finding.decision.optionIndex !== null && ` · Option ${finding.decision.optionIndex + 1}`}</strong>
      <small> · {actorName(finding.decision.decidedBy)}</small>
      {finding.decision.note && <Markdown text={finding.decision.note} />}</div>}
    {finding.notes.length > 0 && <details class="finding-notes"><summary>Notes ({finding.notes.length})</summary>
      {finding.notes.map((note, index) => <div key={index}><small>{actorName(note.author)}</small><Markdown text={note.body} /></div>)}
    </details>}
    {finding.revisions.length > 0 && <details><summary>Amendment history ({finding.revisions.length})</summary>
      {finding.revisions.map((revision, index) => <div key={index}><small>{actorName(revision.author)}</small>
        <Markdown text={revision.condition} /><Markdown text={revision.impact} /></div>)}
    </details>}
    {editable && !finding.decision && <button type="button" class="button button-small finding-investigate" disabled={busy}
      onClick={async () => {
        if (!await change(`/findings/${finding.id}/investigate`, "POST", { rev: finding.rev, agent: "claude" }) || !alive.current) return;
        const investigator = planEntry(taskRef).document?.execution.findings.find(f => f.id === finding.id)?.investigatorSessionId;
        if (!investigator) return;
        openFinding(investigator, taskRef, finding.id);
        navigate(sessionPath(investigator));
      }}>{finding.investigatorSessionId ? "Open Claude investigator" : "Investigate with Claude"}</button>}
    {editable && <form class="finding-decision" onSubmit={async event => {
      event.preventDefault();
      setDraft(current);
      if (await change(`/findings/${finding.id}/decide`, "POST", findingDecisionPayload(current.rev, current.kind, current.option, current.note))) {
        if (alive.current) setDraft(null);
      }
    }}>
      <fieldset disabled={busy || !verifier}><legend>Decision for {finding.id}</legend>
        <div class="finding-choices">{(["fix_now", "fix_later", "wont_fix"] as const).map(kind => <label key={kind}>
          <input type="radio" name={formId} value={kind} checked={current.kind === kind}
            onChange={() => setDraft({ ...current, kind })} />{decisionLabel(kind)}</label>)}</div>
        {current.kind === "fix_now" && <label>Fix option<select value={current.option}
          onChange={event => setDraft({ ...current, option: Number(event.currentTarget.value) })}>
          {finding.options.map((_, index) => <option value={index} key={index}>Option {index + 1}{index === finding.recommended ? " · Recommended" : ""}</option>)}
        </select></label>}
        <label>Decision note<textarea rows={2} value={current.note} spellcheck={false} autoCorrect="off" autocapitalize="off"
          onInput={event => setDraft({ ...current, note: event.currentTarget.value })} /></label>
        {stale && <div class="finding-conflict" role="status">This finding changed. Review its latest assessment before saving your kept decision.
          <button type="button" class="button button-small" onClick={() => setDraft({ ...current, rev: finding.rev,
            option: current.option < finding.options.length ? current.option : finding.recommended })}>Use finding revision {finding.rev}</button>
        </div>}
        <button type="submit" class="button button-small" disabled={stale}>Save decision</button>
      </fieldset>
    </form>}
  </article>;
}
function decisionLabel(kind: FindingDecisionKind): string {
  return kind === "fix_now" ? "Fix now" : kind === "fix_later" ? "Fix later" : "Won’t fix";
}
