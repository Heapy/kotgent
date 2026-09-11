// The one place a 401 is answered (resources/webui/lib/api.js).
//
// Thirty call sites reach the daemon through apiRequest and none of them can be trusted to recognise an
// expired cookie on their own: the reattach probe asked only `isDefiniteAnswer`, which is true for any
// 4xx, and so reported a signed-out operator as a dead session — candidate retired, "Terminal detached."
// written over the real reason, no sign-in prompt and no retry. Leaving for the sign-in page therefore
// belongs to the request function, not to a caller, and `setSignOutHandler` is what lets this tier watch
// it happen without a browser.

import { describe, test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";

import { apiRequest, isUnauthenticated, setSignOutHandler } from "../../resources/webui/lib/api.js";

const realFetch = globalThis.fetch;

function respondWith(status, body = "") {
  globalThis.fetch = async () => ({
    status: status,
    ok: status >= 200 && status < 300,
    text: async () => body,
  });
}

let signOuts;

beforeEach(() => {
  signOuts = 0;
  setSignOutHandler(() => { signOuts += 1; });
});

afterEach(() => {
  globalThis.fetch = realFetch;
  setSignOutHandler(() => { throw new Error("no test installed a sign-out handler"); });
});

async function failureOf(path) {
  try {
    await apiRequest(path);
  } catch (error) {
    return error;
  }
  return null;
}

describe("an expired session cookie", () => {
  test("leaves for the sign-in page and still names the reason", async () => {
    respondWith(401, "unauthorized");
    const error = await failureOf("/sessions/s1");
    assert.equal(signOuts, 1);
    assert.equal(isUnauthenticated(error), true);
    assert.match(error.message, /Signed out/);
    assert.equal(error.status, 401);
  });

  test("is answered the same way whichever read met it", async () => {
    respondWith(401);
    for (const path of ["/sessions/s1", "/preferences", "/projects", "/directories/complete"]) {
      await failureOf(path);
    }
    assert.equal(signOuts, 4, "no caller may opt out of recognising a signed-out operator");
  });
});

describe("every other outcome", () => {
  test("a 404 is a real answer about the resource, not about the operator", async () => {
    respondWith(404, "no such session");
    const error = await failureOf("/sessions/gone");
    assert.equal(signOuts, 0);
    assert.equal(isUnauthenticated(error), false);
    assert.equal(error.status, 404);
  });

  test("a 500 leaves the session alone", async () => {
    respondWith(500, "boom");
    await failureOf("/sessions/s1");
    assert.equal(signOuts, 0);
  });

  test("a success leaves the session alone", async () => {
    respondWith(200, '{"id":"s1"}');
    assert.deepEqual(await apiRequest("/sessions/s1"), { id: "s1" });
    assert.equal(signOuts, 0);
  });
});
