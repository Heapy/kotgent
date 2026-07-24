/*
 * A leading-edge throttle.
 *
 * Leading edge matters for the caller this exists for (mark-read): the FIRST call goes out immediately,
 * so the unread badge clears within one local round-trip instead of after a full window. A burst of calls
 * inside the window collapses into a single trailing call carrying the LATEST arguments, so a session
 * that is emitting events every few milliseconds still costs at most two requests per window.
 *
 * Depends only on `Date.now` / `setTimeout`, so it is import-able and exercisable outside a browser —
 * the `lib/` convention (see app.js's header).
 */

/** Default window: one call per second is plenty for a cursor the human eye is driving. */
const DEFAULT_WINDOW_MS = 1000;

/**
 * Wrap [fn] so it runs at most once per [windowMs], on the leading edge, plus once at the end of the
 * window when calls kept arriving during it. Returns the wrapped function; its return value is dropped
 * (a throttled call may never happen, so there is nothing meaningful to return).
 *
 * Only the LEADING call is decided at call time: the trailing one replays the newest arguments up to a
 * window later, so a caller whose own guard can go stale inside the window (the tab being hidden, the
 * session being archived) may still see that one late invocation. Fine for a mark-read — the write is
 * idempotent and monotonic server-side — but do not wrap something where a stale replay is harmful.
 */
export function throttleLeading(fn, windowMs = DEFAULT_WINDOW_MS) {
  let lastRun = 0;
  let timer = null;
  let pending = null;

  const run = (args) => {
    lastRun = Date.now();
    pending = null;
    fn(...args);
  };

  return function throttled(...args) {
    const elapsed = Date.now() - lastRun;
    if (timer === null && elapsed >= windowMs) {
      run(args); // leading edge — nothing is in flight and the window is clear
      return;
    }
    pending = args; // inside the window: remember the newest arguments, fire once when it closes
    if (timer !== null) return;
    timer = setTimeout(() => {
      timer = null;
      if (pending) run(pending);
    }, windowMs - elapsed);
  };
}
