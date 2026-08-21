// Readiness from resources/webui/lib/readiness.js. It replaced three booleans — `tasksReady`,
// `projectsReady`, and the `ready`/`projectUnavailable` pair the link picker derived from them — which
// between them could spell 64 combinations, of which a handful were legal and none could say "the read
// failed". That gap is finding app.js:388: one 503 on GET /projects left the picker reading "Reading open
// tasks…" with no error, no retry and no timeout, until a board round-trip or a reload repaired it.
//
// Nothing here touches the DOM, the network, or a timer, so every transition is proven at this tier
// rather than through a browser. The two rules worth stating twice, because the picker depends on both:
//
//   * a terminal `failed` always carries a non-empty sentence. A failure with nothing to say is the
//     stuck-forever state under a different name.
//   * `ready` is sticky. A refresh over a good list keeps rendering that list, and a refresh that fails
//     does not blank it — the picker opens against data it already has, and only a source that has never
//     succeeded can fail visibly.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { effect } from "../../resources/webui/vendor/signals-core.module.js";
import {
  FAILED,
  IDLE,
  LOADING,
  READY,
  combineReadiness,
  createReadiness,
} from "../../resources/webui/lib/readiness.js";

function deferred() {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise: promise, resolve: resolve };
}

describe("createReadiness", () => {
  test("a source nobody has read yet is idle, and idle carries no error", () => {
    const readiness = createReadiness();
    assert.deepEqual(readiness.status.value, { state: IDLE, error: null });
  });

  test("begin then succeed is the whole happy path: idle → loading → ready", () => {
    const readiness = createReadiness();
    const token = readiness.begin();
    assert.equal(readiness.status.value.state, LOADING);
    assert.equal(readiness.succeed(token), true);
    assert.deepEqual(readiness.status.value, { state: READY, error: null });
  });

  test("a failed read is terminal and says why", () => {
    const readiness = createReadiness();
    const token = readiness.begin();
    assert.equal(readiness.fail(token, new Error("projects: 503")), true);
    assert.deepEqual(readiness.status.value, { state: FAILED, error: "projects: 503" });
  });

  test("a failure with nothing to say still reaches the operator with a sentence", () => {
    for (const nothing of [undefined, null, new Error(""), "   ", {}]) {
      const readiness = createReadiness();
      readiness.fail(readiness.begin(), nothing);
      const status = readiness.status.value;
      assert.equal(status.state, FAILED);
      assert.equal(typeof status.error, "string");
      assert.ok(status.error.trim().length > 0, "a terminal state with no user-visible outcome");
    }
  });

  test("a pre-formatted sentence is carried verbatim", () => {
    const readiness = createReadiness();
    readiness.fail(readiness.begin(), "Could not load projects: offline");
    assert.equal(readiness.status.value.error, "Could not load projects: offline");
  });

  test("loading → failed → loading on retry, and the retry re-runs the registered load", async () => {
    const calls = [];
    const readiness = createReadiness();
    readiness.setLoader(() => {
      const token = readiness.begin();
      calls.push(token);
      return Promise.resolve(token);
    });

    const first = readiness.begin();
    assert.equal(readiness.status.value.state, LOADING);
    readiness.fail(first, new Error("offline"));
    assert.equal(readiness.status.value.state, FAILED);

    await readiness.retry();
    assert.equal(calls.length, 1, "retry runs the load the owner registered");
    assert.equal(readiness.status.value.state, LOADING);
    assert.equal(readiness.status.value.error, null, "the retry clears the sentence it is retrying");

    readiness.succeed(calls[0]);
    assert.equal(readiness.status.value.state, READY);
  });

  test("a retry cannot start a second read while one is already running", async () => {
    let runs = 0;
    const gate = deferred();
    const readiness = createReadiness();
    readiness.setLoader(() => {
      runs += 1;
      readiness.begin();
      return gate.promise;
    });

    readiness.fail(readiness.begin(), "offline");
    const running = readiness.retry();
    assert.equal(runs, 1);
    await readiness.retry();
    assert.equal(runs, 1, "the second press of the retry control is refused, not queued");

    gate.resolve(null);
    await running;
  });

  test("a readiness with no loader has an inert retry rather than a broken one", async () => {
    const readiness = createReadiness();
    readiness.fail(readiness.begin(), "offline");
    await readiness.retry();
    assert.equal(readiness.status.value.state, FAILED);
  });

  test("a superseded attempt cannot write an outcome", () => {
    const readiness = createReadiness();
    const first = readiness.begin();
    const second = readiness.begin();

    assert.equal(readiness.succeed(second), true);
    assert.equal(readiness.fail(first, "the older read failed later"), false);
    assert.deepEqual(readiness.status.value, { state: READY, error: null });
  });

  test("an untokened outcome is authoritative and supersedes the attempt in flight", () => {
    const readiness = createReadiness();
    const inFlight = readiness.begin();

    // A pushed snapshot — the task list's only source — owns no token and answers for everyone.
    assert.equal(readiness.succeed(), true);
    assert.equal(readiness.status.value.state, READY);
    assert.equal(readiness.fail(inFlight, "the read this snapshot overtook"), false);
    assert.equal(readiness.status.value.state, READY);
  });

  test("ready is sticky: a refresh over a good list neither blanks it nor loses it", () => {
    const readiness = createReadiness();
    readiness.succeed(readiness.begin());

    const token = readiness.begin();
    assert.equal(readiness.status.value.state, READY, "an open-picker refresh must not flicker");
    assert.equal(readiness.fail(token, "the revalidation failed"), false);
    assert.deepEqual(
      readiness.status.value,
      { state: READY, error: null },
      "a failed revalidation leaves the stale-but-usable list on screen",
    );
  });

  test("reset takes a source back to idle, and a read in flight cannot resurrect it", () => {
    const readiness = createReadiness();
    const token = readiness.begin();
    readiness.reset();
    assert.deepEqual(readiness.status.value, { state: IDLE, error: null });
    assert.equal(readiness.succeed(token), false);
    assert.equal(readiness.status.value.state, IDLE);
  });

  test("an unchanged status costs no render", () => {
    const readiness = createReadiness();
    let renders = 0;
    const stop = effect(() => {
      readiness.status.value;
      renders += 1;
    });
    assert.equal(renders, 1);

    readiness.begin();
    assert.equal(renders, 2);
    readiness.begin();
    assert.equal(renders, 2, "a second refresh of the same source is not a new state");

    readiness.succeed();
    assert.equal(renders, 3);
    readiness.succeed();
    assert.equal(renders, 3);
    stop();
  });
});

describe("combineReadiness", () => {
  const idle = { state: IDLE, error: null };
  const loading = { state: LOADING, error: null };
  const ready = { state: READY, error: null };
  const failed = { state: FAILED, error: "Could not load projects: 503" };
  const alsoFailed = { state: FAILED, error: "Could not load tasks: 503" };

  test("the picker is ready only when every source it judges with is", () => {
    assert.equal(combineReadiness(ready, ready).state, READY);
    assert.equal(combineReadiness(ready, loading).state, LOADING);
    assert.equal(combineReadiness(ready, idle).state, IDLE);
    assert.equal(combineReadiness(idle, idle).state, IDLE);
  });

  test("a failure dominates: one unread source is the whole screen's answer", () => {
    assert.deepEqual(combineReadiness(loading, failed), failed);
    assert.deepEqual(combineReadiness(ready, failed), failed);
    assert.deepEqual(combineReadiness(idle, failed), failed);
  });

  test("the first failure is the one shown, so the sentence does not shuffle between renders", () => {
    assert.equal(combineReadiness(failed, alsoFailed).error, failed.error);
    assert.equal(combineReadiness(alsoFailed, failed).error, alsoFailed.error);
  });

  test("combining nothing is idle rather than a vacuous ready", () => {
    assert.equal(combineReadiness().state, IDLE);
  });
});
