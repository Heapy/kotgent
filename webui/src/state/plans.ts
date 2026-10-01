import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { apiRequest, errorMessage } from "../lib/api.ts";
import { createSerialRefresh } from "../lib/refresh.ts";
import { runMutation } from "../lib/mutation.ts";
import { mergePlanDocument } from "../lib/plans.ts";
import type { PlanDocument } from "../lib/plans.ts";

export interface PlanEntry { document: PlanDocument | null; ready: boolean; loading: boolean; error: string | null }
const empty: PlanEntry = { document: null, ready: false, loading: false, error: null };
const state = signal<ReadonlyMap<string, PlanEntry>>(new Map());
export const plans: ReadonlySignal<ReadonlyMap<string, PlanEntry>> = state;
const observed = new Map<string, number>();
const loaders = new Map<string, () => Promise<unknown>>();

export function planEntry(ref: string): PlanEntry { return plans.value.get(ref) ?? empty; }
function update(ref: string, change: Partial<PlanEntry>) {
  const next = new Map(state.value);
  next.set(ref, { ...planEntry(ref), ...change });
  state.value = next;
}
export function mergePlan(document: PlanDocument) {
  const ref = document.plan.taskRef;
  update(ref, { document: mergePlanDocument(planEntry(ref).document, document), ready: true, error: null });
}
export const planApiPath = (ref: string) => "/tasks/" + encodeURIComponent(ref) + "/plan";

export function refreshPlan(ref: string): Promise<unknown> {
  let loader = loaders.get(ref);
  if (!loader) {
    loader = createSerialRefresh({
      begin: () => update(ref, { loading: true }),
      read: async () => {
        const before = planEntry(ref).document?.plan.rev;
        try {
          const result = await apiRequest<PlanDocument>(planApiPath(ref));
          if (!result || typeof result !== "object" || !result.plan || result.plan.taskRef !== ref) throw new Error("Invalid plan response");
          return { document: result, before };
        } catch (error) {
          if ((error as { status?: number }).status === 404) return { document: null, before };
          throw error;
        }
      },
      succeed: result => {
        if (result?.document) mergePlan(result.document);
        else if (result && planEntry(ref).document?.plan.rev === result.before) update(ref, { document: null, ready: true, error: null });
        update(ref, { loading: false });
      },
      fail: (_, error) => update(ref, { loading: false, error: errorMessage(error) }),
      report: () => {},
    });
    loaders.set(ref, loader);
  }
  return loader();
}

export function retainPlan(ref: string): () => void {
  const count = observed.get(ref) ?? 0;
  observed.set(ref, count + 1);
  if (count === 0) void refreshPlan(ref);
  return () => {
    const count = (observed.get(ref) ?? 1) - 1;
    if (count) observed.set(ref, count); else observed.delete(ref);
  };
}
export function planChanged(ref: string, rev: number) {
  if (observed.has(ref) && rev > (planEntry(ref).document?.plan.rev ?? -1)) void refreshPlan(ref);
}
export function recoverPlans() { for (const ref of observed.keys()) void refreshPlan(ref); }

export async function changePlan(ref: string, path: string, method: string, body?: unknown): Promise<PlanDocument> {
  return runMutation("plan", async () => {
    try {
      const result = await apiRequest<PlanDocument>(planApiPath(ref) + path, {
        method, ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!result || typeof result !== "object" || !result.plan) throw new Error("Invalid plan response");
      mergePlan(result);
      return result;
    } catch (error) {
      if ((error as { status?: number }).status === 409) await refreshPlan(ref);
      throw error;
    }
  });
}
