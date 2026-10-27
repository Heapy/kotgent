import type { Terminal } from "@xterm/xterm";
import { terminalScreenBounds } from "./xterm-dom.ts";

/** xterm 6 reports at most one mouse wheel tick per DOM event, discarding larger distances.
 * Convert travel to unit line events, leaving protocol encoding and selection rules to xterm.
 * Browser/OS momentum is already in the input stream; do not add inertia or an unbounded queue. */
export function installTerminalWheel(term: Terminal) {
  const element = term.element!;
  const maxPerFrame = 12;
  const maxPending = 24;
  // Covers float32 wheel-delta rounding across the bounded 24-row bank.
  const rowTolerance = 2e-6;
  const residueIdleMs = 150;
  let pending = 0;
  let lastWheelAt = 0;
  let frame = 0;
  let point: { clientX: number; clientY: number } | null = null;
  const wholeRows = () => Math.trunc(Math.abs(pending) + rowTolerance);

  const reset = () => {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    pending = 0;
    lastWheelAt = 0;
    point = null;
  };

  const flush = () => {
    frame = 0;
    const bounds = terminalScreenBounds(term);
    if (!point || term.modes.mouseTrackingMode === "none" || !bounds?.height || !bounds.width) {
      reset();
      return;
    }
    const direction = Math.sign(pending);
    const count = Math.min(wholeRows(), maxPerFrame);
    pending -= direction * count;
    // Rounding up must not leave a tiny bank in the opposite direction.
    if (Math.abs(pending) <= rowTolerance) pending = 0;
    for (let i = 0; i < count; i++) {
      element.dispatchEvent(new WheelEvent("wheel", {
        bubbles: true, cancelable: true, composed: true,
        ...point, deltaY: direction, deltaMode: WheelEvent.DOM_DELTA_LINE,
      }));
    }
    if (wholeRows() > 0) frame = requestAnimationFrame(flush);
  };

  term.attachCustomWheelEventHandler((event) => {
    // Unit line reports include our own output and the existing touchscreen swipe bridge.
    if (event.deltaMode === WheelEvent.DOM_DELTA_LINE && Math.abs(event.deltaY) === 1) return true;
    if (term.modes.mouseTrackingMode === "none" || event.shiftKey || event.altKey || event.ctrlKey) {
      reset();
      return true;
    }
    if (!Number.isFinite(event.deltaY) || event.deltaY === 0) return true;
    const bounds = terminalScreenBounds(term);
    if (!bounds?.height || !bounds.width) return true;
    const rowHeight = bounds.height / term.rows;
    const lines = event.deltaMode === WheelEvent.DOM_DELTA_PIXEL ? event.deltaY / rowHeight
      : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? event.deltaY * term.rows : event.deltaY;
    const now = performance.now();
    // Expire only fractional travel, lazily at the next input rather than running an idle timer.
    if (now - lastWheelAt >= residueIdleMs && wholeRows() === 0) pending = 0;
    lastWheelAt = now;
    // A new direction must take effect immediately, including before the next animation frame.
    if (pending * lines < 0) pending = 0;
    pending = Math.max(-maxPending, Math.min(maxPending, pending + lines));
    point = { clientX: event.clientX, clientY: event.clientY };
    if (!frame && wholeRows() > 0) frame = requestAnimationFrame(flush);
    return false;
  });

  element.addEventListener("keydown", reset, true);
  element.addEventListener("pointerdown", reset, true);
  window.addEventListener("blur", reset);
  return {
    reset,
    dispose: () => {
      reset();
      term.attachCustomWheelEventHandler(() => true);
      element.removeEventListener("keydown", reset, true);
      element.removeEventListener("pointerdown", reset, true);
      window.removeEventListener("blur", reset);
    },
  };
}
