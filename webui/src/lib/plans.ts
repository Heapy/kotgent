export interface PlanBlock { id: string; rev: number }
export interface PlanSection extends PlanBlock { kind: string; body: string }
export interface PlanDecision extends PlanBlock { title: string; body: string }
export interface PlanStep extends PlanBlock { text: string; done: boolean }
export interface PlanTask extends PlanBlock {
  ordinal: number; title: string; files: { path: string; action: string }[]; steps: PlanStep[];
  dependsOn: string[]; agent: string; status: string;
  worker: { sessionId: string; branch: string; worktree: string } | null;
}
export interface Plan {
  taskRef: string; title: string; status: string; rev: number;
  sections: PlanSection[]; decisions: PlanDecision[]; tasks: PlanTask[];
  mode: "autonomous" | "supervised"; concurrency: number; featureBranch: string | null;
}
export type PlanActor = { type: "operator" } | { type: "session"; sessionId: string };
export interface PlanThread {
  id: string; blockId: string; kind: string; status: "open" | "answered" | "resolved";
  messages: { author: PlanActor; body: string; at: number }[];
}
export interface PlanDocument {
  plan: Plan;
  execution: PlanExecution;
  review: {
    viewMarks: { blockId: string; viewedAtRev: number }[];
    threads: PlanThread[];
    edits: { blockId: string; fromRev: number; toRev: number; before: string; after: string; author: PlanActor; reviewRound: number }[];
    rounds: { n: number; openedAt: number; submittedAt: number | null; verdict: "changes" | "approved" | null }[];
  };
}
export interface DisplayPlanBlock extends PlanBlock { label: string; body: string; task?: PlanTask }

export function planBlocks(plan: Plan): DisplayPlanBlock[] {
  return [
    ...plan.sections.map(block => ({ ...block, label: block.kind[0]!.toUpperCase() + block.kind.slice(1) })),
    ...plan.decisions.map(block => ({ ...block, label: block.title })),
    ...plan.tasks.flatMap(task => [
      { ...task, label: `${task.ordinal}. ${task.title}`, body: task.title, task },
      ...task.steps.map((step, i) => ({ ...step, label: `${task.ordinal}.${i + 1} Step`, body: step.text })),
    ]),
  ];
}

export function isPlanViewed(document: PlanDocument, block: PlanBlock): boolean {
  return document.review.viewMarks.some(mark => mark.blockId === block.id && mark.viewedAtRev === block.rev);
}

export function mergePlanDocument(current: PlanDocument | null, incoming: PlanDocument): PlanDocument {
  if (!current) return incoming;
  return incoming.plan.taskRef === current.plan.taskRef && incoming.plan.rev > current.plan.rev ? incoming : current;
}

export function nextUnviewed(document: PlanDocument, after: string | null): string | null {
  const blocks = planBlocks(document.plan);
  const start = blocks.findIndex(block => block.id === after);
  for (let offset = 1; offset <= blocks.length; offset++) {
    const block = blocks[(start + offset) % blocks.length]!;
    if (!isPlanViewed(document, block)) return block.id;
  }
  return null;
}

export type FindingLevel = "low" | "medium" | "high";
export type FindingDecisionKind = "fix_now" | "fix_later" | "wont_fix";
export interface FindingDecision { kind: FindingDecisionKind; optionIndex: number | null; note: string | null; decidedBy: PlanActor | null }
export interface Finding {
  id: string; rev: number; taskId: string; iteration: number; location: string | null;
  condition: string; impact: string; danger: FindingLevel; likelihood: FindingLevel;
  options: { fix: string; outcome: string; cost: FindingLevel; fit: FindingLevel }[]; recommended: number;
  author: PlanActor | null; verifiedBy: PlanActor | null;
  verifier: { danger: FindingLevel; likelihood: FindingLevel; options: { cost: FindingLevel; fit: FindingLevel }[];
    verdict: "confirmed" | "rejected"; reason: string } | null;
  decision: FindingDecision | null; investigatorSessionId: string | null;
  notes: { author: PlanActor; body: string; at: number }[];
  revisions: { condition: string; impact: string; author: PlanActor; at: number }[];
}
export interface TaskReview { taskId: string; iteration: number; mode: Plan["mode"]; nextMode: Plan["mode"] }
export interface PlanExecution {
  orchestratorSessionId: string | null; reviews: TaskReview[]; findings: Finding[];
  nextEventId: number; events: { id: number; kind: string; taskId: string | null; workerSessionId: string | null;
    findingIds: string[]; rebaseOnto: string | null; at: number }[];
}
export type PlanChange = (path: string, method: string, body?: unknown) => Promise<boolean>;
export function taskReview(document: PlanDocument, taskId: string): TaskReview | undefined {
  return document.execution.reviews.findLast(review => review.taskId === taskId);
}
const score = { low: 1, medium: 2, high: 3 };
export function currentFindings(document: PlanDocument, taskId: string): Finding[] {
  const review = taskReview(document, taskId);
  return document.execution.findings.filter(f => f.taskId === taskId && f.iteration === review?.iteration)
    .sort((a, b) => score[b.danger] * score[b.likelihood] - score[a.danger] * score[a.likelihood] || a.id.localeCompare(b.id));
}
export function canDecideFindings(document: PlanDocument, task: PlanTask): boolean {
  return taskReview(document, task.id)?.mode === "supervised" && ["in_review", "awaiting_decision"].includes(task.status);
}
export function canSendFindings(document: PlanDocument, task: PlanTask): boolean {
  const findings = currentFindings(document, task.id);
  return canDecideFindings(document, task) && findings.length > 0 && findings.every(f => f.verifier && f.decision);
}
export function findingDecisionPayload(rev: number, kind: FindingDecisionKind, optionIndex: number, note: string) {
  return { rev, decision: { kind, optionIndex: kind === "fix_now" ? optionIndex : null, note: note.trim() || null } };
}
