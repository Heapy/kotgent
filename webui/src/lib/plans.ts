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
