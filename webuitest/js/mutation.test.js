// The one mutation runner from resources/webui/lib/mutation.js. It replaced three unrelated idioms that
// each expressed "is something already running": a status-sentence comparison, a boolean save-in-flight
// ref, and a pending-action mirror ref. Everything it owns — the lock and the name it publishes — is
// browser-independent, so it is proven here instead of one level up.
//
// The two properties worth stating twice, because the flows in app.js depend on both: the lock is taken
// synchronously at the call, before the runner's first await, so a second flow entered in the same turn
// is refused rather than interleaved; and the lock is released only when the whole callback settles, so
// a mutation's own follow-up read still holds it. Those two are also why the runner hands the callback
// no currency token: with the lock exclusive, "is my mutation still the newest" has no reachable no.
// Whether a late outcome may still speak is asked of state/status.js instead, and proven in
// state-selection.test.js.

import { describe, test, beforeEach } from "node:test";
import assert from "node:assert/strict";

import { effect } from "../../resources/webui/vendor/signals-core.module.js";
import {
  MUTATION_BUSY_MESSAGE,
  pendingMutation,
  runMutation,
} from "../../resources/webui/lib/mutation.js";

// A deferred stands in for a request in flight: the run is suspended exactly where the network would
// suspend it, without a timer deciding the test's outcome.
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise: promise, resolve: resolve, reject: reject };
}

async function settle(promise) {
  try {
    return { value: await promise, error: null };
  } catch (error) {
    return { value: null, error: error };
  }
}

describe("runMutation", () => {
  beforeEach(() => {
    pendingMutation.value = null;
  });

  test("the lock is taken synchronously, before the callback's first await", () => {
    const gate = deferred();
    const run = runMutation("start", () => gate.promise);

    assert.equal(pendingMutation.value, "start");

    gate.resolve(null);
    return run;
  });

  test("one flow at a time: a second run is refused and its callback never runs", async () => {
    const gate = deferred();
    let secondRan = false;
    const first = runMutation("import", () => gate.promise);

    const refused = await settle(runMutation("link-task", () => {
      secondRan = true;
      return Promise.resolve("linked");
    }));

    assert.equal(secondRan, false, "a refused run must not reach its request");
    assert.equal(refused.error.message, MUTATION_BUSY_MESSAGE);
    assert.equal(pendingMutation.value, "import", "the refusal must not disturb the holder");

    gate.resolve("imported");
    assert.equal(await first, "imported");
    assert.equal(pendingMutation.value, null);
  });

  test("the lock survives the mutation's own follow-up read", async () => {
    const post = deferred();
    const read = deferred();
    const observed = [];

    const run = runMutation("link-task", async () => {
      await post.promise;
      observed.push(pendingMutation.value);
      const row = await read.promise;
      observed.push(pendingMutation.value);
      return row;
    });

    post.resolve(null);
    await post.promise;
    read.resolve("row");

    assert.equal(await run, "row");
    assert.deepEqual(
      observed,
      ["link-task", "link-task"],
      "the badge re-read runs inside the lock, so a second link cannot start and overwrite the first",
    );
    assert.equal(pendingMutation.value, null, "the lock is released once the whole callback settles");
  });

  test("a failed run releases the lock and rethrows the original failure", async () => {
    const failure = new Error("the daemon refused");
    const failed = await settle(runMutation("preferences", () => Promise.reject(failure)));

    assert.equal(failed.error, failure, "the runner reports the flow's own error, not its own");
    assert.equal(pendingMutation.value, null);
    assert.equal(await runMutation("resume", () => Promise.resolve("ok")), "ok");
  });

  test("a synchronous throw inside the callback also releases the lock", async () => {
    const failed = await settle(runMutation("stop", () => {
      throw new Error("nothing was sent");
    }));

    assert.equal(failed.error.message, "nothing was sent");
    assert.equal(pendingMutation.value, null);
  });

  // The runner passes the callback nothing, and that is the assertion: a currency token here would have
  // to answer "did a newer mutation start while mine ran", and the two tests above are why it cannot —
  // the second run is refused before it takes the lock, so no newer mutation exists to supersede the
  // holder. A flow that has to protect a sentence it already announced asks state/status.js instead.
  test("the callback is handed no currency token to mistake for one", async () => {
    let handed = "untouched";
    await runMutation("delete-project", (context) => {
      handed = context;
      return Promise.resolve(null);
    });

    assert.equal(handed, undefined);
  });

  test("the pending name is a signal, so a reader re-renders on both edges", async () => {
    const seen = [];
    const stop = effect(() => { seen.push(pendingMutation.value); });
    const gate = deferred();

    const run = runMutation("undone", () => gate.promise);
    gate.resolve(null);
    await run;
    stop();

    assert.deepEqual(seen, [null, "undone", null]);
  });

  test("the runner returns whatever the flow returned", async () => {
    assert.deepEqual(
      await runMutation("restore-project", () => Promise.resolve({ id: "p1", archived: false })),
      { id: "p1", archived: false },
    );
  });
});
