// Readiness distinguishes idle, loading, ready, and failed sources. Ready is sticky across revalidation;
// initial failure always carries a sentence. Attempt tokens reject stale outcomes, while tokenless pushed
// snapshots are authoritative.

import { signal } from "@preact/signals-core";

export const IDLE = "idle";
export const LOADING = "loading";
export const READY = "ready";
export const FAILED = "failed";

export interface ReadinessStatus {
  state: typeof IDLE | typeof LOADING | typeof READY | typeof FAILED;
  error: string | null;
}

export const IDLE_STATUS: ReadinessStatus = Object.freeze({ state: IDLE, error: null });

// Shown when a request fails with nothing quotable — a rejection with no message, or none at all.
export const UNKNOWN_FAILURE = "The request failed.";

function failureSentence(error: unknown) {
  const message: unknown = typeof error === "string" ? error : error && Reflect.get(Object(error), "message");
  const text = typeof message === "string" ? message : "";
  return text.trim() !== "" ? text : UNKNOWN_FAILURE;
}

export function createReadiness() {
  const status = signal<ReadinessStatus>(IDLE_STATUS);
  let generation = 0;
  let load: (() => unknown) | null = null;

  // Writing an identical status would re-render every subscriber for no news; refreshes are frequent.
  function publish(state: ReadinessStatus["state"], error: string | null) {
    const current = status.value;
    if (current.state === state && current.error === error) return false;
    status.value = { state: state, error: error };
    return true;
  }

  function superseded(token: number | undefined) {
    return token !== undefined && token !== generation;
  }

  // Revalidation keeps existing rows visible.
  function begin() {
    const token = ++generation;
    if (status.value.state !== READY) publish(LOADING, null);
    return token;
  }

  function succeed(token?: number) {
    if (superseded(token)) return false;
    if (token === undefined) generation += 1;
    publish(READY, null);
    return true;
  }

  function fail(token: number | undefined, error?: unknown) {
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

  function setLoader(fn: (() => unknown) | null | undefined) {
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
export function combineReadiness(...statuses: (ReadinessStatus | null | undefined)[]): ReadinessStatus {
  let loading = false;
  let ready = statuses.length > 0;
  for (const status of statuses) {
    const state = status ? status.state : IDLE;
    if (state === FAILED) return { state: FAILED, error: failureSentence(status!.error) };
    if (state === LOADING) loading = true;
    if (state !== READY) ready = false;
  }
  if (ready) return { state: READY, error: null };
  return loading ? { state: LOADING, error: null } : IDLE_STATUS;
}
