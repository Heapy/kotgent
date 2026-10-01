/* xterm owns terminal-host DOM and its WebSocket outside the vdom; one attachment effect owns both. */

import { SidebarToggle } from "./SidebarToggle.tsx";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { createContext } from "preact";
import type { ComponentChildren, JSX } from "preact";
import { useContext, useEffect, useLayoutEffect, useRef, useState } from "preact/hooks";
import { resizeFrame, wsUrl } from "../lib/api.ts";
import type { Command } from "../lib/commands.ts";
import type { Session } from "../lib/sessions.ts";
import type { Task } from "../lib/tasks.ts";
import { installTerminalUnicode, loadTerminalUnicode } from "../lib/unicode.ts";
import { SessionActions, SessionDetails } from "./SessionMenus.tsx";
import { Icon } from "./Icon.tsx";
import { useVisiblePane } from "./useVisiblePane.ts";
import { KeyBar } from "./KeyBar.tsx";
import type { KeyBarProps } from "./KeyBar.tsx";
import type { TerminalUnicodeModeValue } from "../lib/unicode.ts";

interface SessionContextProps {
  session: Session | null;
  tasks: readonly Task[];
}

export interface TerminalPaneProps extends SessionContextProps {
  attachedId: string | null;
  focusRequest: { sessionId: string } | null;
  terminalFontSize: number;
  terminalUnicode: TerminalUnicodeModeValue;
  hint: string | null;
  drawerOpen: boolean;
  sidebarCollapsed: boolean;
  onToggleDrawer: JSX.MouseEventHandler<HTMLButtonElement>;
  onToggleSidebar: JSX.MouseEventHandler<HTMLButtonElement>;
  onOpenPalette: (mode: "leader") => void;
  commands: readonly Command[];
  onCopyCwd: () => void;
  onTerminalClosed: (id: string) => void;
  workspace: ComponentChildren;
  workspaceToolbar: ComponentChildren;
}

interface TerminalSlotPort {
  claim: (slot: HTMLElement) => void;
  release: (slot: HTMLElement) => void;
}

interface TerminalVisibility {
  show: () => void;
  hide: () => void;
}

const TerminalSlotContext = createContext<TerminalSlotPort | null>(null);

/* The pane's single xterm host moves into whichever slot is mounted; with none mounted it is parked hidden
 * and keeps its socket and buffer. */
export function TerminalSlot() {
  const port = useContext(TerminalSlotContext);
  const slotRef = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const slot = slotRef.current;
    if (!port || !slot) return undefined;
    port.claim(slot);
    return () => port.release(slot);
  }, [port]);
  return <div class="terminal-slot" ref={slotRef}></div>;
}

function debounce(fn: () => void, ms: number) {
  let handle: ReturnType<typeof setTimeout> | undefined;
  const debounced = function () {
    clearTimeout(handle);
    handle = setTimeout(fn, ms);
  };
  debounced.cancel = () => clearTimeout(handle);
  return debounced;
}

function sendResize(ws: WebSocket, cols: number, rows: number) {
  if (ws.readyState === WebSocket.OPEN && cols > 0 && rows > 0) {
    ws.send(resizeFrame(cols, rows));
  }
}

/** Apply Ctrl only to one printable character; null leaves the sticky modifier armed. */
function ctrlBytesFor(data: string) {
  const chars = Array.from(data);
  if (chars.length !== 1) return null;
  const char = chars[0]!;
  const codePoint = char.codePointAt(0)!;
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
function installSwipeScroll(term: Terminal) {
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
  let gesture: {
    pointerId: number;
    startX: number;
    startY: number;
    lastY: number;
    claimed: boolean;
  } | null = null;
  let suppressFocusUntil = 0;

  // Scheduler state outlives the touch during inertia.
  let pendingPx = 0;
  let velocity = 0;
  let lastMoveAt = 0;
  let coasting = false;
  let inertiaUntil = 0;
  let lastPoint: { x: number; y: number } | null = null;
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

  const dispatchReports = (count: number, direction: number, bounds: DOMRect) => {
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

  const frame = (now: number) => {
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

  const onPointerDown = (event: PointerEvent) => {
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

  const onPointerMove = (event: PointerEvent) => {
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

  const onPointerUp = (event: PointerEvent) => {
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

  const onPointerCancel = (event: PointerEvent) => {
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
  session, tasks, attachedId, focusRequest, terminalFontSize, terminalUnicode, hint, drawerOpen,
  sidebarCollapsed, onToggleDrawer, onToggleSidebar, onOpenPalette, commands, onCopyCwd, onTerminalClosed, workspace, workspaceToolbar,
}: TerminalPaneProps) {
  const paneRef = useRef<HTMLElement>(null);
  useVisiblePane(paneRef);
  const hostRef = useRef<HTMLDivElement | null>(null);
  if (hostRef.current === null) {
    const created = document.createElement("div");
    created.id = "terminal-host";
    hostRef.current = created;
  }
  const parkingRef = useRef<HTMLDivElement>(null);
  const slotRef = useRef<HTMLElement | null>(null);
  const shownRef = useRef(false);
  const [terminalShown, setTerminalShown] = useState(false);
  const [showRequest, setShowRequest] = useState(0);
  const visibilityRef = useRef<TerminalVisibility | null>(null);
  const slotPortRef = useRef<TerminalSlotPort | null>(null);
  if (slotPortRef.current === null) {
    slotPortRef.current = {
      claim: (slot) => {
        slotRef.current = slot;
        slot.appendChild(hostRef.current!);
        shownRef.current = true;
        setTerminalShown(true);
        setShowRequest((n) => n + 1);
      },
      release: (slot) => {
        if (slotRef.current !== slot) return;
        slotRef.current = null;
        shownRef.current = false;
        setTerminalShown(false);
        visibilityRef.current?.hide();
        parkingRef.current?.appendChild(hostRef.current!);
      },
    };
  }
  useLayoutEffect(() => {
    const host = hostRef.current!;
    if (!host.parentNode) parkingRef.current?.appendChild(host);
  }, []);
  // Fit only after the render that shows the key bar, whose height the terminal gives up.
  useLayoutEffect(() => {
    if (shownRef.current) visibilityRef.current?.show();
  }, [showRequest]);
  const keyBarRef = useRef<HTMLDivElement>(null);
  const terminalRef = useRef<Terminal>(null);
  const fitRef = useRef<FitAddon>(null);
  const socketRef = useRef<WebSocket>(null);
  const sendBytesRef = useRef<KeyBarProps["sendBytesRef"]["current"]>(null);
  const ctrlActiveRef = useRef(false);
  const [ctrlActive, setCtrlActive] = useState(false);
  const fontSizeRef = useRef(terminalFontSize);
  fontSizeRef.current = terminalFontSize;
  // Attachment teardown clears this because term.dispose already disposes loaded addons.
  const unicodeDisposeRef = useRef<(() => void) | null>(null);
  // Callback identity must not tear down a live attachment.
  const closedRef = useRef(onTerminalClosed);
  closedRef.current = onTerminalClosed;

  useEffect(() => {
    if (!attachedId) return undefined;
    const host = hostRef.current;
    if (!host) return undefined;
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
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(host);

    if (shownRef.current) {
      try { fit.fit(); } catch (_) { /* ResizeObserver retries after layout. */ }
    }

    // Put initial geometry in the URL so tmux attaches at the correct size before emitting bytes.
    const ws = new WebSocket(wsUrl(
      "/sessions/" + encodeURIComponent(attachedId) + "/terminal" +
      "?cols=" + term.cols + "&rows=" + term.rows,
    ));
    ws.binaryType = "arraybuffer";
    // Report daemon disconnects, not our own teardown.
    let teardown = false;
    const sendBytes: NonNullable<KeyBarProps["sendBytesRef"]["current"]> = (bytes) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(bytes);
    };
    sendBytesRef.current = sendBytes;

    // A parked host has no geometry, so tmux keeps the last reported size until a slot shows it again.
    let fittedBox: { width: number; height: number } | null = null;
    let fitting = false;
    const fitAndReport = () => {
      if (teardown || !shownRef.current) return;
      fitting = true;
      // A same-size resize forces measurement when open preceded host layout.
      try { term.resize(term.cols, term.rows); } catch (_) {}
      try { fit.fit(); } catch (_) {}
      fitting = false;
      const box = host.getBoundingClientRect();
      fittedBox = { width: box.width, height: box.height };
      sendResize(ws, term.cols, term.rows);
    };

    ws.onopen = () => {
      fitAndReport();
    };
    ws.onmessage = (ev: MessageEvent<string | ArrayBuffer>) => {
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
    // A fit reports its own result once; xterm fires this synchronously from inside it.
    const resizeSubscription = term.onResize(({ cols, rows }) => {
      if (!fitting) sendResize(ws, cols, rows);
    });

    // Host geometry changes without window resize, and initial layout may follow term.open().
    const refit = debounce(fitAndReport, 120);
    // Showing the host fits and reports at once; the observer then sees that same box and stays quiet.
    const observer = new ResizeObserver((entries) => {
      if (!shownRef.current) {
        refit.cancel();
        return;
      }
      const box = entries[entries.length - 1]?.contentRect;
      if (box && fittedBox && Math.abs(box.width - fittedBox.width) < 0.5 &&
          Math.abs(box.height - fittedBox.height) < 0.5) return;
      refit();
    });
    observer.observe(host);

    // iOS keyboard focus must happen synchronously from the completed tap, never from ws.onopen.
    const swipeScroll = installSwipeScroll(term);
    const focusTerminal = () => {
      if (swipeScroll.shouldFocus()) term.focus();
    };
    host.addEventListener("click", focusTerminal);

    const visibility: TerminalVisibility = {
      show: () => {
        refit.cancel();
        fitAndReport();
      },
      hide: () => {
        refit.cancel();
      },
    };
    visibilityRef.current = visibility;

    terminalRef.current = term;
    fitRef.current = fit;
    socketRef.current = ws;

    return () => {
      teardown = true;
      refit.cancel();
      observer.disconnect();
      host.removeEventListener("click", focusTerminal);
      swipeScroll.dispose();
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
      if (visibilityRef.current === visibility) visibilityRef.current = null;
    };
  }, [attachedId]);

  // Each sidebar selection requests focus, even when the attachment stays the same.
  useEffect(() => {
    if (focusRequest?.sessionId === attachedId) terminalRef.current?.focus();
  }, [focusRequest]);

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
    if (!shownRef.current) return;
    try { term.resize(term.cols, term.rows); } catch (_) {}
    try { fit.fit(); } catch (_) {}
    sendResize(ws, term.cols, term.rows);
  }, [terminalFontSize]);

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

  return (
    <main id="terminal-pane" ref={paneRef}>
      <div id="terminal-head">
        <button
          id="drawer-toggle"
          class="icon-button icon-button-small drawer-toggle"
          type="button"
          aria-label="Show the session list"
          aria-expanded={drawerOpen ? "true" : "false"}
          aria-controls="sidebar"
          title="Sessions"
          onClick={onToggleDrawer}
        ><Icon name="list" /></button>
        <SidebarToggle collapsed={sidebarCollapsed} onToggle={onToggleSidebar} />
        <div class="terminal-identity">
          {session ? <SessionDetails key={session.id} session={session} tasks={tasks} commands={commands} />
            : <span id="terminal-title">No session selected</span>}
        </div>
        {workspaceToolbar && <span class="header-separator" aria-hidden="true" />}
        {workspaceToolbar}
        {session ? <SessionActions key={session.id} session={session} commands={commands}
          onOpenPalette={openPalette} onCopyCwd={onCopyCwd} /> : (
          <button
            id="palette-button"
            class="icon-button icon-button-small palette-button"
            type="button"
            aria-label="Open command palette"
            title="Commands"
            onClick={openPalette}
          ><Icon name="more" /></button>)}

      </div>

      <TerminalSlotContext.Provider value={slotPortRef.current}>
        {workspace ?? <TerminalSlot />}
      </TerminalSlotContext.Provider>
      <div class="terminal-parking" ref={parkingRef} hidden></div>

      {attached && terminalShown && (
        <KeyBar
          barRef={keyBarRef}
          sendBytesRef={sendBytesRef}
          ctrlActive={ctrlActive}
          onToggleCtrl={toggleCtrl}
          onReleaseCtrl={releaseCtrl}
        />
      )}

      {hint && <p id="terminal-hint" class="terminal-hint">{hint}</p>}
    </main>
  );
}
