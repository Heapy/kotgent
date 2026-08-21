// The row builders and the two small helpers this tier's tests share. Deliberately not named
// `*.test.js`: the node runner's pattern and the file count in WebUiLogicTest both key on that suffix,
// and a fixture is not a test — the same reason turkish-fold.js is named as it is.
//
// One builder per shape, with one set of defaults. Byte-identical copies of `sessionRow` stood in two
// files and near-copies of `patchFrame` and `taskRow` in two more, differing in a field nobody had
// chosen; a test that needs a different value says so at the call site, where the reader can see it.
//
// Task refs are `<tracker>:<key>` — the shape `src/core/Ids.kt` enforces, the one the Kotlin fixture
// tier writes as `local:N`, and the one the picker's own placeholder shows. A ref no daemon could emit
// is a fixture that agrees with neither the domain nor the tier next door.

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

/** A `session_update` patch frame: no cwd, agent or name, which is why a patch cannot create a row. */
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

// A deferred stands in for a request in flight: the run is suspended exactly where the network would
// suspend it, without a timer deciding the test's outcome. `reject` is handed back too, because a
// failure arriving late is the same shape of question as a success arriving late.
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise: promise, resolve: resolve, reject: reject };
}
