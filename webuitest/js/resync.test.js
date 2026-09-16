import { test } from "node:test";
import assert from "node:assert/strict";
import { createRefreshCoordinator } from "../../resources/webui/lib/resync.js";
import { createEventsConnection } from "../../resources/webui/lib/events.js";
import { watchRefreshSources } from "../../resources/webui/lib/resume.js";

function clock() {
  let now = 0;
  let id = 0;
  const timers = new Map();
  return {
    schedule(fn, delay) { const key = ++id; timers.set(key, { at: now + delay, fn }); return key; },
    cancel(key) { timers.delete(key); },
    size() { return timers.size; },
    tick(ms) {
      const end = now + ms;
      for (;;) {
        const next = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        const [key, timer] = next;
        timers.delete(key);
        now = timer.at;
        timer.fn();
      }
      now = end;
    },
  };
}

function harness(options = {}) {
  const time = clock();
  const runs = [];
  const failures = [];
  const coordinator = createRefreshCoordinator({
    ...time,
    run(port) {
      const run = { ...port, stopped: 0 };
      runs.push(run);
      return () => run.stopped++;
    },
    onFailure: (error) => failures.push(error),
    ...options,
  });
  return { ...coordinator, time, runs, failures };
}

test("a fixed batch window cannot be postponed by a burst, and duplicate requests join a running refresh", () => {
  const h = harness();
  h.request({ reason: "resume" });
  h.time.tick(90);
  h.request({ reason: "online" });
  h.time.tick(10);
  assert.equal(h.runs.length, 1);
  assert.deepEqual(h.runs[0].reasons, ["resume", "online"]);
  h.request({ reason: "focus" });
  h.time.tick(100);
  h.runs[0].complete();
  h.time.tick(20_000);
  assert.equal(h.runs.length, 1);
  assert.equal(h.runs[0].isCurrent(), true, "a synchronized subscription keeps receiving live updates");
  h.dispose();
});

test("new invalidations during a read cause one follow-up and retire callbacks from the old generation", () => {
  const h = harness();
  h.request({ reason: "mount" });
  h.time.tick(100);
  const first = h.runs[0];
  h.request({ reason: "clock-gap", invalidate: true });
  h.request({ reason: "resume", invalidate: true });
  assert.equal(h.runs.length, 1);
  first.complete();
  h.time.tick(100);
  assert.equal(h.runs.length, 2);
  assert.equal(first.stopped, 1);
  assert.equal(first.isCurrent(), false);
  first.complete();
  first.fail(new Error("late close"));
  h.runs[1].complete();
  h.time.tick(60_000);
  assert.equal(h.runs.length, 2);
  assert.deepEqual(h.failures, []);
  h.dispose();
});

test("failures use capped backoff, incoming requests cannot bypass it, and success resets it", () => {
  const h = harness();
  h.request();
  h.time.tick(100);
  for (const delay of [2000, 4000, 8000, 16000, 30000, 30000]) {
    const count = h.runs.length;
    const run = h.runs.at(-1);
    run.fail(new Error("offline"));
    assert.equal(run.isCurrent(), false);
    assert.equal(run.stopped, 1);
    h.request({ reason: "focus" });
    h.time.tick(delay - 1);
    assert.equal(h.runs.length, count);
    h.time.tick(1);
    assert.equal(h.runs.length, count + 1);
  }
  const count = h.runs.length;
  h.runs.at(-1).complete();
  h.runs.at(-1).fail(new Error("connection lost"));
  h.time.tick(2000);
  assert.equal(h.runs.length, count + 1);
  h.dispose();
});

test("an operation that never completes times out and retries", () => {
  const h = harness();
  h.request();
  h.time.tick(10_100);
  assert.equal(h.runs[0].stopped, 1);
  assert.match(h.failures[0].message, /timed out/i);
  h.time.tick(2000);
  assert.equal(h.runs.length, 2);
  h.dispose();
});

test("disposing while batching, reading, ready, or retrying cancels all work", () => {
  for (const phase of ["batch", "reading", "ready", "retry"]) {
    const h = harness();
    h.request();
    if (phase !== "batch") h.time.tick(100);
    const run = h.runs[0];
    if (phase === "ready") run.complete();
    if (phase === "retry") run.fail(new Error("offline"));
    h.dispose();
    h.dispose();
    run?.complete();
    run?.fail(new Error("late"));
    h.request();
    assert.equal(h.time.size(), 0);
    h.time.tick(60_000);
    assert.equal(h.runs.length, phase === "batch" ? 0 : 1);
    if (run) assert.equal(run.stopped, 1);
  }
});

test("synchronous completion and a throwing start both preserve lifecycle ownership", () => {
  let stopped = 0;
  const h = harness({ run(port) { port.complete(); return () => stopped++; } });
  h.request();
  h.time.tick(100);
  assert.equal(h.time.size(), 0);
  h.dispose();
  assert.equal(stopped, 1);

  const broken = harness({ run() { throw new Error("cannot start"); } });
  broken.request();
  broken.time.tick(100);
  assert.equal(broken.failures[0].message, "cannot start");
  assert.equal(broken.time.size(), 1);
  broken.dispose();
});

function eventsHarness(onFrame = () => {}) {
  const time = clock();
  const sockets = [];
  let recovered = 0;
  const connection = createEventsConnection({
    ...time, url: () => "/events", onFrame,
    onReady: (result) => { if (result.recovered) recovered++; }, onFailure: () => {},
    createSocket() {
      const socket = { closed: 0, close() { this.closed++; } };
      sockets.push(socket);
      return socket;
    },
  });
  const frame = (socket, type) => socket.onmessage({ data: JSON.stringify({ type }) });
  const snapshot = (socket) => {
    for (const type of ["usage_snapshot", "sessions_snapshot", "tasks_snapshot"]) frame(socket, type);
  };
  return { ...connection, time, sockets, frame, snapshot, recovered: () => recovered };
}

test("socket open and partial snapshots do not complete a refresh; all applied snapshots do", () => {
  const applied = [];
  const h = eventsHarness((msg) => applied.push(msg.type));
  h.request();
  h.time.tick(100);
  const socket = h.sockets[0];
  socket.onopen();
  h.frame(socket, "usage_snapshot");
  h.frame(socket, "sessions_snapshot");
  h.time.tick(10_000);
  assert.equal(socket.closed, 1, "missing task snapshot times out even though the socket opened");
  h.time.tick(2000);
  const next = h.sockets[1];
  next.onopen();
  h.snapshot(next);
  assert.equal(h.recovered(), 1);
  h.time.tick(10_000);
  assert.equal(next.closed, 0);
  assert.deepEqual(applied, ["usage_snapshot", "sessions_snapshot", "usage_snapshot", "sessions_snapshot", "tasks_snapshot"]);
  h.dispose();
});

test("late frames and closes from replaced sockets cannot change state or start another retry", () => {
  const applied = [];
  const h = eventsHarness((msg) => applied.push(msg.type));
  h.request();
  h.time.tick(100);
  const first = h.sockets[0];
  first.onopen();
  h.snapshot(first);
  const lateMessage = first.onmessage;
  const lateClose = first.onclose;
  h.request({ reason: "resume" });
  h.time.tick(100);
  const next = h.sockets[1];
  next.onopen();
  h.snapshot(next);
  const count = applied.length;
  lateMessage({ data: '{"type":"usage_snapshot"}' });
  lateClose();
  h.time.tick(60_000);
  assert.equal(applied.length, count);
  assert.equal(h.sockets.length, 2);
  assert.equal(first.closed, 1);
  assert.equal(h.recovered(), 1);
  h.dispose();
});

test("a failed snapshot application fails the operation instead of announcing recovery", () => {
  const h = eventsHarness(() => { throw new Error("apply failed"); });
  h.request();
  h.time.tick(100);
  h.sockets[0].onopen();
  h.frame(h.sockets[0], "usage_snapshot");
  assert.equal(h.sockets[0].closed, 1);
  assert.equal(h.recovered(), 0);
  h.dispose();
});

function resumeHarness() {
  const time = clock();
  const window = new EventTarget();
  const document = new EventTarget();
  document.visibilityState = "visible";
  let wall = 1_800_000_000_000;
  let mono = 100;
  const requests = [];
  const dispose = watchRefreshSources({
    ...time, window, document, wallNow: () => wall, monotonicNow: () => mono,
    request: (event) => requests.push(event),
  });
  return {
    time, requests, dispose,
    send(type) { window.dispatchEvent(new Event(type)); },
    visibility(value) { document.visibilityState = value; document.dispatchEvent(new Event("visibilitychange")); },
    advance(w, m) { wall += w; mono += m; },
  };
}

test("a resume burst emits one invalidation and ordinary focus events do not reconnect", () => {
  const h = resumeHarness();
  h.send("focus");
  assert.equal(h.requests.length, 0);
  h.send("blur");
  h.visibility("hidden");
  h.advance(1_800_000, 0);
  h.visibility("visible");
  h.send("focus");
  h.time.tick(10_000);
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].invalidate, true);
  h.dispose();
});

test("a sleeping monotonic clock is detected without visibility events and wall time is only a trigger", () => {
  const h = resumeHarness();
  h.advance(1_800_000, 0);
  h.time.tick(10_000);
  assert.deepEqual(h.requests, [{ reason: "clock-gap", invalidate: true }]);
  h.advance(-3_600_000, 1000);
  h.time.tick(10_000);
  assert.equal(h.requests.length, 2, "backward wall-clock changes also request authoritative time");
  h.advance(30_000, 30_000);
  h.time.tick(30_000);
  assert.equal(h.requests.length, 2, "ordinary elapsed time needs no server read");
  h.dispose();
});

test("hidden clock gaps wait for return, online requests join work, and disposal removes listeners", () => {
  const h = resumeHarness();
  h.visibility("hidden");
  h.advance(1_800_000, 0);
  h.time.tick(10_000);
  assert.equal(h.requests.length, 0);
  h.visibility("visible");
  assert.equal(h.requests.length, 1);
  h.send("online");
  assert.deepEqual(h.requests[1], { reason: "online" });
  h.dispose();
  assert.equal(h.time.size(), 0);
  h.send("blur");
  h.send("focus");
  h.send("online");
  h.visibility("hidden");
  h.visibility("visible");
  assert.equal(h.requests.length, 2);
});
