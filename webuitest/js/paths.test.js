// Sidebar grouping from webui/src/lib/paths.js. ADHD membership reads the same heads, so the tree
// groupSessions draws is pinned here case by case.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  groupEntries,
  groupSessions,
  headChain,
  orderGroupsByRecentChange,
} from "../../webui/src/lib/paths.js";
import { sessionRow } from "./fixtures.js";

const at = (id, cwd, updatedAt) => sessionRow({ id: id, cwd: cwd, updatedAt: updatedAt });

const SESSIONS = Object.freeze([
  at("s1", "/Users/me/dev/api", 30),
  at("s0", "/Users/me/dev", 50),
  at("s5", "/tmp//x/y/", 70),
  at("s2", "/Users/me/dev/api/x", 90),
  at("s3", "/Users/me/dev/web", 10),
  at("s4", "/tmp/x", 40),
  at("s6", "", 20),
  at("s7", "/Users/me", 60),
  at("s8", "/Users/me/dev/api/x/deep/er", 80),
]);

function outline(groups, depth = 0) {
  return groups.flatMap((group) => [
    "  ".repeat(depth) + group.label + " [" + group.path + "]" + (group.inBase ? "" : " outside") +
      " (" + group.sessionCount + ")" +
      (group.sessions.length > 0 ? ": " + group.sessions.map((s) => s.id).join(" ") : ""),
    ...outline(group.children, depth + 1),
  ]);
}

function drawn(basePath, level) {
  return outline(groupSessions(SESSIONS, basePath, level)).join("\n");
}

describe("groupSessions", () => {
  test("level 0 folds everything in the base into the base head", () => {
    assert.equal(drawn("/Users/me/dev", 0), [
      "dev [/Users/me/dev] (5): s1 s0 s2 s3 s8",
      "(unknown) [] outside (1): s6",
      "/tmp/x [/tmp/x] outside (1): s4",
      "/tmp/x/y [/tmp/x/y] outside (1): s5",
      "/Users/me [/Users/me] outside (1): s7",
    ].join("\n"));
  });

  test("below level 0 the base head holds only sessions whose cwd is the base itself", () => {
    assert.equal(drawn("/Users/me/dev", 1), [
      "dev [/Users/me/dev] (1): s0",
      "api [/Users/me/dev/api] (3): s1 s2 s8",
      "web [/Users/me/dev/web] (1): s3",
      "(unknown) [] outside (1): s6",
      "/tmp/x [/tmp/x] outside (1): s4",
      "/tmp/x/y [/tmp/x/y] outside (1): s5",
      "/Users/me [/Users/me] outside (1): s7",
    ].join("\n"));
  });

  test("deeper levels nest heads under their parents", () => {
    const expected = [
      "dev [/Users/me/dev] (1): s0",
      "api [/Users/me/dev/api] (3): s1",
      "  x [/Users/me/dev/api/x] (2): s2 s8",
      "web [/Users/me/dev/web] (1): s3",
      "(unknown) [] outside (1): s6",
      "/tmp/x [/tmp/x] outside (1): s4",
      "/tmp/x/y [/tmp/x/y] outside (1): s5",
      "/Users/me [/Users/me] outside (1): s7",
    ].join("\n");
    assert.equal(drawn("/Users/me/dev", 2), expected);
    assert.equal(drawn("/Users/me/dev", "2"), expected, "a numeric string is the same level");
  });

  test("a level past the deepest cwd draws every folder down to it", () => {
    assert.equal(drawn("/Users/me/dev/", 9), [
      "dev [/Users/me/dev] (1): s0",
      "api [/Users/me/dev/api] (3): s1",
      "  x [/Users/me/dev/api/x] (2): s2",
      "    deep [/Users/me/dev/api/x/deep] (1)",
      "      er [/Users/me/dev/api/x/deep/er] (1): s8",
      "web [/Users/me/dev/web] (1): s3",
      "(unknown) [] outside (1): s6",
      "/tmp/x [/tmp/x] outside (1): s4",
      "/tmp/x/y [/tmp/x/y] outside (1): s5",
      "/Users/me [/Users/me] outside (1): s7",
    ].join("\n"));
  });

  test("a negative level counts as level 0", () => {
    assert.equal(drawn("/Users/me/dev", -1), drawn("/Users/me/dev", 0));
  });

  test("an empty base draws every session under its own exact cwd", () => {
    assert.equal(drawn("", 1), [
      "(unknown) [] outside (1): s6",
      "/tmp/x [/tmp/x] outside (1): s4",
      "/tmp/x/y [/tmp/x/y] outside (1): s5",
      "/Users/me [/Users/me] outside (1): s7",
      "/Users/me/dev [/Users/me/dev] outside (1): s0",
      "/Users/me/dev/api [/Users/me/dev/api] outside (1): s1",
      "/Users/me/dev/api/x [/Users/me/dev/api/x] outside (1): s2",
      "/Users/me/dev/api/x/deep/er [/Users/me/dev/api/x/deep/er] outside (1): s8",
      "/Users/me/dev/web [/Users/me/dev/web] outside (1): s3",
    ].join("\n"));
  });

  test("the root as base leaves only a cwd-less session outside", () => {
    assert.equal(drawn("/", 1), [
      "tmp [/tmp] (2): s5 s4",
      "Users [/Users] (6): s1 s0 s2 s3 s7 s8",
      "(unknown) [] outside (1): s6",
    ].join("\n"));
  });

  test("every node has the same fields, holding the caller's own session objects", () => {
    const nodes = [];
    const walk = (groups) => groups.forEach((group) => { nodes.push(group); walk(group.children); });
    walk(groupSessions(SESSIONS, "/Users/me/dev", 2));

    for (const node of nodes) {
      assert.deepEqual(Object.keys(node), ["path", "label", "inBase", "sessions", "children", "sessionCount"]);
      for (const session of node.sessions) assert.ok(SESSIONS.includes(session));
    }
  });

  test("the Done tree orders the same heads by their newest change", () => {
    const done = (groups, depth = 0) => groups.flatMap((group) => [
      "  ".repeat(depth) + group.label + " [" + group.path + "] newest " + group.newestChange,
      ...groupEntries(group).flatMap((entry) => (entry.session
        ? ["  ".repeat(depth + 1) + entry.session.id]
        : done([entry.group], depth + 1))),
    ]);

    assert.equal(done(orderGroupsByRecentChange(groupSessions(SESSIONS, "/Users/me/dev", 2))).join("\n"), [
      "api [/Users/me/dev/api] newest 90",
      "  x [/Users/me/dev/api/x] newest 90",
      "    s2",
      "    s8",
      "  s1",
      "/tmp/x/y [/tmp/x/y] newest 70",
      "  s5",
      "/Users/me [/Users/me] newest 60",
      "  s7",
      "dev [/Users/me/dev] newest 50",
      "  s0",
      "/tmp/x [/tmp/x] newest 40",
      "  s4",
      "(unknown) [] newest 20",
      "  s6",
      "web [/Users/me/dev/web] newest 10",
      "  s3",
    ].join("\n"));
  });
});

describe("headChain", () => {
  function drawnAbove(cwd, basePath, level) {
    const chain = [];
    let groups = groupSessions([at("only", cwd, 1)], basePath, level);
    while (groups.length > 0) {
      assert.equal(groups.length, 1, "one session draws one head per level");
      chain.push(groups[0].path);
      groups = groups[0].children;
    }
    return chain;
  }

  test("names exactly the heads groupSessions draws, for every cwd, base and level", () => {
    const cwds = [
      "/Users/me/dev", "/Users/me/dev/", "/Users/me/dev/api", "/Users/me/dev/api/x", "/Users/me/dev//api/x/y/z",
      "/Users/me", "/Users/me/devtools", "/tmp/x", "/tmp/x/y", "/", "", "relative/path",
    ];
    const bases = ["/Users/me/dev", "/Users/me/dev/", "/", "", "/tmp/x"];
    const levels = [0, 1, 2, 3, 9, -1, "2", undefined];
    for (const cwd of cwds) {
      for (const base of bases) {
        for (const level of levels) {
          assert.deepEqual(
            headChain(cwd, base, level),
            drawnAbove(cwd, base, level),
            JSON.stringify({ cwd: cwd, base: base, level: level }),
          );
        }
      }
    }
  });

  test("walks nested heads from the outermost down, leaving the base head out", () => {
    assert.deepEqual(
      headChain("/Users/me/dev/api/x/y", "/Users/me/dev", 2),
      ["/Users/me/dev/api", "/Users/me/dev/api/x"],
    );
  });

  test("draws the base head alone for the base itself or at level 0", () => {
    assert.deepEqual(headChain("/Users/me/dev", "/Users/me/dev", 2), ["/Users/me/dev"]);
    assert.deepEqual(headChain("/Users/me/dev/api/x", "/Users/me/dev", 0), ["/Users/me/dev"]);
  });

  test("draws a path outside the base as one head for its exact cwd", () => {
    assert.deepEqual(headChain("/tmp//x/y/", "/Users/me/dev", 2), ["/tmp/x/y"]);
    assert.deepEqual(headChain("/Users/me/dev/api", "", 2), ["/Users/me/dev/api"]);
  });
});
