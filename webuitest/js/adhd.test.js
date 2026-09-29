// ADHD-mode membership from webui/src/lib/adhd.js. The rule decides which sessions the sidebar
// lists while the mode is on, and it touches neither the DOM nor the network, so it is proven here.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { adhdFolderOf, isPathAdhd, isSessionInAdhd } from "../../webui/src/lib/adhd.js";
import { sessionRow } from "./fixtures.js";

const grouping = (basePath, groupingLevel, adhdPaths) => ({ basePath, groupingLevel, adhdPaths });
const listed = (cwd, prefs) => isSessionInAdhd(sessionRow({ cwd: cwd }), prefs);

describe("a folder mark", () => {
  test("covers everything in the base at level 0, and only the base's own sessions below that", () => {
    assert.equal(listed("/work/api/x", grouping("/work", 0, ["/work"])), true);

    assert.equal(listed("/work", grouping("/work", 1, ["/work"])), true);
    assert.equal(listed("/work/api", grouping("/work", 1, ["/work"])), false);
    assert.equal(listed("/work/api/x", grouping("/work", 1, ["/work"])), false);
  });

  test("on the base head leaves the sibling heads drawn beside it to their own pins", () => {
    const prefs = grouping("/Users/me/dev", 1, ["/Users/me/dev"]);

    assert.equal(listed("/Users/me/dev", prefs), true);
    assert.equal(listed("/Users/me/dev/api", prefs), false);
    assert.equal(listed("/Users/me/dev/api/x", prefs), false);
    assert.equal(listed("/Users/me/dev/web", prefs), false);
  });

  test("on a head inside the base covers every session drawn under it", () => {
    const prefs = grouping("/Users/me/dev", 1, ["/Users/me/dev/api"]);

    assert.equal(listed("/Users/me/dev/api", prefs), true);
    assert.equal(listed("/Users/me/dev/api/x", prefs), true);
    assert.equal(listed("/Users/me/dev/web", prefs), false);
    assert.equal(listed("/Users/me/dev", prefs), false);
  });

  test("on an outside head covers only that exact cwd", () => {
    const prefs = grouping("/Users/me/dev", 1, ["/tmp/x"]);

    assert.equal(listed("/tmp/x", prefs), true);
    assert.equal(listed("/tmp/x/y", prefs), false);
  });

  test("on the home folder drawn outside the base does not cover the base tree", () => {
    const prefs = grouping("/Users/me/dev", 1, ["/Users/me"]);

    assert.equal(listed("/Users/me", prefs), true);
    assert.equal(listed("/Users/me/dev", prefs), false);
    assert.equal(listed("/Users/me/dev/api", prefs), false);
  });

  test("keeps acting on its folder once a narrower base draws that folder outside", () => {
    const prefs = grouping("/Users/me/dev/kotgent", 1, ["/Users/me/dev/api"]);

    assert.equal(listed("/Users/me/dev/api", prefs), true);
    assert.equal(listed("/Users/me/dev/api/x", prefs), false);
  });

  test("deeper than the level covers nothing until the level draws it", () => {
    assert.equal(listed("/a/b", grouping("/", 1, ["/a/b"])), false);
    assert.equal(listed("/a/b/c", grouping("/", 1, ["/a/b"])), false);

    assert.equal(listed("/a/b/c", grouping("/", 2, ["/a/b"])), true);
  });

  test("covers nothing with grouping off", () => {
    assert.equal(listed("/a/b", grouping("", 1, ["/a/b"])), false);
    assert.equal(listed("/a/b/c", grouping("", 2, ["/a", "/a/b"])), false);
  });

  test("is not matched by a sibling that merely shares a name prefix", () => {
    assert.equal(listed("/a/bc", grouping("/a", 1, ["/a/b"])), false);
    assert.equal(listed("/a/because", grouping("/", 2, ["/a/b"])), false);
  });

  test("survives trailing and repeated slashes on either side", () => {
    assert.equal(listed("/a//b/c/", grouping("/", 2, ["/a/b/"])), true);
    assert.equal(listed("/a/b/c", grouping("//", 2, ["//a///b"])), true);
  });

  test("needs only one of several marks", () => {
    assert.equal(listed("/x/y", grouping("/", 1, ["/a/b", "/x", "/q"])), true);
  });

  test("is absent when nothing is marked or the session has no cwd", () => {
    assert.equal(listed("/a/b", grouping("/", 1, [])), false);
    assert.equal(listed("/a/b", grouping("/", 1, undefined)), false);
    assert.equal(listed("", grouping("/", 1, ["/"])), false);
    assert.equal(listed(undefined, grouping("/", 1, ["/"])), false);
  });
});

describe("isSessionInAdhd", () => {
  test("a session marked on its own is listed with no folder marks at all", () => {
    assert.equal(isSessionInAdhd(sessionRow({ adhd: true, cwd: "/elsewhere" }), grouping("/", 1, [])), true);
  });

  test("a session marked on its own is listed with grouping off", () => {
    assert.equal(isSessionInAdhd(sessionRow({ adhd: true, cwd: "/a/b" }), grouping("", 1, ["/a/b"])), true);
  });

  test("only an exact true counts as a session mark", () => {
    assert.equal(isSessionInAdhd(sessionRow({ adhd: undefined, cwd: "/other" }), grouping("/", 1, [])), false);
    assert.equal(isSessionInAdhd(sessionRow({ adhd: 1, cwd: "/other" }), grouping("/", 1, [])), false);
  });

  test("a missing session is not listed", () => {
    assert.equal(isSessionInAdhd(null, grouping("/", 0, ["/"])), false);
  });
});

describe("adhdFolderOf", () => {
  test("names the innermost marked head, whatever order the marks arrive in", () => {
    assert.equal(adhdFolderOf("/a/b/c", grouping("/", 2, ["/a", "/a/b"])), "/a/b");
    assert.equal(adhdFolderOf("/a/b/c", grouping("/", 2, ["/a/b", "/a"])), "/a/b");
  });

  test("names the head as it is drawn, not as the mark was spelled", () => {
    assert.equal(adhdFolderOf("/a/b/c", grouping("/", 2, ["/a/b/"])), "/a/b");
  });

  test("falls back to an outer head while the level does not draw the inner one", () => {
    assert.equal(adhdFolderOf("/a/b/c", grouping("/", 1, ["/a", "/a/b"])), "/a");
    assert.equal(adhdFolderOf("/a/b/c", grouping("/", 1, ["/a/b"])), null);
  });

  test("names nothing for a mark deeper than the session or beside it", () => {
    assert.equal(adhdFolderOf("/a/b", grouping("/", 3, ["/a/b/c"])), null);
    assert.equal(adhdFolderOf("/a/bc", grouping("/", 2, ["/a/b"])), null);
  });

  test("names nothing with grouping off, without a cwd, or without a list", () => {
    assert.equal(adhdFolderOf("/a", grouping("", 1, ["/a"])), null);
    assert.equal(adhdFolderOf("", grouping("/", 1, ["/a"])), null);
    assert.equal(adhdFolderOf("/a", grouping("/", 1, undefined)), null);
  });
});

describe("isPathAdhd", () => {
  test("answers only for the exact folder, never for one under a marked ancestor", () => {
    assert.equal(isPathAdhd("/a/b", ["/a/b"]), true);
    assert.equal(isPathAdhd("/a/b", ["/a"]), false);
    assert.equal(isPathAdhd("/a/b", ["/a/b/c"]), false);
  });

  test("normalizes both sides", () => {
    assert.equal(isPathAdhd("/a//b/", ["/a/b"]), true);
  });

  test("answers false for a missing path or list", () => {
    assert.equal(isPathAdhd("", ["/a"]), false);
    assert.equal(isPathAdhd("/a", undefined), false);
  });
});
