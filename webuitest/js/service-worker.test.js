import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Script, createContext } from "node:vm";

import { deferred } from "./fixtures.js";

const workerSource = readFileSync(new URL("../../resources/webui/sw.js", import.meta.url), "utf8");
const workerScript = new Script(workerSource, { filename: "resources/webui/sw.js" });
const clone = (value) => JSON.parse(JSON.stringify(value));

function fakeClock() {
  let now = 0;
  let nextId = 0;
  const timers = new Map();
  return {
    timers,
    setTimeout(callback, delay) {
      const id = ++nextId;
      timers.set(id, { at: now + Number(delay), callback });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    advance(millis) {
      const target = now + millis;
      while (true) {
        const next = Array.from(timers).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next || next[1].at > target) break;
        timers.delete(next[0]);
        now = next[1].at;
        next[1].callback();
      }
      now = target;
    },
  };
}

function harness({ fetchResponse = async () => response([]), clients = [], showNotification = null } = {}) {
  const listeners = new Map();
  const clock = fakeClock();
  const requests = [];
  const shown = [];
  const clientCalls = [];
  let closed = 0;
  const self = {
    addEventListener(type, handler) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(handler);
    },
    skipWaiting: async () => {},
    clients: {
      claim: async () => {},
      matchAll: async (options) => {
        clientCalls.push(["matchAll", clone(options)]);
        return clients;
      },
      openWindow: async (url) => { clientCalls.push(["openWindow", url]); },
    },
    registration: {
      showNotification: async (title, options) => {
        shown.push(clone({ title, ...options }));
        if (showNotification) await showNotification(title, options);
      },
    },
    caches: {
      open() { throw new Error("the notification path must not open an offline cache"); },
      match() { throw new Error("the notification path must not read an offline cache"); },
    },
  };
  const context = createContext({
    self,
    AbortController,
    Date,
    Response,
    Notification: { permission: "granted" },
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    fetch: (url, options) => {
      requests.push({ url, options });
      return fetchResponse(url, options);
    },
  });
  // Script parsing and execution exercise the served classic worker without extracting its helpers.
  workerScript.runInContext(context, { timeout: 1_000 });
  function dispatch(type, fields = {}) {
    const handlers = listeners.get(type);
    assert.ok(handlers?.length, "the actual worker registered a " + type + " listener");
    const pending = [];
    const event = { ...fields, waitUntil: (promise) => pending.push(Promise.resolve(promise)) };
    handlers.forEach((handler) => handler(event));
    return { pending, done: Promise.all(pending) };
  }
  return {
    clock, requests, shown, clientCalls, dispatch,
    get closed() { return closed; },
    click(data) {
      return dispatch("notificationclick", { notification: { data, close() { closed += 1; } } });
    },
  };
}

function response(body, ok = true) {
  return { ok, status: ok ? 200 : 503, json: async () => body };
}

const attention = Object.freeze({
  type: "session.attention", id: "session.attention:s-one", createdAt: 1_800_000_000_000,
  sessionId: "s-one", sessionName: "Review the change",
});
const usage = Object.freeze({
  type: "usage.reset", id: "usage.reset:42", createdAt: 1_800_000_000_000,
  provider: "codex", windowKey: "primary", expectedAt: 1_800_000_600_000,
  usedBefore: 67.5, usedBeforeSeenAt: 1_799_999_940_000, windowSeconds: 604_800,
});

function assertGeneric(h) {
  assert.equal(h.shown.length, 1);
  assert.equal(h.shown[0].title, "Kotgent");
  assert.match(h.shown[0].body, /^Open Kotgent\b/);
  assert.equal(h.shown[0].renotify, false);
  assert.equal(h.clock.timers.size, 0, "completion releases the deadline timer");
}

test("one authenticated network-only inbox fetch displays both kinds with their stable tags", async () => {
  const h = harness({ fetchResponse: async () => response([attention, usage]) });

  const push = h.dispatch("push");
  assert.equal(push.pending.length, 1, "waitUntil keeps notification delivery alive");
  await push.done;

  assert.equal(h.requests.length, 1);
  const { url, options } = h.requests[0];
  assert.equal(url, "/api/v1/notifications");
  assert.equal(options.method || "GET", "GET");
  assert.equal(options.credentials, "include");
  assert.equal(options.cache, "no-store");
  assert.equal(options.signal.aborted, false);
  assert.equal(h.shown.length, 2);
  const session = h.shown.find((item) => item.tag === attention.sessionId);
  assert.equal(session.title, "Kotgent — needs attention");
  assert.equal(session.body, "Review the change needs your attention.");
  assert.deepEqual(session.data, { type: "session.attention", sessionId: attention.sessionId });
  assert.equal(session.renotify, false);
  const reset = h.shown.find((item) => item.tag === usage.id);
  assert.match(reset.body, /codex.*weekly.*reset/i);
  assert.ok(reset.body.includes("67.5%"));
  assert.ok(reset.body.includes(new Date(usage.usedBeforeSeenAt).toLocaleString()));
  assert.equal(reset.data.type, "usage.reset");
  assert.equal(reset.renotify, false);
  assert.equal(h.clock.timers.size, 0);
  h.clock.advance(10_000);
  assert.equal(options.signal.aborted, false, "a completed fetch is not aborted by a leaked timer");
});

test("the push lifetime includes completion of every showNotification call", async () => {
  const delivery = deferred();
  const showing = deferred();
  const h = harness({
    fetchResponse: async () => response([attention]),
    showNotification: async () => { showing.resolve(); await delivery.promise; },
  });
  const push = h.dispatch("push");
  let completed = false;
  push.done.then(() => { completed = true; });
  await showing.promise;
  assert.equal(completed, false);
  delivery.resolve();
  await push.done;
  assert.equal(completed, true);
});

for (const [name, fetchResponse] of [
  ["a rejected fetch", async () => { throw new Error("offline"); }],
  ["a non-success status", async () => response([attention], false)],
  ["invalid JSON", async () => ({ ok: true, json: async () => { throw new SyntaxError("bad JSON"); } })],
  ["a non-array payload", async () => response({ notifications: [attention] })],
  ["an empty inbox", async () => response([])],
  ["only unknown types", async () => response([null, { type: "future.notification", id: "future:1" }])],
  ["only malformed known types", async () => response([
    { ...attention, sessionId: "" }, { ...usage, id: "" }, { ...usage, usedBefore: 101 },
    { ...usage, usedBeforeSeenAt: 1e100 },
  ])],
]) {
  test(name + " still produces one neutral user-visible banner", async () => {
    const h = harness({ fetchResponse });
    await h.dispatch("push").done;
    assertGeneric(h);
    assert.equal(h.requests.length, 1);
  });
}

test("unknown entries cannot suppress a valid notification or add a generic banner beside it", async () => {
  const h = harness({ fetchResponse: async () => response([null, { type: "future" }, attention]) });
  await h.dispatch("push").done;
  assert.equal(h.shown.length, 1);
  assert.equal(h.shown[0].tag, attention.sessionId);
});

test("the fetch aborts at ten seconds and shows fallback without waiting on wall time", async () => {
  const h = harness({ fetchResponse: (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
  }) });
  const push = h.dispatch("push");
  const signal = h.requests[0].options.signal;
  h.clock.advance(9_999);
  assert.equal(signal.aborted, false);
  assert.deepEqual(h.shown, []);
  h.clock.advance(1);
  assert.equal(signal.aborted, true);
  await push.done;
  assertGeneric(h);
});

test("the same deadline also aborts a body that stalls after successful response headers", async () => {
  const reading = deferred();
  const h = harness({ fetchResponse: async (_url, { signal }) => ({
    ok: true,
    json: () => {
      reading.resolve();
      return new Promise((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(new Error("body aborted")), { once: true });
      });
    },
  }) });
  const push = h.dispatch("push");
  await reading.promise;
  h.clock.advance(10_000);
  await push.done;
  assert.equal(h.requests[0].options.signal.aborted, true);
  assertGeneric(h);
});

function windowClient({ navigateResult = "self" } = {}) {
  const calls = [];
  const client = {
    postMessage(message) { calls.push(["postMessage", clone(message)]); },
    async focus() { calls.push(["focus"]); return client; },
    async navigate(url) {
      calls.push(["navigate", url]);
      if (navigateResult === "throw") throw new Error("navigation failed");
      return navigateResult === "self" ? client : navigateResult;
    },
  };
  return { client, calls };
}

for (const data of [{ type: "session.attention", sessionId: "s-one" }, { sessionId: "s-one" }]) {
  test((data.type ? "current" : "legacy") + " session clicks select before focusing the existing window", async () => {
    const window = windowClient();
    const h = harness({ clients: [window.client] });
    const click = h.click(data);
    assert.equal(h.closed, 1);
    assert.equal(click.pending.length, 1);
    await click.done;
    assert.deepEqual(window.calls, [["postMessage", { type: "select-session", sessionId: "s-one" }], ["focus"]]);
    assert.deepEqual(h.clientCalls, [["matchAll", { type: "window", includeUncontrolled: true }]]);
  });
}

test("a cold session click opens the encoded session URL", async () => {
  const h = harness();
  await h.click({ type: "session.attention", sessionId: "s /?#" }).done;
  assert.deepEqual(h.clientCalls.at(-1), ["openWindow", "/?session=s%20%2F%3F%23"]);
  assert.equal(h.closed, 1);
});

test("a generic click focuses the current window without navigating or changing its selection", async () => {
  const window = windowClient();
  const h = harness({ clients: [window.client] });
  await h.click({}).done;
  assert.deepEqual(window.calls, [["focus"]]);
  assert.equal(h.clientCalls.length, 1, "the current terminal remains in its existing document");
  assert.equal(h.closed, 1);
});

test("a usage click navigates to the overview even if its data includes a stray session", async () => {
  const rootCalls = [];
  const root = { async focus() { rootCalls.push("focus"); } };
  const window = windowClient({ navigateResult: root });
  const h = harness({ clients: [window.client] });
  await h.click({ type: "usage.reset", sessionId: "stray-session" }).done;
  assert.deepEqual(window.calls, [["navigate", "/"]]);
  assert.deepEqual(rootCalls, ["focus"]);
  assert.equal(h.clientCalls.length, 1, "a successfully navigated window needs no extra window");
  assert.equal(h.closed, 1);
});

for (const [name, data] of [["usage", { type: "usage.reset" }], ["generic", {}]]) {
  test(name + " clicks open the overview when no window exists", async () => {
    const h = harness();
    await h.click(data).done;
    assert.deepEqual(h.clientCalls.at(-1), ["openWindow", "/"]);
  });
}

for (const result of [null, "throw"]) {
  test("failed overview navigation (" + result + ") opens a new root window", async () => {
    const window = windowClient({ navigateResult: result });
    const h = harness({ clients: [window.client] });
    await h.click({ type: "usage.reset" }).done;
    assert.deepEqual(window.calls, [["navigate", "/"]]);
    assert.deepEqual(h.clientCalls.at(-1), ["openWindow", "/"]);
  });
}

test("the classic worker leaves ordinary fetches to the network", async () => {
  const h = harness();
  let responses = 0;
  const event = h.dispatch("fetch", {
    request: { url: "https://kotgent.example/", mode: "navigate" },
    respondWith() { responses += 1; },
  });
  await event.done;
  assert.equal(responses, 0);
  assert.equal(event.pending.length, 0);
  assert.deepEqual(h.requests, []);
});
