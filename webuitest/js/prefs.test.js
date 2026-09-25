// Preference sanitizing and the device-local ADHD-mode flag from resources/webui/lib/prefs.js. The
// rules decide what a daemon response is allowed to publish and what a reload restores, and neither
// touches the DOM or the network.

import { afterEach, describe, test } from "node:test";
import assert from "node:assert/strict";

import {
  ADHD_MODE_KEY,
  DEFAULT_PREFS,
  loadAdhdMode,
  persistAdhdMode,
  sanitizePrefs,
  sanitizeServerPreferences,
} from "../../resources/webui/lib/prefs.js";

const server = (overrides) => Object.assign({ basePath: "/work", groupingLevel: 1, revision: 3 }, overrides);

function withStorage(storage) {
  globalThis.window = { localStorage: storage };
}

function memoryStorage() {
  const entries = new Map();
  return {
    entries: entries,
    getItem: (key) => (entries.has(key) ? entries.get(key) : null),
    setItem: (key, value) => entries.set(key, String(value)),
  };
}

afterEach(() => {
  delete globalThis.window;
});

describe("sanitizeServerPreferences", () => {
  test("a daemon that omits adhdPaths publishes no marks", () => {
    assert.deepEqual(sanitizeServerPreferences(server({})).adhdPaths, []);
  });

  test("a list of paths is published as given", () => {
    const next = sanitizeServerPreferences(server({ adhdPaths: ["/a", "/b/c"] }));

    assert.deepEqual(next.adhdPaths, ["/a", "/b/c"]);
  });

  test("the published list is a copy, so a later response cannot mutate an applied one", () => {
    const raw = server({ adhdPaths: ["/a"] });

    const next = sanitizeServerPreferences(raw);
    raw.adhdPaths.push("/b");

    assert.deepEqual(next.adhdPaths, ["/a"]);
  });

  test("anything but a list of strings makes the whole payload unreadable", () => {
    for (const adhdPaths of ["/a", 7, {}, null, ["/a", 7], ["/a", null]]) {
      assert.equal(
        sanitizeServerPreferences(server({ adhdPaths: adhdPaths })),
        null,
        `a malformed adhdPaths must reject the payload: ${JSON.stringify(adhdPaths)}`,
      );
    }
  });

  test("the existing basePath, level and revision rules still hold", () => {
    assert.equal(sanitizeServerPreferences(server({ basePath: "relative" })), null);
    assert.equal(sanitizeServerPreferences(server({ groupingLevel: 5 })), null);
    assert.equal(sanitizeServerPreferences(server({ revision: -1 })), null);
  });
});

describe("sanitizePrefs", () => {
  // loadPrefs seeds the signal before any server read, so the sidebar must never see undefined.
  test("it seeds an empty path list when nothing supplies one", () => {
    assert.deepEqual(sanitizePrefs({}).adhdPaths, []);
    assert.deepEqual(DEFAULT_PREFS.adhdPaths, []);
  });

  test("no caller can seed real paths here: only the daemon supplies them", () => {
    assert.deepEqual(sanitizePrefs({ adhdPaths: ["/a"] }).adhdPaths, []);
  });
});

describe("the ADHD-mode flag", () => {
  test("it is off until something stores it", () => {
    withStorage(memoryStorage());

    assert.equal(loadAdhdMode(), false);
  });

  test("it round-trips through storage under its own key", () => {
    const storage = memoryStorage();
    withStorage(storage);

    persistAdhdMode(true);

    assert.equal(storage.getItem(ADHD_MODE_KEY), "true");
    assert.equal(loadAdhdMode(), true);

    persistAdhdMode(false);

    assert.equal(loadAdhdMode(), false);
  });

  test("only an exact true is stored as on", () => {
    const storage = memoryStorage();
    withStorage(storage);

    persistAdhdMode("yes");

    assert.equal(loadAdhdMode(), false, "a truthy non-boolean must not turn the mode on");
  });

  test("a private window that blocks storage reads as off instead of throwing", () => {
    withStorage({
      getItem: () => {
        throw new Error("storage is blocked");
      },
      setItem: () => {
        throw new Error("storage is blocked");
      },
    });

    assert.equal(loadAdhdMode(), false);
    assert.doesNotThrow(() => persistAdhdMode(true));
  });

  test("it reads as off where there is no window at all", () => {
    assert.equal(loadAdhdMode(), false);
  });
});
