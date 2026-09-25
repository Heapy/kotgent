// ADHD-mode membership from resources/webui/lib/adhd.js. The rule decides which sessions the sidebar
// lists while the mode is on, and it touches neither the DOM nor the network, so it is proven here.

import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { adhdFolderOf, isPathAdhd, isSessionInAdhd } from "../../resources/webui/lib/adhd.js";

const session = (over) => Object.freeze(Object.assign({ id: "s1", cwd: "/a/b", adhd: false }, over));
const under = (cwd, paths) => isSessionInAdhd(session({ cwd: cwd }), paths);

describe("a session covered by a marked folder", () => {
  test("is listed anywhere below that folder", () => {
    assert.equal(under("/a/b/c", ["/a/b"]), true);
    assert.equal(under("/a/b/c/d/e", ["/a/b"]), true);
  });

  test("is listed in the folder itself", () => {
    assert.equal(under("/a/b", ["/a/b"]), true);
  });

  test("is not listed from a sibling that merely shares a name prefix", () => {
    assert.equal(under("/a/bc", ["/a/b"]), false);
    assert.equal(under("/a/because", ["/a/b"]), false);
  });

  // Every other case here marks an ancestor, so only this one catches a swapped argument order.
  test("is not listed when the mark is deeper than the session", () => {
    assert.equal(under("/a/b", ["/a/b/c"]), false);
  });

  test("needs only one of several marks", () => {
    assert.equal(under("/x/y", ["/a/b", "/x", "/q"]), true);
  });

  test("is not listed when nothing is marked", () => {
    assert.equal(under("/a/b", []), false);
    assert.equal(under("/a/b", undefined), false);
    assert.equal(under("/a/b", null), false);
  });

  test("is not listed when it has no cwd to place it", () => {
    assert.equal(under("", ["/a/b"]), false);
    assert.equal(under(undefined, ["/a/b"]), false);
  });

  test("survives trailing and repeated slashes on either side", () => {
    assert.equal(under("/a//b/c/", ["/a/b/"]), true);
    assert.equal(under("/a/b/c", ["//a///b"]), true);
  });

  test("is listed under a marked root", () => {
    assert.equal(under("/a/b", ["/"]), true);
  });
});

describe("isSessionInAdhd", () => {
  test("a session marked on its own is listed with no folder marks at all", () => {
    assert.equal(isSessionInAdhd(session({ adhd: true, cwd: "/elsewhere" }), []), true);
  });

  test("an unmarked session outside every marked folder is not listed", () => {
    assert.equal(isSessionInAdhd(session({ cwd: "/other" }), ["/a/b"]), false);
  });

  test("only an exact true counts as a session mark", () => {
    assert.equal(isSessionInAdhd(session({ adhd: undefined, cwd: "/other" }), []), false);
    assert.equal(isSessionInAdhd(session({ adhd: 1, cwd: "/other" }), []), false);
  });

  test("a missing session is not listed", () => {
    assert.equal(isSessionInAdhd(null, ["/"]), false);
  });
});

describe("adhdFolderOf", () => {
  test("names the nearest marked folder, whatever order the marks arrive in", () => {
    assert.equal(adhdFolderOf("/a/b/c", ["/a", "/a/b"]), "/a/b");
    assert.equal(adhdFolderOf("/a/b/c", ["/a/b", "/a"]), "/a/b");
  });

  test("names the folder itself when the session sits in it", () => {
    assert.equal(adhdFolderOf("/a/b", ["/a/b"]), "/a/b");
  });

  test("names nothing for a mark deeper than the session or beside it", () => {
    assert.equal(adhdFolderOf("/a/b", ["/a/b/c"]), null);
    assert.equal(adhdFolderOf("/a/bc", ["/a/b"]), null);
  });

  test("names nothing without a cwd or a list", () => {
    assert.equal(adhdFolderOf("", ["/a"]), null);
    assert.equal(adhdFolderOf("/a", undefined), null);
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
