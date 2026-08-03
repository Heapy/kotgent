/*
 * The terminal pane: the session header, its lifecycle controls, and the xterm.js terminal itself.
 *
 * xterm owns real DOM and a WebSocket, so it is deliberately kept OUT of the vdom: `#terminal-host` is
 * rendered once with no children of its own, and everything inside it is created and disposed by the
 * effect below. The effect keys on the attached session id — attaching, detaching, switching sessions
 * and unmounting all go through the same setup/teardown pair, so there is exactly one place where a
 * terminal (and its socket) can leak.
 */

import { html } from "htm/preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { resizeFrame, wsUrl } from "../lib/api.js";
import { writeClipboard } from "../lib/clipboard.js";
import { displayName, isAliveState, stateBadge, tmuxAttachCommand } from "../lib/sessions.js";
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

/**
 * Apply the terminal's ordinary Ctrl-key rules to one printable character. A null means "this was not
 * one printable key" (paste, an escape sequence, Enter, etc.), so sticky Ctrl must remain armed.
 * Unsupported printable characters pass through unchanged but still consume the one-shot modifier,
 * matching a physical Ctrl key rather than inventing a control code with a blanket `code & 0x1f`.
 */
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

  // The digit aliases are the sequences xterm emits for Ctrl+2 through Ctrl+8.
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

/**
 * Bridge a one-finger phone swipe into xterm's ordinary wheel path.
 *
 * xterm 5.5 handles touch scrolling only while mouse tracking is OFF. Kotgent's tmux client deliberately
 * keeps tracking on so a desktop wheel reaches tmux's pane history, which leaves touchmove as a no-op on
 * phones. Synthetic wheel events reuse xterm's current mouse protocol and coordinate mapping instead of
 * hard-coding SGR bytes here; tmux or a mouse-aware TUI therefore sees exactly the input a real wheel
 * would have produced.
 */
function installTouchScroll(term) {
  const element = term.element;
  if (!element) return { shouldFocus: () => true, dispose: () => {} };

  const startThreshold = 6;
  const maxEventsPerMove = 12;
  // tmux's copy-mode wheel binding is `send-keys -X -N 5 scroll-up`, so ONE report moves five lines of
  // history. Measuring a full-height drag on a real iPhone: 44 reports at one-per-row became 220 lines,
  // about five screens for a single swipe; one report per five rows returned 40 lines, i.e. the screen
  // the finger actually travelled.
  const linesPerReport = 5;
  let gesture = null;
  let suppressFocusUntil = 0;

  const resetGesture = () => { gesture = null; };
  const trackedTouch = (touches, identifier) => {
    for (let i = 0; i < touches.length; i += 1) {
      if (touches[i].identifier === identifier) return touches[i];
    }
    return null;
  };

  const onTouchStart = (event) => {
    // `targetTouches`, never `touches`: the latter counts every contact with the screen, so a thumb
    // resting on the sibling key bar refused the gesture and — with xterm's own touch path off and
    // `touch-action: none` — froze scrolling until every finger lifted. Confirmed on a real iPhone.
    if (event.targetTouches.length !== 1) {
      resetGesture();
      return;
    }
    const touch = event.targetTouches[0];
    gesture = {
      identifier: touch.identifier,
      startX: touch.clientX,
      startY: touch.clientY,
      lastY: touch.clientY,
      remainder: 0,
      claimed: false,
    };
  };

  const onTouchMove = (event) => {
    if (!gesture) return;
    // When tracking is off, xterm's own touch handler scrolls its local buffer. Taking the gesture here
    // would double-scroll it and would turn an otherwise useful native path into synthetic key presses.
    if (event.targetTouches.length !== 1 || term.modes.mouseTrackingMode === "none") {
      resetGesture();
      return;
    }

    const touch = trackedTouch(event.targetTouches, gesture.identifier);
    if (!touch) {
      resetGesture();
      return;
    }

    const deltaY = gesture.lastY - touch.clientY;
    gesture.lastY = touch.clientY;
    gesture.remainder += deltaY;

    if (!gesture.claimed) {
      const totalX = touch.clientX - gesture.startX;
      const totalY = gesture.startY - touch.clientY;
      if (Math.abs(totalY) < startThreshold || Math.abs(totalY) <= Math.abs(totalX)) return;
      gesture.claimed = true;
    }

    // Claim only a proven vertical swipe. A tap therefore still reaches the click-to-focus handler below,
    // while Safari cannot turn the swipe into page navigation or pull-to-refresh.
    if (event.cancelable) event.preventDefault();
    suppressFocusUntil = Date.now() + 350;

    const screen = element.querySelector(".xterm-screen") || element;
    const bounds = screen.getBoundingClientRect();
    const rowHeight = bounds.height / Math.max(term.rows, 1);
    if (!Number.isFinite(rowHeight) || rowHeight <= 0) return;

    const travelPerReport = rowHeight * linesPerReport;
    const reports = Math.trunc(gesture.remainder / travelPerReport);
    if (reports === 0) return;
    const direction = Math.sign(reports);
    // Debit everything the finger travelled, including whatever the cap refuses to dispatch. Banking the
    // overflow instead made a reversal pay off the old direction's backlog first, so the terminal kept
    // scrolling the wrong way after the finger turned around. Only the sub-report fraction carries over.
    gesture.remainder -= reports * travelPerReport;
    const eventCount = Math.min(Math.abs(reports), maxEventsPerMove);

    // Keep the reported position inside the character grid even if the finger leaves it mid-swipe. tmux
    // resolves every wheel report against a cell, and discards coordinates outside its current geometry.
    const clientX = Math.max(bounds.left + 1, Math.min(touch.clientX, bounds.right - 1));
    const clientY = Math.max(bounds.top + 1, Math.min(touch.clientY, bounds.bottom - 1));
    for (let i = 0; i < eventCount; i += 1) {
      const wheelEvent = new WheelEvent("wheel", {
        bubbles: true,
        cancelable: true,
        composed: true,
        clientX,
        clientY,
        deltaY: direction,
        deltaMode: WheelEvent.DOM_DELTA_LINE,
        view: window,
      });
      element.dispatchEvent(wheelEvent);
    }
  };

  element.addEventListener("touchstart", onTouchStart, { passive: true });
  element.addEventListener("touchmove", onTouchMove, { passive: false });
  element.addEventListener("touchend", resetGesture, { passive: true });
  element.addEventListener("touchcancel", resetGesture, { passive: true });

  return {
    shouldFocus: () => Date.now() >= suppressFocusUntil,
    dispose: () => {
      element.removeEventListener("touchstart", onTouchStart);
      element.removeEventListener("touchmove", onTouchMove);
      element.removeEventListener("touchend", resetGesture);
      element.removeEventListener("touchcancel", resetGesture);
      resetGesture();
    },
  };
}

export function TerminalPane({
  session, attachedId, terminalFontSize, pendingAction, hint, drawerOpen, sidebarCollapsed,
  onToggleDrawer, onToggleSidebar, onOpenPalette, onAttach, onInterrupt, onResume, onDetach, onStop,
  onDone, onTerminalClosed,
}) {
  const hostRef = useRef(null);
  const [copyResult, setCopyResult] = useState(null);
  const keyBarRef = useRef(null);
  const terminalRef = useRef(null);
  const fitRef = useRef(null);
  const socketRef = useRef(null);
  const sendBytesRef = useRef(null);
  const ctrlActiveRef = useRef(false);
  const [ctrlActive, setCtrlActive] = useState(false);
  const fontSizeRef = useRef(terminalFontSize);
  fontSizeRef.current = terminalFontSize;
  // The close callback is read through a ref so a re-render cannot re-run the effect (which would tear
  // down a live terminal) just because the parent handed us a fresh closure.
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
      convertEol: false,
      cursorBlink: true,
      fontFamily: "Menlo, Monaco, \"Courier New\", monospace",
      fontSize: fontSizeRef.current,
      theme: { background: "#000000" },
      // When the pane's app turns on mouse reporting, xterm.js disables its selection service and the
      // only way back is shouldForceSelection() — Shift+drag elsewhere, but on macOS Alt+drag AND this
      // option, which defaults to false. Without it a macOS browser cannot select terminal text at all
      // while a mouse-reporting TUI is running, and there is no copy button to fall back on.
      macOptionClickForcesSelection: true,
    });
    const fit = new FitAddon.FitAddon();
    term.loadAddon(fit);
    term.open(host);

    // The layout viewport does not shrink reliably when a phone keyboard opens. Cap this flex item at
    // the visual viewport's bottom before the first fit so even the WebSocket URL carries the correct
    // OPEN geometry when a session is switched while the keyboard is still visible. An installed iOS
    // PWA also reports visualViewport.height short by its safe areas with NO keyboard; tolerate exactly
    // that loss and leave the ordinary 100vh flex layout uncapped in the hidden-keyboard state.
    const viewport = window.visualViewport;
    const sizeForVisualViewport = () => {
      if (!viewport) return;
      if (!Number.isFinite(viewport.height) || !Number.isFinite(viewport.offsetTop) ||
          viewport.height <= 0) return;                  // Safari emits transient zeroes during rotation

      const appBounds = app.getBoundingClientRect();
      if (!Number.isFinite(appBounds.height) || appBounds.height <= 0) return;
      const appStyle = getComputedStyle(app);
      const safeAreaHeight =
        (Number.parseFloat(appStyle.getPropertyValue("--device-safe-area-top")) || 0) +
        (Number.parseFloat(appStyle.getPropertyValue("--device-safe-area-bottom")) || 0);
      const viewportShrunken = viewport.height < appBounds.height - safeAreaHeight - 1;

      // Measure the ordinary flex height, not yesterday's keyboard-constrained one. Restoring the prior
      // cap on a bad measurement avoids collapsing the terminal during an in-progress viewport update.
      const previousHeight = host.style.getPropertyValue("--terminal-visible-height");
      host.classList.remove("visual-viewport-sized");
      host.style.removeProperty("--terminal-visible-height");
      // CSS uses the same state to suppress WebKit's stale bottom inset above the open keyboard. Toggle
      // before measuring the key bar so its now-smaller height is what the xterm ceiling reserves.
      app.classList.toggle("visual-viewport-shrunken", viewportShrunken);
      if (!viewportShrunken) return;

      const bounds = host.getBoundingClientRect();
      const visibleBottom = viewport.offsetTop + viewport.height;
      // The key bar follows the host in the pane's flex column. Its ordinary layout already reduces
      // bounds.height; subtract it from the visual-viewport ceiling too so the row itself stays above
      // the software keyboard instead of occupying the last keyboard-covered pixels.
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
    try { fit.fit(); } catch (_) { /* host not laid out yet — the ResizeObserver below will fit */ }

    // The size travels in the URL, not only in the first resize frame: the daemon opens the upstream
    // `tmux attach` at this geometry, so the first bytes we receive are already the right shape
    // instead of the pty's default 80x24 reflowing to ours a moment later.
    const ws = new WebSocket(wsUrl(
      "/sessions/" + encodeURIComponent(attachedId) + "/terminal" +
      "?cols=" + term.cols + "&rows=" + term.rows,
    ));
    ws.binaryType = "arraybuffer";
    // Distinguishes "we tore this down" from "the daemon dropped us": only the latter is worth
    // reporting to the user and reflecting in the parent's state.
    let teardown = false;
    const sendBytes = (bytes) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(bytes);
    };
    sendBytesRef.current = sendBytes;

    const fitAndReport = () => {
      if (teardown) return;
      // A Terminal opened before its host was laid out has no valid character measurement, and fit()
      // silently bails on one; resizing to the current size is the public way to force a re-measure
      // (it skips the actual resize path, so it costs nothing and fires no onResize).
      try { term.resize(term.cols, term.rows); } catch (_) {}
      try { fit.fit(); } catch (_) {}
      sendResize(ws, term.cols, term.rows);
    };

    ws.onopen = () => {
      fitAndReport();
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") return;            // no server->client text frames defined
      term.write(new Uint8Array(ev.data));                // raw terminal bytes (seed, then live deltas)
    };
    ws.onclose = () => {
      if (teardown) return;
      term.write("\r\n[terminal disconnected]\r\n");
      // Name the socket that closed: the parent may already be rendering another selected session by
      // the time this callback runs, and must never tear that replacement attachment down by mistake.
      closedRef.current(attachedId);
    };

    // Keystrokes / pastes -> UTF-8 binary frames (binary = input per the terminal WS protocol).
    const dataSubscription = term.onData((data) => {
      if (ctrlActiveRef.current) {
        const ctrlBytes = ctrlBytesFor(data);
        if (ctrlBytes !== null) {
          // Clear the live ref before scheduling the render: two input events can arrive in one turn.
          ctrlActiveRef.current = false;
          setCtrlActive(false);
          sendBytes(ctrlBytes);
          return;
        }
      }
      sendBytes(new TextEncoder().encode(data));
    });
    // xterm-initiated resizes (including from fit) -> text resize control frame.
    const resizeSubscription = term.onResize(({ cols, rows }) => sendResize(ws, cols, rows));

    // Observe the HOST, not just the window: the pane also changes size without a window resize (the
    // hint paragraph appearing/disappearing, the sidebar collapsing at the mobile breakpoint), and the
    // observer's initial callback re-fits if `term.open()` ran before the host had been laid out — a
    // fit() on an unmeasured terminal is a silent no-op that would otherwise leave it at 80x24.
    const refit = debounce(fitAndReport, 120);
    const observer = new ResizeObserver(refit);
    observer.observe(host);

    // iOS only opens the software keyboard when the textarea is focused synchronously from a user
    // gesture. In particular, never move this back to ws.onopen: asynchronous focus cannot summon the
    // keyboard and steals focus from whichever control the operator was using. A click is the browser's
    // completed-tap signal, so a swipe over the terminal does not open the keyboard on pointer-down.
    //
    // What actually keeps a swipe from summoning the keyboard is the bridge's `preventDefault()`, which
    // suppresses the whole compatibility mouse burst — measured on a real iPhone: after a swipe neither
    // xterm's own `mousedown` focus nor this click handler runs. `shouldFocus()` is therefore a second
    // line for a browser that still delivers a click, not the mechanism; do not "prove" the keyboard
    // rule by asserting this call exists.
    const touchScroll = installTouchScroll(term);
    const focusTerminal = () => {
      if (touchScroll.shouldFocus()) term.focus();
    };
    host.addEventListener("click", focusTerminal);

    const viewportChanged = () => {
      sizeForVisualViewport();
      // visualViewport fires throughout the keyboard animation. Move the host immediately, but wait
      // for the settled metrics before reflowing tmux rather than sending every intermediate geometry.
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
      touchScroll.dispose();
      if (viewport) {
        viewport.removeEventListener("resize", viewportChanged);
        viewport.removeEventListener("scroll", viewportChanged);
      }
      host.classList.remove("visual-viewport-sized");
      host.style.removeProperty("--terminal-visible-height");
      app.classList.remove("visual-viewport-shrunken");
      dataSubscription.dispose();
      resizeSubscription.dispose();
      ws.onopen = null;
      ws.onmessage = null;
      ws.onclose = null;
      try { ws.close(); } catch (_) {}
      try { term.dispose(); } catch (_) {}
      host.replaceChildren();
      ctrlActiveRef.current = false;
      if (terminalRef.current === term) terminalRef.current = null;
      if (fitRef.current === fit) fitRef.current = null;
      if (socketRef.current === ws) socketRef.current = null;
      if (sendBytesRef.current === sendBytes) sendBytesRef.current = null;
    };
  }, [attachedId]);

  // Font changes are a view preference, not a new terminal attachment. Updating the live xterm instance
  // in a separate effect keeps the one upstream WebSocket intact, then re-fits and reports its new grid.
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
  const alive = session ? isAliveState(session.state) : false;
  const attached = !!session && session.id === attachedId;
  const busy = pendingAction !== null;
  const tmuxCommand = session && session.tmuxSession ? tmuxAttachCommand(session.tmuxSession) : "";
  const copyState = copyResult && copyResult.command === tmuxCommand ? copyResult.state : "idle";
  const copyTmuxCommand = async () => {
    try {
      await writeClipboard(tmuxCommand);
      setCopyResult({ command: tmuxCommand, state: "copied" });
    } catch (_) {
      setCopyResult({ command: tmuxCommand, state: "failed" });
    }
  };
  const toggleCtrl = () => {
    const next = !ctrlActiveRef.current;
    ctrlActiveRef.current = next;
    setCtrlActive(next);
  };
  const releaseCtrl = () => {
    ctrlActiveRef.current = false;
    setCtrlActive(false);
  };
  const openPalette = () => {
    const mode = window.matchMedia("(max-width: 720px)").matches ? "leader" : "search";
    onOpenPalette(mode);
  };

  return html`
    <main id="terminal-pane">
      <div id="terminal-head">
        ${/* The drawer opener. Rendered on every screen but display:none above the mobile breakpoint, so
              the desktop header is laid out exactly as it was before the drawer existed. */ ""}
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
          title=${sidebarCollapsed ? "Expand sidebar (⌘1)" : "Collapse sidebar (⌘1)"}
          onClick=${onToggleSidebar}
        >${sidebarCollapsed ? "›" : "‹"}</button>
        <div class="terminal-identity">
          <span id="terminal-title">${session ? displayName(session) : "No session selected"}</span>
          <span id="terminal-state" class=${badge ? "badge " + badge.cls : "badge"}>
            ${badge ? badge.label : ""}
          </span>
        </div>
        <button
          id="palette-button"
          class="icon-button icon-button-small palette-button"
          type="button"
          aria-label="Open command palette"
          title="Commands"
          onClick=${openPalette}
        >⋯</button>
        ${/* Each control carries its label as text AND an icon in `data-icon`. Above the mobile
              breakpoint the palette replaces this row; below it, the text collapses and style.css draws
              `data-icon` through ::before, so one row of markup serves both without a JS branch. The
              aria-label keeps the accessible name identical to the wording either way. */ ""}
        ${session && html`
          <div id="session-actions" class="session-actions">
            ${alive && tmuxCommand && html`
              <button id="copy-tmux-button" class="button button-quiet copy-tmux-button" type="button"
                      title=${tmuxCommand} onClick=${copyTmuxCommand}>
                ${copyState === "copied" ? "Copied tmux" :
                  copyState === "failed" ? "Copy failed" : "Copy tmux"}
              </button>
              <span class="visually-hidden" role="status" aria-live="polite">
                ${copyState === "copied" ? "Tmux command copied to clipboard." :
                  copyState === "failed" ? "Could not copy the tmux command." : ""}
              </span>
            `}
            ${alive && !attached && html`
              <button id="attach-button" class="button button-primary" type="button" data-icon="🔗"
                      aria-label="Attach" disabled=${busy} onClick=${onAttach}>Attach</button>`}
            ${alive && html`
              <button id="interrupt-button" class="button button-quiet" type="button" data-icon="⏸"
                      aria-label="Interrupt" disabled=${busy} onClick=${onInterrupt}>Interrupt</button>`}
            ${!alive && html`
              <button id="resume-button" class="button button-primary" type="button" data-icon="▶"
                      aria-label="Resume" disabled=${busy} onClick=${onResume}>Resume</button>`}
            ${attached && html`
              <button id="detach-button" class="button button-quiet" type="button" data-icon="⏏"
                      aria-label="Detach" disabled=${busy} onClick=${onDetach}>Detach</button>`}
            ${alive && html`
              <button id="stop-button" class="button button-danger" type="button" data-icon="⏹"
                      aria-label="Stop" disabled=${busy} onClick=${onStop}>Stop</button>`}
            <button id="done-button" class="button button-quiet" type="button" data-icon="✓"
                    aria-label="Done" disabled=${busy} onClick=${onDone}
                    title="Stop the agent and hide this session from the sidebar">Done</button>
          </div>
        `}
      </div>

      ${/* Owned by xterm.js, never by the vdom: rendered childless so Preact has nothing to diff. */ ""}
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
