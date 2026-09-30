import type { Session, SessionUpdate } from "../../webui/src/lib/sessions.ts";
import type { Task } from "../../webui/src/lib/tasks.ts";

// Shared builders use daemon-valid shapes and frozen defaults; tests override only relevant fields.

// Frozen inputs turn an accidental in-place write into a TypeError; ES modules are always strict mode.
export function sessionRow(overrides?: Partial<Session>): Readonly<Session> {
  return Object.freeze({
    id: "s1",
    name: "one",
    tags: [],
    tmuxSession: "kotgent-one",
    cwd: "/work/one",
    agent: "claude",
    model: "opus",
    cliVersion: null,
    cliPath: null,
    providerSessionId: null,
    state: "running",
    needsAttention: false,
    alive: true,
    paneId: null,
    lastSeq: 7,
    readCursor: 7,
    unread: 0,
    archived: false,
    taskRef: null,
    projectId: "p1",
    adhd: false,
    parentSessionId: null,
    readOnly: false,
    promptPath: null,
    createdAt: 50,
    updatedAt: 100,
    rev: 2,
    ...overrides,
  });
}

/** A `session_update` patch frame: no cwd or agent, which is why a patch cannot create a row. */
export function patchFrame(overrides?: Partial<SessionUpdate>): Readonly<SessionUpdate> {
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

export function taskRow(overrides?: Partial<Task>): Readonly<Task> {
  return Object.freeze({
    ref: "local:12",
    project: "p1",
    title: "wire the board",
    body: "",
    url: null,
    state: "todo",
    blocked: false,
    dependsOn: [],
    position: 100,
    createdAt: 10,
    updatedAt: 10,
    rev: 2,
    ...overrides,
  });
}

export function listOf<T>(...rows: T[]): readonly T[] {
  return Object.freeze(rows);
}

// Suspend at a controlled request boundary without relying on wall time.
export function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise: promise, resolve: resolve, reject: reject };
}

// A macrotask boundary drains any pending microtask chain.
export function flush() {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}
