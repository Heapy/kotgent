// One coordinator per source. Decisions are pure; its runner owns timers and the current resource.
export function initialRefreshState() {
  return Object.freeze({ phase: "idle", gen: 0, ticket: 0, active: false, pending: [], failures: 0 });
}

function next(state, changes, effects = []) {
  return { state: Object.freeze({ ...state, ...changes }), effects };
}

function addReason(reasons, reason) {
  return reasons.includes(reason) ? reasons : [...reasons, reason];
}

export function reduceRefresh(state, event, policy) {
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
      const effects = [{ kind: "cancelDeadline" }];
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
  run, onFailure = () => {}, schedule = setTimeout, cancel = clearTimeout,
  batchMs = 100, timeoutMs = 10_000, retryMs = 2000, maxRetryMs = 30_000,
}) {
  const policy = { batchMs, timeoutMs, retryMs, maxRetryMs };
  let state = initialRefreshState();
  let timer = null;
  let deadline = null;
  let resource = null;
  let dispatching = false;
  const events = [];

  function perform(effect) {
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

  function dispatch(event) {
    events.push(event);
    if (dispatching) return;
    dispatching = true;
    try {
      while (events.length) {
        const step = reduceRefresh(state, events.shift(), policy);
        state = step.state;
        for (const effect of step.effects) perform(effect);
      }
    } finally {
      dispatching = false;
    }
  }

  return {
    request: ({ reason = "requested", invalidate = false } = {}) => dispatch({ type: "request", reason, invalidate }),
    dispose: () => dispatch({ type: "dispose" }),
  };
}
