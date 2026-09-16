// Wall time detects a discontinuity, but never supplies the displayed time or usage freshness.
export function watchRefreshSources({
  request, window, document, wallNow = () => Date.now(), monotonicNow = () => performance.now(),
  schedule = setTimeout, cancel = clearTimeout, intervalMs = 10_000, gapMs = 5000,
}) {
  let wall = wallNow();
  let mono = monotonicNow();
  let away = document.visibilityState !== "visible";
  let gap = false;
  let stopped = false;
  let timer = null;

  function sample() {
    const nextWall = wallNow();
    const nextMono = monotonicNow();
    gap ||= Math.abs((nextWall - wall) - (nextMono - mono)) > gapMs;
    wall = nextWall;
    mono = nextMono;
  }

  function refreshAfterReturn() {
    sample();
    if (document.visibilityState !== "visible" || (!away && !gap)) return;
    const reason = gap ? "clock-gap" : "resume";
    away = gap = false;
    request({ reason, invalidate: true });
  }

  const markAway = () => { away = true; };
  const visibility = () => {
    if (document.visibilityState === "visible") refreshAfterReturn();
    else markAway();
  };
  const pageshow = (event) => {
    if (event.persisted) away = true;
    refreshAfterReturn();
  };
  const online = () => { request({ reason: "online" }); };
  const tick = () => {
    if (stopped) return;
    sample();
    // A blurred but visible page has not necessarily returned. Only a clock gap needs timer recovery.
    if (gap && document.visibilityState === "visible") refreshAfterReturn();
    timer = schedule(tick, intervalMs);
  };

  window.addEventListener("blur", markAway);
  window.addEventListener("focus", refreshAfterReturn);
  window.addEventListener("pageshow", pageshow);
  window.addEventListener("online", online);
  document.addEventListener("visibilitychange", visibility);
  timer = schedule(tick, intervalMs);
  return () => {
    stopped = true;
    cancel(timer);
    window.removeEventListener("blur", markAway);
    window.removeEventListener("focus", refreshAfterReturn);
    window.removeEventListener("pageshow", pageshow);
    window.removeEventListener("online", online);
    document.removeEventListener("visibilitychange", visibility);
  };
}
