// Shared builders use daemon-valid shapes and frozen defaults; tests override only relevant fields.

// Frozen inputs turn an accidental in-place write into a TypeError; ES modules are always strict mode.
export function sessionRow(overrides) {
  return Object.freeze({
    id: "s1",
    name: "one",
    tmuxSession: "kotgent-one",
    cwd: "/work/one",
    agent: "claude",
    state: "running",
    needsAttention: false,
    alive: true,
    lastSeq: 7,
    unread: 0,
    archived: false,
    model: "opus",
    taskRef: null,
    projectId: "p1",
    updatedAt: 100,
    rev: 2,
    ...overrides,
  });
}

/** A `session_update` patch frame: no cwd or agent, which is why a patch cannot create a row. */
export function patchFrame(overrides) {
  return Object.freeze({
    sessionId: "s1",
    state: "ready",
    needsAttention: false,
    lastSeq: 9,
    unread: 1,
    archived: false,
    model: "opus",
    taskRef: null,
    projectId: "p1",
    updatedAt: 300,
    rev: 3,
    ...overrides,
  });
}

export function taskRow(overrides) {
  return Object.freeze({
    ref: "local:12",
    project: "p1",
    title: "wire the board",
    state: "todo",
    position: 100,
    createdAt: 10,
    rev: 2,
    ...overrides,
  });
}

export function listOf(...rows) {
  return Object.freeze(rows);
}

// Suspend at a controlled request boundary without relying on wall time.
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise: promise, resolve: resolve, reject: reject };
}

// A macrotask boundary drains any pending microtask chain.
export function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
