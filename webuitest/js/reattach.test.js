// Pure coverage for the reattach machine's ordered guards, generation-stamped effects, retained hidden
// candidates, and grants that may arrive before a terminal-close candidate.

import { describe, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  ABORT_PROBE,
  ATTACH,
  CANCEL_TIMER,
  HINT_CLEAR,
  HINT_DEAD,
  HINT_DETACHED,
  PROBE,
  SCHEDULE,
  cancel,
  grant,
  grantAndSchedule,
  hidden,
  initialReattachState,
  probeFailed,
  probeResolved,
  reduceReattach,
  sessionStateChanged,
  sessionsPruned,
  terminalClosed,
  timerFired,
} from "../../resources/webui/lib/reattach.js";
import * as machine from "../../resources/webui/lib/reattach.js";
import { sessionRow } from "./fixtures.js";

const VISIBLE = Object.freeze({ visible: true, activeSessionId: "s1", pending: null });
const HIDDEN = Object.freeze({ visible: false, activeSessionId: "s1", pending: null });

/** Reset all state except the monotonic generation that rejects pre-cancel effects. */
function idle(state) {
  return { candidate: state.candidate, granted: state.granted, timer: state.timer, probe: state.probe };
}

const IDLE = Object.freeze(idle(initialReattachState()));

function run(state, events, env = VISIBLE) {
  let current = state;
  let effects = [];
  for (const event of events) {
    const step = reduceReattach(current, event, env);
    current = step.state;
    effects = step.effects;
  }
  return { state: current, effects: effects };
}

function kinds(effects) {
  return effects.map((effect) => effect.kind);
}

/** The state a probe for `s1` is running in: closed, granted, timer fired, request in flight. */
function probing(env = VISIBLE) {
  return run(initialReattachState(), [grant(), terminalClosed("s1"), timerFired(1)], env);
}

describe("grants and scheduling", () => {
  test("a grant arms nothing, so it survives until a candidate exists", () => {
    const { state, effects } = run(initialReattachState(), [grant()]);
    assert.equal(state.granted, true);
    assert.equal(state.candidate, null);
    assert.equal(state.timer, null);
    assert.deepEqual(effects, []);
  });

  test("a terminal closing while visible names the candidate and arms the timer", () => {
    const { state, effects } = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    assert.equal(state.candidate, "s1");
    assert.equal(state.granted, true);
    assert.notEqual(state.timer, null);
    assert.deepEqual(kinds(effects), [SCHEDULE]);
  });

  test("a terminal closing while hidden names the candidate and arms nothing", () => {
    const { state, effects } = run(initialReattachState(), [grant(), terminalClosed("s1")], HIDDEN);
    assert.equal(state.candidate, "s1");
    assert.equal(state.timer, null);
    assert.deepEqual(effects, []);
  });

  test("a terminal closing without a grant arms nothing", () => {
    const { state, effects } = run(initialReattachState(), [terminalClosed("s1")]);
    assert.equal(state.candidate, "s1");
    assert.equal(state.granted, false);
    assert.equal(state.timer, null);
    assert.deepEqual(effects, []);
  });

  test("the timer is armed once, however many reasons to schedule arrive", () => {
    const first = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const second = reduceReattach(first.state, grantAndSchedule(), VISIBLE);
    assert.deepEqual(second.effects, []);
    assert.equal(second.state.timer, first.state.timer);
  });

  test("foregrounding grants and schedules in one event", () => {
    const closed = run(initialReattachState(), [terminalClosed("s1")], HIDDEN);
    const { state, effects } = reduceReattach(closed.state, grantAndSchedule(), VISIBLE);
    assert.equal(state.granted, true);
    assert.deepEqual(kinds(effects), [SCHEDULE]);
  });

  test("nothing is scheduled while the page is hidden", () => {
    const closed = run(initialReattachState(), [terminalClosed("s1")], HIDDEN);
    const { effects } = reduceReattach(closed.state, grantAndSchedule(), HIDDEN);
    assert.deepEqual(effects, []);
  });
});

describe("the timer", () => {
  test("firing with a candidate spends the grant and probes it", () => {
    const armed = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const { state, effects } = reduceReattach(armed.state, timerFired(armed.state.timer), VISIBLE);
    assert.equal(state.timer, null);
    assert.equal(state.granted, false);
    assert.equal(state.probe.id, "s1");
    assert.deepEqual(kinds(effects), [PROBE]);
    assert.equal(effects[0].id, "s1");
  });

  test("firing with no candidate preserves the grant for the close that follows", () => {
    const armed = run(initialReattachState(), [grantAndSchedule()]);
    const { state, effects } = reduceReattach(armed.state, timerFired(armed.state.timer), VISIBLE);
    assert.equal(state.granted, true);
    assert.equal(state.probe, null);
    assert.deepEqual(effects, []);
  });

  test("firing while hidden preserves the grant", () => {
    const armed = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const { state, effects } = reduceReattach(armed.state, timerFired(armed.state.timer), HIDDEN);
    assert.equal(state.granted, true);
    assert.equal(state.candidate, "s1");
    assert.equal(state.probe, null);
    assert.deepEqual(effects, []);
  });

  test("a stale generation changes nothing and says nothing", () => {
    const armed = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const { state, effects } = reduceReattach(armed.state, timerFired(armed.state.timer + 100), VISIBLE);
    assert.deepEqual(state, armed.state);
    assert.deepEqual(effects, []);
  });

  test("a probe replaces an earlier one by aborting it first", () => {
    const first = probing();
    const again = run(first.state, [grant(), terminalClosed("s2")]);
    const { state, effects } = reduceReattach(again.state, timerFired(again.state.timer), VISIBLE);
    assert.deepEqual(kinds(effects), [ABORT_PROBE, PROBE]);
    assert.equal(effects[0].gen, first.state.probe.gen);
    assert.equal(state.probe.id, "s2");
  });
});

describe("probeResolved, in guard order", () => {
  test("A: a stale generation changes nothing and says nothing", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeResolved(state.probe.gen + 100, sessionRow()), VISIBLE);
    assert.deepEqual(step.state, state);
    assert.deepEqual(step.effects, []);
  });

  test("B: a probe about a session that is no longer the candidate changes nothing", () => {
    const first = probing();
    const moved = run(first.state, [terminalClosed("s2")]);
    const step = reduceReattach(moved.state, probeResolved(first.state.probe.gen, sessionRow()), VISIBLE);
    assert.equal(step.state.candidate, "s2");
    assert.deepEqual(step.effects, []);
    assert.equal(step.state.probe, null, "the request settled, whoever it turned out to be about");
  });

  test("B: a settled probe is not aborted again by the cancel that follows", () => {
    const first = probing();
    const moved = run(first.state, [terminalClosed("s2")]);
    const settled = reduceReattach(moved.state, probeResolved(first.state.probe.gen, sessionRow()), VISIBLE);
    const step = reduceReattach(settled.state, cancel(), VISIBLE);
    assert.deepEqual(step.effects, [], "app.js released that generation when the answer arrived");
  });

  test("C: hidden keeps the candidate and attaches nothing", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow()), HIDDEN);
    assert.equal(step.state.candidate, "s1");
    assert.equal(step.state.probe, null);
    assert.deepEqual(step.effects, []);
  });

  test("D: a selection that moved clears the candidate and says nothing", () => {
    const { state } = probing();
    const env = { visible: true, activeSessionId: "s2", pending: null };
    const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow()), env);
    assert.equal(step.state.candidate, null);
    assert.deepEqual(step.effects, []);
  });

  test("D outranks E: a moved selection clears even while a mutation is pending", () => {
    const { state } = probing();
    const env = { visible: true, activeSessionId: "s2", pending: "resume" };
    const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow()), env);
    assert.equal(step.state.candidate, null);
    assert.deepEqual(step.effects, []);
  });

  test("E: a pending control action owns the attachment, so the candidate is retained", () => {
    for (const pending of ["stop", "done", "resume", "import"]) {
      const { state } = probing();
      const env = { visible: true, activeSessionId: "s1", pending: pending };
      const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow()), env);
      assert.equal(step.state.candidate, "s1", pending + " must retain the candidate");
      assert.equal(step.state.probe, null);
      assert.deepEqual(step.effects, []);
    }
  });

  // Mutations unrelated to attachment must not suppress reattach scheduling.
  test("E: a mutation that rewrites no attachment does not hold the reattach", () => {
    for (const pending of ["rename", "link-task", "preferences", "start", "delete-project"]) {
      const { state } = probing();
      const env = { visible: true, activeSessionId: "s1", pending: pending };
      const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow()), env);
      assert.deepEqual(kinds(step.effects), [ATTACH, HINT_CLEAR], pending + " must not hold the attach");
    }
  });

  test("F: a dead session retires the candidate and explains why", () => {
    const { state } = probing();
    const row = sessionRow({ state: "resumable", alive: false });
    const step = reduceReattach(state, probeResolved(state.probe.gen, row), VISIBLE);
    assert.equal(step.state.candidate, null);
    assert.deepEqual(kinds(step.effects), [HINT_DEAD]);
    assert.equal(step.effects[0].state, "resumable");
  });

  test("F: an absent row is a dead row, and its state is unknown", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeResolved(state.probe.gen, null), VISIBLE);
    assert.equal(step.state.candidate, null);
    assert.deepEqual(kinds(step.effects), [HINT_DEAD]);
    assert.equal(step.effects[0].state, null);
  });

  test("G: a live session is attached and the hint is cleared", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow()), VISIBLE);
    assert.equal(step.state.candidate, null);
    assert.equal(step.state.probe, null);
    assert.deepEqual(kinds(step.effects), [ATTACH, HINT_CLEAR]);
    assert.equal(step.effects[0].id, "s1");
  });

  test("every alive state attaches and no dead state does", () => {
    for (const alive of ["running", "ready", "needs_approval", "needs_answer"]) {
      const { state } = probing();
      const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow({ state: alive })), VISIBLE);
      assert.deepEqual(kinds(step.effects), [ATTACH, HINT_CLEAR], alive + " must attach");
    }
    for (const dead of ["stopped", "crashed", "resumable"]) {
      const { state } = probing();
      const step = reduceReattach(state, probeResolved(state.probe.gen, sessionRow({ state: dead })), VISIBLE);
      assert.deepEqual(kinds(step.effects), [HINT_DEAD], dead + " must not attach");
    }
  });
});

describe("probeFailed", () => {
  test("a definite answer retires the candidate", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeFailed(state.probe.gen, { definite: true }), VISIBLE);
    assert.equal(step.state.candidate, null);
    assert.deepEqual(kinds(step.effects), [HINT_DETACHED]);
  });

  test("a transient failure keeps the candidate for the next grant", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeFailed(state.probe.gen, { definite: false }), VISIBLE);
    assert.equal(step.state.candidate, "s1");
    assert.equal(step.state.probe, null);
    assert.deepEqual(kinds(step.effects), [HINT_DETACHED]);
  });

  test("the hint belongs to the selected session, so another selection is told nothing", () => {
    const { state } = probing();
    const env = { visible: true, activeSessionId: "s2", pending: null };
    const step = reduceReattach(state, probeFailed(state.probe.gen, { definite: false }), env);
    assert.deepEqual(step.effects, []);
  });

  test("a stale generation changes nothing and says nothing", () => {
    const { state } = probing();
    const step = reduceReattach(state, probeFailed(state.probe.gen + 100, { definite: true }), VISIBLE);
    assert.deepEqual(step.state, state);
    assert.deepEqual(step.effects, []);
  });

  // A current-generation failure still must match the candidate it could retire.
  test("a failure about one session does not retire another session's candidate", () => {
    const first = probing();
    const moved = run(first.state, [terminalClosed("s2")]);
    const step = reduceReattach(moved.state, probeFailed(first.state.probe.gen, { definite: true }), VISIBLE);
    assert.equal(step.state.candidate, "s2");
    assert.deepEqual(step.effects, []);
    assert.equal(step.state.probe, null, "the request settled, whoever it turned out to be about");
  });

  test("a settled failure is not aborted again by the cancel that follows", () => {
    const first = probing();
    const moved = run(first.state, [terminalClosed("s2")]);
    const settled = reduceReattach(moved.state, probeFailed(first.state.probe.gen, { definite: true }), VISIBLE);
    const step = reduceReattach(settled.state, cancel(), VISIBLE);
    assert.deepEqual(step.effects, [], "app.js released that generation when the failure arrived");
  });
});

describe("hidden, cancel and the snapshot prune", () => {
  // Cancellation must spend an unconsumed grant before the ensuing pane death.
  test("cancel spends an unspent grant", () => {
    const granted = run(initialReattachState(), [grant()]);
    const step = reduceReattach(granted.state, cancel(), VISIBLE);
    assert.equal(step.state.granted, false);
  });

  test("hidden spends an unspent grant while keeping the candidate", () => {
    const closed = run(initialReattachState(), [grant(), terminalClosed("s1")], HIDDEN);
    const step = reduceReattach(closed.state, hidden(), HIDDEN);
    assert.equal(step.state.granted, false);
    assert.equal(step.state.candidate, "s1");
  });

  test("a prune that dropped the candidate spends an unspent grant", () => {
    const armed = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const step = reduceReattach(armed.state, sessionsPruned(new Set(["s2"])), VISIBLE);
    assert.equal(step.state.granted, false);
    assert.equal(step.state.candidate, null);
  });

  test("hidden keeps the candidate while spending nothing", () => {
    const { state } = probing();
    const step = reduceReattach(state, hidden(), HIDDEN);
    assert.equal(step.state.candidate, "s1");
    assert.equal(step.state.granted, false);
    assert.equal(step.state.probe, null);
    assert.deepEqual(kinds(step.effects), [ABORT_PROBE]);
  });

  test("hidden with a timer armed clears the timer, not the candidate", () => {
    const armed = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const step = reduceReattach(armed.state, hidden(), HIDDEN);
    assert.equal(step.state.candidate, "s1");
    assert.equal(step.state.timer, null);
    assert.deepEqual(kinds(step.effects), [CANCEL_TIMER]);
  });

  test("cancel returns the machine to idle and stops only what was running", () => {
    const { state } = probing();
    const step = reduceReattach(state, cancel(), VISIBLE);
    assert.deepEqual(idle(step.state), IDLE);
    assert.deepEqual(kinds(step.effects), [ABORT_PROBE]);
  });

  test("cancel over an idle machine says nothing", () => {
    const step = reduceReattach(initialReattachState(), cancel(), VISIBLE);
    assert.deepEqual(idle(step.state), IDLE);
    assert.deepEqual(step.effects, []);
  });

  test("cancel never rewinds the generation, so a timer from before it cannot fire after", () => {
    const { state } = probing();
    const cancelled = reduceReattach(state, cancel(), VISIBLE);
    const rearmed = run(cancelled.state, [grant(), terminalClosed("s1")]);
    assert.notEqual(rearmed.state.timer, state.timer);
    const stale = reduceReattach(rearmed.state, timerFired(state.timer), VISIBLE);
    assert.deepEqual(stale.effects, []);
  });

  test("a snapshot that still lists the candidate changes nothing", () => {
    const { state } = probing();
    const step = reduceReattach(state, sessionsPruned(new Set(["s1", "s2"])), VISIBLE);
    assert.deepEqual(step.state, state);
    assert.deepEqual(step.effects, []);
  });

  test("a snapshot that dropped the candidate cancels", () => {
    const { state } = probing();
    const step = reduceReattach(state, sessionsPruned(new Set(["s2"])), VISIBLE);
    assert.deepEqual(idle(step.state), IDLE);
    assert.deepEqual(kinds(step.effects), [ABORT_PROBE]);
  });

  test("a snapshot with no candidate held changes nothing, whatever it lists", () => {
    const granted = run(initialReattachState(), [grant()]);
    const step = reduceReattach(granted.state, sessionsPruned(new Set()), VISIBLE);
    assert.deepEqual(step.state, granted.state);
    assert.deepEqual(step.effects, []);
  });
});

describe("a candidate whose session is dead", () => {
  // Stop dispatches cancel BEFORE the POST, and the daemon kills the pane before it answers, so the
  // close that cancel meant to ignore arrives after it and names the stopped session as the candidate.
  test("the stop journey leaves no candidate for the next grant to spend", () => {
    const stopped = run(initialReattachState(), [
      grant(),
      cancel(),
      terminalClosed("s1"),
      sessionStateChanged("s1", "stopped"),
    ]);
    assert.equal(stopped.state.candidate, null);
    const back = run(stopped.state, [hidden(), grantAndSchedule()]);
    const fired = reduceReattach(back.state, timerFired(back.state.timer), VISIBLE);
    assert.deepEqual(fired.effects, [], "foregrounding has nothing to probe");
    assert.equal(fired.state.granted, true, "the grant waits for a real drop");
  });

  test("a close reported with a dead state records no candidate", () => {
    for (const dead of ["stopped", "crashed", "resumable"]) {
      const { state, effects } = run(initialReattachState(), [grant(), terminalClosed("s1", dead)]);
      assert.equal(state.candidate, null, dead + " must not become the candidate");
      assert.deepEqual(effects, []);
    }
  });

  test("a close reported alive, or with no row to judge, still records the candidate", () => {
    for (const known of ["running", null, undefined]) {
      const { state } = run(initialReattachState(), [grant(), terminalClosed("s1", known)]);
      assert.equal(state.candidate, "s1");
    }
  });

  test("a crash arriving while the probe is in flight aborts it and retires the candidate", () => {
    const { state } = probing();
    const step = reduceReattach(state, sessionStateChanged("s1", "crashed"), VISIBLE);
    assert.equal(step.state.candidate, null);
    assert.equal(step.state.probe, null);
    assert.deepEqual(kinds(step.effects), [ABORT_PROBE]);
  });

  test("another session's state, or a live state, changes nothing", () => {
    const { state } = probing();
    for (const event of [sessionStateChanged("s2", "stopped"), sessionStateChanged("s1", "ready")]) {
      const step = reduceReattach(state, event, VISIBLE);
      assert.deepEqual(step.state, state);
      assert.deepEqual(step.effects, []);
    }
  });
});

describe("the reconnect journey", () => {
  // A failed liveness read retains the candidate for the recovered socket's grant.
  test("an unreachable daemon keeps the candidate and the recovered socket spends it", () => {
    const first = probing();
    const failed = reduceReattach(first.state, probeFailed(first.state.probe.gen, { definite: false }), VISIBLE);
    assert.equal(failed.state.candidate, "s1");
    assert.equal(failed.state.granted, false);

    const regranted = reduceReattach(failed.state, grantAndSchedule(), VISIBLE);
    assert.deepEqual(kinds(regranted.effects), [SCHEDULE]);

    const second = reduceReattach(regranted.state, timerFired(regranted.state.timer), VISIBLE);
    assert.deepEqual(kinds(second.effects), [PROBE]);
    assert.equal(second.effects[0].id, "s1");
  });

  test("a snapshot arriving between the grant and the timer leaves the journey intact", () => {
    const first = probing();
    const failed = reduceReattach(first.state, probeFailed(first.state.probe.gen, { definite: false }), VISIBLE);
    const regranted = reduceReattach(failed.state, grantAndSchedule(), VISIBLE);
    const pruned = reduceReattach(regranted.state, sessionsPruned(new Set(["s1"])), VISIBLE);
    const second = reduceReattach(pruned.state, timerFired(pruned.state.timer), VISIBLE);
    assert.deepEqual(kinds(second.effects), [PROBE]);
  });

  test("backgrounding and foregrounding costs the journey nothing but a grant", () => {
    const closed = run(initialReattachState(), [grant(), terminalClosed("s1")]);
    const away = reduceReattach(closed.state, hidden(), HIDDEN);
    const back = reduceReattach(away.state, grantAndSchedule(), VISIBLE);
    const fired = reduceReattach(back.state, timerFired(back.state.timer), VISIBLE);
    assert.deepEqual(kinds(fired.effects), [PROBE]);
    assert.equal(fired.effects[0].id, "s1");
  });
});

// Close the adapter vocabulary so a new reducer effect cannot be silently discarded by app.js.
describe("the adapter's effect vocabulary", () => {
  test("app.js carries an arm for every effect kind the machine declares", () => {
    const source = readFileSync(new URL("../../resources/webui/app.js", import.meta.url), "utf8");
    const kinds = Object.keys(machine).filter((name) =>
      /^[A-Z][A-Z_]*$/.test(name) && typeof machine[name] === "string");
    assert.ok(kinds.length >= 8, "the scan found no effect kinds to check");
    for (const kind of kinds) {
      assert.match(source, new RegExp("\\bcase " + kind + ":"), kind + " has no arm in app.js");
    }
  });
});
