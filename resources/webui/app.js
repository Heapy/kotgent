/*
 * Kotgent Web UI — a Preact SPA with no build step.
 *
 * Preact, its hooks and htm are vendored as plain ES modules under `vendor/` and wired through the
 * import map in index.html, so the browser loads exactly the files on disk: no bundler, no transpiler,
 * no CDN. Markup is htm tagged templates (`html` below), which is JSX-shaped but needs no compiler.
 *
 * Flow:
 *   1. The browser already holds the `kotgent_session` cookie the login flow (`kotgent web`) set, so this
 *      page needs no token: every request carries the cookie ambiently (`credentials: "same-origin"`).
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

import { apiRequest, errorMessage, wsUrl } from "./lib/api.js";
import { loadPrefs, persistPrefs } from "./lib/prefs.js";
import { notifyAttention } from "./lib/notify.js";
import { capitalize, displayName, isAliveState, stateBadge } from "./lib/sessions.js";
import { throttleLeading } from "./lib/throttle.js";
import { Sidebar } from "./components/Sidebar.js";
import { TerminalPane } from "./components/TerminalPane.js";
import { HelpDialog, NewSessionDialog, PhoneDialog, PreferencesDialog } from "./components/dialogs.js";

const SELECT_HINT = "Select a session on the left to attach its terminal.";

/** The hint shown for a session that cannot be attached because it is not alive. */
function deadHint(state) {
  return state === "resumable"
    ? "This session can be resumed."
    : "This session is " + stateBadge(state).label + ". Resume it to continue.";
}

/**
 * One throttled poster PER session id. A single shared throttle would silently drop a pending mark for
 * session A the moment a call for B superseded it inside the window, leaving A with a residual badge until
 * it is selected again — the /events heartbeat only re-fires for the ACTIVE session.
 *
 * Bounded by [pruneReadPosters] whenever the session list is refreshed: this page is meant to stay open for
 * days on a machine that keeps creating sessions, and each entry can hold a live `setTimeout`.
 */
const readPosters = new Map();

/**
 * Drop the posters of sessions GET /sessions no longer lists. Insurance, not a live path: nothing removes a
 * session row today (marking one "done" archives it, and the list returns archived rows too), so this
 * normally deletes nothing — it mirrors the setActiveId/setAttachedId guards at its call site so the Map
 * cannot grow without bound if that ever changes. A pruned poster's pending timer fires at most once more,
 * into a request [postRead] swallows.
 */
function pruneReadPosters(ids) {
  for (const id of readPosters.keys()) {
    if (!ids.has(id)) readPosters.delete(id);
  }
}

/**
 * "I have seen this session through [seq]" — fire-and-forget, throttled to one request per window.
 *
 * The daemon persists the cursor and broadcasts the recomputed `unread` as an ordinary session_update, so
 * nothing is zeroed locally: the server stays the single source of truth and every other client (phone,
 * second browser) clears the same badge. A failed POST is swallowed on purpose — the next /events frame
 * re-evaluates the guard and retries.
 */
function postRead(id, seq) {
  let post = readPosters.get(id);
  if (!post) {
    post = throttleLeading((atSeq) => {
      apiRequest("/sessions/" + encodeURIComponent(id) + "/read", {
        method: "POST",
        body: JSON.stringify({ seq: atSeq }),
      }).catch(() => { /* the next /events frame retries */ });
    });
    readPosters.set(id, post);
  }
  post(seq);
}

/**
 * Mark the session the user is looking at as read. Takes the three values the guard reads rather than a row,
 * because two of its three callers do not have one: a `session_update` frame carries newer numbers than
 * `sessionsRef` (which has not re-rendered yet), and only the visibility trigger looks a row up.
 *
 * Archived ("done") sessions are marked too — their rows are selectable and still draw the pill, so
 * excluding them would leave a badge no click could ever clear; the emitted signal carries `archived`, which
 * is what keeps them hidden (pinned by
 * TransportTest.markingAnArchivedSessionReadDoesNotUnHideItInOtherClients).
 *
 * Called imperatively from the three triggers rather than from a `useEffect` on `[id, lastSeq, unread]`:
 * when a POST fails those primitives do not change, so the 15 s resync re-sends EQUAL numbers and preact's
 * `Object.is` dep check would skip the effect — a lost POST would never be retried, exactly when it matters
 * most (a `needs_approval` session may emit no further event). Checking on every frame instead turns the
 * existing resync into a heartbeat.
 */
function markReadIfViewing(id, unread, lastSeq) {
  if (!id || !(unread > 0)) return;
  // `visible` is the closest the platform gets: it stays true for an unfocused or occluded window, so a
  // badge can clear while the user is in another app. `document.hasFocus()` would be stricter but also
  // false whenever devtools has focus, which would look broken while debugging.
  if (document.visibilityState !== "visible") return;
  postRead(id, lastSeq);
}

function App() {
  const [sessions, setSessions] = useState([]);
  const [currentVersion, setCurrentVersion] = useState("");
  const [activeId, setActiveId] = useState(null);
  const [attachedId, setAttachedId] = useState(null);   // the session whose terminal is open here
  const [pendingAction, setPendingAction] = useState(null);
  const [prefs, setPrefs] = useState(loadPrefs);
  const [dialog, setDialog] = useState(null);           // null | {kind:'new',cwd} | {kind:'prefs'} | {kind:'help'} | {kind:'phone'}
  const [status, setStatus] = useState({ text: "", error: false });
  const [hint, setHint] = useState(SELECT_HINT);

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
    // From the row we were handed: `sessionsRef` may not list it yet (startSession selects what it created).
    markReadIfViewing(session.id, session.unread, session.lastSeq);
  }, []);

  const selectSession = useCallback((id) => {
    const session = sessionsRef.current.find((s) => s.id === id);
    if (session) showSession(session);
  }, [showSession]);

  const loadSessions = useCallback(async () => {
    try {
      const list = await apiRequest("/sessions");
      setSessions(list);
      say(list.length + " session(s).");
      // Defensive, all three: a session that disappeared from the list must not stay selected or attached,
      // nor keep a mark-read throttle (and its timer) alive for the rest of the page's life.
      const ids = new Set(list.map((s) => s.id));
      setActiveId((id) => (id && !ids.has(id) ? null : id));
      setAttachedId((id) => (id && !ids.has(id) ? null : id));
      pruneReadPosters(ids);
    } catch (e) {
      say("Could not load sessions: " + errorMessage(e), true);
    }
  }, [say]);

  useEffect(() => { loadSessions(); }, [loadSessions]);

  // Version metadata is independent of the session list: if this best-effort request fails, the
  // working UI still loads normally and simply omits the footer label.
  useEffect(() => {
    let stopped = false;
    apiRequest("/version")
      .then((info) => {
        if (!stopped && info && typeof info.version === "string") {
          setCurrentVersion(info.version);
        }
      })
      .catch(() => { /* the version label is optional */ });
    return () => { stopped = true; };
  }, []);

  // Live updates. The daemon re-sends a full snapshot on connect, so a reconnect resyncs cleanly.
  const loadRef = useRef(loadSessions);
  loadRef.current = loadSessions;
  useEffect(() => {
    let socket = null;
    let timer = null;
    let stopped = false;

    const connect = () => {
      if (stopped) return;
      try {
        socket = new WebSocket(wsUrl("/events"));
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
        // Notify on a genuine live transition INTO needs-attention (was not, now is). Comparing against the
        // known prior row means the initial snapshot / 15s resync of an already-attention session is silent.
        const prevSession = sessionsRef.current.find((s) => s.id === msg.sessionId);
        if (prevSession && !prevSession.needsAttention && msg.needsAttention) {
          notifyAttention(prevSession);
        }
        setSessions((prev) => prev.map((s) => (s.id === msg.sessionId
          ? Object.assign({}, s, {
              state: msg.state,
              needsAttention: msg.needsAttention,
              alive: isAliveState(msg.state),
              lastSeq: msg.lastSeq,
              unread: msg.unread,
              archived: msg.archived,
            }, msg.model != null ? { model: msg.model } : {}) // only the resync carries model; never blank it
          : s)));
        // Both a live update and the 15 s resync land here, which makes this the mark-read heartbeat: a
        // POST that was lost heals on the next frame. Judged on the frame's own numbers — `sessionsRef`
        // has not caught up with the setSessions above yet.
        if (msg.sessionId === activeRef.current) {
          markReadIfViewing(msg.sessionId, msg.unread, msg.lastSeq);
        }
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

  // Coming back to the tab is the third trigger: while it was hidden the guard suppressed every POST, so
  // the badge kept counting — as it should — and now clears. Registered once; the handler reads refs.
  useEffect(() => {
    const onVisibilityChange = () => {
      const s = sessionsRef.current.find((x) => x.id === activeRef.current);
      if (s) markReadIfViewing(s.id, s.unread, s.lastSeq);
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, []);

  // --- actions ---------------------------------------------------------------------------------

  const startSession = useCallback(async (body) => {
    const created = await apiRequest("/sessions", {
      method: "POST",
      body: JSON.stringify(body),
    });
    setSessions((prev) => prev.concat([created]));
    setDialog(null);
    say("Started " + displayName(created) + ".");
    showSession(created);   // from `created` directly: the ref has not caught up with setSessions yet
  }, [say, showSession]);

  const controlSession = useCallback(async (action, id) => {
    // Acts on an explicit session [id] when given (e.g. Restore from a sidebar row), else the active one.
    const s = sessionsRef.current.find((x) => x.id === (id || activeRef.current));
    if (!s || pendingRef.current) return;
    if (action === "stop" &&
        !window.confirm("Stop " + displayName(s) + "? The conversation can be resumed later.")) {
      return;
    }
    if (action === "done" &&
        !window.confirm("Mark " + displayName(s) + " done? This stops the agent and hides the session.")) {
      return;
    }

    setPendingAction(action);
    say(capitalize(action) + " in progress…");
    try {
      const updated = await apiRequest(
        "/sessions/" + encodeURIComponent(s.id) + "/" + encodeURIComponent(action),
        { method: "POST" },
      );
      if (updated && updated.id) {
        setSessions((prev) => prev.map((x) => (x.id === updated.id ? updated : x)));
      }
      if (action === "stop" || action === "done") {
        if (s.id === activeRef.current) setAttachedId(null);
        setHint(action === "done"
          ? "Marked done. Find it under “Show done”."
          : "Session stopped. Resume it to continue.");
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
  const openPhone = useCallback(() => setDialog({ kind: "phone" }), []);

  const interrupt = useCallback(() => controlSession("interrupt"), [controlSession]);
  const resume = useCallback(() => controlSession("resume"), [controlSession]);
  const stop = useCallback(() => controlSession("stop"), [controlSession]);
  const done = useCallback(() => controlSession("done"), [controlSession]);
  const restore = useCallback((id) => controlSession("undone", id), [controlSession]);

  // --- render ----------------------------------------------------------------------------------

  return html`
    <${Sidebar}
      sessions=${sessions}
      activeId=${activeId}
      prefs=${prefs}
      status=${status}
      currentVersion=${currentVersion}
      onSelect=${selectSession}
      onNewSession=${openNewSession}
      onOpenPrefs=${openPrefs}
      onOpenHelp=${openHelp}
      onOpenPhone=${openPhone}
      onRestore=${restore}
    />
    <${TerminalPane}
      session=${activeSession}
      attachedId=${attachedId}
      pendingAction=${pendingAction}
      hint=${hint}
      onAttach=${attach}
      onInterrupt=${interrupt}
      onResume=${resume}
      onDetach=${detach}
      onStop=${stop}
      onDone=${done}
      onTerminalClosed=${onTerminalClosed}
    />
    ${dialog && dialog.kind === "new" && html`
      <${NewSessionDialog} initialCwd=${dialog.cwd} basePath=${prefs.basePath}
                           onStart=${startSession} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "prefs" && html`
      <${PreferencesDialog} prefs=${prefs} sessions=${sessions}
                            onSave=${savePreferences} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "help" && html`<${HelpDialog} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "phone" && html`<${PhoneDialog} onClose=${closeDialog} />`}
  `;
}

render(html`<${App} />`, document.getElementById("app"));
