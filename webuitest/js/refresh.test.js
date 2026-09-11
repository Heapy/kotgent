// The serial queue discards superseded reads, gives each read its own readiness token, and reports a
// failed read only when at least one waiter requested an announcement.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { createSerialRefresh } from "../../resources/webui/lib/refresh.js";
import { deferred, flush } from "./fixtures.js";

/** A recording adapter whose reads are resolved by hand, so ordering is chosen rather than raced. */
function harness(applyRows = null) {
  const reads = [];
  const calls = { begin: 0, succeed: [], fail: [], report: [] };
  let tokens = 0;
  const refresh = createSerialRefresh({
    read: () => {
      const gate = deferred();
      reads.push(gate);
      return gate.promise;
    },
    begin: () => {
      calls.begin += 1;
      return "token-" + (++tokens);
    },
    // Recorded after the store has taken the rows, so a store that refuses them records no application.
    succeed: (rows) => {
      if (applyRows) applyRows(rows);
      calls.succeed.push(rows);
    },
    fail: (token, error) => calls.fail.push({ token: token, error: error }),
    report: (error) => calls.report.push(error),
  });
  return { refresh: refresh, reads: reads, calls: calls };
}

describe("one read at a time", () => {
  test("a single request reads once, applies the rows and resolves with them", async () => {
    const h = harness();
    const result = h.refresh();
    await flush();
    assert.equal(h.reads.length, 1);
    h.reads[0].resolve(["p1"]);
    assert.deepEqual(await result, ["p1"]);
    assert.deepEqual(h.calls.succeed, [["p1"]]);
    assert.equal(h.calls.begin, 1);
  });

  test("a second request while one is in flight starts no second read", async () => {
    const h = harness();
    h.refresh();
    await flush();
    h.refresh();
    await flush();
    assert.equal(h.reads.length, 1);
  });

  test("a request made after the queue went idle starts a new read", async () => {
    const h = harness();
    const first = h.refresh();
    await flush();
    h.reads[0].resolve(["p1"]);
    await first;
    await flush();

    const second = h.refresh();
    await flush();
    assert.equal(h.reads.length, 2);
    h.reads[1].resolve(["p2"]);
    assert.deepEqual(await second, ["p2"]);
  });
});

describe("a later request is a later observation", () => {
  test("an answer that arrives after a newer request is discarded, not applied", async () => {
    const h = harness();
    const first = h.refresh();
    await flush();
    const second = h.refresh();
    await flush();

    h.reads[0].resolve(["stale"]);
    await flush();
    assert.deepEqual(h.calls.succeed, [], "the overtaken read applies nothing");
    assert.equal(h.reads.length, 2, "the queue reads again for the newer request");

    h.reads[1].resolve(["fresh"]);
    assert.deepEqual(await first, ["fresh"]);
    assert.deepEqual(await second, ["fresh"]);
    assert.deepEqual(h.calls.succeed, [["fresh"]]);
  });

  test("a discarded failure reports nothing and fails nothing", async () => {
    const h = harness();
    h.refresh();
    await flush();
    h.refresh();
    await flush();

    h.reads[0].reject(new Error("overtaken"));
    await flush();
    assert.deepEqual(h.calls.fail, []);
    assert.deepEqual(h.calls.report, []);
  });

  test("every waiter the settled read covers is resolved with that read's rows", async () => {
    const h = harness();
    const a = h.refresh();
    await flush();
    const b = h.refresh();
    const c = h.refresh();
    await flush();
    h.reads[0].resolve(["stale"]);
    await flush();
    h.reads[1].resolve(["fresh"]);
    assert.deepEqual(await Promise.all([a, b, c]), [["fresh"], ["fresh"], ["fresh"]]);
    assert.deepEqual(h.calls.succeed, [["fresh"]], "the list is applied once, not once per waiter");
  });
});

describe("failure", () => {
  test("a failure resolves waiters with null rather than rejecting", async () => {
    const h = harness();
    const result = h.refresh();
    await flush();
    h.reads[0].reject(new Error("503"));
    assert.equal(await result, null);
  });

  test("a failure fails the readiness and reports the sentence", async () => {
    const h = harness();
    const boom = new Error("503");
    h.refresh();
    await flush();
    h.reads[0].reject(boom);
    await flush();
    assert.equal(h.calls.fail.length, 1);
    assert.equal(h.calls.fail[0].error, boom);
    assert.deepEqual(h.calls.report, [boom]);
  });

  test("a quiet refresh fails the readiness but says nothing out loud", async () => {
    const h = harness();
    h.refresh(false);
    await flush();
    h.reads[0].reject(new Error("503"));
    await flush();
    assert.equal(h.calls.fail.length, 1, "the picker still needs its retry state");
    assert.deepEqual(h.calls.report, [], "nobody asked to be told");
  });

  test("one waiter asking to be told is enough for the whole settled read", async () => {
    const h = harness();
    h.refresh(false);
    await flush();
    h.refresh(true);
    await flush();
    h.reads[0].reject(new Error("first"));
    await flush();
    h.reads[1].reject(new Error("second"));
    await flush();
    assert.equal(h.calls.report.length, 1);
  });

  test("two callers asking to be told still hear it once", async () => {
    const h = harness();
    h.refresh(true);
    await flush();
    h.refresh(true);
    await flush();
    h.reads[0].reject(new Error("first"));
    await flush();
    h.reads[1].reject(new Error("second"));
    await flush();
    assert.equal(h.calls.report.length, 1, "announced once for the read, not once per waiter");
  });

  test("a failed read does not stop the queue", async () => {
    const h = harness();
    const first = h.refresh();
    await flush();
    h.reads[0].reject(new Error("503"));
    assert.equal(await first, null);
    await flush();

    const second = h.refresh();
    await flush();
    h.reads[1].resolve(["p1"]);
    assert.deepEqual(await second, ["p1"]);
  });
});

// A store refusal fails the refresh and releases the queue for later work.
describe("a port that throws", () => {
  test("is a failed refresh, and the queue stays usable", { timeout: 500 }, async () => {
    const boom = new Error("the store blew up");
    let firstApply = true;
    const h = harness(() => {
      if (!firstApply) return;
      firstApply = false;
      throw boom;
    });

    const first = h.refresh();
    await flush();
    h.reads[0].resolve(["p1"]);
    assert.equal(await first, null, "rows the store refused were never loaded");
    assert.deepEqual(h.calls.succeed, [], "nothing was applied");
    assert.equal(h.calls.fail.length, 1);
    assert.equal(h.calls.fail[0].error, boom);
    assert.deepEqual(h.calls.report, [boom], "the operator hears the real reason");

    await flush();
    const second = h.refresh();
    await flush();
    assert.equal(h.reads.length, 2, "the queue is not wedged");
    h.reads[1].resolve(["p2"]);
    assert.deepEqual(await second, ["p2"]);
  });
});

describe("readiness tokens", () => {
  test("begin is called once per read, not once per request", async () => {
    const h = harness();
    h.refresh();
    await flush();
    h.refresh();
    h.refresh();
    await flush();
    assert.equal(h.calls.begin, 1, "three requests, one read so far");

    h.reads[0].resolve(["stale"]);
    await flush();
    assert.equal(h.calls.begin, 2, "the second read opens its own attempt");
  });

  test("a failure carries the token of the read that settled, not of an earlier one", async () => {
    const h = harness();
    h.refresh();
    await flush();
    h.refresh();
    await flush();
    h.reads[0].resolve(["stale"]);
    await flush();
    h.reads[1].reject(new Error("503"));
    await flush();
    assert.equal(h.calls.fail.length, 1);
    assert.equal(h.calls.fail[0].token, "token-2");
  });
});
