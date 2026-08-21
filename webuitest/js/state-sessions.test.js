// The shared session/task/project state in resources/webui/state/. `lib/sessions.js` and `lib/tasks.js`
// still own the merge arithmetic; these modules own the current value and are the only writers of it,
// which is what makes the interleave in finding app.js:649 unrepresentable — there is no second copy for
// three of seven writers to forget.
//
// They are provable here because they import signals-core by relative path rather than through the
// import map: nothing in them touches the DOM, the network, or a timer. `effect()` stands in for a
// Preact render, so "does this frame cost a render" is an assertion rather than a claim.

import { describe, test, beforeEach } from "node:test";
import assert from "node:assert/strict";

import { effect } from "../../resources/webui/vendor/signals-core.module.js";
import { IDLE, READY } from "../../resources/webui/lib/readiness.js";
import {
  findSession,
  mergeSessionPatch,
  mergeSessionRow,
  replaceSessions,
  sessions,
  sessionsReadiness,
} from "../../resources/webui/state/sessions.js";
import {
  dropTask,
  findTask,
  mergeTaskPatch,
  mergeTaskRow,
  replaceTasks,
  tasks,
  tasksReadiness,
} from "../../resources/webui/state/tasks.js";
import {
  applyProjectRow,
  findProject,
  isLiveProject,
  projects,
  projectsReadiness,
  removeProjectRow,
  replaceProjects,
} from "../../resources/webui/state/projects.js";
import { patchFrame, sessionRow, taskRow } from "./fixtures.js";

// The modules are singletons, exactly as a browser holds them. Reset the value rather than the module.
function resetState() {
  replaceSessions([]);
  sessionsReadiness.reset();
  replaceTasks([]);
  tasksReadiness.reset();
  replaceProjects([]);
  projectsReadiness.reset();
}

// Count notifications the way a subscribed component would see them: the first run is the initial
// subscription, so a frame that costs no render leaves the count where it was.
function countRenders(target) {
  const seen = { renders: 0 };
  seen.stop = effect(() => {
    target.value;
    seen.renders += 1;
  });
  return seen;
}

beforeEach(resetState);

describe("session writers compose instead of discarding each other", () => {
  // The interleave finding app.js:649 describes: `setSessions((prev) => upsertIfNewer(prev, created))`
  // queued a functional update that the mirror ref never saw, so the very next writer — which read the
  // mirror — rebuilt the list from a value that predated the queued write and dropped it. One signal
  // read synchronously by every writer cannot express that.
  test("a row written by one writer is visible to the next writer immediately", () => {
    mergeSessionRow(sessionRow({ id: "s1" }));
    const second = mergeSessionRow(sessionRow({ id: "s2", name: "two" }));

    assert.equal(second.changed, true);
    assert.deepEqual(sessions.value.map((s) => s.id), ["s1", "s2"]);
    assert.ok(findSession("s1"), "the first writer's row survived the second writer");
  });

  test("a patch applied straight after a row write sees that row", () => {
    mergeSessionRow(sessionRow({ rev: 2, unread: 0 }));
    const patched = mergeSessionPatch(patchFrame({ rev: 3, unread: 4 }));

    assert.equal(patched.changed, true);
    assert.equal(patched.winner.unread, 4);
    assert.equal(findSession("s1").unread, 4);
  });

  test("the writers never mutate the list they were handed", () => {
    const initial = Object.freeze([sessionRow({})]);
    replaceSessions(initial);
    mergeSessionRow(sessionRow({ id: "s2" }));

    assert.equal(initial.length, 1, "the caller's array is not a live handle on the signal");
  });
});

describe("out-of-order and duplicate session frames converge", () => {
  test("a newer revision wins whichever order the two observations arrive in", () => {
    mergeSessionRow(sessionRow({ rev: 5, state: "ready" }));
    const stale = mergeSessionRow(sessionRow({ rev: 3, state: "running" }));

    assert.equal(stale.changed, false, "an older revision is not applied");
    assert.equal(findSession("s1").state, "ready");

    resetState();
    mergeSessionRow(sessionRow({ rev: 3, state: "running" }));
    mergeSessionRow(sessionRow({ rev: 5, state: "ready" }));

    assert.equal(findSession("s1").state, "ready", "both arrival orders reach the same value");
  });

  test("a duplicate row leaves the list identical, so no component re-renders", () => {
    mergeSessionRow(sessionRow({ rev: 4 }));
    const watch = countRenders(sessions);
    const before = watch.renders;

    const duplicate = mergeSessionRow(sessionRow({ rev: 4 }));

    assert.equal(duplicate.changed, false);
    assert.equal(watch.renders, before, "a redelivered row must not cost a render");
    watch.stop();
  });

  test("an older patch cannot roll a newer patch back", () => {
    mergeSessionRow(sessionRow({ rev: 1 }));
    mergeSessionPatch(patchFrame({ rev: 9, unread: 2 }));
    mergeSessionPatch(patchFrame({ rev: 4, unread: 0 }));

    assert.equal(findSession("s1").unread, 2);
    assert.equal(findSession("s1").rev, 9);
  });

  test("a patch for a session the list has never seen is dropped", () => {
    const orphan = mergeSessionPatch(patchFrame({ sessionId: "gone" }));

    assert.equal(orphan.changed, false);
    assert.equal(orphan.winner, null, "there is no row to poke a read against");
    assert.equal(sessions.value.length, 0, "a partial frame never invents a row");
  });
});

describe("the equal-revision rule", () => {
  // app.js:152 states the intent: "Imperative triggers can retry a failed POST even when unread and seq
  // do not change." The patch path used to return early whenever the merge produced no new list, which
  // silently withdrew that retry for exactly the frames that carry it — a redelivered session_update is
  // the only trigger a stalled read POST gets when nothing about the row changes.
  test("an equal-revision frame still reports a winner, so markReadIfViewing is still reached", () => {
    mergeSessionRow(sessionRow({ rev: 4, unread: 5, lastSeq: 11 }));

    const redelivered = mergeSessionPatch(patchFrame({ rev: 4, unread: 5, lastSeq: 11 }));

    assert.equal(redelivered.changed, false, "an equal revision is not applied");
    assert.ok(redelivered.winner, "the caller still gets the row it must retry the read for");
    assert.equal(redelivered.winner.unread, 5);
    assert.equal(redelivered.winner.lastSeq, 11);
  });

  test("the winner of an equal-revision frame is the row on hand, not the frame", () => {
    // A patch frame carries no cwd, agent, or name. Reporting the frame would hand the caller a row
    // that cannot be displayed and whose unread could disagree with the authoritative one.
    mergeSessionRow(sessionRow({ rev: 4, unread: 5, cwd: "/work/one" }));

    const redelivered = mergeSessionPatch(patchFrame({ rev: 4, unread: 99 }));

    assert.equal(redelivered.winner, findSession("s1"));
    assert.equal(redelivered.winner.cwd, "/work/one");
    assert.equal(redelivered.winner.unread, 5, "the declined frame's fields are not adopted");
  });

  test("an older frame reports the current row too, and still costs no render", () => {
    mergeSessionRow(sessionRow({ rev: 8, unread: 2 }));
    const watch = countRenders(sessions);
    const before = watch.renders;

    const stale = mergeSessionPatch(patchFrame({ rev: 2, unread: 0 }));

    assert.equal(stale.changed, false);
    assert.ok(stale.winner, "a stale frame is still a trigger to retry a stalled read");
    assert.equal(watch.renders, before, "the retry is free: no signal write, so no render");
    watch.stop();
  });

  test("a genuinely newer frame does cost exactly one render", () => {
    mergeSessionRow(sessionRow({ rev: 4 }));
    const watch = countRenders(sessions);
    const before = watch.renders;

    mergeSessionPatch(patchFrame({ rev: 5, unread: 1 }));

    assert.equal(watch.renders, before + 1, "one applied frame is one render, not a loop");
    watch.stop();
  });

  test("the previous row is reported so an attention transition can be judged once", () => {
    mergeSessionRow(sessionRow({ rev: 1, needsAttention: false }));

    const raised = mergeSessionPatch(patchFrame({ rev: 2, needsAttention: true }));
    assert.equal(raised.previous.needsAttention, false);
    assert.equal(raised.winner.needsAttention, true);

    const redelivered = mergeSessionPatch(patchFrame({ rev: 2, needsAttention: true }));
    assert.equal(redelivered.changed, false, "the redelivery must not notify a second time");
    assert.equal(redelivered.previous.needsAttention, true);
  });
});

describe("session snapshots", () => {
  test("a reconnect snapshot is authoritative, including deletions", () => {
    mergeSessionRow(sessionRow({ id: "s1" }));
    mergeSessionRow(sessionRow({ id: "s2" }));

    replaceSessions([sessionRow({ id: "s2", rev: 1 })]);

    assert.deepEqual(sessions.value.map((s) => s.id), ["s2"]);
    assert.equal(findSession("s1"), null, "a row missing from the snapshot is gone, whatever its rev");
  });

  test("the snapshot hands back the list it replaced so attention transitions stay judgeable", () => {
    mergeSessionRow(sessionRow({ id: "s1", needsAttention: false }));

    const applied = replaceSessions([sessionRow({ id: "s1", needsAttention: true, rev: 3 })]);

    assert.equal(applied.previous.length, 1);
    assert.equal(applied.previous[0].needsAttention, false);
  });

  test("only the first snapshot is the readiness transition", () => {
    assert.equal(
      sessionsReadiness.status.value.state, IDLE, "an unloaded list is not an empty one",
    );

    assert.equal(replaceSessions([]).first, true, "an empty first snapshot still establishes readiness");
    assert.equal(sessionsReadiness.status.value.state, READY);
    assert.equal(replaceSessions([sessionRow({})]).first, false, "reconnects do not re-announce");
  });

  test("a null or missing snapshot body is an empty list, not a crash", () => {
    mergeSessionRow(sessionRow({}));
    replaceSessions(null);

    assert.deepEqual(sessions.value, []);
  });
});

describe("session lookup", () => {
  test("the index finds the row a linear scan would", () => {
    replaceSessions([sessionRow({ id: "s1" }), sessionRow({ id: "s2", name: "two" })]);

    assert.equal(findSession("s2").name, "two");
  });

  test("an unknown or absent id is null rather than undefined", () => {
    replaceSessions([sessionRow({})]);

    assert.equal(findSession("nope"), null);
    assert.equal(findSession(null), null);
    assert.equal(findSession(undefined), null);
  });

  test("the index tracks writes rather than being rebuilt by a caller", () => {
    replaceSessions([sessionRow({ id: "s1" })]);
    assert.equal(findSession("s2"), null);

    mergeSessionRow(sessionRow({ id: "s2" }));

    assert.ok(findSession("s2"), "a derived lookup that needs reconciling is a mirror by another name");
  });
});

describe("task writers", () => {
  test("a snapshot replaces the list and establishes readiness", () => {
    assert.equal(
      tasksReadiness.status.value.state,
      IDLE,
      "a destructive confirmation must not read an unloaded list",
    );

    replaceTasks([taskRow({ ref: "local:1" })]);

    assert.equal(tasksReadiness.status.value.state, READY);
    assert.deepEqual(tasks.value.map((t) => t.ref), ["local:1"]);
  });

  test("an empty snapshot is a loaded empty backlog", () => {
    replaceTasks([]);

    assert.equal(tasksReadiness.status.value.state, READY);
    assert.deepEqual(tasks.value, []);
  });

  test("rows merge by revision and compose across writers", () => {
    mergeTaskRow(taskRow({ ref: "local:1", rev: 2 }));
    mergeTaskRow(taskRow({ ref: "local:2", rev: 1 }));
    const stale = mergeTaskRow(taskRow({ ref: "local:1", rev: 1, title: "stale" }));

    assert.equal(stale.changed, false);
    assert.equal(findTask("local:1").title, "wire the board");
    assert.deepEqual(tasks.value.map((t) => t.ref), ["local:1", "local:2"]);
  });

  test("a patch merges by revision and reports the winner", () => {
    mergeTaskRow(taskRow({ ref: "local:1", rev: 2 }));

    const moved = mergeTaskPatch({ ref: "local:1", state: "in_progress", rev: 3 });

    assert.equal(moved.changed, true);
    assert.equal(moved.winner.state, "in_progress");
    assert.equal(moved.winner.title, "wire the board", "a patch keeps the fields it does not carry");
  });

  test("a patch for an unknown ref is dropped", () => {
    const orphan = mergeTaskPatch({ ref: "local:9", state: "done", rev: 3 });

    assert.equal(orphan.changed, false);
    assert.equal(orphan.winner, null);
    assert.equal(tasks.value.length, 0);
  });

  test("removal carries no revision and is authoritative", () => {
    mergeTaskRow(taskRow({ ref: "local:1", rev: 7 }));

    assert.equal(dropTask("local:1").changed, true);
    assert.equal(findTask("local:1"), null);
    assert.equal(dropTask("local:1").changed, false, "removing twice is not an error");
  });

  test("an unchanged task write costs no render", () => {
    mergeTaskRow(taskRow({ rev: 4 }));
    const watch = countRenders(tasks);
    const before = watch.renders;

    mergeTaskRow(taskRow({ rev: 4 }));
    dropTask("local:404");

    assert.equal(watch.renders, before);
    watch.stop();
  });
});

describe("project writers", () => {
  // Project rows carry no revision — the daemon emits no project frame — so these writers apply a
  // confirmed response verbatim rather than arbitrating it.
  test("a refresh replaces the list and establishes readiness", () => {
    assert.equal(projectsReadiness.status.value.state, IDLE);

    replaceProjects([{ id: "p1", name: "one" }]);

    assert.equal(projectsReadiness.status.value.state, READY);
    assert.equal(findProject("p1").name, "one");
  });

  test("a confirmed row is applied verbatim, appending when it is new", () => {
    replaceProjects([{ id: "p1", name: "one" }]);

    applyProjectRow({ id: "p2", name: "two" });
    applyProjectRow({ id: "p1", name: "renamed" });

    assert.deepEqual(projects.value.map((p) => p.name), ["renamed", "two"]);
  });

  test("removing a row drops it, and removing it again writes nothing at all", () => {
    replaceProjects([{ id: "p1" }, { id: "p2" }]);

    removeProjectRow("p1");
    const afterRemoval = projects.value;
    removeProjectRow("p1");

    assert.deepEqual(projects.value.map((p) => p.id), ["p2"]);
    assert.equal(
      projects.value,
      afterRemoval,
      "the same list object, not merely an equal one: a rewrite would re-render every subscriber for no news",
    );
  });

  test("liveness answers the question the link guard asks", () => {
    replaceProjects([{ id: "p1" }]);

    assert.equal(isLiveProject("p1"), true);
    assert.equal(isLiveProject("p2"), false, "a deleted project must not be linkable");
    assert.equal(isLiveProject(null), false);
  });

  test("a null refresh body is an empty list", () => {
    replaceProjects([{ id: "p1" }]);
    replaceProjects(null);

    assert.deepEqual(projects.value, []);
    assert.equal(findProject("p1"), null);
  });
});
