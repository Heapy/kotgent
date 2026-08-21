// The session/task link rules from resources/webui/lib/sessions.js. They share one module and must stay
// distinguishable: `sessionTaskLinkDisabledReason` answers whether the command and the picker are offered
// at all, `sessionTaskLinkSubmitBlocked` re-checks the world immediately before the POST,
// `normalizeTaskQuery`/`taskMatchesQuery` decide what the picker's search shows, and
// `sessionTaskLinkOutcome` phrases what the committed link turned out to be. None of them touches the
// DOM, the network, or a timer, so they are proven here rather than in the browser tier.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  normalizeTaskQuery,
  sessionTaskLinkDisabledReason,
  sessionTaskLinkOutcome,
  sessionTaskLinkSubmitBlocked,
  taskMatchesQuery,
} from "../../resources/webui/lib/sessions.js";
import { sessionRow } from "./fixtures.js";
import { underTurkishFold } from "./turkish-fold.js";

describe("sessionTaskLinkDisabledReason", () => {
  test("a running session in a project with no task is linkable", () => {
    assert.equal(sessionTaskLinkDisabledReason(sessionRow({}), null), null);
  });

  test("every alive state is linkable and every dead state is not", () => {
    // Mirrors isAliveState; a state added to the badge vocabulary without a decision here shows up
    // as a failure rather than as a silently disabled command.
    for (const state of ["running", "ready", "needs_approval", "needs_answer"]) {
      assert.equal(sessionTaskLinkDisabledReason(sessionRow({ state: state }), null), null, state);
    }
    for (const state of ["stopped", "crashed", "resumable", "unknown", undefined]) {
      assert.equal(
        sessionTaskLinkDisabledReason(sessionRow({ state: state }), null),
        "the selected session is not running",
        String(state),
      );
    }
  });

  test("a pending action refuses before anything about the session is read", () => {
    assert.equal(
      sessionTaskLinkDisabledReason(null, "link-task"),
      "another action is still in progress",
    );
    assert.equal(
      sessionTaskLinkDisabledReason(sessionRow({}), "stop"),
      "another action is still in progress",
    );
  });

  test("no selection is its own refusal, and reading a null session never throws", () => {
    assert.equal(sessionTaskLinkDisabledReason(null, null), "no session is selected");
    assert.equal(sessionTaskLinkDisabledReason(undefined, null), "no session is selected");
  });

  test("an already linked session names the task it is holding", () => {
    assert.equal(
      sessionTaskLinkDisabledReason(sessionRow({ taskRef: "local:12" }), null),
      "the selected session is already linked to local:12",
    );
  });

  test("a session with no project cannot be linked", () => {
    assert.equal(
      sessionTaskLinkDisabledReason(sessionRow({ projectId: null }), null),
      "the selected session has no project",
    );
  });

  test("an existing link outranks a missing project", () => {
    // Both are true here; the sentence the operator reads must be the actionable one.
    assert.equal(
      sessionTaskLinkDisabledReason(sessionRow({ taskRef: "local:12", projectId: null }), null),
      "the selected session is already linked to local:12",
    );
  });

  test("the pending argument defaults to absent, as linkSessionChanged in dialogs.js calls it", () => {
    assert.equal(sessionTaskLinkDisabledReason(sessionRow({})), null);
  });
});

describe("sessionTaskLinkSubmitBlocked", () => {
  // The picker's guard and this one are different rules that share a matcher. This one runs against the
  // live world one statement before the POST, so every clause below is a race the operator can lose.
  function submission(overrides) {
    return {
      session: sessionRow({}),
      pendingAction: null,
      activeSessionId: "s1",
      sessionId: "s1",
      expectedProjectId: "p1",
      projects: [{ id: "p1", name: "kotgent" }],
      task: { ref: "local:12", project: "p1", state: "todo" },
      ...overrides,
    };
  }

  test("an unchanged world submits", () => {
    assert.equal(sessionTaskLinkSubmitBlocked(submission({})), false);
  });

  test("every reason that disables the command also blocks the submission", () => {
    for (const overrides of [
      { pendingAction: "stop" },
      { session: null },
      { session: sessionRow({ state: "stopped" }) },
      { session: sessionRow({ taskRef: "local:9" }) },
      { session: sessionRow({ projectId: null }) },
    ]) {
      assert.equal(sessionTaskLinkSubmitBlocked(submission(overrides)), true, JSON.stringify(overrides));
    }
  });

  test("a null session is refused before its project is read", () => {
    // Order, not just outcome: reading `.projectId` off nothing would throw instead of refusing.
    assert.doesNotThrow(() => sessionTaskLinkSubmitBlocked(submission({ session: null })));
  });

  test("a selection that moved while the picker was open blocks the submission", () => {
    assert.equal(sessionTaskLinkSubmitBlocked(submission({ activeSessionId: "s2" })), true);
  });

  test("a session that changed project since the picker opened blocks the submission", () => {
    assert.equal(
      sessionTaskLinkSubmitBlocked(submission({ session: sessionRow({ projectId: "p2" }) })),
      true,
    );
  });

  test("a project that is no longer active blocks the submission", () => {
    assert.equal(sessionTaskLinkSubmitBlocked(submission({ projects: [] })), true);
    assert.equal(
      sessionTaskLinkSubmitBlocked(submission({ projects: [{ id: "p2", name: "other" }] })),
      true,
    );
  });

  test("a task that was deleted while the picker was open blocks the submission", () => {
    assert.equal(sessionTaskLinkSubmitBlocked(submission({ task: null })), true);
    assert.equal(sessionTaskLinkSubmitBlocked(submission({ task: undefined })), true);
  });

  test("a task that moved to another project blocks the submission", () => {
    assert.equal(
      sessionTaskLinkSubmitBlocked(
        submission({ task: { ref: "local:12", project: "p2", state: "todo" } }),
      ),
      true,
    );
  });

  test("only an open task may be linked", () => {
    for (const state of ["todo", "in_progress", "review"]) {
      assert.equal(
        sessionTaskLinkSubmitBlocked(
          submission({ task: { ref: "local:12", project: "p1", state: state } }),
        ),
        false,
        state,
      );
    }
    for (const state of ["done", "unknown", undefined]) {
      assert.equal(
        sessionTaskLinkSubmitBlocked(
          submission({ task: { ref: "local:12", project: "p1", state: state } }),
        ),
        true,
        String(state),
      );
    }
  });
});

describe("task query matching", () => {
  const INDEX_TASK = Object.freeze({ ref: "local:12", project: "p1", title: "Index the API", state: "todo" });

  test("an empty or blank query matches every task", () => {
    assert.equal(normalizeTaskQuery(""), "");
    assert.equal(normalizeTaskQuery("   "), "");
    assert.equal(taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("")), true);
    assert.equal(taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("  ")), true);
  });

  test("the ref and the title are both searchable, and case is folded on both sides", () => {
    assert.equal(taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("LOCAL:12")), true);
    assert.equal(taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("the api")), true);
    assert.equal(taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("  index  ")), true);
    assert.equal(taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("board")), false);
  });

  test("a task with no title still matches on its ref", () => {
    assert.equal(taskMatchesQuery({ ref: "local:12", title: null }, normalizeTaskQuery("12")), true);
    assert.equal(taskMatchesQuery({ ref: "local:12", title: null }, normalizeTaskQuery("index")), false);
  });

  test("a task the list no longer holds matches nothing", () => {
    assert.equal(taskMatchesQuery(null, normalizeTaskQuery("index")), false);
  });

  // The finding: under a tr/az browser locale toLocaleLowerCase() folds "I" to dotless "ı", so a title
  // typed in ASCII stops matching an ASCII query. Naming the buggy fold explicitly is not enough to
  // prove anything: on any non-Turkish host "INDEX".toLocaleLowerCase() and "INDEX".toLowerCase() agree,
  // so the byte assertions below hold under either implementation and reverting the fix stays green.
  // The default fold is therefore redirected to the real Turkish one (./turkish-fold.js) around every
  // call into the rule, which is the same technique the palette's fold uses in typeahead.test.js. What
  // is asserted is that these rules never *call* the locale-sensitive method.
  test("a Turkish browser locale cannot break the match", () => {
    assert.notEqual(
      INDEX_TASK.title.toLocaleLowerCase("tr"),
      INDEX_TASK.title.toLowerCase(),
      "the fixture must actually discriminate the two folds, or the assertions below prove nothing",
    );
    assert.equal(
      INDEX_TASK.title.toLocaleLowerCase("tr").includes("index"),
      false,
      "this is the bug being fixed: the locale fold loses the ASCII query",
    );
    assert.notEqual("INDEX".toLocaleLowerCase("tr"), "INDEX".toLowerCase());

    // Byte-exact, not just "it matched": a locale fold would return "ındex" for both of these.
    assert.equal(underTurkishFold(() => normalizeTaskQuery("INDEX")), "index");
    assert.equal(underTurkishFold(() => normalizeTaskQuery(INDEX_TASK.title)), "index the api");

    // The query side and the task side fold separately, so both are exercised under the substitution.
    assert.equal(
      underTurkishFold(() => taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("index"))),
      true,
    );
    assert.equal(
      underTurkishFold(() => taskMatchesQuery(INDEX_TASK, normalizeTaskQuery("INDEX"))),
      true,
    );
  });
});

describe("sessionTaskLinkOutcome", () => {
  const REF = "local:12";

  test("the committed link is reported as plain status", () => {
    const winner = sessionRow({ name: "one", taskRef: REF });

    assert.deepEqual(
      sessionTaskLinkOutcome({ label: "one", ref: REF, fresh: winner, winner: winner }),
      { text: "Linked one to " + REF + ".", error: false },
    );
  });

  test("the winner's own current name is reported, not the name captured before the POST", () => {
    const winner = sessionRow({ name: "renamed", taskRef: REF });

    assert.equal(
      sessionTaskLinkOutcome({ label: "one", ref: REF, fresh: winner, winner: winner }).text,
      "Linked renamed to " + REF + ".",
    );
  });

  test("a link that could not be re-read is reported as a warning, not a failure", () => {
    assert.deepEqual(
      sessionTaskLinkOutcome({ label: "one", ref: REF, fresh: null, winner: null }),
      {
        text: "Linked one to " + REF +
          ", but the session could not be re-read. A live update may still bring the badge in.",
        error: true,
      },
    );
  });

  test("a session that ended up linked elsewhere names the task it actually holds", () => {
    const winner = sessionRow({ name: "one", taskRef: "local:99" });

    assert.deepEqual(
      sessionTaskLinkOutcome({ label: "one", ref: REF, fresh: winner, winner: winner }),
      { text: "The link request completed, but one is now linked to local:99.", error: true },
    );
  });

  test("a session that ended up linked to nothing says so", () => {
    const winner = sessionRow({ name: "one", taskRef: null });

    assert.equal(
      sessionTaskLinkOutcome({ label: "one", ref: REF, fresh: winner, winner: winner }).text,
      "The link request completed, but one is now linked to no task.",
    );
  });

  test("with no winner the label captured before the POST is the only name left", () => {
    // Defensive: the caller resolves `winner` from the live list falling back to the re-read row, so a
    // readable session always has one. The sentence must still name something if that ever changes.
    assert.equal(
      sessionTaskLinkOutcome({ label: "one", ref: REF, fresh: sessionRow({}), winner: null }).text,
      "The link request completed, but one is now linked to no task.",
    );
  });
});
