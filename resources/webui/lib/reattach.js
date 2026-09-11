// Pure decision machine for terminal reattachment; app.js performs its timer, probe, attachment, and hint
// effects. Hidden pages retain candidates, grants wait for a candidate, and generations reject late work.

import { affectsAttachment } from "./commands.js";
import { isAliveState } from "./sessions.js";

export const SCHEDULE = "schedule";
export const CANCEL_TIMER = "cancelTimer";
export const PROBE = "probe";
export const ABORT_PROBE = "abortProbe";
export const ATTACH = "attach";
export const HINT_DEAD = "hintDead";
export const HINT_DETACHED = "hintDetached";
export const HINT_CLEAR = "hintClear";

const GRANT = "grant";
const GRANT_AND_SCHEDULE = "grantAndSchedule";
const TERMINAL_CLOSED = "terminalClosed";
const CANCEL = "cancel";
const SNAPSHOT_APPLIED = "snapshotApplied";
const SESSION_STATE_CHANGED = "sessionStateChanged";
const HIDDEN = "hidden";
const TIMER_FIRED = "timerFired";
const PROBE_RESOLVED = "probeResolved";
const PROBE_FAILED = "probeFailed";

export function initialReattachState() {
  return Object.freeze({ candidate: null, granted: false, timer: null, probe: null, gen: 0 });
}

export const grant = () => ({ type: GRANT });
export const grantAndSchedule = () => ({ type: GRANT_AND_SCHEDULE });
export const terminalClosed = (id, state = null) => ({ type: TERMINAL_CLOSED, id: id, state: state });
export const cancel = () => ({ type: CANCEL });
export const snapshotApplied = (liveIds) => ({ type: SNAPSHOT_APPLIED, liveIds: liveIds });
export const sessionStateChanged = (id, state) => ({ type: SESSION_STATE_CHANGED, id: id, state: state });
export const hidden = () => ({ type: HIDDEN });
export const timerFired = (gen) => ({ type: TIMER_FIRED, gen: gen });
export const probeResolved = (gen, row) => ({ type: PROBE_RESOLVED, gen: gen, row: row });
export const probeFailed = (gen, outcome) => ({
  type: PROBE_FAILED,
  gen: gen,
  definite: !!(outcome && outcome.definite),
});

function next(state, changes, effects = []) {
  return { state: Object.freeze({ ...state, ...changes }), effects: effects };
}

function unchanged(state) {
  return { state: state, effects: [] };
}

/** Arm the zero-delay timer only while visible, granted, and not already armed. */
function schedule(state, env) {
  if (!env.visible || !state.granted || state.timer !== null) return unchanged(state);
  const gen = state.gen + 1;
  return next(state, { gen: gen, timer: gen }, [{ kind: SCHEDULE, gen: gen }]);
}

function stop(state, changes) {
  const effects = [];
  if (state.timer !== null) effects.push({ kind: CANCEL_TIMER, gen: state.timer });
  if (state.probe !== null) effects.push({ kind: ABORT_PROBE, gen: state.probe.gen });
  return next(state, { timer: null, probe: null, ...changes }, effects);
}

export function reduceReattach(state, event, env) {
  switch (event.type) {
    case GRANT:
      return next(state, { granted: true });

    case GRANT_AND_SCHEDULE:
      return schedule(Object.freeze({ ...state, granted: true }), env);

    case TERMINAL_CLOSED:
      if (event.state != null && !isAliveState(event.state)) return unchanged(state);
      return schedule(Object.freeze({ ...state, candidate: event.id }), env);

    case CANCEL:
      return stop(state, { candidate: null, granted: false });

    case SNAPSHOT_APPLIED:
      if (!state.candidate || event.liveIds.has(state.candidate)) return unchanged(state);
      return stop(state, { candidate: null, granted: false });

    // Stop cancels before the POST and the pane dies before the answer, so the close that cancel meant
    // to ignore lands after it. The row's own state is what retires such a candidate.
    case SESSION_STATE_CHANGED:
      if (event.id !== state.candidate || isAliveState(event.state)) return unchanged(state);
      return stop(state, { candidate: null, granted: false });

    // The candidate survives; the grant does not, because foregrounding issues a fresh one.
    case HIDDEN:
      return stop(state, { granted: false });

    case TIMER_FIRED: {
      if (event.gen !== state.timer) return unchanged(state);
      const cleared = Object.freeze({ ...state, timer: null });
      // Do not spend a grant without a visible candidate.
      if (!cleared.candidate || !env.visible) return { state: cleared, effects: [] };
      const gen = cleared.gen + 1;
      const effects = [];
      if (cleared.probe !== null) effects.push({ kind: ABORT_PROBE, gen: cleared.probe.gen });
      effects.push({ kind: PROBE, id: cleared.candidate, gen: gen });
      return next(cleared, { granted: false, gen: gen, probe: { id: cleared.candidate, gen: gen } }, effects);
    }

    case PROBE_RESOLVED: {
      const probe = state.probe;
      if (!probe || probe.gen !== event.gen) return unchanged(state);
      // A current-generation probe can still belong to a replaced candidate.
      if (probe.id !== state.candidate) return next(state, { probe: null });
      const settled = Object.freeze({ ...state, probe: null });
      if (!env.visible) return { state: settled, effects: [] };
      if (env.activeSessionId !== probe.id) return next(settled, { candidate: null });
      // A pending control action temporarily owns the attachment; retain the candidate for after it.
      if (affectsAttachment(env.pending)) return { state: settled, effects: [] };
      const row = event.row;
      if (!row || !isAliveState(row.state)) {
        return next(settled, { candidate: null }, [{ kind: HINT_DEAD, state: row ? row.state : null }]);
      }
      return next(settled, { candidate: null }, [
        { kind: ATTACH, id: probe.id },
        { kind: HINT_CLEAR },
      ]);
    }

    case PROBE_FAILED: {
      const probe = state.probe;
      if (!probe || probe.gen !== event.gen) return unchanged(state);
      if (probe.id !== state.candidate) return next(state, { probe: null });
      const settled = Object.freeze({ ...state, probe: null });
      // Definite failures retire the candidate; transient ones leave it for the next recovery grant.
      const changes = event.definite ? { candidate: null } : {};
      const effects = env.activeSessionId === probe.id ? [{ kind: HINT_DETACHED }] : [];
      return next(settled, changes, effects);
    }

    default:
      return unchanged(state);
  }
}
