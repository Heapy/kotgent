// Readiness distinguishes idle, loading, ready, and failed sources. Ready is sticky across revalidation;
// initial failure always carries a sentence. Attempt tokens reject stale outcomes, while tokenless pushed
// snapshots are authoritative.

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

  // Revalidation keeps existing rows visible.
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

// Failure dominates combined state; otherwise every source must be ready.
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
