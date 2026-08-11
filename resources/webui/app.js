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
 *   2. GET /preferences -> daemon-wide grouping preferences (and the first-run 401 gate to /auth).
 *   3. Open the GET /events WebSocket -> one sessions_snapshot frame builds the whole list; session_row /
 *      session_update / preferences_update frames keep it live, applied newest-rev-wins (lib/sessions.js).
 *      There is no GET /sessions on load — the socket is the list's only routine source.
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
import { writeClipboard } from "./lib/clipboard.js";
import { affectsAttachment, buildCommands } from "./lib/commands.js";
import {
  loadPrefs,
  loadSidebarCollapsed,
  persistSidebarCollapsed,
  persistTerminalFontSize,
  persistTerminalUnicode,
  sanitizeServerPreferences,
} from "./lib/prefs.js";
import { notifyAttention } from "./lib/notify.js";
import {
  capitalize,
  displayName,
  isAliveState,
  patchIfNewer,
  stateBadge,
  tmuxAttachCommand,
  upsertIfNewer,
} from "./lib/sessions.js";
import {
  SCREEN_SESSIONS,
  SCREEN_TASK,
  SCREEN_TASKS,
  navigate,
  parseRoute,
  routePath,
  sessionPath,
  subscribeToRoute,
  taskPath,
} from "./lib/router.js";
import {
  applyTasksSnapshot,
  fetchProjects,
  patchTaskIfNewer,
  removeTask,
  upsertTaskIfNewer,
} from "./lib/tasks.js";
import { Board } from "./components/Board.js";
import { TaskDetail } from "./components/TaskDetail.js";
import { CommandPalette } from "./components/CommandPalette.js";
import { Sidebar } from "./components/Sidebar.js";
import { TerminalPane } from "./components/TerminalPane.js";
import {
  HelpDialog,
  NewSessionDialog,
  PhoneDialog,
  PreferencesDialog,
  UploadFilesDialog,
} from "./components/dialogs.js";

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
 * One retrying poster PER session id. A single shared one would drop a pending mark for session A the
 * moment a call for B superseded it, leaving A with a residual badge until it is selected again.
 *
 * Bounded by [pruneReadPosters] whenever a snapshot installs the list: this page is meant to stay open
 * for days on a machine that keeps creating sessions, and each entry can hold a live retry timer.
 */
const readPosters = new Map();

/** Delay before a failed (but retryable) mark-read POST is attempted again. */
const READ_RETRY_DELAY_MS = 2000;

/**
 * Drop the posters (and their retry timers) of sessions the snapshot no longer lists. Insurance, not a
 * live path: nothing removes a session row today (marking one "done" archives it, and the snapshot
 * carries archived rows too), so this normally deletes nothing — it mirrors the setActiveId/setAttachedId
 * guards at its call site so the Map cannot grow without bound if that ever changes.
 */
function pruneReadPosters(ids) {
  for (const [id, poster] of readPosters) {
    if (ids.has(id)) continue;
    if (poster.timer !== null) clearTimeout(poster.timer);
    readPosters.delete(id);
  }
}

/**
 * "I have seen this session through [seq]" — retried until the daemon confirms, coalesced to the newest
 * seq, one request in flight per session.
 *
 * The daemon persists the cursor and broadcasts the recomputed `unread` as an ordinary session_update, so
 * nothing is zeroed locally: the server stays the single source of truth and every other client (phone,
 * second browser) clears the same badge. The retry loop replaced the old 15 s resync heartbeat — a
 * `needs_approval` session may emit no further frame, so a lost POST must heal itself. It stops on
 * [isDefiniteAnswer]: a 401 (rotated token) or 404 (vanished session) can never succeed, and a page that
 * lives for days must not hammer the daemon with unwinnable requests; a network failure (no `status`)
 * keeps retrying.
 */
function postRead(id, seq) {
  let poster = readPosters.get(id);
  if (!poster) {
    poster = { seq: 0, inFlight: false, timer: null };
    readPosters.set(id, poster);
  }
  poster.seq = Math.max(poster.seq, seq);
  deliverRead(id, poster);
}

function deliverRead(id, poster) {
  if (poster.inFlight || poster.timer !== null) return; // the live loop picks the newest seq up itself
  poster.inFlight = true;
  const attempted = poster.seq;
  apiRequest("/sessions/" + encodeURIComponent(id) + "/read", {
    method: "POST",
    body: JSON.stringify({ seq: attempted }),
  }).then(() => {
    poster.inFlight = false;
    if (readPosters.get(id) !== poster) return; // pruned while in flight
    if (poster.seq > attempted) deliverRead(id, poster); // a newer mark arrived meanwhile
  }).catch((e) => {
    poster.inFlight = false;
    if (readPosters.get(id) !== poster) return;
    if (isDefiniteAnswer(e)) return; // 4xx is final — retrying cannot ever succeed
    poster.timer = setTimeout(() => {
      poster.timer = null;
      if (readPosters.get(id) === poster) deliverRead(id, poster);
    }, READ_RETRY_DELAY_MS);
  });
}

/**
 * Whether the session view — the ONLY screen that displays a session — is the one on screen.
 *
 * Before the router there was one screen, so `activeId` and "the operator is looking at it" were the
 * same statement. The board replaced the whole session view as a route while `activeId` kept pointing at
 * a session nobody can see: grooming the backlog for ten minutes silently zeroed that session's unread
 * pill, and the operator came back unable to tell that anything had happened. CLAUDE.md's rule is
 * "`app.js` POSTs `/sessions/{id}/read` for the session it DISPLAYS", so displaying it is the gate.
 *
 * Assigned from `App`'s render body, which is legal precisely because it is never READ there: all four
 * mark-read triggers run from a handler or an effect, i.e. after the render that set it.
 */
let sessionViewOnScreen = true;

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
 * when a POST fails those primitives do not change, so preact's `Object.is` dep check would skip the
 * effect and a lost POST would never be retried — exactly when it matters most (a `needs_approval`
 * session may emit no further event). The retry itself lives in [postRead]; these triggers only decide
 * WHEN a viewing mark is warranted.
 */
function markReadIfViewing(id, unread, lastSeq) {
  if (!id || !(unread > 0)) return;
  // Two independent questions, and both must answer yes. Is this tab in front of the operator —
  // `visible` is the closest the platform gets: it stays true for an unfocused or occluded window, so a
  // badge can clear while the user is in another app. `document.hasFocus()` would be stricter but also
  // false whenever devtools has focus, which would look broken while debugging. And is the session view
  // the screen this tab is showing at all: see [sessionViewOnScreen].
  if (document.visibilityState !== "visible") return;
  if (!sessionViewOnScreen) return;
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
  // The task layer. `tasks` is a flat list of BacklogEntryDto across every project, merged
  // newest-rev-wins exactly like `sessions`; the board filters it by the selected project. `route` is
  // the History-API screen, and it is the app's single owner of BOTH "which screen" and "which session":
  // `/s/{id}` selects, and every selection navigates back into it (see showSession).
  const [tasks, setTasks] = useState([]);
  // The project list and the ONE selected project. Both used to live inside `Board`, which was right
  // while the board owned the selector; the sidebar is shell furniture and now draws that list on every
  // screen, so the state moved up to its one common ancestor rather than being mirrored in two places.
  // There is still exactly one owner of the selection — this — and `Board` is a consumer of it.
  const [projects, setProjects] = useState([]);
  const [projectId, setProjectId] = useState(null);
  const [route, setRoute] = useState(() => parseRoute(window.location.pathname, window.location.search));
  const [currentVersion, setCurrentVersion] = useState("");
  const [activeId, setActiveId] = useState(null);
  const [attachedId, setAttachedId] = useState(null);   // the session whose terminal is open here
  const [pendingAction, setPendingAction] = useState(null);
  const [prefs, setPrefs] = useState(loadPrefs);
  const [dialog, setDialog] = useState(null);           // null | new | upload | prefs | help | phone
  const [palette, setPalette] = useState(null);         // null | {mode:'search'|'leader'}
  const [showDone, setShowDone] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(loadSidebarCollapsed);
  const [status, setStatus] = useState({ text: "", error: false });
  const [hint, setHint] = useState(SELECT_HINT);
  // Narrow screens only: the sidebar is an overlay drawer there, so its open/closed state lives here —
  // the hamburger that opens it sits in the terminal header, on the other side of the tree. Above the
  // breakpoint the drawer classes mean nothing (the sidebar is a plain flex column) and this stays false,
  // because the toggle that flips it is display:none.
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Which screen the router has put on. `/tasks` and `/tasks/{ref}` replace the session view entirely
  // (the branch at the bottom of this render), and four things below need that answer, so it is computed
  // once here rather than at the render site.
  const onBoard = route.screen === SCREEN_TASKS || route.screen === SCREEN_TASK;
  // The mark-read gate, set from the render body and read only from handlers and effects. See the
  // [sessionViewOnScreen] header: `activeId` outlives the screen that shows it.
  sessionViewOnScreen = !onBoard;

  const openPalette = useCallback((mode = "leader") => setPalette({ mode: mode }), []);
  const closePalette = useCallback(() => setPalette(null), []);
  useEffect(() => { persistSidebarCollapsed(sidebarCollapsed); }, [sidebarCollapsed]);
  // One subscription for both directions of navigation: the browser's Back/Forward (`popstate`) and the
  // app's own `navigate()`. The router owns both so no component has to know about `history`.
  useEffect(() => subscribeToRoute(setRoute), []);

  /** Replace the task list from a `tasks_snapshot` — a connect/reconnect baseline, so it REPLACES. */
  const applyTasksBaseline = useCallback((rows) => {
    setTasks((current) => applyTasksSnapshot(current, rows));
  }, []);
  /** Upsert one full entry (a `task_row` frame, or a fetched/POSTed DTO), newest-rev-wins. */
  const applyTaskRow = useCallback((row) => {
    setTasks((current) => upsertTaskIfNewer(current, row));
  }, []);
  /** Apply a `task_update`. An unknown ref is ignored — the daemon does not send those. */
  const applyTaskPatch = useCallback((msg) => {
    setTasks((current) => patchTaskIfNewer(current, msg));
  }, []);
  /** Drop a deleted ref (`task_removed`). */
  const applyTaskRemoved = useCallback((ref) => {
    setTasks((current) => removeTask(current, ref));
  }, []);
  // The two appliers above are also handed DOWN, to `Board` and `TaskDetail`, as `onTaskRow` /
  // `onTaskRemoved`. Every task write answers with the committed `BacklogEntryDto` and its `rev`, so the
  // response merges through exactly the same newest-rev-wins path the socket's frame will take — it is
  // one more SOURCE for this list, not a second copy of it. That matters twice: while `/events` is down
  // or reconnecting, REST still works and a create/move/delete would otherwise change nothing on screen;
  // and it is what keeps an open detail panel and the card behind it looking at the same row.
  //
  // The live row for that panel is read here rather than inside it, because this list is the one store:
  // a component that kept its own copy is exactly the divergence being removed.
  const openTaskEntry = route.screen === SCREEN_TASK && route.id
    ? tasks.find((task) => task.ref === route.id) || null
    : null;

  // Latest values for handlers that must not be re-created on every update.
  const sessionsRef = useRef(sessions);
  sessionsRef.current = sessions;
  const activeRef = useRef(activeId);
  activeRef.current = activeId;
  // Monotonic selection generation, bumped by every showSession call (a sidebar click, a notification
  // deep link, a flow's own auto-select). Flows that steer the selection after an await capture it at
  // submit and only auto-select while it is unchanged. Comparing session IDs instead has an ABA hole:
  // A→B→A, or re-selecting the already-active session during the await, compares equal and would let
  // the completion yank the operator's newer (re-)selection away.
  const selectionGenRef = useRef(0);
  const pendingRef = useRef(pendingAction);
  pendingRef.current = pendingAction;
  // Which dialog object a submission came from: a completion may only close ITS OWN dialog (see
  // closeDialogFrom) — a bare setDialog(null) from a dismissed request would close whatever dialog
  // the operator opened since and discard its draft.
  const dialogRef = useRef(dialog);
  dialogRef.current = dialog;
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
  // The session a notification tap asked for, honoured once a snapshot (or a fetched row) contains it —
  // the id means nothing until the row exists. Seeded from the URL at mount; a focused stale client can
  // replace it with the worker's message.
  const deepLinkRef = useRef(deepLinkSessionId());
  // Whether the first sessions_snapshot has landed — before that the sidebar says "Loading sessions…"
  // instead of an honest-looking but false "No sessions yet", and the routine "N session(s)." line is
  // announced exactly once (a reconnect snapshot must not repeat it into the aria-live region).
  const [sessionsReady, setSessionsReady] = useState(false);
  const sessionsReadyRef = useRef(false);
  // One disconnect announcement per outage: onclose refires every ~2 s while the daemon is down, and an
  // aria-live region that repeats itself loops a screen reader. Re-armed by the next snapshot.
  const disconnectAnnouncedRef = useRef(false);
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

  // Capture before xterm.js or a focused form field sees the opener. A native app dialog owns the
  // keyboard while it is open; the palette itself is separate state so the same binding can toggle its
  // two views. `code` keeps the shortcut tied to the physical K key across keyboard layouts.
  // ⌘K opens the leader grid — the root view, whose every entry is one more keystroke away — and the
  // search view is reached from it by that same K (⌘K K, handled bare inside the palette) or by
  // repeating the opener, which is why this toggles rather than re-opening the root.
  useEffect(() => {
    const handler = (event) => {
      const opensPalette =
        (event.metaKey && event.code === "KeyK") ||
        (event.ctrlKey && event.shiftKey && event.code === "KeyK");
      // A normal browser tab reserves Command-1 for tab switching; the installed PWA receives it
      // reliably. The visible desktop toggle remains the guaranteed path in either surface.
      const togglesSidebar = event.metaKey && event.code === "Digit1";
      if ((!opensPalette && !togglesSidebar) || dialogRef.current) return;
      event.preventDefault();
      event.stopPropagation();
      if (togglesSidebar) {
        setSidebarCollapsed((collapsed) => !collapsed);
        return;
      }
      setPalette((current) => current
        ? { mode: current.mode === "search" ? "leader" : "search" }
        : { mode: "leader" });
    };
    document.addEventListener("keydown", handler, true);
    return () => document.removeEventListener("keydown", handler, true);
  }, []);

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

  // --- projects ------------------------------------------------------------------------------------
  //
  // The one thing on the task side that is FETCHED rather than pushed. Tasks arrive over `/events` (a
  // snapshot on connect, a frame per change), but there is no projects frame: a `BacklogEntryDto` carries
  // only the project uuid, and the names come from `GET /projects`. So the list is read on mount and
  // again on every entry to the board — a project created from the CLI, or in another tab, appears on the
  // next visit to `/tasks` rather than never. It is deliberately not polled: the sidebar's per-project
  // counts are computed from `tasks`, which IS live, so a stale row shows a fresh number.

  /** Re-read `GET /projects`; answers the rows, or null when the read failed (announced, not thrown). */
  const reloadProjects = useCallback(async () => {
    try {
      const response = await fetchProjects();
      const rows = Array.isArray(response)
        ? response
        : (response && Array.isArray(response.projects) ? response.projects : []);
      setProjects(rows);
      return rows;
    } catch (e) {
      say("Could not load projects: " + errorMessage(e), true);
      return null;
    }
  }, [say]);

  useEffect(() => { reloadProjects(); }, [reloadProjects]);

  // Exactly one project is selected at all times once any exists; a selection naming a project that is
  // no longer listed falls back to the first rather than leaving the board empty under a live sidebar.
  useEffect(() => {
    if (projects.length === 0) return;
    setProjectId((current) =>
      current && projects.some((project) => project.id === current) ? current : projects[0].id);
  }, [projects]);

  const selectProject = useCallback((id) => {
    setProjectId(id);
    // Picking a project is the drawer's whole purpose on a phone, exactly as picking a session is: the
    // board is behind it. Same rule, same place — beside the selection rather than in a click handler.
    setDrawerOpen(false);
  }, []);

  /**
   * A project the board's form just created: re-read the list and select it. The board owns the FORM
   * (it is a dialog beside the create-task one) but not the list, so the write reports back here.
   */
  const projectCreated = useCallback(async (created) => {
    const rows = await reloadProjects();
    if (created && created.id && rows && rows.some((project) => project.id === created.id)) {
      setProjectId(created.id);
    }
  }, [reloadProjects]);

  /** Apply a validated daemon preference payload unless a newer committed revision already arrived. */
  const applyServerPreferences = useCallback((raw) => {
    const next = sanitizeServerPreferences(raw);
    if (!next || next.revision < preferencesRevisionRef.current) return false;
    preferencesRevisionRef.current = next.revision;
    serverPreferencesRef.current = next;
    setPrefs((current) => Object.assign({}, current, next));
    return true;
  }, []);

  // Close the dialog a submission came from — and ONLY that one. The dialog's Cancel/×/Esc stay live
  // while a request is in flight, so the operator may have dismissed it and opened ANOTHER dialog;
  // a completion of the old request must not close the new dialog and discard its draft.
  const closeDialogFrom = useCallback((submitted) => {
    setDialog((current) => (current === submitted ? null : current));
  }, []);

  const activeSession = sessions.find((s) => s.id === activeId) || null;

  /** Select [session]: attach its terminal when it is alive, explain why not when it is not. */
  const showSession = useCallback((session) => {
    selectionGenRef.current += 1; // every selection, user- or flow-driven, invalidates older submits
    cancelReattach();
    setActiveId(session.id);
    // Picking a session is the drawer's whole purpose, so it closes itself — on a phone the terminal is
    // behind it. This covers every entry point (a tap in the list, a freshly started session, a
    // notification deep link), which is why it lives here and not in the click handler.
    setDrawerOpen(false);
    // The URL names the selected session, and navigating is therefore what LEAVES the board. Without
    // this the address bar and the selection were two independent owners: `onBoard` is computed from the
    // route alone, so while the board was on screen a palette row, a notification tap and the session a
    // task had just started all changed state nobody could see — the terminal socket opened and the
    // unread badge cleared behind a kanban board. `navigate` is a no-op when the page is already at that
    // path, which is what keeps the route→selection effect below from bouncing against this.
    navigate(sessionPath(session.id));
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

  // The other half of that coupling, and the one consumer `/s/{id}` never had: a route naming a session
  // SELECTS it. That is a pasted link, a reload, ⌘-click into a new tab, a session dot on a task card or
  // in the task detail, and the browser's Back out of the board — every one of which used to land on the
  // session view with nothing selected. `?session=` arrives here too, because `parseRoute` folds the
  // notification deep link into the same `{screen, id}`.
  //
  // Held until the row exists: an id means nothing before the first snapshot lands, so a deep-linked
  // reload retries on each list change. Guarded on the active id, which is what makes it idempotent —
  // showSession's own `navigate` re-enters this effect and finds its work already done.
  //
  // One-directional on purpose: a route naming NO session (`/`) deselects nothing. `/` is where a plain
  // load starts and where the board's "Sessions" link goes, and clearing the selection there would tear
  // down a live terminal for a navigation the operator made to reach exactly that terminal.
  const routeSessionId = route.screen === SCREEN_SESSIONS ? route.id : null;
  useEffect(() => {
    if (!routeSessionId || routeSessionId === activeId) return;
    const target = sessions.find((s) => s.id === routeSessionId);
    if (!target) return;
    // This honours the retained notification target as well, so retire it here too: a later row for the
    // same id must not re-select a session the operator has since left.
    if (deepLinkRef.current === routeSessionId) deepLinkRef.current = null;
    showSession(target);
  }, [routeSessionId, sessions, activeId, showSession]);

  // The board is the screen whose sidebar body is the project list, and that list is the one fetched
  // thing here — so entering it is when it is re-read. Keyed on `onBoard` rather than on the whole route,
  // because moving between `/tasks` and `/tasks/{ref}` is not an arrival.
  useEffect(() => {
    if (onBoard) reloadProjects();
  }, [onBoard, reloadProjects]);

  // Opening `/tasks/{ref}` selects that task's project — once per ref, so a later manual pick in the
  // sidebar sticks even while the socket keeps patching rows. A ref that is not in the list yet retries
  // on the next frame, which is what makes a deep link work when the snapshot has not landed at mount.
  // It lives here, beside the selection it writes, rather than in `Board`: the sidebar is what shows the
  // answer, and on a phone the board may not even be the thing on screen when the detail is open.
  const appliedTaskProjectRef = useRef(null);
  const openTaskRef = route.screen === SCREEN_TASK ? route.id : null;
  useEffect(() => {
    if (!openTaskRef) { appliedTaskProjectRef.current = null; return; }
    if (appliedTaskProjectRef.current === openTaskRef) return;
    const entry = tasks.find((task) => task.ref === openTaskRef);
    if (!entry) return;
    appliedTaskProjectRef.current = openTaskRef;
    setProjectId(entry.project);
  }, [openTaskRef, tasks]);

  // Coming back to the session view is the fourth mark-read trigger, and the only one that can fire
  // here: the tab never stopped being visible, and the active session may have emitted nothing while the
  // board owned the screen. Without it a badge the gate correctly refused to clear would sit there until
  // the session's next frame.
  useEffect(() => {
    if (onBoard) return;
    const s = sessionsRef.current.find((x) => x.id === activeRef.current);
    if (s) markReadIfViewing(s.id, s.unread, s.lastSeq);
  }, [onBoard]);

  // The three frame applicators. Ordering across channels needs no election machinery any more: every
  // row observation (a WS frame or an HTTP DTO) carries the daemon-stamped `rev`, and the helpers apply
  // it only when newer — a stale response arriving late loses by comparison, not by protocol ordering.

  /** Install a connect/reconnect sessions_snapshot: replace the list, diffing per row against the old one. */
  const applySessionsSnapshot = useCallback((rows) => {
    // Notify per row against the PREVIOUS list: a session that entered needs-attention while the socket
    // was down (a sleeping laptop, a daemon restart) has this snapshot as its only carrier — it must ring
    // exactly like a live transition. A row with no prior is silent, like every first sighting.
    const prev = sessionsRef.current;
    for (const row of rows) {
      const prevRow = prev.find((s) => s.id === row.id);
      if (prevRow && !prevRow.needsAttention && row.needsAttention) notifyAttention(prevRow);
    }
    // Wholesale replace: the daemon read this snapshot after (re)connect, so it is at least as fresh as
    // anything this client holds — and it is also the only carrier of a row DELETION.
    setSessions(rows);
    // Defensive, all three: a session that disappeared from the list must not stay selected or attached,
    // nor keep a mark-read poster (and its retry timer) alive for the rest of the page's life.
    const ids = new Set(rows.map((s) => s.id));
    setActiveId((id) => (id && !ids.has(id) ? null : id));
    setAttachedId((id) => (id && !ids.has(id) ? null : id));
    pruneReadPosters(ids);
    if (reattachIdRef.current && !ids.has(reattachIdRef.current)) cancelReattach();
    // A deep link from a notification tap: select it now that the list is here, once.
    const wanted = deepLinkRef.current;
    if (wanted) {
      const target = rows.find((s) => s.id === wanted);
      if (target) {
        deepLinkRef.current = null;
        clearDeepLink();
        showSession(target);
      }
    }
    // The active session's badge: the snapshot may carry unread the dead socket never told us about.
    // Judged on the snapshot's own numbers — sessionsRef has not caught up with setSessions yet.
    const active = rows.find((s) => s.id === activeRef.current);
    if (active) markReadIfViewing(active.id, active.unread, active.lastSeq);
    disconnectAnnouncedRef.current = false;
    if (!sessionsReadyRef.current) {
      sessionsReadyRef.current = true;
      setSessionsReady(true);
      // Announced on the FIRST snapshot only: a reconnect must not repeat the routine line into the
      // aria-live region (and must not overwrite a flow's own status text).
      say(rows.length + " session(s).");
    }
  }, [cancelReattach, say, showSession]);

  /** Upsert one full row (a session_row frame, or a fetched/POSTed SessionDto), newest-rev-wins. */
  const applySessionRow = useCallback((row) => {
    const prevRow = sessionsRef.current.find((s) => s.id === row.id);
    if (prevRow && !prevRow.needsAttention && row.needsAttention) notifyAttention(prevRow);
    setSessions((prev) => upsertIfNewer(prev, row));
    // A retained notification target can arrive as a single row (the worker's fetch) — honour it here
    // exactly like the snapshot path does.
    if (deepLinkRef.current === row.id) {
      deepLinkRef.current = null;
      clearDeepLink();
      showSession(row);
      return;
    }
    if (row.id === activeRef.current) markReadIfViewing(row.id, row.unread, row.lastSeq);
  }, [showSession]);

  /** Apply a light session_update patch. An unknown id is silently ignored — the daemon does not send those. */
  const applySessionPatch = useCallback((msg) => {
    const prevSession = sessionsRef.current.find((s) => s.id === msg.sessionId);
    if (!prevSession) return;
    // Notify on a genuine live transition INTO needs-attention (was not, now is).
    if (!prevSession.needsAttention && msg.needsAttention) notifyAttention(prevSession);
    setSessions((prev) => patchIfNewer(prev, msg));
    // Judged on the frame's own numbers — `sessionsRef` has not caught up with the setSessions above yet.
    if (msg.sessionId === activeRef.current) {
      markReadIfViewing(msg.sessionId, msg.unread, msg.lastSeq);
    }
  }, []);

  /**
   * Fetch ONE session row and upsert it (newest-rev-wins — the response cannot roll back a fresher WS
   * frame, which is what makes this targeted GET safe where the old wholesale reload was not). Resolves
   * to the row, or null on any failure: the caller decides how loud a miss is.
   */
  const fetchSessionRow = useCallback(async (id) => {
    try {
      const row = await apiRequest("/sessions/" + encodeURIComponent(id));
      if (row && row.id) {
        applySessionRow(row);
        return row;
      }
    } catch (_) { /* resolved below as null */ }
    return null;
  }, [applySessionRow]);
  // Ref indirection for the service-worker message handler, whose effect deps must stay [selectSession].
  const fetchSessionRowRef = useRef(fetchSessionRow);
  fetchSessionRowRef.current = fetchSessionRow;

  // Preferences load independently from sessions. A concurrent save or WebSocket frame may overtake this
  // GET; applyServerPreferences's revision guard prevents that older response from rolling the UI back.
  // This is also the page's ONE first-load 401 gate now that no GET /sessions happens on mount: an
  // installed home-screen app has its OWN cookie jar, launches at start_url holding nothing, and its very
  // first request answers 401 with no link to hand it — send it to the sign-in page, `replace` so the
  // back button does not bounce into this dead page. The effect is mount-only by construction (both deps
  // are stable useCallback([])), so its 401 is always the FIRST-load one; a later 401 (a rotated token)
  // reaches only postRead's finality stop and the status line, never a navigation that would throw a
  // live page with an attached terminal away.
  useEffect(() => {
    let stopped = false;
    apiRequest("/preferences")
      .then((value) => { if (!stopped) applyServerPreferences(value); })
      .catch((e) => {
        if (stopped) return;
        if (isUnauthenticated(e)) {
          window.location.replace(AUTH_PATH);
          return;
        }
        say("Could not load preferences: " + errorMessage(e), true);
      });
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
  // The frame dispatcher reaches the socket through a ref, never through the effect's deps: any new
  // dependency would rebuild the socket on every applicator identity change.
  const onSessionsFrame = useCallback((msg) => {
    if (msg.type === "sessions_snapshot") applySessionsSnapshot(msg.sessions);
    else if (msg.type === "session_row") applySessionRow(msg.session);
    else if (msg.type === "session_update") applySessionPatch(msg);
    // The task frames ride the SAME socket, with the same protocol: one baseline, a full row for a ref
    // this socket has not carried yet, a patch for every later change, and a removal.
    else if (msg.type === "tasks_snapshot") applyTasksBaseline(msg.tasks);
    else if (msg.type === "task_row") applyTaskRow(msg.task);
    else if (msg.type === "task_update") applyTaskPatch(msg.task);
    else if (msg.type === "task_removed") applyTaskRemoved(msg.ref);
  }, [
    applySessionsSnapshot, applySessionRow, applySessionPatch,
    applyTasksBaseline, applyTaskRow, applyTaskPatch, applyTaskRemoved,
  ]);
  const sessionsFrameRef = useRef(onSessionsFrame);
  sessionsFrameRef.current = onSessionsFrame;
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
        // The list has no HTTP fallback, so giving up here would mean a permanently empty UI —
        // retry on the same cadence as onclose.
        say("events WS error: " + e, true);
        timer = setTimeout(connect, 2000);
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
        if (!msg) return;
        if (msg.type === "preferences_update") {
          applyServerPreferences(msg);
          return;
        }
        sessionsFrameRef.current(msg);
      };
      socket.onclose = () => {
        if (stopped) return;
        // One announcement per outage — re-armed by the next snapshot. Without any, a frozen list looks
        // healthy; with one per retry, a screen reader loops on the 2 s reconnect cadence.
        if (!disconnectAnnouncedRef.current) {
          disconnectAnnouncedRef.current = true;
          say("Daemon connection lost — reconnecting…", true);
        }
        timer = setTimeout(connect, 2000);
      };
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
        // This tap supersedes an older retained target: an earlier unknown-session notification's row
        // can land later and must not switch away from the session just tapped.
        deepLinkRef.current = null;
        clearDeepLink();
        selectSession(msg.sessionId);
        return;
      }
      // A backgrounded page can hold an older snapshot than the worker that woke it. Retain the target
      // and fetch that ONE row; applySessionRow honours the retained deep link when the row arrives.
      deepLinkRef.current = msg.sessionId;
      fetchSessionRowRef.current(msg.sessionId);
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
    const submittedDialog = dialogRef.current;
    // Same steering rule as importSession's: auto-select the new session only while the selection
    // GENERATION is unchanged since submit — a sidebar click or a notification tap during the POST
    // (even one landing back on the very session that was active at submit) must not be yanked away
    // to the freshly started session.
    const selectionAtSubmit = selectionGenRef.current;
    let created;
    try {
      created = await apiRequest("/sessions", {
        method: "POST",
        body: JSON.stringify(body),
      });
    } catch (e) {
      // The submitting form owns the error only while it is still the mounted one (savePreferences'
      // rule): the dialog's Cancel/×/Esc stay live during the POST, and a setError on an unmounted
      // form is a silent no-op — so a late failure is routed to the status line instead of vanishing.
      if (dialogRef.current === submittedDialog) throw e;
      say("Could not start session: " + errorMessage(e), true);
      return;
    }
    // Upsert, not a bare concat: the session_row frame for the new session may have arrived first, and
    // whichever observation is older loses by rev instead of duplicating the row.
    setSessions((prev) => upsertIfNewer(prev, created));
    closeDialogFrom(submittedDialog);
    say("Started " + displayName(created) + ".");
    // From `created` directly: the ref has not caught up with setSessions yet.
    if (selectionGenRef.current === selectionAtSubmit) showSession(created);
  }, [closeDialogFrom, say, showSession]);

  /**
   * Import a conversation started outside kotgent (`POST /sessions/import`), then — unless the dialog's
   * "register only" was ticked — resume it through the ordinary resume endpoint, exactly like the CLI's
   * `kotgent import`. An import failure (400/409) propagates to the dialog, which shows the daemon's
   * text in the form's own error line — but only while that exact form is still MOUNTED
   * (savePreferences' rule): the dialog's Cancel/×/Esc stay live during the request, and a setError on
   * an unmounted (or replaced) instance is a silent no-op, so a failure landing after a dismissal is
   * routed to the status line instead of vanishing. A failed FOLLOW-UP resume is different: the
   * registration itself succeeded, so the dialog is already closed and the session is shown honestly
   * resumable with the resume error in the status line — retrying the import would only 409.
   *
   * Every HTTP DTO here (the 201, the resume response, the targeted GETs) merges into the list through
   * upsertIfNewer: rows carry the daemon-stamped `rev`, so a DTO racing the /events stream is ordered by
   * comparison and can never roll a fresher row back. Each step still AWAITS a targeted
   * `GET /sessions/{id}` (fetchSessionRow) so the terminal-attach decision (showSession) reads the
   * freshest known state; the step's own DTO remains the FALLBACK for a failed fetch — upserted too, so
   * the imported session is never invisible.
   *
   * The whole flow occupies the one-action-at-a-time slot (`pendingAction`), like controlSession's
   * verbs: without it a Done/Stop on the just-imported row could run between registration and the
   * follow-up resume — and the delayed resume would then restart the session the operator had just
   * stopped or archived.
   *
   * The flow STEERS the selection (showSession) only while the selection GENERATION is unchanged since
   * submit: every showSession below runs after an await, and in that window a sidebar click or a push
   * notification tap can select another session — auto-selecting the imported one would yank the
   * operator back and discard that newer choice. The guard counts selection EVENTS
   * (selectionGenRef), not the selected id: id equality has an ABA hole where A→B→A, or re-selecting
   * the already-active session, reads as "unmoved" and is stolen anyway. The say() lines are not
   * guarded: the status line reports the outcome without moving anyone.
   */
  const importSession = useCallback(async (body, registerOnly) => {
    if (pendingRef.current) {
      // Refused, not queued: the rejection surfaces in the dialog's own error line.
      throw new Error("Another action is still in progress — try again in a moment.");
    }
    const submittedDialog = dialogRef.current;
    const selectionAtSubmit = selectionGenRef.current;
    const selectionUnmoved = () => selectionGenRef.current === selectionAtSubmit;
    setPendingAction("import");
    try {
      let created;
      try {
        created = await apiRequest("/sessions/import", {
          method: "POST",
          body: JSON.stringify(body),
        });
      } catch (e) {
        // Rethrown into the form's error line only while the submitted dialog is still the mounted
        // one; a late failure otherwise goes to the status line (verbatim — the daemon's import text
        // is already user-facing), never into an unmounted form's silent setError.
        if (dialogRef.current === submittedDialog) throw e;
        say(errorMessage(e), true);
        return;
      }
      // This targeted GET started after the import committed, so its row is at least as fresh as the
      // 201 DTO (including the SessionBound append the response reflects — an /events push of the
      // earlier pre-bind upsert may have installed an OLDER row). fetchSessionRow upserts what it got;
      // the DTO remains only the fallback for a failed fetch, upserted below so the imported row is
      // never INVISIBLE — the 201 committed, and no frame lists it until its next change.
      const fetched = await fetchSessionRow(created.id);
      const registered = fetched || created;
      if (!fetched) setSessions((prev) => upsertIfNewer(prev, created));
      closeDialogFrom(submittedDialog);
      if (registerOnly) {
        // Said only when the fetch answered: after a failed one the selected row below is feedback
        // enough that the import itself landed.
        if (fetched) say("Imported " + displayName(registered) + " — registered only.");
        if (selectionUnmoved()) showSession(registered);   // resumable → the dead hint explains the next step
        return;
      }
      try {
        const resumedDto = await apiRequest(
          "/sessions/" + encodeURIComponent(created.id) + "/resume",
          { method: "POST" },
        );
        // Select from a fresh post-resume row first: the resumed agent's first events — or its
        // immediate exit — may have landed through /events while the response was in flight, and
        // attaching a terminal to an already-dead session must not happen on state known to be stale.
        const freshRow = await fetchSessionRow(created.id);
        // When that fetch fails, the RESUME DTO is the fallback — post-resume state, alive, so
        // showSession still attaches the terminal; the pre-resume `registered` row would report
        // success while silently dropping the attach. (The action route answers a plain "ok" with no
        // DTO only when the row vanished mid-request — then the pre-resume row is all that is left.)
        // Upserted newest-rev-wins, like every DTO.
        const row = freshRow || (resumedDto && resumedDto.id ? resumedDto : registered);
        if (!freshRow && resumedDto && resumedDto.id) setSessions((prev) => upsertIfNewer(prev, resumedDto));
        if (freshRow) say("Imported and resumed " + displayName(row) + ".");
        if (selectionUnmoved()) showSession(row); // alive in the fresh row (or the DTO) → attaches the terminal
      } catch (e) {
        // The daemon's message (e.g. the `kotgent install` hint for a missing binary) says what to fix;
        // the fresh row decides what the session looks like now (still resumable, normally).
        const after = await fetchSessionRow(created.id);
        if (selectionUnmoved()) showSession(after || registered);
        say("Imported, but resume failed: " + errorMessage(e), true);
      }
    } finally {
      setPendingAction(null);
    }
  }, [closeDialogFrom, fetchSessionRow, say, showSession]);

  const controlSession = useCallback(async (action, id) => {
    // Acts on an explicit session [id] when given (e.g. Restore from a sidebar row), else the active one.
    const s = sessionsRef.current.find((x) => x.id === (id || activeRef.current));
    if (!s) return;
    // A vanished row has nothing to report, but a refusal does: the palette closes before the command
    // even runs and owns no live region that outlives it, so a silently dropped second action looked
    // exactly like a dead chord. Reached in practice from the sidebar's Restore button, which carries
    // no disabled state of its own; every palette route is already dimmed by the registry.
    if (pendingRef.current) {
      say("Another action is still in progress — try again in a moment.", true);
      return;
    }
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
        // Newest-rev-wins: a WS patch that landed while the POST response was in flight is fresher than
        // the DTO, and a verbatim replacement would roll the row back.
        setSessions((prev) => upsertIfNewer(prev, updated));
      }
      if (action === "stop" || action === "done") {
        if (s.id === activeRef.current) setAttachedId(null);
        setHint(action === "done"
          ? "Marked done. Find it under “Show done”."
          : "Session stopped. Resume it to continue.");
      } else if (action === "resume") {
        reattachAvailableRef.current = true;
        // Both writes are guarded, and for the same reason: the POST is awaited, and in that window a
        // sidebar tap or a notification deep link can move the selection. TerminalPane titles itself
        // from `session` but opens its socket from `attachedId`, so an unguarded attachment names the
        // newly selected session over the resumed one's terminal — and an unguarded `setHint(null)`
        // erases the explanation the new selection just installed, leaving a dead pane with no title
        // row, no socket and nothing saying why. Guarding one and not the other trades a wrong terminal
        // for a blank one. reattachAvailableRef stays unguarded: the scheduled attempt re-checks the
        // active id itself and destroys a candidate that no longer matches.
        if (s.id === activeRef.current) {
          setAttachedId(s.id);
          setHint(null);
        }
      }
      say(capitalize(action) + " completed for " + displayName(s) + ".");
    } catch (e) {
      say(capitalize(action) + " failed: " + errorMessage(e), true);
    } finally {
      setPendingAction(null);
    }
  }, [cancelReattach, say]);

  // These two are local state writes rather than POSTs, which is why they were the pair that never
  // consulted pendingRef at all — and exactly why they need it: an Attach fired during a pending Stop
  // sets attachedId with nothing in flight of its own, and the stop's completion then resets it to null.
  // The question they ask is NARROWER than controlSession's, though, and `affectsAttachment` is the one
  // home of that rule: a pending Interrupt or Restore can never rewrite the attachment, so refusing a
  // Detach during one would strand the operator on a live terminal they asked to leave.
  const attach = useCallback(() => {
    if (!activeRef.current) return;
    if (affectsAttachment(pendingRef.current)) {
      say("Another action is still in progress — try again in a moment.", true);
      return;
    }
    cancelReattach();
    reattachAvailableRef.current = true;
    setAttachedId(activeRef.current);
    setHint(null);
  }, [cancelReattach, say]);

  const detach = useCallback(() => {
    if (affectsAttachment(pendingRef.current)) {
      say("Another action is still in progress — try again in a moment.", true);
      return;
    }
    const s = sessionsRef.current.find((x) => x.id === activeRef.current);
    cancelReattach();
    setAttachedId(null);
    setHint(detachedHint(s));
  }, [cancelReattach, say]);

  // The palette's own aria-live region is unmounted with the palette, and the palette closes
  // synchronously before this clipboard work settles — nothing announced there would survive to be read.
  // Report its copy result through the sidebar status line after the palette has closed.
  const copyTmuxCommand = useCallback(async () => {
    const s = sessionsRef.current.find((x) => x.id === activeRef.current);
    const command = s && isAliveState(s.state) && s.tmuxSession
      ? tmuxAttachCommand(s.tmuxSession)
      : "";
    if (!command) {
      say("Could not copy tmux command: select a live session with a tmux name.", true);
      return;
    }
    try {
      await writeClipboard(command);
      say("Tmux command copied to clipboard.");
    } catch (_) {
      say("Could not copy the tmux command.", true);
    }
  }, [say]);

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

  // One preferences PUT at a time. The dialog's own `busy` flag cannot enforce this: ×/Esc stay live
  // while a save is in flight, so the dialog can be closed and REOPENED — a fresh mount, busy=false,
  // draft seeded from the still-uncommitted prefs — and an early preferences_update echo remounts even
  // the OPEN dialog through its revision key, resetting busy mid-save. A second PUT from either stale
  // draft would commit pre-save values under a FRESH revision — a rollback the revision guard cannot
  // catch. Refused, not queued (importSession's precedent): the rejection lands in the dialog's error
  // line, and once the first commit arrives the revision-key remount re-seeds any open draft anyway.
  const prefsSaveInFlightRef = useRef(false);

  const savePreferences = useCallback(async (next) => {
    if (prefsSaveInFlightRef.current) {
      throw new Error("A preferences save is already in progress — try again in a moment.");
    }
    prefsSaveInFlightRef.current = true;
    const submittedDialog = dialogRef.current;
    // Which FORM this save came from, not just which dialog: a commit landing mid-flight bumps
    // prefs.revision and remounts PreferencesDialog under the SAME dialog object (see the render key),
    // re-seeding a fresh draft the operator may already be editing — the dialog identity alone cannot
    // tell the two forms apart. prefsRef tracks the last RENDERED prefs, so this matches the mounted
    // form's key. It survives only to route a LATE FAILURE to the instance that can still show it
    // (the catch below); it is deliberately no longer part of the success path's close decision, which
    // used to read it and got the ordinary case wrong — see there.
    const revisionAtSubmit = prefsRef.current.revision;
    try {
      const saved = await apiRequest("/preferences", {
        method: "PUT",
        body: JSON.stringify({
          basePath: next.basePath,
          groupingLevel: next.groupingLevel,
        }),
      });
      // A 2xx whose body is not a preference snapshot is a BROKEN DAEMON, not a concurrent edit: it
      // says nothing about what is committed, so it cannot be allowed to look like one. Raised before
      // anything is applied or persisted, so the whole save fails as one and the operator can retry.
      if (!sanitizeServerPreferences(saved)) {
        throw new Error("the daemon answered the save with an unreadable preferences payload");
      }
      // A preferences_update for this write (or a later write from another browser) may have arrived
      // while the PUT was in flight, so the response is applied only if it is not older; the
      // per-device font and unicode mode are independent of the daemon's revision entirely.
      //
      // That same answer is the close decision, and the two must not be separated: this returns TRUE
      // exactly when the revision the daemon just answered with is still the newest one this browser
      // knows of. Equal counts, and equal is the ordinary case — the daemon publishes every accepted
      // save to /events and over loopback that echo BEATS the PUT response, every single time. The
      // echo carries this very write's revision, so "equal" means "the newest thing we know is our own
      // commit" and the dialog closes. The check this replaced asked instead whether the mounted
      // form's revision had changed since submit; the echo always changes it, so Save never closed the
      // dialog at all. FALSE means a STRICTLY newer revision already landed: a write this browser did
      // not make, whose values are what the remounted form is now showing. Closing it would hide an
      // external change behind our own success, so the form stays open and the status line says so.
      const applied = applyServerPreferences(saved);
      persistTerminalFontSize(next.terminalFontSize);
      persistTerminalUnicode(next.terminalUnicode);
      setPrefs((current) => Object.assign({}, current, {
        terminalFontSize: next.terminalFontSize,
        terminalUnicode: next.terminalUnicode,
      }));
      if (!applied) {
        say("Preferences were saved, but newer settings arrived. Review the current values.");
        return;
      }
      // No await between the apply and this close: an interleaved delivery could otherwise turn a
      // decision already taken into a stale one.
      closeDialogFrom(submittedDialog);
      const current = serverPreferencesRef.current;
      say(current.basePath.length > 0
        ? "Grouping by " + current.basePath + " (level " + current.groupingLevel + ")."
        : "Grouping off — no base path set.");
    } catch (e) {
      // The submitting form owns the error only while it is still the mounted one. After a remount (or
      // a close-and-reopen) that instance is unmounted and its setError is a silent no-op, so a late
      // failure is routed to the status line instead of vanishing.
      if (dialogRef.current === submittedDialog && prefsRef.current.revision === revisionAtSubmit) throw e;
      say("Could not save preferences: " + errorMessage(e), true);
    } finally {
      prefsSaveInFlightRef.current = false;
    }
  }, [applyServerPreferences, closeDialogFrom, say]);

  /**
   * Open the ONE New-session dialog. An explicit directory (a group's "+") wins, then the selected
   * session's, then the base path.
   *
   * [taskRef] is what the task detail view's "Start session" passes:
   * the dialog pre-fills it, puts it in the submitted body, and `startSession` POSTs that body verbatim
   * — so a task-launched session goes through the same single `POST /api/v1/sessions` as every other
   * one. There is deliberately no second launch path, which is why this callback (and not a bespoke
   * one) is what `TaskDetail` is handed.
   */
  const openNewSession = useCallback((cwd, initialMode = "start", initialAgent = "", taskRef = null) => {
    const selected = sessionsRef.current.find((x) => x.id === activeRef.current);
    setDialog({
      kind: "new",
      cwd: cwd || (selected && selected.cwd) || prefsRef.current.basePath,
      initialMode: initialMode,
      initialAgent: initialAgent,
      taskRef: taskRef,
    });
  }, []);
  const openImportSession = useCallback(() => openNewSession(null, "import"), [openNewSession]);
  const openFreeTerminal = useCallback(
    () => openNewSession(null, "start", "shell"),
    [openNewSession],
  );
  /** `TaskDetail`'s "Start session": the ordinary dialog, pre-filled with the project cwd and the task. */
  const startSessionForTask = useCallback(
    (cwd, taskRef) => openNewSession(cwd, "start", "", taskRef),
    [openNewSession],
  );

  // --- task navigation (the palette's three task commands) ---------------------------------------

  const openBoard = useCallback(() => navigate(routePath({ screen: SCREEN_TASKS, id: null })), []);
  /**
   * The way back out of the board, and the other half of the palette's one `o` mnemonic.
   *
   * It names the selected session in the URL when there is one, rather than going to `/` the way the
   * board's own "Sessions" link does: leaving the board does not change WHICH session is selected, and a
   * bare `/` would leave the address bar describing a screen that is in fact showing a terminal — so a
   * reload, a bookmark or a shared link would land somewhere else than the operator left off. With no
   * selection at all `/` is exactly right, and that is what `routePath` answers for a null id.
   *
   * It deliberately does NOT go through `showSession`: the selection is unchanged, so re-running it
   * would bump the selection generation, cancel a pending reattach and rewrite the attachment for a
   * navigation that only swapped the screen.
   */
  const openSessions = useCallback(() => {
    const id = activeRef.current;
    navigate(routePath({ screen: SCREEN_SESSIONS, id: id }));
  }, []);
  /**
   * "New task" goes to the board and asks it to open its create form. It is a one-shot COUNTER rather
   * than a boolean: the palette can be used again while the board is already open, and a boolean would
   * need a reset round-trip to fire twice. `0` means "never asked", so `Board` opens nothing on mount.
   * The board owns the form because it owns the project selector — the browser has no session to infer
   * a project from, so a create must name one.
   */
  const [newTaskRequest, setNewTaskRequest] = useState(0);
  const newTask = useCallback(() => {
    navigate(routePath({ screen: SCREEN_TASKS, id: null }));
    setNewTaskRequest((n) => n + 1);
  }, []);
  /**
   * "New project" is the same one-shot counter for the board's other form. It has TWO callers now — the
   * palette's chordless command and the sidebar's "+ New project" — and that is exactly why it stayed a
   * counter routed through the board instead of becoming a dialog the sidebar owns: the form is a
   * sibling of the create-task one, both are `Board`'s, and a second copy in the sidebar would be a
   * second implementation of the same directory-completion field.
   *
   * From the session view it navigates first, which is the honest thing: a project is created to hold
   * tasks, and the board is where the created one is then selected.
   */
  const [newProjectRequest, setNewProjectRequest] = useState(0);
  const newProject = useCallback(() => {
    navigate(routePath({ screen: SCREEN_TASKS, id: null }));
    setNewProjectRequest((n) => n + 1);
  }, []);
  // Retired the moment the board goes away, and that is the whole reason it can be a counter at all.
  // `Board` compares against a ref it recreates on every MOUNT (starting from 0, because the palette
  // navigates and bumps in one event, so the board is usually mounting with the counter already at 1) —
  // so a counter that only ever grew re-opened the create form on every LATER visit: ⌘K w once, back to
  // a session, then a task badge tapped weeks later pops a New-task modal over the detail, unasked.
  // Resetting on the way out puts both sides back at 0, which is exactly "never asked".
  useEffect(() => {
    if (!onBoard) {
      setNewTaskRequest(0);
      setNewProjectRequest(0);
    }
  }, [onBoard]);
  /** "Open this session's task" — disabled upstream when the active session carries no `taskRef`. */
  const openSessionTask = useCallback(() => {
    const selected = sessionsRef.current.find((x) => x.id === activeRef.current);
    if (selected && selected.taskRef) navigate(taskPath(selected.taskRef));
  }, []);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);
  const toggleDrawer = useCallback(() => setDrawerOpen((open) => !open), []);
  const toggleSidebar = useCallback(() => setSidebarCollapsed((collapsed) => !collapsed), []);

  const closeDialog = useCallback(() => setDialog(null), []);
  const openPrefs = useCallback(() => setDialog({ kind: "prefs" }), []);
  const openHelp = useCallback(() => setDialog({ kind: "help" }), []);
  const openPhone = useCallback(() => setDialog({ kind: "phone" }), []);
  const openUpload = useCallback(() => {
    const selected = sessionsRef.current.find((session) => session.id === activeRef.current);
    if (selected) setDialog({ kind: "upload", session: selected });
  }, []);

  const interrupt = useCallback(() => controlSession("interrupt"), [controlSession]);
  const resume = useCallback(() => controlSession("resume"), [controlSession]);
  const stop = useCallback(() => controlSession("stop"), [controlSession]);
  const done = useCallback(() => controlSession("done"), [controlSession]);
  const restore = useCallback((id) => controlSession("undone", id), [controlSession]);
  const toggleShowDone = useCallback(() => setShowDone((shown) => !shown), []);
  const changePaletteMode = useCallback((mode) => {
    setPalette((current) => current ? { mode: mode } : current);
  }, []);

  const commands = buildCommands({
    sessions: sessions,
    activeSession: activeSession,
    attachedId: attachedId,
    pendingAction: pendingAction,
    // Which screen is on, so the registry can drop the session group the board makes unreachable and
    // point its one board mnemonic the other way. The app answers this because the route is app state.
    onBoard: onBoard,
    actions: {
      selectSession: selectSession,
      interrupt: interrupt,
      resume: resume,
      attach: attach,
      detach: detach,
      stop: stop,
      done: done,
      copyTmux: copyTmuxCommand,
      uploadFiles: openUpload,
      newSession: () => openNewSession(null),
      importSession: openImportSession,
      freeTerminal: openFreeTerminal,
      toggleShowDone: toggleShowDone,
      help: openHelp,
      phone: openPhone,
      preferences: openPrefs,
      // The three task commands Task 27 registers in `lib/commands.js`. They live here, in the one
      // `actions` object, because that file is the ONLY command registry and it owns no state of its
      // own — a command that reached for `history` or the session list itself would be a second one.
      openBoard: openBoard,
      openSessions: openSessions,
      newTask: newTask,
      newProject: newProject,
      openSessionTask: openSessionTask,
    },
  });

  // --- render ----------------------------------------------------------------------------------

  // `onBoard` is computed at the top of this component; the palette, the drawer scrim and every dialog
  // stay outside the branch below, because they belong to the shell rather than to either screen.

  return html`
    ${palette && html`
      <${CommandPalette}
        commands=${commands}
        mode=${palette.mode}
        onModeChange=${changePaletteMode}
        onClose=${closePalette}
      />`}
    ${/* The drawer's tap-outside dismissal — a real button so it is reachable by keyboard and by a screen
          reader too, and rendered only while the drawer is open so desktop never has it in the tree.

          It used to need a companion effect closing the drawer on the way to the board, because the
          board's branch unmounted the sidebar and left this scrim over a screen with no drawer behind
          it. The sidebar is shell furniture now — it is rendered on both screens — so the pair can never
          come apart, and the effect is gone rather than kept as a belt. */ ""}
    ${drawerOpen && html`
      <button type="button" class="drawer-scrim" aria-label="Close the sidebar"
              onClick=${closeDrawer}></button>`}
    ${/* One sidebar for the whole app, outside the screen branch below. Its body is what changes: the
          session list on the session view, the project list on the board. That is what makes the two
          links in its head reachable from anywhere, and it is why ⌘1, the mobile drawer and the status
          footer are written once instead of once per screen. */ ""}
    <${Sidebar}
      screen=${onBoard ? SCREEN_TASKS : SCREEN_SESSIONS}
      sessions=${sessions}
      tasks=${tasks}
      projects=${projects}
      projectId=${projectId}
      activeId=${activeId}
      prefs=${prefs}
      status=${status}
      currentVersion=${currentVersion}
      drawerOpen=${drawerOpen}
      collapsed=${sidebarCollapsed}
      showDone=${showDone}
      sessionsReady=${sessionsReady}
      onSelect=${selectSession}
      onSelectProject=${selectProject}
      onNewSession=${openNewSession}
      onNewProject=${newProject}
      onOpenPrefs=${openPrefs}
      onRestore=${restore}
      onCloseDrawer=${closeDrawer}
      onToggleShowDone=${toggleShowDone}
    />
    ${/* The one place the router decides what the page IS.

          `tasks` reaches BOTH sides of this branch on purpose. The board obviously needs it; the
          sidebar and the terminal header need it to render a session's task badge as a TITLE rather
          than a bare `local:42`, because a session row carries only the ref. Without the prop the
          badge could only ever render its unknown-task arm — which is the fallback for the brief
          window after a delete, not the normal case.

          At `/tasks/{ref}` the board and the detail render TOGETHER, as siblings: the board keeps
          highlighting the open card (`aria-current`) and its project selector stays live, which is the
          master-detail shape every card's `active` prop was written for. `style.css` sizes the pair —
          the detail is a bounded right-hand panel on a desktop and takes the whole screen on a phone,
          where two half-screens were unusable.

          The board's announcements need a renderer of their own: `status` has exactly ONE elsewhere,
          `#status-line` in the sidebar footer, and the sidebar is precisely what this branch unmounts.
          Without it a refused drag, a failed delete, a dependency refused for a cycle and every palette
          action run from here produced nothing at all — the click simply did not happen. */ ""}
    ${onBoard ? html`
      <${Board}
        tasks=${tasks}
        sessions=${sessions}
        route=${route}
        projects=${projects}
        projectId=${projectId}
        basePath=${prefs.basePath}
        newTaskRequest=${newTaskRequest}
        newProjectRequest=${newProjectRequest}
        drawerOpen=${drawerOpen}
        sidebarCollapsed=${sidebarCollapsed}
        onTaskRow=${applyTaskRow}
        onTaskRemoved=${applyTaskRemoved}
        onProjectCreated=${projectCreated}
        onToggleDrawer=${toggleDrawer}
        onToggleSidebar=${toggleSidebar}
        onOpenPalette=${openPalette}
        onAnnounce=${say}
      />
      ${route.screen === SCREEN_TASK && html`
        <${TaskDetail} taskRef=${route.id} entry=${openTaskEntry} sessions=${sessions}
                       onTaskRow=${applyTaskRow} onTaskRemoved=${applyTaskRemoved}
                       onStartSession=${startSessionForTask} onAnnounce=${say} />`}
      <p id="board-status" class=${"status-line board-status" + (status.error ? " error" : "")}
         role="status" aria-live="polite">${status.text}</p>
    ` : html`
      <${TerminalPane}
        session=${activeSession}
        tasks=${tasks}
        attachedId=${attachedId}
        terminalFontSize=${prefs.terminalFontSize}
        terminalUnicode=${prefs.terminalUnicode}
        hint=${hint}
        drawerOpen=${drawerOpen}
        sidebarCollapsed=${sidebarCollapsed}
        onToggleDrawer=${toggleDrawer}
        onToggleSidebar=${toggleSidebar}
        onOpenPalette=${openPalette}
        onTerminalClosed=${onTerminalClosed}
      />
    `}
    ${dialog && dialog.kind === "new" && html`
      <${NewSessionDialog} initialCwd=${dialog.cwd} initialMode=${dialog.initialMode}
                           initialAgent=${dialog.initialAgent}
                           initialTaskRef=${dialog.taskRef}
                           basePath=${prefs.basePath}
                           onStart=${startSession} onImport=${importSession} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "upload" && html`
      <${UploadFilesDialog} session=${dialog.session} onClose=${closeDialog} />`}
    ${/* Keyed on the committed server revision: the dialog seeds its draft from `prefs` once, at mount
          (useState), so a dialog REOPENED while a save's PUT was still in flight holds a pre-save draft —
          and closeDialogFrom rightly preserves it. When the commit lands (applyServerPreferences bumps
          the revision), the key remounts the dialog and re-seeds the draft from the committed values;
          without it, saving that stale draft would roll back the write under a fresh revision, which the
          revision guard cannot catch. The key alone is NOT a save guard — before the revision arrives,
          the reopened (or echo-remounted, busy reset) dialog could still land an overlapping stale PUT;
          prefsSaveInFlightRef in savePreferences refuses that second PUT until the first settles. And
          because a remount keeps the dialog OBJECT identical, savePreferences also captures the
          revision at submit — but only to route a LATE FAILURE away from an instance whose setError
          is already a no-op. Whether a success closes the form is decided by the revision the daemon
          answered with, not by this key: a form remounted by someone ELSE's write is not closed (its
          re-seeded draft is fresher), while a form remounted by the echo of this write is, because
          that echo IS this write. The belief this comment used to record — that the user's own save
          never remounts visibly, because applyServerPreferences and closeDialogFrom batch into one
          render — is simply wrong over loopback: the preferences_update for the write arrives BEFORE
          the PUT response, so the remount happens first, every time, and reading this key at
          completion made Save stop closing the dialog at all. */ ""}
    ${dialog && dialog.kind === "prefs" && html`
      <${PreferencesDialog} key=${prefs.revision} prefs=${prefs} sessions=${sessions}
                            onSave=${savePreferences} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "help" && html`<${HelpDialog} onClose=${closeDialog} />`}
    ${dialog && dialog.kind === "phone" && html`<${PhoneDialog} onClose=${closeDialog} />`}
  `;
}

// WebKit reports a manifest `display: standalone` home-screen app as fullscreen on current iOS, while
// older releases also expose only the vendor `navigator.standalone` signal. Resolve all three before the
// first render so the installed shell uses the physical `vh` height without a one-frame safe-area gap.
const appRoot = document.getElementById("app");
const installedApp =
  window.matchMedia("(display-mode: standalone)").matches ||
  window.matchMedia("(display-mode: fullscreen)").matches ||
  window.navigator.standalone === true;
appRoot.classList.toggle("installed-app", installedApp);
render(html`<${App} />`, appRoot);
