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
 *   5. Create sessions with POST /sessions and operate on the selected session with the lifecycle
 *      endpoints (interrupt, stop, resume); detaching closes only this browser's terminal client.
 *   6. Preferences (base path + grouping level) live in localStorage — they are a per-browser view
 *      setting, not daemon state, so there is no server round-trip. The base path groups the session
 *      list by working directory and seeds the cwd of new sessions.
 *
 * The pure helpers (token parse, WS URL, state->badge, resize frame, needs-attention, path grouping) are
 * small named functions with no I/O, so they are trivially inspectable and verified in the Task-18 manual
 * walkthrough (the browser JS cannot run in the macosArm64 test binary; the Kotlin serving test covers the
 * serving path).
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

/** States backed by a currently live agent process. */
function isAliveState(state) {
  return state === "running" || state === "ready" ||
    state === "needs_approval" || state === "needs_answer";
}

/** The text control frame the terminal WS expects for a resize (matches TerminalWs's protocol). */
function resizeFrame(cols, rows) {
  return JSON.stringify({ type: "resize", cols: cols, rows: rows });
}

/** Trim, collapse repeated slashes and drop the trailing slash, so paths compare and concatenate cleanly. */
function normalizePath(path) {
  const trimmed = (path || "").trim().replace(/\/{2,}/g, "/");
  return trimmed.length > 1 ? trimmed.replace(/\/+$/, "") : trimmed;
}

/** The last segment of [path] ("/Users/me/dev" -> "dev"). */
function basename(path) {
  const p = normalizePath(path);
  const cut = p.lastIndexOf("/");
  return cut >= 0 ? p.slice(cut + 1) : p;
}

/** Join [base] with already-normalized [segments], handling a "/" base. */
function joinPath(base, segments) {
  if (segments.length === 0) return base;
  return (base === "/" ? "" : base) + "/" + segments.join("/");
}

/** The segments of [path] below [base], `[]` when they are the same path, or null when [path] is outside. */
function segmentsUnder(base, path) {
  const b = normalizePath(base);
  const p = normalizePath(path);
  if (!b || !p) return null;
  if (p === b) return [];
  const prefix = b === "/" ? "/" : b + "/";
  if (p.indexOf(prefix) !== 0) return null;
  return p.slice(prefix.length).split("/").filter((segment) => segment.length > 0);
}

/**
 * The group a session's [cwd] belongs to: the base path plus at most [level] directories below it
 * (level 0 = a single group at the base). A cwd outside the base path is its own group, listed after
 * the in-base ones — the base path decides the shape of the tree, it does not hide anything.
 */
function groupFor(cwd, basePath, level) {
  const path = normalizePath(cwd);
  const segments = segmentsUnder(basePath, path);
  if (segments === null) return { path: path, label: path || "(unknown)", inBase: false };
  const kept = segments.slice(0, Math.max(0, level));
  const base = normalizePath(basePath);
  return {
    path: joinPath(base, kept),
    label: kept.length > 0 ? kept.join("/") : (basename(base) || base),
    inBase: true,
  };
}

/** Fold [list] into `[{path, label, inBase, sessions}]` — in-base groups first, each side path-sorted. */
function groupSessions(list, basePath, level) {
  const groups = new Map();
  for (const s of list) {
    const g = groupFor(s.cwd, basePath, level);
    const existing = groups.get(g.path);
    if (existing) existing.sessions.push(s);
    else groups.set(g.path, { path: g.path, label: g.label, inBase: g.inBase, sessions: [s] });
  }
  return Array.from(groups.values()).sort((a, b) => {
    if (a.inBase !== b.inBase) return a.inBase ? -1 : 1;
    return a.path.localeCompare(b.path);
  });
}

/** Coerce anything read back from localStorage into a valid prefs shape. */
function sanitizePrefs(raw) {
  const level = Number.parseInt(raw && raw.groupingLevel, 10);
  return {
    basePath: normalizePath(raw && raw.basePath),
    groupingLevel: Number.isFinite(level)
      ? Math.min(MAX_GROUPING_LEVEL, Math.max(0, level))
      : DEFAULT_PREFS.groupingLevel,
  };
}

// ---------------------------------------------------------------------------------------------------
// App state.
// ---------------------------------------------------------------------------------------------------

const PREFS_KEY = "kotgent.prefs.v1";
const MAX_GROUPING_LEVEL = 4;
const DEFAULT_PREFS = { basePath: "", groupingLevel: 1 };

const TOKEN = parseToken(window.location.hash);
const sessions = new Map();     // id -> latest known session shape (from GET /sessions, patched by /events)
let activeId = null;            // currently selected session id (it may be detached or stopped)
let terminal = null;            // { term, ws, fit, id, onWinResize } or null
let pendingAction = null;       // lifecycle action currently awaiting its REST response
let prefs = DEFAULT_PREFS;      // { basePath, groupingLevel } — view settings, loaded from localStorage

const dom = {
  newSessionButton: document.getElementById("new-session-button"),
  newSessionDialog: document.getElementById("new-session-dialog"),
  newSessionForm: document.getElementById("new-session-form"),
  newSessionClose: document.getElementById("new-session-close"),
  newSessionCancel: document.getElementById("new-session-cancel"),
  newSessionSubmit: document.getElementById("new-session-submit"),
  newSessionError: document.getElementById("new-session-error"),
  sessionAgent: document.getElementById("session-agent"),
  sessionCwd: document.getElementById("session-cwd"),
  sessionName: document.getElementById("session-name"),
  sessionTags: document.getElementById("session-tags"),
  helpButton: document.getElementById("help-button"),
  helpDialog: document.getElementById("help-dialog"),
  helpBody: document.getElementById("help-body"),
  helpClose: document.getElementById("help-close"),
  helpDone: document.getElementById("help-done"),
  prefsButton: document.getElementById("prefs-button"),
  prefsDialog: document.getElementById("prefs-dialog"),
  prefsForm: document.getElementById("prefs-form"),
  prefsClose: document.getElementById("prefs-close"),
  prefsCancel: document.getElementById("prefs-cancel"),
  prefsError: document.getElementById("prefs-error"),
  prefsBasePath: document.getElementById("prefs-base-path"),
  prefsGroupingLevel: document.getElementById("prefs-grouping-level"),
  prefsGroupingPreview: document.getElementById("prefs-grouping-preview"),
  basePathNote: document.getElementById("base-path-note"),
  attentionCount: document.getElementById("attention-count"),
  attentionNum: document.getElementById("attention-num"),
  attentionSection: document.getElementById("attention-section"),
  attentionList: document.getElementById("attention-list"),
  sessionList: document.getElementById("session-list"),
  emptySessions: document.getElementById("empty-sessions"),
  status: document.getElementById("status-line"),
  termTitle: document.getElementById("terminal-title"),
  termState: document.getElementById("terminal-state"),
  termHost: document.getElementById("terminal-host"),
  termHint: document.getElementById("terminal-hint"),
  sessionActions: document.getElementById("session-actions"),
  attachButton: document.getElementById("attach-button"),
  interruptButton: document.getElementById("interrupt-button"),
  resumeButton: document.getElementById("resume-button"),
  detachButton: document.getElementById("detach-button"),
  stopButton: document.getElementById("stop-button"),
};

// ---------------------------------------------------------------------------------------------------
// Help: a static explainer of sessions, states and controls (markup lives in index.html).
// ---------------------------------------------------------------------------------------------------

function showHelpDialog() {
  dom.helpDialog.showModal();
  dom.helpBody.scrollTop = 0; // a reopen should start at the top, not where the last read ended
  window.setTimeout(() => dom.helpClose.focus(), 0);
}

function closeHelpDialog() {
  if (dom.helpDialog.open) dom.helpDialog.close();
}

// ---------------------------------------------------------------------------------------------------
// Preferences: base path + grouping level, persisted per browser in localStorage.
// ---------------------------------------------------------------------------------------------------

function loadPrefs() {
  try {
    const raw = window.localStorage.getItem(PREFS_KEY);
    return sanitizePrefs(raw ? JSON.parse(raw) : DEFAULT_PREFS);
  } catch (_) {
    return sanitizePrefs(DEFAULT_PREFS); // unreadable / disabled storage — fall back to the defaults
  }
}

function persistPrefs(next) {
  try {
    window.localStorage.setItem(PREFS_KEY, JSON.stringify(next));
  } catch (_) { /* private mode / quota — the prefs still apply to this page load */ }
}

/** Grouping is what the base path buys; without one the sidebar stays a single flat list. */
function groupingEnabled() {
  return prefs.basePath.length > 0;
}

function showPrefsDialog() {
  dom.prefsBasePath.value = prefs.basePath;
  dom.prefsGroupingLevel.value = String(prefs.groupingLevel);
  dom.prefsError.hidden = true;
  dom.prefsError.textContent = "";
  updateGroupingPreview();
  dom.prefsDialog.showModal();
  window.setTimeout(() => dom.prefsBasePath.focus(), 0);
}

function closePrefsDialog() {
  if (dom.prefsDialog.open) dom.prefsDialog.close();
}

/** Show what the current form values would do, using a real session cwd when one is available. */
function updateGroupingPreview() {
  const draft = sanitizePrefs({
    basePath: dom.prefsBasePath.value,
    groupingLevel: dom.prefsGroupingLevel.value,
  });
  if (!draft.basePath) {
    dom.prefsGroupingPreview.textContent = "No base path — sessions are listed flat.";
    return;
  }
  const sample = Array.from(sessions.values())
    .find((s) => segmentsUnder(draft.basePath, s.cwd) !== null);
  if (!sample) {
    dom.prefsGroupingPreview.textContent = "Groups at " +
      joinPath(draft.basePath, Array.from({ length: draft.groupingLevel }, (_, i) => "<dir" + (i + 1) + ">"));
    return;
  }
  const g = groupFor(sample.cwd, draft.basePath, draft.groupingLevel);
  dom.prefsGroupingPreview.textContent = normalizePath(sample.cwd) + " → " + g.path;
}

function savePreferences(event) {
  event.preventDefault();
  const basePath = normalizePath(dom.prefsBasePath.value);
  if (basePath.length > 0 && basePath.charAt(0) !== "/") {
    dom.prefsError.textContent = "Base path must be absolute (start with /).";
    dom.prefsError.hidden = false;
    return;
  }

  prefs = sanitizePrefs({ basePath: basePath, groupingLevel: dom.prefsGroupingLevel.value });
  persistPrefs(prefs);
  closePrefsDialog();
  setStatus(groupingEnabled()
    ? "Grouping by " + prefs.basePath + " (level " + prefs.groupingLevel + ")."
    : "Grouping off — no base path set.");
  render();
}

// ---------------------------------------------------------------------------------------------------
// Sessions: fetch + render.
// ---------------------------------------------------------------------------------------------------

function authHeaders() {
  return { "Authorization": "Bearer " + TOKEN };
}

/** Fetch one authenticated JSON API response and surface its server-provided error text. */
async function apiRequest(path, options) {
  const opts = Object.assign({}, options || {});
  opts.headers = Object.assign({}, authHeaders(), opts.headers || {});
  if (opts.body) opts.headers["Content-Type"] = "application/json";

  const resp = await fetch(path, opts);
  const text = await resp.text();
  if (resp.status === 401) throw new Error("Unauthorized — check the #token in the URL.");
  if (!resp.ok) throw new Error(text || ("HTTP " + resp.status));
  if (!text) return null;
  try { return JSON.parse(text); } catch (_) { return text; }
}

async function loadSessions() {
  try {
    const list = await apiRequest("/sessions");
    sessions.clear();
    for (const s of list) sessions.set(s.id, s);
    if (activeId && !sessions.has(activeId)) {
      closeTerminal();
      activeId = null;
    }
    setStatus(list.length + " session(s).");
    render();
  } catch (e) {
    setStatus("Could not load sessions: " + errorMessage(e), true);
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
  li.tabIndex = 0;
  li.setAttribute("role", "button");
  li.setAttribute("aria-label", "Open " + displayName(s) + ", " + stateBadge(s.state).label);
  if (s.id === activeId) li.setAttribute("aria-current", "true");
  li.addEventListener("click", () => selectSession(s.id));
  li.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectSession(s.id);
    }
  });

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

/** Build one directory group: a header (label, count, "new session here") followed by its rows. */
function groupNode(group) {
  const li = document.createElement("li");
  li.className = "session-group";

  const title = document.createElement("span");
  title.className = "group-title";
  title.textContent = group.label;
  title.title = group.path;

  const count = document.createElement("span");
  count.className = "group-count";
  count.textContent = String(group.sessions.length);

  const head = document.createElement("div");
  head.className = "group-head";
  head.replaceChildren(title, count);

  // The per-group shortcut the whole grouping exists for: start an agent in *this* directory.
  if (group.path) {
    const add = document.createElement("button");
    add.type = "button";
    add.className = "icon-button icon-button-small group-new";
    add.textContent = "+";
    add.title = "New session in " + group.path;
    add.setAttribute("aria-label", "New session in " + group.path);
    add.addEventListener("click", () => showNewSessionDialog(group.path));
    head.appendChild(add);
  }

  const list = document.createElement("ul");
  list.className = "session-list group-sessions";
  list.replaceChildren(...group.sessions.map(sessionRow));

  li.replaceChildren(head, list);
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

  // Full list — grouped by working directory when a base path is set, flat otherwise.
  const grouped = groupingEnabled();
  dom.sessionList.classList.toggle("grouped", grouped);
  dom.sessionList.replaceChildren(
    ...(grouped
      ? groupSessions(all, prefs.basePath, prefs.groupingLevel).map(groupNode)
      : all.map(sessionRow)),
  );
  dom.emptySessions.hidden = all.length !== 0;

  // The base path doubles as a shortcut back into preferences.
  dom.basePathNote.hidden = !grouped;
  dom.basePathNote.textContent = prefs.basePath;
  dom.basePathNote.title = "Grouping under " + prefs.basePath +
    " at level " + prefs.groupingLevel + " — click to change";

  // Keep the terminal header and available controls in sync with the selected session.
  if (activeId && sessions.has(activeId)) {
    const s = sessions.get(activeId);
    const badge = stateBadge(s.state);
    const alive = isAliveState(s.state);
    const attached = !!terminal && terminal.id === s.id;

    dom.termTitle.textContent = displayName(s);
    dom.termState.className = "badge " + badge.cls;
    dom.termState.textContent = badge.label;
    dom.sessionActions.hidden = false;
    dom.attachButton.hidden = !alive || attached;
    dom.interruptButton.hidden = !alive;
    dom.stopButton.hidden = !alive;
    dom.detachButton.hidden = !attached;
    dom.resumeButton.hidden = alive;
    for (const button of dom.sessionActions.querySelectorAll("button")) {
      button.disabled = pendingAction !== null;
    }
  } else {
    dom.termTitle.textContent = "No session selected";
    dom.termState.className = "badge";
    dom.termState.textContent = "";
    dom.sessionActions.hidden = true;
  }
}

function setStatus(text, isError) {
  dom.status.textContent = text;
  dom.status.classList.toggle("error", !!isError);
}

function errorMessage(error) {
  return error && error.message ? error.message : String(error);
}

// ---------------------------------------------------------------------------------------------------
// Session creation and lifecycle controls.
// ---------------------------------------------------------------------------------------------------

/**
 * Open the new-session form with its cwd pre-filled: an explicit [presetCwd] (a group's "+" button) wins,
 * then the selected session's directory, then the configured base path.
 */
function showNewSessionDialog(presetCwd) {
  const selected = activeId ? sessions.get(activeId) : null;
  const preset = presetCwd || (selected && selected.cwd) || prefs.basePath;
  if (preset) dom.sessionCwd.value = preset;
  dom.newSessionError.hidden = true;
  dom.newSessionError.textContent = "";
  dom.newSessionDialog.showModal();
  window.setTimeout(() => dom.sessionCwd.focus(), 0);
}

function closeNewSessionDialog() {
  if (dom.newSessionDialog.open) dom.newSessionDialog.close();
}

async function startSession(event) {
  event.preventDefault();
  if (!dom.newSessionForm.reportValidity()) return;

  const tags = dom.sessionTags.value
    .split(",")
    .map((tag) => tag.trim())
    .filter((tag, index, all) => tag.length > 0 && all.indexOf(tag) === index);
  const body = {
    agent: dom.sessionAgent.value,
    cwd: dom.sessionCwd.value.trim(),
    name: dom.sessionName.value.trim() || null,
    tags: tags,
  };

  dom.newSessionSubmit.disabled = true;
  dom.newSessionSubmit.textContent = "Starting…";
  dom.newSessionError.hidden = true;
  try {
    const created = await apiRequest("/sessions", {
      method: "POST",
      body: JSON.stringify(body),
    });
    sessions.set(created.id, created);
    dom.newSessionForm.reset();
    closeNewSessionDialog();
    setStatus("Started " + displayName(created) + ".");
    selectSession(created.id);
  } catch (e) {
    dom.newSessionError.textContent = "Could not start session: " + errorMessage(e);
    dom.newSessionError.hidden = false;
  } finally {
    dom.newSessionSubmit.disabled = false;
    dom.newSessionSubmit.textContent = "Start session";
    render();
  }
}

async function controlSession(action) {
  const s = activeId ? sessions.get(activeId) : null;
  if (!s || pendingAction) return;
  if (action === "stop" && !window.confirm("Stop " + displayName(s) + "? The conversation can be resumed later.")) {
    return;
  }

  pendingAction = action;
  render();
  setStatus(capitalize(action) + " in progress…");
  try {
    const updated = await apiRequest(
      "/sessions/" + encodeURIComponent(s.id) + "/" + encodeURIComponent(action),
      { method: "POST" },
    );
    if (updated && updated.id) sessions.set(updated.id, updated);

    if (action === "stop") {
      closeTerminal();
      showTerminalHint("Session stopped. Resume it to continue.");
    } else if (action === "resume") {
      openTerminal(s.id);
    }
    setStatus(capitalize(action) + " completed for " + displayName(s) + ".");
  } catch (e) {
    setStatus(capitalize(action) + " failed: " + errorMessage(e), true);
  } finally {
    pendingAction = null;
    render();
  }
}

function detachTerminal() {
  const s = activeId ? sessions.get(activeId) : null;
  closeTerminal();
  showTerminalHint(s ? "Detached from " + displayName(s) + ". The agent keeps running." : "Terminal detached.");
  render();
}

function capitalize(text) {
  return text.charAt(0).toUpperCase() + text.slice(1);
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
    existing.alive = isAliveState(u.state);
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

function selectSession(id) {
  const s = sessions.get(id);
  if (!s) return;
  activeId = id;
  if (isAliveState(s.state)) {
    openTerminal(id);
  } else {
    closeTerminal();
    showTerminalHint(
      s.state === "resumable"
        ? "This session can be resumed."
        : "This session is " + stateBadge(s.state).label + ". Resume it to continue.",
    );
    render();
  }
}

function openTerminal(id) {
  if (terminal && terminal.id === id) return; // already attached
  closeTerminal();

  const s = sessions.get(id);
  activeId = id;
  if (s && !isAliveState(s.state)) {
    showTerminalHint("This session is " + stateBadge(s.state).label + ". Resume it to continue.");
    render();
    return;
  }

  dom.termHost.replaceChildren();
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
  ws.onclose = () => {
    term.write("\r\n[terminal disconnected]\r\n");
    if (terminal && terminal.ws === ws) {
      window.removeEventListener("resize", terminal.onWinResize);
      terminal = null;
    }
    render();
  };

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
  dom.termHost.replaceChildren();
}

function showTerminalHint(text) {
  dom.termHint.textContent = text;
  dom.termHint.hidden = false;
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
  // Help and preferences are purely local, so they are wired (and applied) before the token guard.
  prefs = loadPrefs();
  dom.helpButton.addEventListener("click", showHelpDialog);
  dom.helpClose.addEventListener("click", closeHelpDialog);
  dom.helpDone.addEventListener("click", closeHelpDialog);
  dom.prefsButton.addEventListener("click", showPrefsDialog);
  dom.basePathNote.addEventListener("click", showPrefsDialog);
  dom.prefsClose.addEventListener("click", closePrefsDialog);
  dom.prefsCancel.addEventListener("click", closePrefsDialog);
  dom.prefsForm.addEventListener("submit", savePreferences);
  dom.prefsBasePath.addEventListener("input", updateGroupingPreview);
  dom.prefsGroupingLevel.addEventListener("change", updateGroupingPreview);
  render();

  if (!TOKEN) {
    setStatus("No token. Open this page as http://127.0.0.1:PORT/#token=YOUR_TOKEN", true);
    dom.termHint.textContent = "Missing #token= fragment — cannot reach the daemon.";
    return;
  }

  dom.newSessionButton.addEventListener("click", () => showNewSessionDialog());
  dom.newSessionClose.addEventListener("click", closeNewSessionDialog);
  dom.newSessionCancel.addEventListener("click", closeNewSessionDialog);
  dom.newSessionForm.addEventListener("submit", startSession);
  dom.attachButton.addEventListener("click", () => { if (activeId) openTerminal(activeId); });
  dom.interruptButton.addEventListener("click", () => controlSession("interrupt"));
  dom.resumeButton.addEventListener("click", () => controlSession("resume"));
  dom.detachButton.addEventListener("click", detachTerminal);
  dom.stopButton.addEventListener("click", () => controlSession("stop"));

  loadSessions();
  connectEvents();
}

main();
