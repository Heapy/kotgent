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
import { displayName, isAliveState, stateBadge, tmuxAttachCommand } from "../lib/sessions.js";

function debounce(fn, ms) {
  let handle;
  return function () {
    clearTimeout(handle);
    handle = setTimeout(fn, ms);
  };
}

function sendResize(ws, cols, rows) {
  if (ws.readyState === WebSocket.OPEN && cols > 0 && rows > 0) {
    ws.send(resizeFrame(cols, rows));
  }
}

async function writeClipboard(text) {
  if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch (_) {
      // A non-secure origin or browser permission can reject the modern API; the click still gives the
      // legacy path the user gesture it needs.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.readOnly = true;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  try {
    textarea.select();
    if (!document.execCommand("copy")) throw new Error("copy command was rejected");
  } finally {
    textarea.remove();
  }
}

export function TerminalPane({
  session, attachedId, pendingAction, hint,
  onAttach, onInterrupt, onResume, onDetach, onStop, onDone, onTerminalClosed,
}) {
  const hostRef = useRef(null);
  const [copyResult, setCopyResult] = useState(null);
  // The close callback is read through a ref so a re-render cannot re-run the effect (which would tear
  // down a live terminal) just because the parent handed us a fresh closure.
  const closedRef = useRef(onTerminalClosed);
  closedRef.current = onTerminalClosed;

  useEffect(() => {
    if (!attachedId) return undefined;
    const host = hostRef.current;
    if (!host) return undefined;

    const term = new Terminal({
      convertEol: false,
      cursorBlink: true,
      fontFamily: "Menlo, Monaco, \"Courier New\", monospace",
      fontSize: 13,
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

    ws.onopen = () => {
      try { fit.fit(); } catch (_) {}
      sendResize(ws, term.cols, term.rows);
      term.focus();
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") return;            // no server->client text frames defined
      term.write(new Uint8Array(ev.data));                // raw terminal bytes (seed, then live deltas)
    };
    ws.onclose = () => {
      if (teardown) return;
      term.write("\r\n[terminal disconnected]\r\n");
      closedRef.current();
    };

    // Keystrokes / pastes -> UTF-8 binary frames (binary = input per the terminal WS protocol).
    term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(new TextEncoder().encode(data));
    });
    // xterm-initiated resizes (including from fit) -> text resize control frame.
    term.onResize(({ cols, rows }) => sendResize(ws, cols, rows));

    // Observe the HOST, not just the window: the pane also changes size without a window resize (the
    // hint paragraph appearing/disappearing, the sidebar collapsing at the mobile breakpoint), and the
    // observer's initial callback re-fits if `term.open()` ran before the host had been laid out — a
    // fit() on an unmeasured terminal is a silent no-op that would otherwise leave it at 80x24.
    const refit = debounce(() => {
      // A Terminal opened before its host was laid out has no valid character measurement, and fit()
      // silently bails on one; resizing to the current size is the public way to force a re-measure
      // (it skips the actual resize path, so it costs nothing and fires no onResize).
      try { term.resize(term.cols, term.rows); } catch (_) {}
      try { fit.fit(); } catch (_) {}
    }, 120);
    const observer = new ResizeObserver(refit);
    observer.observe(host);

    return () => {
      teardown = true;
      observer.disconnect();
      try { ws.close(); } catch (_) {}
      try { term.dispose(); } catch (_) {}
      host.replaceChildren();
    };
  }, [attachedId]);

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

  return html`
    <main id="terminal-pane">
      <div id="terminal-head">
        <div class="terminal-identity">
          <span id="terminal-title">${session ? displayName(session) : "No session selected"}</span>
          <span id="terminal-state" class=${badge ? "badge " + badge.cls : "badge"}>
            ${badge ? badge.label : ""}
          </span>
        </div>
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
              <button id="attach-button" class="button button-primary" type="button"
                      disabled=${busy} onClick=${onAttach}>Attach</button>`}
            ${alive && html`
              <button id="interrupt-button" class="button button-quiet" type="button"
                      disabled=${busy} onClick=${onInterrupt}>Interrupt</button>`}
            ${!alive && html`
              <button id="resume-button" class="button button-primary" type="button"
                      disabled=${busy} onClick=${onResume}>Resume</button>`}
            ${attached && html`
              <button id="detach-button" class="button button-quiet" type="button"
                      disabled=${busy} onClick=${onDetach}>Detach</button>`}
            ${alive && html`
              <button id="stop-button" class="button button-danger" type="button"
                      disabled=${busy} onClick=${onStop}>Stop</button>`}
            <button id="done-button" class="button button-quiet" type="button"
                    disabled=${busy} onClick=${onDone}
                    title="Stop the agent and hide this session from the sidebar">Done</button>
          </div>
        `}
      </div>

      ${/* Owned by xterm.js, never by the vdom: rendered childless so Preact has nothing to diff. */ ""}
      <div id="terminal-host" ref=${hostRef}></div>

      ${hint && html`<p id="terminal-hint" class="terminal-hint">${hint}</p>`}
    </main>
  `;
}
