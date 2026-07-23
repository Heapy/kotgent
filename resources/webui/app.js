"use strict";
/*
 * Kotgent Web UI (plan Task 17) — a minimal vanilla SPA, no build step.
 *
 * Flow:
 *   1. Read the token from the URL fragment `#token=...` (a fragment, so it is never sent to the
 *      server / its logs) and keep it in memory.
 *   2. GET /sessions (Authorization: Bearer <token>) -> render the left session list with a state
 *      badge + a "needs attention" indicator and count.
 *   3. Open the GET /events?token=<token> WebSocket -> on each session_update, patch the row live.
 *   4. Click a session -> open an xterm.js Terminal on GET /sessions/{id}/terminal?token=<token>
 *      (binary WS): incoming binary -> term.write; keystrokes -> binary frames; term.onResize/fit ->
 *      a text resize control frame.
 *
 * The pure helpers (token parse, WS URL, state->badge, resize frame, needs-attention) are small named
 * functions with no I/O, so they are trivially inspectable and verified in the Task-18 manual walkthrough
 * (the browser JS cannot run in the macosArm64 test binary; the Kotlin serving test covers the serving path).
 */

// ---------------------------------------------------------------------------------------------------
// Pure helpers (no I/O — token parse, WS URL construction, state -> badge, resize frame).
// ---------------------------------------------------------------------------------------------------

/** Extract the bearer token from a location fragment like `#token=abc` (or `#foo=1&token=abc`). */
function parseToken(hash) {
  const m = /(?:^#|[#&])token=([^&]*)/.exec(hash || "");
  if (!m || !m[1]) return null;
  try { return decodeURIComponent(m[1]); } catch (_) { return m[1]; }
}

/** Build a same-origin WebSocket URL for [path], carrying the token as `?token=` (browsers can't set WS headers). */
function wsUrl(path, token, base) {
  const loc = base || window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  const sep = path.indexOf("?") >= 0 ? "&" : "?";
  return proto + "//" + loc.host + path + sep + "token=" + encodeURIComponent(token);
}

/** Map a canonical session state (7 values) to a display label + a CSS badge class. */
function stateBadge(state) {
  switch (state) {
    case "running":       return { label: "running", cls: "badge-running" };
    case "ready":         return { label: "ready", cls: "badge-ready" };
    case "needs_approval":return { label: "needs approval", cls: "badge-attention" };
    case "needs_answer":  return { label: "needs answer", cls: "badge-attention" };
    case "stopped":       return { label: "stopped", cls: "badge-dead" };
    case "crashed":       return { label: "crashed", cls: "badge-crashed" };
    case "resumable":     return { label: "resumable", cls: "badge-resumable" };
    default:              return { label: state || "unknown", cls: "badge-dead" };
  }
}

/** The two states that block on the human (an approval or a forward-modeled answer). */
function isNeedsAttention(state) {
  return state === "needs_approval" || state === "needs_answer";
}

/** The text control frame the terminal WS expects for a resize (matches TerminalWs's protocol). */
function resizeFrame(cols, rows) {
  return JSON.stringify({ type: "resize", cols: cols, rows: rows });
}

// ---------------------------------------------------------------------------------------------------
// App state.
// ---------------------------------------------------------------------------------------------------

const TOKEN = parseToken(window.location.hash);
const sessions = new Map();     // id -> latest known session shape (from GET /sessions, patched by /events)
let activeId = null;            // currently attached session id
let terminal = null;            // { term, ws, fit, id, onWinResize } or null

const dom = {
  attentionCount: document.getElementById("attention-count"),
  attentionNum: document.getElementById("attention-num"),
  attentionSection: document.getElementById("attention-section"),
  attentionList: document.getElementById("attention-list"),
  sessionList: document.getElementById("session-list"),
  status: document.getElementById("status-line"),
  termTitle: document.getElementById("terminal-title"),
  termState: document.getElementById("terminal-state"),
  termHost: document.getElementById("terminal-host"),
  termHint: document.getElementById("terminal-hint"),
};

// ---------------------------------------------------------------------------------------------------
// Sessions: fetch + render.
// ---------------------------------------------------------------------------------------------------

function authHeaders() {
  return { "Authorization": "Bearer " + TOKEN };
}

async function loadSessions() {
  try {
    const resp = await fetch("/sessions", { headers: authHeaders() });
    if (resp.status === 401) { setStatus("Unauthorized — check the #token in the URL.", true); return; }
    if (!resp.ok) { setStatus("GET /sessions failed: HTTP " + resp.status, true); return; }
    const list = await resp.json();
    sessions.clear();
    for (const s of list) sessions.set(s.id, s);
    setStatus(list.length + " session(s).");
    render();
  } catch (e) {
    setStatus("GET /sessions error: " + e, true);
  }
}

function displayName(s) {
  if (s.name && s.name.length > 0) return s.name;
  if (s.tmuxSession && s.tmuxSession.length > 0) return s.tmuxSession;
  return s.id;
}

/** Build one <li> session row. */
function sessionRow(s) {
  const li = document.createElement("li");
  li.className = "session-row" + (s.id === activeId ? " active" : "");
  li.dataset.id = s.id;
  li.addEventListener("click", () => openTerminal(s.id));

  if (isNeedsAttention(s.state)) {
    const dot = document.createElement("span");
    dot.className = "attn-dot";
    dot.title = "Needs attention";
    li.appendChild(dot);
  }

  const main = document.createElement("div");
  main.className = "session-main";
  const name = document.createElement("div");
  name.className = "session-name";
  name.textContent = displayName(s);
  const sub = document.createElement("div");
  sub.className = "session-sub";
  sub.textContent = (s.agent || "?") + " · " + (s.cwd || "");
  main.appendChild(name);
  main.appendChild(sub);
  li.appendChild(main);

  if (s.unread && s.unread > 0) {
    const pill = document.createElement("span");
    pill.className = "unread-pill";
    pill.textContent = String(s.unread);
    pill.title = s.unread + " unread event(s)";
    li.appendChild(pill);
  }

  const badge = stateBadge(s.state);
  const b = document.createElement("span");
  b.className = "badge " + badge.cls;
  b.textContent = badge.label;
  li.appendChild(b);

  return li;
}

function render() {
  const all = Array.from(sessions.values());
  const attention = all.filter((s) => isNeedsAttention(s.state));

  // Needs-attention count + highlight.
  dom.attentionNum.textContent = String(attention.length);
  dom.attentionCount.classList.toggle("active", attention.length > 0);

  // Needs-attention section (only when non-empty).
  dom.attentionSection.hidden = attention.length === 0;
  dom.attentionList.replaceChildren(...attention.map(sessionRow));

  // Full list.
  dom.sessionList.replaceChildren(...all.map(sessionRow));

  // Keep the terminal header state badge in sync with the active session.
  if (activeId && sessions.has(activeId)) {
    const s = sessions.get(activeId);
    const badge = stateBadge(s.state);
    dom.termState.className = "badge " + badge.cls;
    dom.termState.textContent = badge.label;
  }
}

function setStatus(text, isError) {
  dom.status.textContent = text;
  dom.status.classList.toggle("error", !!isError);
}

// ---------------------------------------------------------------------------------------------------
// Live updates: GET /events WebSocket (global snapshot-then-stream of session_update messages).
// ---------------------------------------------------------------------------------------------------

function connectEvents() {
  let ws;
  try {
    ws = new WebSocket(wsUrl("/events", TOKEN));
  } catch (e) {
    setStatus("events WS error: " + e, true);
    return;
  }
  ws.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch (_) { return; }
    if (msg && msg.type === "session_update") applyUpdate(msg);
  };
  ws.onclose = () => {
    // Reconnect (the daemon re-sends a fresh snapshot on connect, so we resync cleanly).
    setTimeout(connectEvents, 2000);
  };
  ws.onerror = () => { /* surfaced via onclose */ };
}

/** Patch a session row in place from a {sessionId, state, needsAttention, lastSeq, unread} update. */
function applyUpdate(u) {
  const existing = sessions.get(u.sessionId);
  if (existing) {
    existing.state = u.state;
    existing.needsAttention = u.needsAttention;
    existing.lastSeq = u.lastSeq;
    existing.unread = u.unread;
    render();
  } else {
    // A session we have never seen (e.g. started elsewhere) — pull the full list to get its metadata.
    loadSessions();
  }
}

// ---------------------------------------------------------------------------------------------------
// Terminal: xterm.js over the GET /sessions/{id}/terminal binary WebSocket.
// ---------------------------------------------------------------------------------------------------

function openTerminal(id) {
  if (terminal && terminal.id === id) return; // already attached
  closeTerminal();

  const s = sessions.get(id);
  activeId = id;
  dom.termHint.hidden = true;
  dom.termTitle.textContent = s ? displayName(s) : id;

  const term = new Terminal({
    convertEol: false,
    cursorBlink: true,
    fontFamily: "Menlo, Monaco, \"Courier New\", monospace",
    fontSize: 13,
    theme: { background: "#000000" },
  });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(dom.termHost);
  try { fit.fit(); } catch (_) { /* host not laid out yet — a later resize will fit */ }

  const ws = new WebSocket(wsUrl("/sessions/" + encodeURIComponent(id) + "/terminal", TOKEN));
  ws.binaryType = "arraybuffer";

  ws.onopen = () => {
    try { fit.fit(); } catch (_) {}
    sendResize(ws, term.cols, term.rows);
    term.focus();
  };
  ws.onmessage = (ev) => {
    if (typeof ev.data === "string") return;              // no server->client text frames defined
    term.write(new Uint8Array(ev.data));                  // raw terminal bytes (seed, then live deltas)
  };
  ws.onclose = () => { term.write("\r\n[terminal disconnected]\r\n"); };

  // Keystrokes / pastes -> UTF-8 binary frames (binary = input per the terminal WS protocol).
  term.onData((data) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(new TextEncoder().encode(data));
  });
  // xterm-initiated resizes (including from fit) -> text resize control frame.
  term.onResize(({ cols, rows }) => sendResize(ws, cols, rows));

  const onWinResize = debounce(() => { try { fit.fit(); } catch (_) {} }, 120);
  window.addEventListener("resize", onWinResize);

  terminal = { term, ws, fit, id, onWinResize };
  render(); // reflect the active row + header badge
}

function sendResize(ws, cols, rows) {
  if (ws.readyState === WebSocket.OPEN && cols > 0 && rows > 0) {
    ws.send(resizeFrame(cols, rows));
  }
}

function closeTerminal() {
  if (!terminal) return;
  const t = terminal;
  terminal = null;
  window.removeEventListener("resize", t.onWinResize);
  try { t.ws.close(); } catch (_) {}
  try { t.term.dispose(); } catch (_) {}
}

function debounce(fn, ms) {
  let h;
  return function () {
    clearTimeout(h);
    h = setTimeout(fn, ms);
  };
}

// ---------------------------------------------------------------------------------------------------
// Boot.
// ---------------------------------------------------------------------------------------------------

function main() {
  if (!TOKEN) {
    setStatus("No token. Open this page as http://127.0.0.1:PORT/#token=YOUR_TOKEN", true);
    dom.termHint.textContent = "Missing #token= fragment — cannot reach the daemon.";
    return;
  }
  loadSessions();
  connectEvents();
}

main();
