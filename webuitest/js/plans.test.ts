import { test } from "node:test";
import assert from "node:assert/strict";
import { canSendFindings, canDecideFindings, currentFindings, findingDecisionPayload, isPlanViewed, mergePlanDocument, nextUnviewed } from "../../webui/src/lib/plans.ts";
import type { Finding, PlanDocument } from "../../webui/src/lib/plans.ts";
import { activeTabOf, defaultWorkspace, openPlanTab, selectTab } from "../../webui/src/lib/workspace.ts";
import { parseRoute, planPath, routePath, SCREEN_PLAN } from "../../webui/src/lib/router.ts";
import { mergePlan, planEntry, retainPlan, planChanged, recoverPlans, refreshPlan } from "../../webui/src/state/plans.ts";

function doc(ref = "local:1", rev = 1): PlanDocument {
  return {
    plan: { taskRef: ref, title: "Test", rev, status: "draft", mode: "supervised", concurrency: 3, featureBranch: null,
      sections: [{ id: "s_a", kind: "overview", body: "First", rev: 1 }, { id: "s_b", kind: "solution", body: "Second", rev: 1 }], decisions: [], tasks: [] },
    execution: { orchestratorSessionId: null, reviews: [], findings: [], events: [], nextEventId: 1 },
    review: { viewMarks: [], threads: [], edits: [], rounds: [] },
  };
}
const tick = () => new Promise<void>(resolve => setTimeout(resolve, 0));

test("newer revisions win and a viewed mark belongs to one block revision", () => {
  const current = doc();
  current.review.viewMarks.push({ blockId: "s_a", viewedAtRev: 1 });
  assert.equal(isPlanViewed(current, current.plan.sections[0]!), true);
  const next = structuredClone(current);
  next.plan.rev = 2;
  next.plan.sections[0]!.rev = 2;
  assert.equal(isPlanViewed(next, next.plan.sections[0]!), false);
  assert.equal(mergePlanDocument(current, next), next);
  assert.equal(mergePlanDocument(next, current), next);
  assert.equal(mergePlanDocument(current, doc("local:other", 100)), current);
  assert.equal(mergePlanDocument(current, doc()), current);
  assert.equal(nextUnviewed(current, "s_b"), "s_b");
  current.review.viewMarks.push({ blockId: "s_b", viewedAtRev: 1 });
  assert.equal(nextUnviewed(current, null), null);
  assert.equal(nextUnviewed({ ...current, plan: { ...current.plan, sections: [] } }, null), null);
});

test("first Plan open adds Terminal · Plan and later opens reuse it focused", () => {
  const ws = openPlanTab(defaultWorkspace(1), 2);
  assert.deepEqual(activeTabOf(ws).columns, [{ type: "terminal" }, { type: "plan" }]);
  assert.equal(activeTabOf(ws).focus, 1);
  assert.equal(openPlanTab(ws, 3), ws);
  const selected = openPlanTab(selectTab(ws, "t1", 3), 4);
  assert.equal(selected.tabs.length, 2);
  assert.equal(activeTabOf(selected).id, "t2");
});

test("plan deep links preserve opaque task refs", () => {
  const path = planPath("local:a b");
  assert.equal(path, "/tasks/local%3Aa%20b/plan");
  const route = parseRoute(path, "?session=ignored");
  assert.deepEqual(route, { screen: SCREEN_PLAN, id: "local:a b" });
  assert.equal(routePath(route), path);
});

test("open viewers reread hints and recovery; stale reads cannot overwrite mutations", async () => {
  const original = globalThis.fetch;
  const ref = "local:state";
  const reads: ((response: Response) => void)[] = [];
  globalThis.fetch = () => new Promise<Response>(resolve => reads.push(resolve));
  const stop = retainPlan(ref);
  try {
    assert.equal(reads.length, 1);
    reads.shift()!(Response.json(doc(ref)));
    await tick();
    planChanged(ref, 1);
    assert.equal(reads.length, 0);
    planChanged(ref, 2);
    assert.equal(reads.length, 1);
    mergePlan(doc(ref, 3));
    reads.shift()!(Response.json(doc(ref, 2)));
    await tick();
    assert.equal(planEntry(ref).document?.plan.rev, 3);
    recoverPlans();
    assert.equal(reads.length, 1);
    mergePlan(doc(ref, 4));
    reads.shift()!(new Response("missing", { status: 404 }));
    await tick();
    assert.equal(planEntry(ref).document?.plan.rev, 4, "a late 404 cannot erase newer data");
    const removed = refreshPlan(ref);
    reads.shift()!(new Response("missing", { status: 404 }));
    await removed;
    assert.equal(planEntry(ref).document, null);
  } finally { stop(); globalThis.fetch = original; }
});


test("finding decisions use the displayed revision and only fix-now carries an option", () => {
  assert.deepEqual(findingDecisionPayload(7, "fix_now", 1, " Reason "),
    { rev: 7, decision: { kind: "fix_now", optionIndex: 1, note: "Reason" } });
  assert.deepEqual(findingDecisionPayload(8, "wont_fix", 1, ""),
    { rev: 8, decision: { kind: "wont_fix", optionIndex: null, note: null } });
});

test("only a fully verified and decided current supervised batch can be sent", () => {
  const document = doc();
  const task = { id: "t_1", rev: 1, ordinal: 1, title: "Work", files: [], steps: [], dependsOn: [], agent: "any", status: "in_review", worker: null };
  document.plan.tasks = [task];
  document.execution.reviews.push({ taskId: "t_1", iteration: 2, mode: "supervised", nextMode: "autonomous" });
  document.plan.mode = "autonomous";
  assert.equal(canDecideFindings(document, task), true, "mode is frozen for the current review");
  assert.equal(canSendFindings(document, task), false);
  const finding: Finding = { id: "f_1", rev: 1, taskId: "t_1", iteration: 2, location: null, condition: "C", impact: "I",
    danger: "low", likelihood: "high", options: [{ fix: "F", outcome: "O", cost: "low", fit: "high" }], recommended: 0,
    author: null, verifiedBy: null, verifier: null, decision: null, investigatorSessionId: null, notes: [], revisions: [] };
  document.execution.findings = [finding, { ...finding, id: "f_old", iteration: 1 }, { ...finding, id: "f_high", danger: "high" }];
  assert.deepEqual(currentFindings(document, task.id).map(f => f.id), ["f_high", "f_1"]);
  for (const f of document.execution.findings.filter(f => f.iteration === 2)) {
    f.decision = { kind: "fix_later", optionIndex: null, note: null, decidedBy: { type: "operator" } };
  }
  assert.equal(canSendFindings(document, task), false, "unverified decisions never enable send");
  for (const f of document.execution.findings.filter(f => f.iteration === 2)) {
    f.verifier = { danger: "low", likelihood: "high", options: [{ cost: "low", fit: "high" }], verdict: "confirmed", reason: "Proof" };
  }
  assert.equal(canSendFindings(document, task), true, "previous iterations do not block this batch");
  task.status = "running";
  assert.equal(canSendFindings(document, task), false);
  task.status = "in_review";
  document.execution.reviews[0]!.mode = "autonomous";
  assert.equal(canDecideFindings(document, task), false);
  assert.equal(canSendFindings(document, task), false);
});
