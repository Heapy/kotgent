import { afterEach, describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  STALE_BUILD_RELOAD_KEY,
  claimStaleBuildReload,
  isStaleBuild,
  sessionStorageOrNull,
} from "../../webui/src/lib/stale-build.js";

const ORIGIN = "https://kotgent.example";
const FIRST_BUILD = "https://kotgent.example/assets/index-first.js";
const NEXT_BUILD = "https://kotgent.example/assets/index-next.js";
const LATER_BUILD = "https://kotgent.example/assets/index-later.js";

function memoryStorage() {
  const entries = new Map();
  return {
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, String(value)),
  };
}

afterEach(() => {
  delete globalThis.window;
});

test("importing the module does not read the browser window", async () => {
  let reads = 0;
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    get() {
      reads += 1;
      throw new Error("there is no browser window");
    },
  });

  await import("../../webui/src/lib/stale-build.js?node-import");

  assert.equal(reads, 0);
});

describe("isStaleBuild", () => {
  test("the same absolute or root-relative entry is not stale", () => {
    assert.equal(isStaleBuild(FIRST_BUILD, FIRST_BUILD, ORIGIN), false);
    assert.equal(isStaleBuild("/assets/index-first.js", FIRST_BUILD, ORIGIN), false);
  });

  test("a different absolute or root-relative entry is stale", () => {
    assert.equal(isStaleBuild(NEXT_BUILD, FIRST_BUILD, ORIGIN), true);
    assert.equal(isStaleBuild("/assets/index-next.js", FIRST_BUILD, ORIGIN), true);
  });

  test("query strings are part of the build identity", () => {
    const nextBuild = FIRST_BUILD + "?build=next";
    assert.equal(isStaleBuild(nextBuild, FIRST_BUILD, ORIGIN), true);
    assert.equal(isStaleBuild("/assets/index-first.js?build=next", FIRST_BUILD, ORIGIN), true);
    assert.equal(isStaleBuild(FIRST_BUILD, nextBuild, ORIGIN), true);
    assert.equal(isStaleBuild(nextBuild, nextBuild, ORIGIN), false);
    assert.equal(isStaleBuild("/assets/index-first.js?build=next", nextBuild, ORIGIN), false);
  });

  test("a missing entry cannot establish a stale build", () => {
    for (const src of [null, undefined, "", "  "]) {
      assert.equal(isStaleBuild(src, FIRST_BUILD, ORIGIN), false);
    }
  });

  test("an invalid entry URL cannot establish a stale build", () => {
    assert.equal(isStaleBuild("https://[invalid", FIRST_BUILD, ORIGIN), false);
  });
});

describe("claimStaleBuildReload", () => {
  test("the first claim records both entry module URLs", () => {
    const storage = memoryStorage();

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), true);
    assert.equal(STALE_BUILD_RELOAD_KEY, "kotgent.staleBuildReload.v1");
    assert.deepEqual(JSON.parse(storage.getItem(STALE_BUILD_RELOAD_KEY)), [FIRST_BUILD, NEXT_BUILD]);
  });

  test("the same running and served pair cannot claim another reload", () => {
    const storage = memoryStorage();

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), true);
    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), false);
    assert.deepEqual(JSON.parse(storage.getItem(STALE_BUILD_RELOAD_KEY)), [FIRST_BUILD, NEXT_BUILD]);
  });

  test("a new served target from the same running build can claim a reload", () => {
    const storage = memoryStorage();

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), true);
    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, LATER_BUILD), true);
    assert.deepEqual(JSON.parse(storage.getItem(STALE_BUILD_RELOAD_KEY)), [FIRST_BUILD, LATER_BUILD]);
    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, LATER_BUILD), false);
  });

  test("a different running build can claim a reload to the same served target", () => {
    const storage = memoryStorage();

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, LATER_BUILD), true);
    assert.equal(claimStaleBuildReload(storage, NEXT_BUILD, LATER_BUILD), true);
    assert.deepEqual(JSON.parse(storage.getItem(STALE_BUILD_RELOAD_KEY)), [NEXT_BUILD, LATER_BUILD]);
    assert.equal(claimStaleBuildReload(storage, NEXT_BUILD, LATER_BUILD), false);
  });

  test("the slot remembers only the last pair", () => {
    const storage = memoryStorage();

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), true);
    assert.equal(claimStaleBuildReload(storage, NEXT_BUILD, LATER_BUILD), true);
    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), true);
  });

  test("entry URLs round-trip exactly, including query strings and pair delimiters", () => {
    const storage = memoryStorage();
    const running = FIRST_BUILD + '?build="a,b"&next=→';
    const served = NEXT_BUILD + '?build=["c"]&next=%22';

    assert.equal(claimStaleBuildReload(storage, running, served), true);
    assert.deepEqual(JSON.parse(storage.getItem(STALE_BUILD_RELOAD_KEY)), [running, served]);
    assert.equal(claimStaleBuildReload(storage, running, served), false);
    assert.equal(claimStaleBuildReload(storage, served, running), true);
  });

  test("malformed slot contents are replaced by the first pair without throwing", () => {
    for (const malformed of ["", FIRST_BUILD, "{", "null", "{}", "[]", '["one"]', '[1,2]']) {
      const storage = memoryStorage();
      storage.setItem(STALE_BUILD_RELOAD_KEY, malformed);

      assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), true);
      assert.deepEqual(JSON.parse(storage.getItem(STALE_BUILD_RELOAD_KEY)), [FIRST_BUILD, NEXT_BUILD]);
      assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), false);
    }
  });

  test("unavailable storage cannot claim a reload", () => {
    assert.equal(claimStaleBuildReload(null, FIRST_BUILD, NEXT_BUILD), false);
  });

  test("a failed storage read cannot claim a reload", () => {
    const storage = memoryStorage();
    storage.getItem = () => { throw new Error("storage reads are blocked"); };

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), false);
  });

  test("a failed storage write cannot claim a reload", () => {
    const storage = memoryStorage();
    let writes = 0;
    storage.setItem = () => {
      writes += 1;
      throw new Error("storage writes are blocked");
    };

    assert.equal(claimStaleBuildReload(storage, FIRST_BUILD, NEXT_BUILD), false);
    assert.equal(writes, 1);
  });
});

describe("sessionStorageOrNull", () => {
  test("it returns the available session storage", () => {
    const storage = memoryStorage();
    globalThis.window = { sessionStorage: storage };

    assert.equal(sessionStorageOrNull(), storage);
  });

  test("a throwing storage accessor becomes null", () => {
    globalThis.window = {
      get sessionStorage() { throw new Error("storage access is blocked"); },
    };

    assert.equal(sessionStorageOrNull(), null);
  });

  test("it returns null without a browser window", () => {
    assert.equal(sessionStorageOrNull(), null);
  });
});
