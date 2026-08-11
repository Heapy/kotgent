/* xterm owns terminal-host DOM and its WebSocket outside the vdom; one attachment effect owns both. */

import { html } from "htm/preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { resizeFrame, wsUrl } from "../lib/api.js";
import { displayName, stateBadge, taskBadge } from "../lib/sessions.js";
import { navigate, taskPath } from "../lib/router.js";
import { installTerminalUnicode, loadTerminalUnicode } from "../lib/unicode.js";
import { KeyBar } from "./KeyBar.js";

function debounce(fn, ms) {
  let handle;
  const debounced = function () {
    clearTimeout(handle);
    handle = setTimeout(fn, ms);
  };
  debounced.cancel = () => clearTimeout(handle);
  return debounced;
}

function sendResize(ws, cols, rows) {
  if (ws.readyState === WebSocket.OPEN && cols > 0 && rows > 0) {
    ws.send(resizeFrame(cols, rows));
  }
}

/** Apply Ctrl only to one printable character; null leaves the sticky modifier armed. */
function ctrlBytesFor(data) {
  const chars = Array.from(data);
  if (chars.length !== 1) return null;
  const char = chars[0];
  const codePoint = char.codePointAt(0);
  if (codePoint < 0x20 || (codePoint >= 0x7f && codePoint <= 0x9f)) return null;

  const upper = char.toUpperCase();
  const upperCode = upper.length === 1 ? upper.charCodeAt(0) : -1;
  if (upperCode >= 0x40 && upperCode <= 0x5f) {
    return Uint8Array.of(upperCode & 0x1f);
  }

  // Match xterm's Ctrl+2 through Ctrl+8 aliases.
  switch (char) {
    case " ":
    case "2": return Uint8Array.of(0x00);
    case "3": return Uint8Array.of(0x1b);
    case "4": return Uint8Array.of(0x1c);
    case "5": return Uint8Array.of(0x1d);
    case "6": return Uint8Array.of(0x1e);
    case "7": return Uint8Array.of(0x1f);
    case "8":
    case "?": return Uint8Array.of(0x7f);
    default: return new TextEncoder().encode(char);
  }
}

/* Capture touch pointers across xterm repaints and feed synthetic wheel events through xterm's current
 * mouse protocol. Animation-frame banking bounds bursts and supplies momentum after release. */
function installSwipeScroll(term) {
  const element = term.element;
  if (!element) return { shouldFocus: () => true, dispose: () => {} };

  const startThreshold = 6;
  // Keep this above measured finger delivery; excess travel remains banked.
  const maxReportsPerFrame = 6;
  const velocityWeight = 0.6;
  const inertiaDecayPerMs = 0.995;
  const minInertiaVelocity = 0.03;
  const maxInertiaMs = 1200;
  // A rested finger stops rather than handing stale velocity to inertia.
  const inertiaHandoffMs = 90;
  // Emit one report per row because the browser cannot know whether tmux or a TUI consumes it.
  let gesture = null;
  let suppressFocusUntil = 0;

  // Scheduler state outlives the touch during inertia.
  let pendingPx = 0;
  let velocity = 0;
  let lastMoveAt = 0;
  let coasting = false;
  let inertiaUntil = 0;
  let lastPoint = null;
  let frameHandle = 0;
  let lastFrameAt = 0;

  const stopScheduler = () => {
    if (frameHandle) cancelAnimationFrame(frameHandle);
    frameHandle = 0;
    lastFrameAt = 0;
  };

  const stopMotion = () => {
    pendingPx = 0;
    velocity = 0;
    coasting = false;
    stopScheduler();
  };

  const dispatchReports = (count, direction, bounds) => {
    // Inertial reports still need coordinates inside a cell tmux accepts.
    const point = lastPoint || { x: bounds.left + bounds.width / 2, y: bounds.top + bounds.height / 2 };
    const clientX = Math.max(bounds.left + 1, Math.min(point.x, bounds.right - 1));
    const clientY = Math.max(bounds.top + 1, Math.min(point.y, bounds.bottom - 1));
    for (let i = 0; i < count; i += 1) {
      element.dispatchEvent(new WheelEvent("wheel", {
        bubbles: true,
        cancelable: true,
        composed: true,
        clientX,
        clientY,
        deltaY: direction,
        deltaMode: WheelEvent.DOM_DELTA_LINE,
        view: window,
      }));
    }
  };

  const frame = (now) => {
    frameHandle = 0;
    // Clamp background-tab gaps so inertia cannot jump on resume.
    const elapsed = lastFrameAt ? Math.min(now - lastFrameAt, 64) : 16.7;
    lastFrameAt = now;

    // Stop if the pane disables mouse tracking during inertia.
    if (term.modes.mouseTrackingMode === "none") {
      stopMotion();
      return;
    }

    if (coasting) {
      if (now >= inertiaUntil || Math.abs(velocity) < minInertiaVelocity) velocity = 0;
      else {
        pendingPx += velocity * elapsed;
        velocity *= Math.pow(inertiaDecayPerMs, elapsed);
      }
    }

    const screen = element.querySelector(".xterm-screen") || element;
    const bounds = screen.getBoundingClientRect();
    const rowHeight = bounds.height / Math.max(term.rows, 1);
    if (!Number.isFinite(rowHeight) || rowHeight <= 0) {
      stopMotion();
      return;
    }

    const banked = Math.trunc(pendingPx / rowHeight);
    if (banked !== 0) {
      const direction = Math.sign(banked);
      const count = Math.min(Math.abs(banked), maxReportsPerFrame);
      // Remove only emitted travel; reversals subtract from what remains banked.
      pendingPx -= direction * count * rowHeight;
      dispatchReports(count, direction, bounds);
    }

    const finished = !gesture && velocity === 0 && Math.abs(pendingPx) < rowHeight;
    if (finished) stopMotion();
    else frameHandle = requestAnimationFrame(frame);
  };

  const ensureScheduler = () => {
    if (!frameHandle) frameHandle = requestAnimationFrame(frame);
  };

  const onPointerDown = (event) => {
    if (event.pointerType !== "touch") return;
    // A new touch catches an inertial scroll.
    stopMotion();
    // Immediate capture keeps the stream alive while xterm repaints rows beneath it.
    element.setPointerCapture(event.pointerId);
    lastMoveAt = performance.now();
    lastPoint = { x: event.clientX, y: event.clientY };
    gesture = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastY: event.clientY,
      claimed: false,
    };
  };

  const onPointerMove = (event) => {
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    // Yield when xterm is not requesting mouse reports.
    if (term.modes.mouseTrackingMode === "none") {
      gesture = null;
      stopMotion();
      return;
    }

    const deltaY = gesture.lastY - event.clientY;
    gesture.lastY = event.clientY;

    if (!gesture.claimed) {
      const totalX = event.clientX - gesture.startX;
      const totalY = gesture.startY - event.clientY;
      if (Math.abs(totalY) < startThreshold || Math.abs(totalY) <= Math.abs(totalX)) return;
      gesture.claimed = true;
    }

    // Claim only vertical swipes, preserving tap-to-focus.
    if (event.cancelable) event.preventDefault();
    suppressFocusUntil = Date.now() + 350;

    pendingPx += deltaY;
    lastPoint = { x: event.clientX, y: event.clientY };
    // Do not mix event.timeStamp with performance.now; their origins may differ.
    const now = performance.now();
    const elapsed = now - lastMoveAt;
    lastMoveAt = now;
    // Smooth uneven iOS move delivery.
    if (elapsed > 0) {
      const sample = deltaY / elapsed;
      velocity = velocity === 0 ? sample : velocity * (1 - velocityWeight) + sample * velocityWeight;
    }
    ensureScheduler();
  };

  const onPointerUp = (event) => {
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    const threw = gesture.claimed;
    gesture = null;
    if (!threw) {
      stopMotion();
      return;
    }
    if (performance.now() - lastMoveAt > inertiaHandoffMs) velocity = 0;
    coasting = true;
    inertiaUntil = performance.now() + maxInertiaMs;
    // Flush the final bank even when velocity has expired.
    ensureScheduler();
  };

  const onPointerCancel = (event) => {
    if (gesture && event.pointerId !== gesture.pointerId) return;
    gesture = null;
    stopMotion();
  };

  element.addEventListener("pointerdown", onPointerDown);
  element.addEventListener("pointermove", onPointerMove);
  element.addEventListener("pointerup", onPointerUp);
  element.addEventListener("pointercancel", onPointerCancel);

  return {
    shouldFocus: () => Date.now() >= suppressFocusUntil,
    dispose: () => {
      element.removeEventListener("pointerdown", onPointerDown);
      element.removeEventListener("pointermove", onPointerMove);
      element.removeEventListener("pointerup", onPointerUp);
      element.removeEventListener("pointercancel", onPointerCancel);
      gesture = null;
      stopMotion();
    },
  };
}

export function TerminalPane({
  session, tasks, attachedId, terminalFontSize, terminalUnicode, hint, drawerOpen,
  sidebarCollapsed, onToggleDrawer, onToggleSidebar, onOpenPalette, onTerminalClosed,
}) {
  const hostRef = useRef(null);
  const keyBarRef = useRef(null);
  const terminalRef = useRef(null);
  const fitRef = useRef(null);
  const socketRef = useRef(null);
  const sendBytesRef = useRef(null);
  const ctrlActiveRef = useRef(false);
  const [ctrlActive, setCtrlActive] = useState(false);
  const fontSizeRef = useRef(terminalFontSize);
  fontSizeRef.current = terminalFontSize;
  // Attachment teardown clears this because term.dispose already disposes loaded addons.
  const unicodeDisposeRef = useRef(null);
  // Callback identity must not tear down a live attachment.
  const closedRef = useRef(onTerminalClosed);
  closedRef.current = onTerminalClosed;

  useEffect(() => {
    if (!attachedId) return undefined;
    const host = hostRef.current;
    if (!host) return undefined;
    const app = host.closest("#app");
    if (!app) return undefined;
    ctrlActiveRef.current = false;
    setCtrlActive(false);

    const term = new Terminal({
      // Required for term.unicode.activeVersion used by the optional width providers.
      allowProposedApi: true,
      convertEol: false,
      // DOM row repaints restart CSS cursor animation, so default to steady; terminal modes may override.
      cursorBlink: false,
      // History belongs to tmux; local scrollback duplicates it and reserves 14px for an xterm scrollbar.
      scrollback: 0,
      fontFamily: "Menlo, Monaco, \"Courier New\", monospace",
      fontSize: fontSizeRef.current,
      theme: { background: "#000000" },
      // Preserve macOS Alt-drag selection while a TUI has mouse reporting enabled.
      macOptionClickForcesSelection: true,
    });
    const fit = new FitAddon.FitAddon();
    term.loadAddon(fit);
    term.open(host);

    // Use visualViewport for keyboard geometry, tolerating iOS PWA safe-area loss without a keyboard.
    const viewport = window.visualViewport;
    const sizeForVisualViewport = () => {
      if (!viewport) return;
      if (!Number.isFinite(viewport.height) || !Number.isFinite(viewport.offsetTop) ||
          viewport.height <= 0) return; // Safari emits transient zeroes during rotation.

      const appBounds = app.getBoundingClientRect();
      if (!Number.isFinite(appBounds.height) || appBounds.height <= 0) return;
      const appStyle = getComputedStyle(app);
      const safeAreaHeight =
        (Number.parseFloat(appStyle.getPropertyValue("--device-safe-area-top")) || 0) +
        (Number.parseFloat(appStyle.getPropertyValue("--device-safe-area-bottom")) || 0);
      const viewportShrunken = viewport.height < appBounds.height - safeAreaHeight - 1;

      // Measure without the previous keyboard cap; restore it if new metrics are invalid.
      const previousHeight = host.style.getPropertyValue("--terminal-visible-height");
      host.classList.remove("visual-viewport-sized");
      host.style.removeProperty("--terminal-visible-height");
      // Toggle before measuring the key bar because the state changes its safe-area height.
      app.classList.toggle("visual-viewport-shrunken", viewportShrunken);
      if (!viewportShrunken) return;

      const bounds = host.getBoundingClientRect();
      const visibleBottom = viewport.offsetTop + viewport.height;
      // Reserve the key bar inside the visual-viewport ceiling.
      const keyBarHeight = keyBarRef.current?.getBoundingClientRect().height || 0;
      const visibleHeight = Math.floor(Math.min(
        bounds.height,
        visibleBottom - bounds.top - keyBarHeight,
      ));
      if (!Number.isFinite(visibleHeight) || visibleHeight <= 0) {
        if (previousHeight) {
          host.classList.add("visual-viewport-sized");
          host.style.setProperty("--terminal-visible-height", previousHeight);
        }
        return;
      }
      host.classList.add("visual-viewport-sized");
      host.style.setProperty("--terminal-visible-height", visibleHeight + "px");
    };
    sizeForVisualViewport();
    try { fit.fit(); } catch (_) { /* ResizeObserver retries after layout. */ }

    // Put initial geometry in the URL so tmux attaches at the correct size before emitting bytes.
    const ws = new WebSocket(wsUrl(
      "/sessions/" + encodeURIComponent(attachedId) + "/terminal" +
      "?cols=" + term.cols + "&rows=" + term.rows,
    ));
    ws.binaryType = "arraybuffer";
    // Report daemon disconnects, not our own teardown.
    let teardown = false;
    const sendBytes = (bytes) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(bytes);
    };
    sendBytesRef.current = sendBytes;

    const fitAndReport = () => {
      if (teardown) return;
      // A same-size resize forces measurement when open preceded host layout.
      try { term.resize(term.cols, term.rows); } catch (_) {}
      try { fit.fit(); } catch (_) {}
      sendResize(ws, term.cols, term.rows);
    };

    ws.onopen = () => {
      fitAndReport();
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") return;
      term.write(new Uint8Array(ev.data));
    };
    ws.onclose = () => {
      if (teardown) return;
      term.write("\r\n[terminal disconnected]\r\n");
      // Identify the closed attachment so a late callback cannot tear down its replacement.
      closedRef.current(attachedId);
    };

    const dataSubscription = term.onData((data) => {
      if (ctrlActiveRef.current) {
        const ctrlBytes = ctrlBytesFor(data);
        if (ctrlBytes !== null) {
          // Clear synchronously because two input events may arrive before rendering.
          ctrlActiveRef.current = false;
          setCtrlActive(false);
          sendBytes(ctrlBytes);
          return;
        }
      }
      sendBytes(new TextEncoder().encode(data));
    });
    // Legacy X10 mouse reports arrive on onBinary and must not be UTF-8 encoded.
    const binarySubscription = term.onBinary((data) => {
      const bytes = new Uint8Array(data.length);
      for (let i = 0; i < data.length; i += 1) bytes[i] = data.charCodeAt(i) & 0xff;
      sendBytes(bytes);
    });
    const resizeSubscription = term.onResize(({ cols, rows }) => sendResize(ws, cols, rows));

    // Host geometry changes without window resize, and initial layout may follow term.open().
    const refit = debounce(fitAndReport, 120);
    const observer = new ResizeObserver(refit);
    observer.observe(host);

    // iOS keyboard focus must happen synchronously from the completed tap, never from ws.onopen.
    const swipeScroll = installSwipeScroll(term);
    const focusTerminal = () => {
      if (swipeScroll.shouldFocus()) term.focus();
    };
    host.addEventListener("click", focusTerminal);

    const viewportChanged = () => {
      sizeForVisualViewport();
      // Move immediately but debounce tmux reflow across keyboard animation frames.
      refit();
    };
    if (viewport) {
      viewport.addEventListener("resize", viewportChanged);
      viewport.addEventListener("scroll", viewportChanged);
    }

    terminalRef.current = term;
    fitRef.current = fit;
    socketRef.current = ws;

    return () => {
      teardown = true;
      refit.cancel();
      observer.disconnect();
      host.removeEventListener("click", focusTerminal);
      swipeScroll.dispose();
      if (viewport) {
        viewport.removeEventListener("resize", viewportChanged);
        viewport.removeEventListener("scroll", viewportChanged);
      }
      host.classList.remove("visual-viewport-sized");
      host.style.removeProperty("--terminal-visible-height");
      app.classList.remove("visual-viewport-shrunken");
      dataSubscription.dispose();
      binarySubscription.dispose();
      resizeSubscription.dispose();
      ws.onopen = null;
      ws.onmessage = null;
      ws.onclose = null;
      try { ws.close(); } catch (_) {}
      unicodeDisposeRef.current = null;
      try { term.dispose(); } catch (_) {}
      host.replaceChildren();
      ctrlActiveRef.current = false;
      if (terminalRef.current === term) terminalRef.current = null;
      if (fitRef.current === fit) fitRef.current = null;
      if (socketRef.current === ws) socketRef.current = null;
      if (sendBytesRef.current === sendBytes) sendBytesRef.current = null;
    };
  }, [attachedId]);

  /* Install unicode providers on the live attachment. attachedId retriggers for each Terminal, and
   * cancellation prevents out-of-order asynchronous loads from installing stale providers. */
  useEffect(() => {
    const term = terminalRef.current;
    if (!term) return undefined;
    let cancelled = false;

    const previous = unicodeDisposeRef.current;
    unicodeDisposeRef.current = null;
    if (previous) previous();

    loadTerminalUnicode(terminalUnicode).then((loaded) => {
      if (cancelled || !loaded) return;
      unicodeDisposeRef.current = installTerminalUnicode(term, loaded);
    }).catch(() => {
      // Addon failure falls back to xterm's built-in width table.
    });

    return () => { cancelled = true; };
  }, [attachedId, terminalUnicode]);

  // Font changes re-fit the live terminal without replacing its WebSocket.
  useEffect(() => {
    const term = terminalRef.current;
    const fit = fitRef.current;
    const ws = socketRef.current;
    if (!term || !fit || !ws) return;
    term.options.fontSize = terminalFontSize;
    try { term.resize(term.cols, term.rows); } catch (_) {}
    try { fit.fit(); } catch (_) {}
    sendResize(ws, term.cols, term.rows);
  }, [terminalFontSize]);

  const badge = session ? stateBadge(session.state) : null;
  const attached = !!session && session.id === attachedId;
  const toggleCtrl = () => {
    const next = !ctrlActiveRef.current;
    ctrlActiveRef.current = next;
    setCtrlActive(next);
  };
  const releaseCtrl = () => {
    ctrlActiveRef.current = false;
    setCtrlActive(false);
  };
  const openPalette = () => onOpenPalette("leader");

  return html`
    <main id="terminal-pane">
      <div id="terminal-head">
        <button
          id="drawer-toggle"
          class="icon-button icon-button-small drawer-toggle"
          type="button"
          aria-label="Show the session list"
          aria-expanded=${drawerOpen ? "true" : "false"}
          aria-controls="sidebar"
          title="Sessions"
          onClick=${onToggleDrawer}
        >☰</button>
        <button
          id="sidebar-toggle"
          class="icon-button icon-button-small sidebar-toggle"
          type="button"
          aria-label=${sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-expanded=${sidebarCollapsed ? "false" : "true"}
          aria-controls="sidebar"
          title=${sidebarCollapsed ? "Expand sidebar (⌘.)" : "Collapse sidebar (⌘.)"}
          onClick=${onToggleSidebar}
        >${sidebarCollapsed ? "›" : "‹"}</button>
        <div class="terminal-identity">
          <span id="terminal-title">${session ? displayName(session) : "No session selected"}</span>
          <span id="terminal-state" class=${badge ? "badge " + badge.cls : "badge"}>
            ${badge ? badge.label : ""}
          </span>
          <${HeaderTaskBadge} session=${session} tasks=${tasks} />
        </div>
        <button
          id="palette-button"
          class="icon-button icon-button-small palette-button"
          type="button"
          aria-label="Open command palette"
          title="Commands"
          onClick=${openPalette}
        >⋯</button>
      </div>

      <div id="terminal-host" ref=${hostRef}></div>

      ${attached && html`
        <${KeyBar}
          barRef=${keyBarRef}
          sendBytesRef=${sendBytesRef}
          ctrlActive=${ctrlActive}
          onToggleCtrl=${toggleCtrl}
          onReleaseCtrl=${releaseCtrl}
        />
      `}

      ${hint && html`<p id="terminal-hint" class="terminal-hint">${hint}</p>`}
    </main>
  `;
}

/** Preserve real-link behavior while routing plain task-badge clicks in-app. */
function HeaderTaskBadge({ session, tasks }) {
  const task = session ? taskBadge(session, tasks) : null;
  if (!task) return null;
  const open = (event) => {
    event.stopPropagation();
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(taskPath(task.ref));
  };
  return html`
    <a
      id="terminal-task"
      class=${"task-badge" + (task.known ? "" : " task-badge-unknown")}
      href=${taskPath(task.ref)}
      title=${task.tooltip}
      onClick=${open}
    >
      <span class="task-session-dot" data-state=${session.state}></span>${task.label}
    </a>
  `;
}
