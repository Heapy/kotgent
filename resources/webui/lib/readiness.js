// Whether a screen may be judged yet, as a state rather than a boolean.
//
// The link picker used to ask three booleans — `tasksReady`, `projectsReady`, and a `projectActive` the
// caller derived from the project list — and spell its answer as `ready = tasksReady && projectsReady`
// plus `projectUnavailable = projectsReady && !projectActive`. Three flags describe eight combinations,
// of which four are legal, and none of them can say "the read failed". That is finding app.js:388: one
// 503 on GET /projects left the picker reading "Reading open tasks…" for the rest of the page's life,
// with no error, no retry and no timeout, because a boolean that is false cannot distinguish "not yet"
// from "never".
//
// A readiness owns one source and answers `idle | loading | ready | failed` for it, with the sentence a
// failure has to carry. Two rules make it usable by a dialog that opens over a list it already has:
//
//   * `ready` is sticky. `begin()` over a ready source keeps it ready, so opening the picker can
//     revalidate without flickering the list away, and `fail()` over a ready source is declined, so a
//     failed revalidation leaves the stale-but-usable rows on screen. Only a source that has never
//     succeeded can fail visibly. `reset()` is the one way back.
//   * `failed` always carries a non-empty sentence. A terminal state with nothing to say is the
//     stuck-forever state under another name, so an empty or absent error is replaced rather than shown.
//
// `begin()` returns a token that scopes an outcome to that attempt: a read overtaken by a later one
// cannot write. Omitting the token makes the outcome authoritative — that is how a pushed snapshot,
// which owns no attempt, supersedes whatever read was in flight.
//
// signals-core is imported by relative path rather than through the "@preact/signals-core" bare
// specifier the import map wires; see the comment at state/sessions.js for why the two resolve to one
// module instance. Nothing here touches the DOM, the network, or a timer, so the rules are proven at the
// node tier in webuitest/js/readiness.test.js.

import { signal } from "../vendor/signals-core.module.js";

export const IDLE = "idle";
export const LOADING = "loading";
export const READY = "ready";
export const FAILED = "failed";

export const IDLE_STATUS = Object.freeze({ state: IDLE, error: null });

// Shown when a request fails with nothing quotable — a rejection with no message, or none at all.
export const UNKNOWN_FAILURE = "The request failed.";

function failureSentence(error) {
  const text = typeof error === "string"
    ? error
    : (error && typeof error.message === "string" ? error.message : "");
  return text.trim() !== "" ? text : UNKNOWN_FAILURE;
}

export function createReadiness() {
  const status = signal(IDLE_STATUS);
  let generation = 0;
  // Registered through `setLoader` rather than passed in: the owner of the request is not the code that
  // creates the readiness, and a second way in would be a second thing to keep in step.
  let load = null;

  // Writing an identical status would re-render every subscriber for no news; refreshes are frequent.
  function publish(state, error) {
    const current = status.value;
    if (current.state === state && current.error === error) return false;
    status.value = { state: state, error: error };
    return true;
  }

  function superseded(token) {
    return token !== undefined && token !== generation;
  }

  // Claims the source for one attempt. A ready source stays ready: the rows it already holds are what
  // the operator is looking at, and a revalidation is not a reason to take them away.
  function begin() {
    const token = ++generation;
    if (status.value.state !== READY) publish(LOADING, null);
    return token;
  }

  function succeed(token) {
    if (superseded(token)) return false;
    if (token === undefined) generation += 1;
    publish(READY, null);
    return true;
  }

  function fail(token, error) {
    if (superseded(token)) return false;
    if (token === undefined) generation += 1;
    if (status.value.state === READY) return false;
    publish(FAILED, failureSentence(error));
    return true;
  }

  function reset() {
    generation += 1;
    status.value = IDLE_STATUS;
  }

  // The owner of the request registers it here so a retry control anywhere on screen can ask for another
  // read without a callback threaded through every component between them.
  function setLoader(fn) {
    load = fn || null;
  }

  function retry() {
    if (!load || status.value.state === LOADING) return Promise.resolve(null);
    return Promise.resolve(load());
  }

  return {
    status: status,
    begin: begin,
    succeed: succeed,
    fail: fail,
    reset: reset,
    setLoader: setLoader,
    retry: retry,
  };
}

// A screen judged from several sources reports one state. A failure dominates, because an operator who
// cannot see it has no way to ask for the read again; the first one named is the one shown, so the
// sentence does not shuffle between renders. Otherwise every source must be ready before the screen is.
export function combineReadiness(...statuses) {
  let loading = false;
  let ready = statuses.length > 0;
  for (const status of statuses) {
    const state = status ? status.state : IDLE;
    if (state === FAILED) return { state: FAILED, error: failureSentence(status.error) };
    if (state === LOADING) loading = true;
    if (state !== READY) ready = false;
  }
  if (ready) return { state: READY, error: null };
  return loading ? { state: LOADING, error: null } : IDLE_STATUS;
}
