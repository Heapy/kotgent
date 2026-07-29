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
 *   2. GET /sessions + GET /preferences -> daemon state and daemon-wide grouping preferences.
 *   3. Open the GET /events WebSocket -> session_update and preferences_update frames patch live state.
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
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "preact/hooks";
import { html } from "htm/preact";

import {
  AUTH_PATH,
  apiRequest,
  errorMessage,
  isDefiniteAnswer,
  isUnauthenticated,
  wsUrl,
} from "./lib/api.js";
import {
  loadPrefs,
  persistTerminalFontSize,
  sanitizeServerPreferences,
} from "./lib/prefs.js";
import { notifyAttention } from "./lib/notify.js";
import { capitalize, displayName, isAliveState, stateBadge } from "./lib/sessions.js";
import { throttleLeading } from "./lib/throttle.js";
import { Sidebar } from "./components/Sidebar.js";
import { TerminalPane } from "./components/TerminalPane.js";
import { HelpDialog, NewSessionDialog, PhoneDialog, PreferencesDialog } from "./components/dialogs.js";

const SELECT_HINT = "Select a session on the left to attach its terminal.";
const REATTACH_LIVENESS_TIMEOUT_MS = 10_000;

/**
 * The query parameter a notification tap deep-links with (`/?session=<id>`) — the shape `sw.js` builds in
 * `openWindow`. Keep the two in step; the service worker is a classic script and cannot import this.
 */
const DEEP_LINK_PARAM = "session";

/** The session id this page was opened for, or null. */
function deepLinkSessionId() {
  try {
    return new URLSearchParams(window.location.search).get(DEEP_LINK_PARAM);
  } catch (_) {
    return null;
  }
}

/** Drop `?session=` from the address bar once it has been honoured, so a reload does not re-select it. */
function clearDeepLink() {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has(DEEP_LINK_PARAM)) return;
    url.searchParams.delete(DEEP_LINK_PARAM);
    window.history.replaceState(null, "", url.pathname + url.search + url.hash);
  } catch (_) { /* no History API — the parameter just lingers, which is harmless */ }
}

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

/** The same detached copy is used for an explicit detach and for a terminal socket that failed. */
function detachedHint(session) {
  return session
    ? "Detached from " + displayName(session) + ". The agent keeps running."
    : "Terminal detached.";
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
  // Narrow screens only: the sidebar is an overlay drawer there, so its open/closed state lives here —
  // the hamburger that opens it sits in the terminal header, on the other side of the tree. Above the
  // breakpoint the drawer classes mean nothing (the sidebar is a plain flex column) and this stays false,
  // because the toggle that flips it is display:none.
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Latest values for handlers that must not be re-created on every update.
  const sessionsRef = useRef(sessions);
  sessionsRef.current = sessions;
  const activeRef = useRef(activeId);
  activeRef.current = activeId;
  const pendingRef = useRef(pendingAction);
  pendingRef.current = pendingAction;
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;
  // HTTP and WebSocket preference responses race independently. The persisted revision is their one
  // ordering authority; refs advance synchronously so two deliveries in one browser turn cannot both win.
  const preferencesRevisionRef = useRef(prefs.revision);
  const serverPreferencesRef = useRef({
    basePath: prefs.basePath,
    groupingLevel: prefs.groupingLevel,
    revision: prefs.revision,
  });
  // The session a notification tap asked for, honoured once a /sessions load contains it (the id means
  // nothing until the list exists). Seeded from the URL at mount; a focused stale client can replace it
  // with the worker's message and trigger a refresh.
  const deepLinkRef = useRef(deepLinkSessionId());
  // Whether the initial /sessions phase has succeeded — before that, a current 401 means "this browser was
  // never signed in"; afterwards it means the credential died under a running page (see loadSessions).
  const firstLoadRef = useRef(true);
  // Only the newest list response may mutate state. A notification can request a refresh while the initial
  // load is still in flight, and letting that older response land last would erase the notification target.
  const sessionsLoadVersionRef = useRef(0);
  // An unexpected terminal close leaves one reattach candidate. The timer is a deliberate render
  // boundary: setting attachedId null and straight back to the same id in one turn can be batched into no
  // change, so TerminalPane's keyed effect would never build a replacement socket.
  const reattachIdRef = useRef(null);
  const reattachTimerRef = useRef(null);
  // The AbortController is also the ownership token for the async liveness read. Timer identity only
  // guards a queued callback; once it fires, this prevents an older same-id request from mutating a newer
  // foreground attempt.
  const reattachRequestRef = useRef(null);
  // A fresh attachment, foreground transition, or recovered events socket grants one attempt. It remains
  // available when the zero-delay timer wins the race against the terminal's close callback, then is
  // consumed as soon as a real candidate is evaluated.
  const reattachAvailableRef = useRef(false);

  const cancelReattach = useCallback(() => {
    reattachIdRef.current = null;
    reattachAvailableRef.current = false;
    const request = reattachRequestRef.current;
    reattachRequestRef.current = null;
    if (request) request.abort();
    if (reattachTimerRef.current !== null) {
      clearTimeout(reattachTimerRef.current);
      reattachTimerRef.current = null;
    }
  }, []);

  const scheduleReattach = useCallback(() => {
    if (document.visibilityState !== "visible" ||
        !reattachAvailableRef.current ||
        reattachTimerRef.current !== null) return;
    reattachTimerRef.current = setTimeout(async () => {
      reattachTimerRef.current = null;
      const id = reattachIdRef.current;
      // If a grant won the race with the queued WebSocket close, preserve it. onTerminalClosed will
      // fill the candidate and schedule this same check again.
      if (!id || document.visibilityState !== "visible") return;
      reattachAvailableRef.current = false;

      // The events socket can be suspended or reconnecting along with the terminal, so the cached row is
      // not a liveness check. Ask the daemon for this session, then re-check every local intent after await.
      const controller = new AbortController();
      const previousRequest = reattachRequestRef.current;
      reattachRequestRef.current = controller;
      if (previousRequest) previousRequest.abort();
      const livenessTimeout = setTimeout(
        () => controller.abort(),
        REATTACH_LIVENESS_TIMEOUT_MS,
      );
      try {
        const s = await apiRequest(
          "/sessions/" + encodeURIComponent(id),
          { signal: controller.signal },
        );
        if (reattachRequestRef.current !== controller) return;
        if (reattachIdRef.current !== id) return;
        if (document.visibilityState !== "visible") return;
        if (activeRef.current !== id) {
          reattachIdRef.current = null;
          return;
        }
        // A control action owns the attachment decision until it settles, and it is as transient as an
        // unreachable daemon — so keep the candidate. Its own outcome then decides: resume reattaches
        // explicitly, while stop/archive leave a session the next grant reads as dead.
        if (pendingRef.current) return;
        if (!s || !isAliveState(s.state)) {
          reattachIdRef.current = null;
          setHint(deadHint(s && s.state));
          return;
        }

        reattachIdRef.current = null;       // consume before rendering: a failed replacement is a new close
        setAttachedId(id);
        setHint(null);
      } catch (err) {
        if (reattachRequestRef.current !== controller) return;
        // A 4xx answered this session specifically — it is gone, or this client is signed out — and every
        // later grant would re-ask the same doomed question, so that candidate dies here. Any other
        // failure only means the daemon was unreachable: keep it, because the events socket's successful
        // reconnect grants a fresh attempt at once. Explicit selection/detach clears it via cancelReattach.
        if (isDefiniteAnswer(err)) reattachIdRef.current = null;
        if (activeRef.current === id) setHint(detachedHint(null));
      } finally {
        clearTimeout(livenessTimeout);
        if (reattachRequestRef.current === controller) reattachRequestRef.current = null;
      }
    }, 0);
  }, []);

  const say = useCallback((text, error) => setStatus({ text: text, error: !!error }), []);

  /** Apply a validated daemon preference payload unless a newer committed revision already arrived. */
  const applyServerPreferences = useCallback((raw) => {
    const next = sanitizeServerPreferences(raw);
    if (!next || next.revision < preferencesRevisionRef.current) return false;
    preferencesRevisionRef.current = next.revision;
    serverPreferencesRef.current = next;
    setPrefs((current) => Object.assign({}, current, next));
    return true;
  }, []);

  const activeSession = sessions.find((s) => s.id === activeId) || null;

  /** Select [session]: attach its terminal when it is alive, explain why not when it is not. */
  const showSession = useCallback((session) => {
    cancelReattach();
    setActiveId(session.id);
    // Picking a session is the drawer's whole purpose, so it closes itself — on a phone the terminal is
    // behind it. This covers every entry point (a tap in the list, a freshly started session, a
    // notification deep link), which is why it lives here and not in the click handler.
    setDrawerOpen(false);
    if (isAliveState(session.state)) {
      reattachAvailableRef.current = true;
      setAttachedId(session.id);
      setHint(null);
    } else {
      setAttachedId(null);
      setHint(deadHint(session.state));
    }
    // From the row we were handed: `sessionsRef` may not list it yet (startSession selects what it created).
    markReadIfViewing(session.id, session.unread, session.lastSeq);
  }, [cancelReattach]);

  const selectSession = useCallback((id) => {
    const session = sessionsRef.current.find((s) => s.id === id);
    if (session) showSession(session);
  }, [showSession]);

  const loadSessions = useCallback(async () => {
    const version = ++sessionsLoadVersionRef.current;
    const isFirstLoad = firstLoadRef.current;
    try {
      const list = await apiRequest("/sessions");
      if (version !== sessionsLoadVersionRef.current) return;
      firstLoadRef.current = false;
      setSessions(list);
      say(list.length + " session(s).");
      // Defensive, all three: a session that disappeared from the list must not stay selected or attached,
      // nor keep a mark-read throttle (and its timer) alive for the rest of the page's life.
      const ids = new Set(list.map((s) => s.id));
      setActiveId((id) => (id && !ids.has(id) ? null : id));
      setAttachedId((id) => (id && !ids.has(id) ? null : id));
      pruneReadPosters(ids);
      if (reattachIdRef.current && !ids.has(reattachIdRef.current)) cancelReattach();
      // A deep link from a notification tap: select it now that the list is here, once.
      const wanted = deepLinkRef.current;
      if (wanted) {
        const target = list.find((s) => s.id === wanted);
        if (target) {
          deepLinkRef.current = null;
          clearDeepLink();
          showSession(target);
        }
      }
    } catch (e) {
      if (version !== sessionsLoadVersionRef.current) return;
      // An installed home-screen app has its OWN cookie jar: it launches at start_url holding nothing, so
      // its very first request is a 401 and there is no link to hand it. Send it to the sign-in page, where
      // the code form is the only way in. `replace` so the back button does not bounce straight back into
      // this dead page. Only the initial load phase routes: a 401 after one successful list (a rotated token)
      // leaves a live page with an attached terminal on screen instead of throwing that terminal away.
      if (isFirstLoad && isUnauthenticated(e)) {
        window.location.replace(AUTH_PATH);
        return;
      }
      say("Could not load sessions: " + errorMessage(e), true);
    }
  }, [cancelReattach, say, showSession]);

  useEffect(() => { loadSessions(); }, [loadSessions]);

  // Preferences load independently from sessions. A concurrent save or WebSocket frame may overtake this
  // GET; applyServerPreferences's revision guard prevents that older response from rolling the UI back.
  useEffect(() => {
    let stopped = false;
    apiRequest("/preferences")
      .then((value) => { if (!stopped) applyServerPreferences(value); })
      .catch((e) => { if (!stopped) say("Could not load preferences: " + errorMessage(e), true); });
    return () => { stopped = true; };
  }, [applyServerPreferences, say]);

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
  // Same indirection, and load-bearing for a second reason: `opened` lives in the effect, so rebuilding
  // this socket would reset it and the next open would no longer read as a recovery.
  const scheduleReattachRef = useRef(scheduleReattach);
  scheduleReattachRef.current = scheduleReattach;
  useEffect(() => {
    let socket = null;
    let timer = null;
    let stopped = false;
    let opened = false;

    const connect = () => {
      if (stopped) return;
      try {
        socket = new WebSocket(wsUrl("/events"));
      } catch (e) {
        say("events WS error: " + e, true);
        return;
      }
      socket.onopen = () => {
        if (opened) {
          // This socket is the daemon-availability signal. A terminal retry that raced the restart may
          // have failed (or still be waiting on the old daemon); grant a fresh, owned liveness check now.
          reattachAvailableRef.current = true;
          scheduleReattachRef.current();
        }
        opened = true;
      };
      socket.onmessage = (ev) => {
        let msg;
        try { msg = JSON.parse(ev.data); } catch (_) { return; }
        if (msg && msg.type === "preferences_update") {
          applyServerPreferences(msg);
          return;
        }
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
  }, [applyServerPreferences, say]);

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

  // A notification tapped while this page was already open: the service worker focuses this window and
  // posts the session id, because a bare focus would leave whatever session was on screen — which is the
  // common case on a phone and would make the tap useless.
  useLayoutEffect(() => {
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return undefined;
    const onMessage = (event) => {
      const msg = event.data;
      if (!msg || msg.type !== "select-session" || !msg.sessionId) return;
      if (sessionsRef.current.some((session) => session.id === msg.sessionId)) {
        // This tap supersedes both an older retained target and any list already in flight. Otherwise an
        // earlier unknown-session notification can land later and switch away from the session just tapped.
        deepLinkRef.current = null;
        clearDeepLink();
        sessionsLoadVersionRef.current += 1;
        selectSession(msg.sessionId);
        return;
      }
      // A backgrounded page can hold an older snapshot than the worker that woke it. Retain the target so
      // loadSessions selects it from the refreshed list instead of silently discarding the notification tap.
      deepLinkRef.current = msg.sessionId;
      loadRef.current();
    };
    navigator.serviceWorker.addEventListener("message", onMessage);
    return () => navigator.serviceWorker.removeEventListener("message", onMessage);
  }, [selectSession]);

  // Mobile browsers commonly discard a terminal WebSocket while the page is suspended. A return to the
  // foreground grants exactly one attempt; daemon recovery grants through the events socket above. Schedule
  // even when the close callback has not landed yet: the zero-delay timer preserves the grant across that
  // ordering while also forcing Preact to render the detached state first.
  useEffect(() => {
    const reconnectWhenVisible = () => {
      const visible = document.visibilityState === "visible";
      reattachAvailableRef.current = visible;
      if (!visible) {
        if (reattachTimerRef.current !== null) {
          clearTimeout(reattachTimerRef.current);
          reattachTimerRef.current = null;
        }
        const request = reattachRequestRef.current;
        reattachRequestRef.current = null;
        if (request) request.abort();
        return;
      }
      scheduleReattach();
    };

    document.addEventListener("visibilitychange", reconnectWhenVisible);
    return () => {
      document.removeEventListener("visibilitychange", reconnectWhenVisible);
      cancelReattach();
    };
  }, [cancelReattach, scheduleReattach]);

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

  /**
   * Import a conversation started outside kotgent (`POST /sessions/import`), then — unless the dialog's
   * "register only" was ticked — resume it through the ordinary resume endpoint, exactly like the CLI's
   * `kotgent import`. An import failure (400/409) propagates to the dialog, which shows the daemon's
   * text in the form's own error line. A failed FOLLOW-UP resume is different: the registration itself
   * succeeded, so the dialog is already closed and the session is shown honestly resumable with the
   * resume error in the status line — retrying the import would only 409.
   */
  const importSession = useCallback(async (body, registerOnly) => {
    const created = await apiRequest("/sessions/import", {
      method: "POST",
      body: JSON.stringify(body),
    });
    // The import's SessionBound append can already have pushed this row here through /events (an unknown
    // id triggers a full list reload), so merge instead of blindly concatenating — two rows for one id
    // would break the keyed sidebar.
    setSessions((prev) => (prev.some((s) => s.id === created.id)
      ? prev.map((s) => (s.id === created.id ? created : s))
      : prev.concat([created])));
    setDialog(null);
    if (registerOnly) {
      say("Imported " + displayName(created) + " — registered only.");
      showSession(created);   // resumable → the dead hint explains the next step
      return;
    }
    try {
      const resumed = await apiRequest(
        "/sessions/" + encodeURIComponent(created.id) + "/resume",
        { method: "POST" },
      );
      const row = resumed && resumed.id ? resumed : created;
      setSessions((prev) => prev.map((s) => (s.id === row.id ? row : s)));
      say("Imported and resumed " + displayName(row) + ".");
      showSession(row);       // alive now → attaches the terminal
    } catch (e) {
      // The daemon's message (e.g. the `kotgent install` hint for a missing binary) says what to fix.
      showSession(created);
      say("Imported, but resume failed: " + errorMessage(e), true);
    }
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
    if (action === "stop" || action === "done" || action === "resume") cancelReattach();

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
        reattachAvailableRef.current = true;
        setAttachedId(s.id);
        setHint(null);
      }
      say(capitalize(action) + " completed for " + displayName(s) + ".");
    } catch (e) {
      say(capitalize(action) + " failed: " + errorMessage(e), true);
    } finally {
      setPendingAction(null);
    }
  }, [cancelReattach, say]);

  const attach = useCallback(() => {
    if (!activeRef.current) return;
    cancelReattach();
    reattachAvailableRef.current = true;
    setAttachedId(activeRef.current);
    setHint(null);
  }, [cancelReattach]);

  const detach = useCallback(() => {
    const s = sessionsRef.current.find((x) => x.id === activeRef.current);
    cancelReattach();
    setAttachedId(null);
    setHint(detachedHint(s));
  }, [cancelReattach]);

  /** The daemon dropped our terminal socket — this is not our own teardown. */
  const onTerminalClosed = useCallback((id) => {
    const s = sessionsRef.current.find((session) => session.id === id);
    reattachIdRef.current = id;
    setAttachedId((current) => (current === id ? null : current));
    if (activeRef.current === id) setHint(detachedHint(s));
    // visibilitychange can run just before this queued close callback. Its timer deliberately leaves the
    // one-shot attempt available when no id exists; now that the candidate is known, schedule it again.
    if (document.visibilityState === "visible") scheduleReattach();
  }, [scheduleReattach]);

  const savePreferences = useCallback(async (next) => {
    const saved = await apiRequest("/preferences", {
      method: "PUT",
      body: JSON.stringify({
        basePath: next.basePath,
        groupingLevel: next.groupingLevel,
      }),
    });
    // A preferences_update for this write (or a later write from another browser) may have arrived while
    // PUT was in flight. Apply only if this response is not older; the per-device font is independent.
    applyServerPreferences(saved);
    persistTerminalFontSize(next.terminalFontSize);
    setPrefs((current) => Object.assign({}, current, { terminalFontSize: next.terminalFontSize }));
    setDialog(null);
    const current = serverPreferencesRef.current;
    say(current.basePath.length > 0
      ? "Grouping by " + current.basePath + " (level " + current.groupingLevel + ")."
      : "Grouping off — no base path set.");
  }, [applyServerPreferences, say]);

  /** An explicit directory (a group's "+") wins, then the selected session's, then the base path. */
  const openNewSession = useCallback((cwd) => {
    const selected = sessionsRef.current.find((x) => x.id === activeRef.current);
    setDialog({ kind: "new", cwd: cwd || (selected && selected.cwd) || prefsRef.current.basePath });
  }, []);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);
  const toggleDrawer = useCallback(() => setDrawerOpen((open) => !open), []);

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
    ${/* The drawer's tap-outside dismissal — a real button so it is reachable by keyboard and by a screen
          reader too, and rendered only while the drawer is open so desktop never has it in the tree. */ ""}
    ${drawerOpen && html`
      <button type="button" class="drawer-scrim" aria-label="Close the session list"
              onClick=${closeDrawer}></button>`}
    <${Sidebar}
      sessions=${sessions}
      activeId=${activeId}
      prefs=${prefs}
      status=${status}
      currentVersion=${currentVersion}
      drawerOpen=${drawerOpen}
      onSelect=${selectSession}
      onNewSession=${openNewSession}
      onOpenPrefs=${openPrefs}
      onOpenHelp=${openHelp}
      onOpenPhone=${openPhone}
      onRestore=${restore}
      onCloseDrawer=${closeDrawer}
    />
    <${TerminalPane}
      session=${activeSession}
      attachedId=${attachedId}
      terminalFontSize=${prefs.terminalFontSize}
      pendingAction=${pendingAction}
      hint=${hint}
      drawerOpen=${drawerOpen}
      onToggleDrawer=${toggleDrawer}
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
                           onStart=${startSession} onImport=${importSession} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "prefs" && html`
      <${PreferencesDialog} prefs=${prefs} sessions=${sessions}
                            onSave=${savePreferences} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "help" && html`<${HelpDialog} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "phone" && html`<${PhoneDialog} onClose=${closeDialog} />`}
  `;
}

render(html`<${App} />`, document.getElementById("app"));
