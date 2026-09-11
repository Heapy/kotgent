// The shared mutation lock is acquired synchronously and held until the full callback settles.

import { describe, test, beforeEach } from "node:test";
import assert from "node:assert/strict";

import { effect } from "../../resources/webui/vendor/signals-core.module.js";
import {
  MUTATION_BUSY_MESSAGE,
  pendingMutation,
  runMutation,
} from "../../resources/webui/lib/mutation.js";
import { buildCommands } from "../../resources/webui/lib/commands.js";
import { deferred } from "./fixtures.js";

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

  // Exclusivity makes a per-run currency token unnecessary.
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

// The palette gates every registered mutation while any flow holds the lock.
describe("the palette while a flow holds the lock", () => {
  const BUSY_REASON = "another action is still in progress";
  const active = {
    id: "s1", name: "one", state: "running", tmuxSession: "kotgent-one", agent: "claude", cwd: "/work/one",
    tags: [],
  };

  function gatedIds(pendingAction) {
    return buildCommands({
      sessions: [active],
      activeSession: active,
      attachedId: active.id,
      pendingAction: pendingAction,
      actions: {},
    })
      .filter((command) => command.group === "session" && command.disabled === BUSY_REASON)
      .map((command) => command.id);
  }

  test("a rename closes the session commands, its own included, exactly as the flows before it do", () => {
    const underRename = gatedIds("rename");

    assert.ok(underRename.includes("session.stop"), "the gated set is not empty: " + underRename.join(" "));
    assert.ok(underRename.includes("session.rename"), "a rename gates itself: " + underRename.join(" "));
    assert.deepEqual(underRename, gatedIds("start"));
    assert.deepEqual(underRename, gatedIds("link-task"));
  });

  test("with nothing in flight no session command names the wait", () => {
    assert.deepEqual(gatedIds(null), []);
  });
});
