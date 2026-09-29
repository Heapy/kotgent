// One coordinator per source. Decisions are pure; its runner owns timers and the current resource.
export interface RefreshState {
  phase: "idle" | "waiting" | "running" | "ready" | "disposed";
  gen: number;
  ticket: number;
  active: boolean;
  pending: readonly string[];
  failures: number;
}

export type RefreshEvent =
  | { type: "request"; reason: string; invalidate?: boolean }
  | { type: "scheduled"; ticket: number }
  | { type: "complete"; gen: number }
  | { type: "failed"; gen: number; error?: unknown }
  | { type: "dispose" };

export interface RefreshPolicy {
  batchMs: number;
  timeoutMs: number;
  retryMs: number;
  maxRetryMs: number;
}

export type RefreshEffect =
  | { kind: "schedule"; ticket: number; delay: number }
  | { kind: "deadline"; gen: number; delay: number }
  | { kind: "start"; gen: number; reasons: readonly string[] }
  | { kind: "report"; error?: unknown }
  | { kind: "stop" | "cancelSchedule" | "cancelDeadline" };

export interface RefreshStep {
  state: Readonly<RefreshState>;
  effects: RefreshEffect[];
}

export type TimerHandle = ReturnType<typeof setTimeout>;
export interface RefreshTimers {
  schedule?: (callback: () => void, delay: number) => TimerHandle;
  cancel?: (timer: TimerHandle | null) => void;
}

export interface RefreshRequest {
  reason?: string;
  invalidate?: boolean;
}

export interface RefreshRun {
  reasons: readonly string[];
  isCurrent: () => boolean;
  complete: () => void;
  fail: (error?: unknown) => void;
}

export interface RefreshCoordinatorOptions extends Partial<RefreshPolicy>, RefreshTimers {
  run: (context: RefreshRun) => (() => void) | null | undefined | void;
  onFailure?: (error: unknown) => void;
}

export function initialRefreshState(): Readonly<RefreshState> {
  return Object.freeze({ phase: "idle", gen: 0, ticket: 0, active: false, pending: [], failures: 0 });
}

function next(state: Readonly<RefreshState>, changes: Partial<RefreshState>, effects: RefreshEffect[] = []): RefreshStep {
  return { state: Object.freeze({ ...state, ...changes }), effects };
}

function addReason(reasons: readonly string[], reason: string) {
  return reasons.includes(reason) ? reasons : [...reasons, reason];
}

export function reduceRefresh(state: Readonly<RefreshState>, event: RefreshEvent, policy: RefreshPolicy): RefreshStep {
  const unchanged = { state, effects: [] };
  if (state.phase === "disposed") return unchanged;
  switch (event.type) {
    case "request": {
      if (state.phase === "running" && !event.invalidate) return unchanged;
      const pending = addReason(state.pending, event.reason);
      if (state.phase === "waiting" || state.phase === "running") return next(state, { pending });
      const ticket = state.ticket + 1;
      return next(state, { phase: "waiting", pending, ticket }, [
        { kind: "schedule", ticket, delay: policy.batchMs },
      ]);
    }
    case "scheduled": {
      if (state.phase !== "waiting" || event.ticket !== state.ticket) return unchanged;
      const gen = state.gen + 1;
      return next(state, { phase: "running", gen, active: true, pending: [] }, [
        { kind: "stop" },
        { kind: "deadline", gen, delay: policy.timeoutMs },
        { kind: "start", gen, reasons: state.pending },
      ]);
    }
    case "complete": {
      if (!state.active || event.gen !== state.gen || state.phase !== "running") return unchanged;
      const effects: RefreshEffect[] = [{ kind: "cancelDeadline" }];
      if (state.pending.length) {
        const ticket = state.ticket + 1;
        effects.push({ kind: "schedule", ticket, delay: policy.batchMs });
        return next(state, { phase: "waiting", ticket, failures: 0 }, effects);
      }
      return next(state, { phase: "ready", failures: 0 }, effects);
    }
    case "failed": {
      if (!state.active || event.gen !== state.gen) return unchanged;
      const failures = state.failures + 1;
      const ticket = state.ticket + 1;
      const delay = Math.min(policy.maxRetryMs, policy.retryMs * 2 ** Math.min(failures - 1, 20));
      return next(state, {
        phase: "waiting", active: false, failures, ticket, pending: addReason(state.pending, "retry"),
      }, [
        { kind: "cancelDeadline" }, { kind: "cancelSchedule" }, { kind: "stop" },
        { kind: "schedule", ticket, delay }, { kind: "report", error: event.error },
      ]);
    }
    case "dispose":
      return next(state, { phase: "disposed", active: false, pending: [] }, [
        { kind: "cancelDeadline" }, { kind: "cancelSchedule" }, { kind: "stop" },
      ]);
    default:
      return unchanged;
  }
}

// run returns a disposer and calls complete only after applying its baseline. Subscriptions may keep
// delivering until disposed, and fail even after completion. invalidate requests require a newer run.
export function createRefreshCoordinator({
  run, onFailure = () => {}, schedule = setTimeout, cancel = (timer) => clearTimeout(timer ?? undefined),
  batchMs = 100, timeoutMs = 10_000, retryMs = 2000, maxRetryMs = 30_000,
}: RefreshCoordinatorOptions) {
  const policy = { batchMs, timeoutMs, retryMs, maxRetryMs };
  let state = initialRefreshState();
  let timer: TimerHandle | null = null;
  let deadline: TimerHandle | null = null;
  let resource: (() => void) | null | undefined | void = null;
  let dispatching = false;
  const events: RefreshEvent[] = [];

  function perform(effect: RefreshEffect) {
    switch (effect.kind) {
      case "schedule":
        timer = schedule(() => dispatch({ type: "scheduled", ticket: effect.ticket }), effect.delay);
        break;
      case "cancelSchedule":
        cancel(timer);
        timer = null;
        break;
      case "deadline":
        deadline = schedule(() => dispatch({
          type: "failed", gen: effect.gen, error: new Error("Refresh timed out"),
        }), effect.delay);
        break;
      case "cancelDeadline":
        cancel(deadline);
        deadline = null;
        break;
      case "stop": {
        const dispose = resource;
        resource = null;
        if (dispose) dispose();
        break;
      }
      case "start":
        try {
          resource = run({
            reasons: effect.reasons,
            isCurrent: () => state.active && state.gen === effect.gen,
            complete: () => dispatch({ type: "complete", gen: effect.gen }),
            fail: (error) => dispatch({ type: "failed", gen: effect.gen, error }),
          });
        } catch (error) {
          dispatch({ type: "failed", gen: effect.gen, error });
        }
        break;
      case "report":
        onFailure(effect.error);
        break;
    }
  }

  function dispatch(event: RefreshEvent) {
    events.push(event);
    if (dispatching) return;
    dispatching = true;
    try {
      while (events.length) {
        const step = reduceRefresh(state, events.shift()!, policy);
        state = step.state;
        for (const effect of step.effects) perform(effect);
      }
    } finally {
      dispatching = false;
    }
  }

  return {
    request: ({ reason = "requested", invalidate = false }: RefreshRequest = {}) => dispatch({ type: "request", reason, invalidate }),
    dispose: () => dispatch({ type: "dispose" }),
  };
}
