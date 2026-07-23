/*
 * Kotgent Web UI — a Preact SPA with no build step.
 *
 * Preact, its hooks and htm are vendored as plain ES modules under `vendor/` and wired through the
 * import map in index.html, so the browser loads exactly the files on disk: no bundler, no transpiler,
 * no CDN. Markup is htm tagged templates (`html` below), which is JSX-shaped but needs no compiler.
 *
 * Flow:
 *   1. Read the token from the URL fragment `#token=…` (a fragment, so it is never sent to the server
 *      or its logs) and keep it in memory.
 *   2. GET /sessions -> the session list; the sidebar draws it flat, or grouped by working directory
 *      when a base path is configured in Preferences.
 *   3. Open the GET /events WebSocket -> each session_update patches one keyed row in place.
 *   4. Selecting a live session attaches an xterm.js terminal on its binary terminal WebSocket.
 *   5. Lifecycle actions (interrupt / stop / resume) are REST calls; detaching closes only this
 *      browser's terminal client and leaves the agent running.
 *
 * This module owns all mutable state; everything below it is a function of props. Handlers read the
 * current sessions through a ref rather than closing over them, so they keep a stable identity across
 * updates — a session changing state re-renders one keyed row, not the whole tree.
 *
 * The pure helpers this builds on live in `lib/` and are import-able, so they can be exercised outside
 * a browser: the macosArm64 test binary cannot run this JS, and `WebUiServingTest` only proves that the
 * daemon serves it.
 */

import { render } from "preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { html } from "htm/preact";

import { apiRequest, errorMessage, parseToken, wsUrl } from "./lib/api.js";
import { loadPrefs, persistPrefs } from "./lib/prefs.js";
import { capitalize, displayName, isAliveState, stateBadge } from "./lib/sessions.js";
import { Sidebar } from "./components/Sidebar.js";
import { TerminalPane } from "./components/TerminalPane.js";
import { HelpDialog, NewSessionDialog, PreferencesDialog } from "./components/dialogs.js";

const TOKEN = parseToken(window.location.hash);
const SELECT_HINT = "Select a session on the left to attach its terminal.";
const NO_TOKEN_STATUS = "No token. Open this page as http://127.0.0.1:PORT/#token=YOUR_TOKEN";
const NO_TOKEN_HINT = "Missing #token= fragment — cannot reach the daemon.";

/** The hint shown for a session that cannot be attached because it is not alive. */
function deadHint(state) {
  return state === "resumable"
    ? "This session can be resumed."
    : "This session is " + stateBadge(state).label + ". Resume it to continue.";
}

function App() {
  const [sessions, setSessions] = useState([]);
  const [activeId, setActiveId] = useState(null);
  const [attachedId, setAttachedId] = useState(null);   // the session whose terminal is open here
  const [pendingAction, setPendingAction] = useState(null);
  const [prefs, setPrefs] = useState(loadPrefs);
  const [dialog, setDialog] = useState(null);           // null | {kind:'new',cwd} | {kind:'prefs'} | {kind:'help'}
  const [status, setStatus] = useState(
    TOKEN ? { text: "", error: false } : { text: NO_TOKEN_STATUS, error: true },
  );
  const [hint, setHint] = useState(TOKEN ? SELECT_HINT : NO_TOKEN_HINT);

  // Latest values for handlers that must not be re-created on every update.
  const sessionsRef = useRef(sessions);
  sessionsRef.current = sessions;
  const activeRef = useRef(activeId);
  activeRef.current = activeId;
  const pendingRef = useRef(pendingAction);
  pendingRef.current = pendingAction;
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;

  const say = useCallback((text, error) => setStatus({ text: text, error: !!error }), []);

  const activeSession = sessions.find((s) => s.id === activeId) || null;

  /** Select [session]: attach its terminal when it is alive, explain why not when it is not. */
  const showSession = useCallback((session) => {
    setActiveId(session.id);
    if (isAliveState(session.state)) {
      setAttachedId(session.id);
      setHint(null);
    } else {
      setAttachedId(null);
      setHint(deadHint(session.state));
    }
  }, []);

  const selectSession = useCallback((id) => {
    const session = sessionsRef.current.find((s) => s.id === id);
    if (session) showSession(session);
  }, [showSession]);

  const loadSessions = useCallback(async () => {
    try {
      const list = await apiRequest(TOKEN, "/sessions");
      setSessions(list);
      say(list.length + " session(s).");
      // A session that vanished server-side must not stay selected or attached.
      const ids = new Set(list.map((s) => s.id));
      setActiveId((id) => (id && !ids.has(id) ? null : id));
      setAttachedId((id) => (id && !ids.has(id) ? null : id));
    } catch (e) {
      say("Could not load sessions: " + errorMessage(e), true);
    }
  }, [say]);

  useEffect(() => { if (TOKEN) loadSessions(); }, [loadSessions]);

  // Live updates. The daemon re-sends a full snapshot on connect, so a reconnect resyncs cleanly.
  const loadRef = useRef(loadSessions);
  loadRef.current = loadSessions;
  useEffect(() => {
    if (!TOKEN) return undefined;
    let socket = null;
    let timer = null;
    let stopped = false;

    const connect = () => {
      if (stopped) return;
      try {
        socket = new WebSocket(wsUrl("/events", TOKEN));
      } catch (e) {
        say("events WS error: " + e, true);
        return;
      }
      socket.onmessage = (ev) => {
        let msg;
        try { msg = JSON.parse(ev.data); } catch (_) { return; }
        if (!msg || msg.type !== "session_update") return;
        // A session we have never seen (started elsewhere) — pull the list to get its metadata.
        if (!sessionsRef.current.some((s) => s.id === msg.sessionId)) {
          loadRef.current();
          return;
        }
        setSessions((prev) => prev.map((s) => (s.id === msg.sessionId
          ? Object.assign({}, s, {
              state: msg.state,
              needsAttention: msg.needsAttention,
              alive: isAliveState(msg.state),
              lastSeq: msg.lastSeq,
              unread: msg.unread,
            })
          : s)));
      };
      socket.onclose = () => { if (!stopped) timer = setTimeout(connect, 2000); };
      socket.onerror = () => { /* surfaced via onclose */ };
    };

    connect();
    return () => {
      stopped = true;
      clearTimeout(timer);
      if (socket) {
        socket.onclose = null;   // a teardown close must not schedule a reconnect
        try { socket.close(); } catch (_) {}
      }
    };
  }, [say]);

  // --- actions ---------------------------------------------------------------------------------

  const startSession = useCallback(async (body) => {
    const created = await apiRequest(TOKEN, "/sessions", {
      method: "POST",
      body: JSON.stringify(body),
    });
    setSessions((prev) => prev.concat([created]));
    setDialog(null);
    say("Started " + displayName(created) + ".");
    showSession(created);   // from `created` directly: the ref has not caught up with setSessions yet
  }, [say, showSession]);

  const controlSession = useCallback(async (action) => {
    const s = sessionsRef.current.find((x) => x.id === activeRef.current);
    if (!s || pendingRef.current) return;
    if (action === "stop" &&
        !window.confirm("Stop " + displayName(s) + "? The conversation can be resumed later.")) {
      return;
    }

    setPendingAction(action);
    say(capitalize(action) + " in progress…");
    try {
      const updated = await apiRequest(
        TOKEN,
        "/sessions/" + encodeURIComponent(s.id) + "/" + encodeURIComponent(action),
        { method: "POST" },
      );
      if (updated && updated.id) {
        setSessions((prev) => prev.map((x) => (x.id === updated.id ? updated : x)));
      }
      if (action === "stop") {
        setAttachedId(null);
        setHint("Session stopped. Resume it to continue.");
      } else if (action === "resume") {
        setAttachedId(s.id);
        setHint(null);
      }
      say(capitalize(action) + " completed for " + displayName(s) + ".");
    } catch (e) {
      say(capitalize(action) + " failed: " + errorMessage(e), true);
    } finally {
      setPendingAction(null);
    }
  }, [say]);

  const attach = useCallback(() => {
    if (!activeRef.current) return;
    setAttachedId(activeRef.current);
    setHint(null);
  }, []);

  const detach = useCallback(() => {
    const s = sessionsRef.current.find((x) => x.id === activeRef.current);
    setAttachedId(null);
    setHint(s ? "Detached from " + displayName(s) + ". The agent keeps running." : "Terminal detached.");
  }, []);

  /** The daemon dropped our terminal socket — this is not our own teardown. */
  const onTerminalClosed = useCallback(() => setAttachedId(null), []);

  const savePreferences = useCallback((next) => {
    setPrefs(next);
    persistPrefs(next);
    setDialog(null);
    say(next.basePath.length > 0
      ? "Grouping by " + next.basePath + " (level " + next.groupingLevel + ")."
      : "Grouping off — no base path set.");
  }, [say]);

  /** An explicit directory (a group's "+") wins, then the selected session's, then the base path. */
  const openNewSession = useCallback((cwd) => {
    const selected = sessionsRef.current.find((x) => x.id === activeRef.current);
    setDialog({ kind: "new", cwd: cwd || (selected && selected.cwd) || prefsRef.current.basePath });
  }, []);

  const closeDialog = useCallback(() => setDialog(null), []);
  const openPrefs = useCallback(() => setDialog({ kind: "prefs" }), []);
  const openHelp = useCallback(() => setDialog({ kind: "help" }), []);

  const interrupt = useCallback(() => controlSession("interrupt"), [controlSession]);
  const resume = useCallback(() => controlSession("resume"), [controlSession]);
  const stop = useCallback(() => controlSession("stop"), [controlSession]);

  // --- render ----------------------------------------------------------------------------------

  return html`
    <${Sidebar}
      sessions=${sessions}
      activeId=${activeId}
      prefs=${prefs}
      status=${status}
      onSelect=${selectSession}
      onNewSession=${openNewSession}
      onOpenPrefs=${openPrefs}
      onOpenHelp=${openHelp}
    />
    <${TerminalPane}
      session=${activeSession}
      attachedId=${attachedId}
      token=${TOKEN}
      pendingAction=${pendingAction}
      hint=${hint}
      onAttach=${attach}
      onInterrupt=${interrupt}
      onResume=${resume}
      onDetach=${detach}
      onStop=${stop}
      onTerminalClosed=${onTerminalClosed}
    />
    ${dialog && dialog.kind === "new" && html`
      <${NewSessionDialog} initialCwd=${dialog.cwd} onStart=${startSession} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "prefs" && html`
      <${PreferencesDialog} prefs=${prefs} sessions=${sessions}
                            onSave=${savePreferences} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "help" && html`<${HelpDialog} onClose=${closeDialog} />`}
  `;
}

render(html`<${App} />`, document.getElementById("app"));
