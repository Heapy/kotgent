/*
 * The kanban board: four columns (todo / in_progress / review / done) over ONE selected project.
 *
 * There is deliberately no "all projects" mode. `position` is a project-wide gap-based rank, so a
 * combined view would have to invent an ordering across projects that no move could then express — and
 * the one thing the board is for is ordering a backlog.
 *
 * Props `app.js` passes, which are fixed:
 *   tasks           BacklogEntryDto[] across every project, already merged newest-rev-wins. Filter by
 *                   the selected project here; the app does not know which one is selected.
 *   sessions        SessionDto[] — the card's session dots come from `session.taskRef`, never a fetch.
 *   route           { screen, id } from `lib/router.js`.
 *   basePath        the Preferences base path, the same value the New-session dialog is handed. It is
 *                   the New-project form's starting directory and the base its completion resolves a
 *                   relative name against — a project lives under the same tree a session does, so
 *                   there is no second place to configure it.
 *   newTaskRequest  a monotonically increasing counter. It increments when the palette's "new task"
 *                   command fires; `0` means "never asked". The create form opens when it CHANGES (an
 *                   effect keyed on it), not when it is truthy — the same command can fire twice while
 *                   the board is already open, and a boolean would need a reset round-trip to do that.
 *                   The comparison starts from `0`, not from the mounted value, because the palette
 *                   NAVIGATES and bumps in one event: the board is usually mounting with the counter
 *                   already at 1, and that mount is the request.
 *   onTaskRow       (BacklogEntryDto) → merge a row into `app.js`'s one task list, newest-rev-wins. It is
 *                   the SAME merge the socket's frames go through, so it is not a second path into the
 *                   list — just a second source for it.
 *   onTaskRemoved   (ref) → drop a row from that list; the task is gone on the daemon.
 *   onAnnounce      (text, isError) — the existing announcement channel; every rejected mutation goes
 *                   through it rather than failing silently.
 *
 * ## What the board never does
 * It does not fetch tasks. The `/events` socket sends `tasks_snapshot` on connect and a per-row frame
 * for every later change, so a created, moved, deleted or re-blocked card arrives the same way in every
 * connected tab — including this one.
 *
 * ## What it does with a write's own answer
 * It merges it. Every task write answers with the committed `BacklogEntryDto` carrying its `rev`, and
 * handing that to `onTaskRow` is the same newest-rev-wins merge the frame would do — an older answer
 * simply loses to the frame, and a frame that has not arrived yet loses to nothing. Discarding it was
 * a real hole rather than a purity: while the events socket is down or reconnecting REST still works,
 * and a create, a move or a delete then left the whole board unchanged with no error to show for it.
 *
 * ## The one thing it does fetch
 * `GET /projects`, because the selector needs project NAMES and a `BacklogEntryDto` carries only the
 * uuid. It is re-read after a project is created, and never polled.
 *
 * ## Class names
 * Every class here comes from the plan's "Board CSS vocabulary" — Task 28 writes `style.css` at the same
 * time as this file and has no other way to learn what was emitted. The generic `button` / `field` /
 * `dialog-*` / `path-*` classes inside the two forms are existing ones, reused rather than invented, and
 * the modal itself is the shared [Dialog] wrapper so the board inherits Esc, the focus trap and both
 * light-dismiss gestures instead of re-implementing them.
 */

import { html } from "htm/preact";
import { useCallback, useEffect, useMemo, useRef, useState } from "preact/hooks";
import { apiRequest, errorMessage } from "../lib/api.js";
import { joinPath, normalizePath } from "../lib/paths.js";
import { SCREEN_SESSIONS, navigate, routePath, sessionPath, taskPath } from "../lib/router.js";
import {
  createProject,
  createTask,
  deleteTask,
  fetchProjects,
  moveTask,
  patchTask,
} from "../lib/tasks.js";
import { Dialog } from "./dialogs.js";
import { TaskCard } from "./TaskCard.js";

/** The four workflow states, in board order. The `state` values are `io.kotgent.task.TaskState` names. */
export const BOARD_COLUMNS = [
  { state: "todo", label: "To do" },
  { state: "in_progress", label: "In progress" },
  { state: "review", label: "Review" },
  { state: "done", label: "Done" },
];

/**
 * How many `done` cards the column shows before the "show all" toggle. Done is unbounded and grows
 * forever, while the useful part of it is "what closed recently" — the tail of the ordered column.
 */
export const DONE_VISIBLE_LIMIT = 10;

/** Travel that turns a press on the handle into a drag. Below it the press is still a click. */
const DRAG_SLOP_PX = 8;

/** The phone breakpoint, the same one `style.css` uses for its single-column layout. */
const PHONE_QUERY = "(max-width: 720px)";

/** Where the "Sessions" link goes — the router's own spelling of the session view, not a literal. */
const SESSIONS_PATH = routePath({ screen: SCREEN_SESSIONS, id: null });

/** Debounce before a keystroke in the project-path field asks the daemon to complete it. */
const DIRECTORY_COMPLETION_DELAY_MS = 150;

/**
 * The cap `POST /projects` really enforces — `PROJECT_NAME_MAX_LENGTH` in `src/task/ProjectFile.kt`, the
 * one number both the route and the `.kotgent.json` parser measure a name against. The field used to say
 * 80, which is not a stricter client-side rule but a shorter one than the daemon's: an `<input>` maxlength
 * silently REFUSES the 81st keystroke, so a name the API would have accepted could not be typed at all.
 */
const PROJECT_NAME_MAX_LENGTH = 100;

function phoneNow() {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" &&
    window.matchMedia(PHONE_QUERY).matches;
}

/**
 * The rows of a list response, whichever shape the route answers with. `GET /projects` is a bare JSON
 * array today; accepting `{ projects: [...] }` too costs one line and keeps a board that meets an older
 * or newer daemon showing an empty selector rather than throwing inside a render.
 */
function rowsOf(response, key) {
  if (Array.isArray(response)) return response;
  if (response && Array.isArray(response[key])) return response[key];
  return [];
}

/**
 * Which column the pointer is over and which card it would land above, read from the DOM at the
 * pointer's position rather than from React state: the columns are the authority on their own geometry,
 * and a captured pointer's coordinates are all the gesture has.
 *
 * [draggedRef] is skipped so a card never measures itself; the answer is `beforeRef = null` for "below
 * every card in this column".
 */
export function dropTargetAt(x, y, draggedRef) {
  if (typeof document === "undefined" || typeof document.elementFromPoint !== "function") return null;
  const at = document.elementFromPoint(x, y);
  const column = at && typeof at.closest === "function" ? at.closest(".board-column") : null;
  if (!column) return null;
  const state = column.getAttribute("data-state");
  if (!state) return null;
  const cards = Array.prototype.slice.call(column.querySelectorAll(".task-card"));
  for (let index = 0; index < cards.length; index += 1) {
    const card = cards[index];
    const ref = card.getAttribute("data-ref");
    if (!ref || ref === draggedRef) continue;
    const rect = card.getBoundingClientRect();
    if (y < rect.top + rect.height / 2) return { state: state, beforeRef: ref };
  }
  return { state: state, beforeRef: null };
}

/**
 * The two requests a drop is worth, given where the card is now and where it was dropped.
 *
 * `/move` takes no state and `PATCH` takes no position, so a drop that changes both is two requests —
 * the `PATCH` first, then the `move`. A drop that changes only the column is ONE request, which is why
 * this compares against the position the card would land at after a bare `PATCH` ([natural]) rather than
 * assuming a cross-column drop always needs a move as well.
 *
 * Returns `{ state, move }`, either of which may be null, or null when the drop changes nothing at all.
 */
export function dropPlan(entry, target, columnEntries) {
  if (!entry || !target || !target.state) return null;
  const others = columnEntries.filter((row) => row.ref !== entry.ref);
  const desired = target.beforeRef
    ? others.findIndex((row) => row.ref === target.beforeRef)
    : others.length;
  // The named neighbour vanished between the last frame and the release: refuse rather than guess.
  if (desired < 0) return null;

  const stateChanged = target.state !== entry.state;
  let current;
  if (stateChanged) {
    // Where a bare PATCH would leave it: `position` is project-wide and a transition does not touch it.
    const at = others.findIndex((row) => row.position > entry.position);
    current = at < 0 ? others.length : at;
  } else {
    // Its index among `others` is its index in the column: removing itself shifts only what followed it.
    current = columnEntries.findIndex((row) => row.ref === entry.ref);
  }
  const needsMove = desired !== current;
  if (!stateChanged && !needsMove) return null;

  let move = null;
  if (needsMove) {
    if (target.beforeRef) move = { before: target.beforeRef };
    else if (others.length > 0) move = { after: others[others.length - 1].ref };
    // An empty column has no neighbour to name, so the only expressible landing is the backlog's end.
    else move = { bottom: true };
  }
  return { state: stateChanged ? target.state : null, move: move };
}

export function Board({
  tasks = [],
  sessions = [],
  route = null,
  basePath = "",
  newTaskRequest = 0,
  onTaskRow,
  onTaskRemoved,
  onAnnounce,
}) {
  const [projects, setProjects] = useState([]);
  const [projectId, setProjectId] = useState(null);
  const [form, setForm] = useState(null);          // null | "task" | "project"
  const [showAllDone, setShowAllDone] = useState(false);
  const [phone, setPhone] = useState(phoneNow);
  const [activeColumn, setActiveColumn] = useState(BOARD_COLUMNS[0].state);
  const [draggingRef, setDraggingRef] = useState(null);
  const [dropTarget, setDropTarget] = useState(null);

  const say = useCallback((text, error) => {
    if (onAnnounce) onAnnounce(text, error);
  }, [onAnnounce]);

  /** Merge the committed row a write answered with into the app's list — see the header. */
  const publishRow = useCallback((row) => {
    if (row && row.ref && onTaskRow) onTaskRow(row);
  }, [onTaskRow]);

  // --- projects ------------------------------------------------------------------------------------

  const reloadProjects = useCallback(async () => {
    try {
      const rows = rowsOf(await fetchProjects(), "projects");
      setProjects(rows);
      return rows;
    } catch (e) {
      say("Could not load projects: " + errorMessage(e), true);
      return null;
    }
  }, [say]);

  useEffect(() => { reloadProjects(); }, [reloadProjects]);

  // Exactly one project is selected at all times once any exists; a selection naming a project that is
  // no longer listed falls back to the first rather than showing an empty board with a live selector.
  useEffect(() => {
    if (projects.length === 0) return;
    setProjectId((current) =>
      current && projects.some((project) => project.id === current) ? current : projects[0].id);
  }, [projects]);

  // Opening /tasks/{ref} selects that task's project — once per ref, so a later manual choice sticks
  // even while the socket keeps patching rows. A ref that is not in the list yet retries on the next
  // frame, which is what makes a deep link work when the snapshot has not landed at mount.
  const appliedRouteRef = useRef(null);
  const routeId = route && route.id;
  useEffect(() => {
    if (!routeId) { appliedRouteRef.current = null; return; }
    if (appliedRouteRef.current === routeId) return;
    const entry = tasks.find((task) => task.ref === routeId);
    if (!entry) return;
    appliedRouteRef.current = routeId;
    setProjectId(entry.project);
  }, [routeId, tasks]);

  // --- layout --------------------------------------------------------------------------------------

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return undefined;
    const query = window.matchMedia(PHONE_QUERY);
    const apply = () => setPhone(query.matches);
    apply();
    // Safari below 14 has no addEventListener on a MediaQueryList; the deprecated form is the fallback.
    if (query.addEventListener) query.addEventListener("change", apply);
    else query.addListener(apply);
    return () => {
      if (query.removeEventListener) query.removeEventListener("change", apply);
      else query.removeListener(apply);
    };
  }, []);

  // The palette's "new task" is a one-shot counter, so the effect compares against what it last acted
  // on. Starting from 0 (not from the mounted value) is what makes the navigate-and-bump case open the
  // form: the board mounts with the counter already incremented.
  const servedRequestRef = useRef(0);
  useEffect(() => {
    if (newTaskRequest === servedRequestRef.current) return;
    servedRequestRef.current = newTaskRequest;
    setForm("task");
  }, [newTaskRequest]);

  // --- the board's rows ----------------------------------------------------------------------------

  const entries = useMemo(() => tasks
    .filter((task) => task.project === projectId)
    .slice()
    .sort((a, b) => (a.position - b.position) || (a.createdAt - b.createdAt) ||
      (a.ref < b.ref ? -1 : a.ref > b.ref ? 1 : 0)), [tasks, projectId]);
  const entriesRef = useRef(entries);
  entriesRef.current = entries;

  /** One walk of the session list for the whole board, keyed by the ref each session points at. */
  const sessionsByTask = useMemo(() => {
    const map = new Map();
    for (const session of sessions) {
      if (!session.taskRef) continue;
      const held = map.get(session.taskRef);
      if (held) held.push(session);
      else map.set(session.taskRef, [session]);
    }
    return map;
  }, [sessions]);

  const columns = useMemo(() => BOARD_COLUMNS.map((column) => ({
    state: column.state,
    label: column.label,
    entries: entries.filter((entry) => entry.state === column.state),
  })), [entries]);

  // --- mutations -----------------------------------------------------------------------------------

  const openTask = useCallback((ref) => navigate(taskPath(ref)), []);
  const openSession = useCallback((id) => navigate(sessionPath(id)), []);
  /** The way out of this screen without picking a session — see the header row's own comment. */
  const leaveForSessions = useCallback((event) => {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (event.button !== undefined && event.button !== 0) return;
    event.preventDefault();
    navigate(SESSIONS_PATH);
  }, []);

  const applyDrop = useCallback(async (ref, target) => {
    const entry = entriesRef.current.find((row) => row.ref === ref);
    if (!entry || !target) return;
    const column = entriesRef.current.filter((row) => row.state === target.state);
    const plan = dropPlan(entry, target, column);
    if (!plan) return;
    try {
      // The PATCH first: `/move` takes no state and PATCH takes no position, so the state lands before
      // the rank is resolved against the column the card is joining. Each answer is merged as it comes,
      // so a failing second request still leaves the first one's committed row on the board.
      if (plan.state) publishRow(await patchTask(ref, { state: plan.state }));
      if (plan.move) publishRow(await moveTask(ref, plan.move));
    } catch (e) {
      say("Could not move " + ref + ": " + errorMessage(e), true);
    }
  }, [publishRow, say]);

  const moveToState = useCallback(async (entry, state) => {
    try {
      publishRow(await patchTask(entry.ref, { state: state }));
    } catch (e) {
      say("Could not move " + entry.ref + ": " + errorMessage(e), true);
    }
  }, [publishRow, say]);

  const moveWithinColumn = useCallback(async (entry, offset) => {
    const column = entriesRef.current.filter((row) => row.state === entry.state);
    const index = column.findIndex((row) => row.ref === entry.ref);
    const neighbour = column[index + offset];
    if (index < 0 || !neighbour) return;
    const target = offset < 0 ? { before: neighbour.ref } : { after: neighbour.ref };
    try {
      publishRow(await moveTask(entry.ref, target));
    } catch (e) {
      say("Could not move " + entry.ref + ": " + errorMessage(e), true);
    }
  }, [publishRow, say]);

  const moveUp = useCallback((entry) => moveWithinColumn(entry, -1), [moveWithinColumn]);
  const moveDown = useCallback((entry) => moveWithinColumn(entry, 1), [moveWithinColumn]);

  const removeTaskCard = useCallback(async (entry) => {
    const label = entry.title ? entry.title + " (" + entry.ref + ")" : entry.ref;
    if (typeof window !== "undefined" && typeof window.confirm === "function" &&
      !window.confirm("Delete " + label + "? Its dependencies and activity go with it.")) return;
    try {
      await deleteTask(entry.ref);
      // A delete answers `ok` and no row, so the removal is the answer: drop it from the list here.
      if (onTaskRemoved) onTaskRemoved(entry.ref);
      say("Deleted " + entry.ref + ".");
    } catch (e) {
      say("Could not delete " + entry.ref + ": " + errorMessage(e), true);
    }
  }, [onTaskRemoved, say]);

  const submitTask = useCallback(async (title, body) => {
    const created = await createTask(projectId, title, body);
    publishRow(created);
    setForm(null);
    say("Created " + ((created && created.ref) || "the task") + ".");
  }, [projectId, publishRow, say]);

  const submitProject = useCallback(async (path, name) => {
    const created = await createProject(path, name);
    setForm(null);
    const rows = await reloadProjects();
    if (created && created.id && rows && rows.some((project) => project.id === created.id)) {
      setProjectId(created.id);
    }
    say("Project " + ((created && created.name) || path) + " is ready.");
  }, [reloadProjects, say]);

  // --- dragging (desktop; the phone has the menu's move actions instead) ---------------------------

  const gestureRef = useRef(null);

  const dragPointerDown = useCallback((event, entry) => {
    if (event.isPrimary === false) return;
    if (event.button !== undefined && event.button !== 0) return;
    gestureRef.current = {
      pointerId: event.pointerId,
      ref: entry.ref,
      startX: event.clientX,
      startY: event.clientY,
      claimed: false,
      element: event.currentTarget,
    };
  }, []);

  const dragPointerMove = useCallback((event) => {
    const gesture = gestureRef.current;
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    if (!gesture.claimed) {
      // Below the slop the press is still a press: a click on the handle must not become a drag.
      if (Math.abs(event.clientX - gesture.startX) < DRAG_SLOP_PX &&
        Math.abs(event.clientY - gesture.startY) < DRAG_SLOP_PX) return;
      gesture.claimed = true;
      // Capture retargets every later move to the handle, so the gesture survives the re-render its own
      // drop-target highlight causes and the pointer leaving the card it started on.
      if (gesture.element && gesture.element.setPointerCapture) {
        gesture.element.setPointerCapture(event.pointerId);
      }
      setDraggingRef(gesture.ref);
    }
    if (event.cancelable) event.preventDefault();
    setDropTarget(dropTargetAt(event.clientX, event.clientY, gesture.ref));
  }, []);

  const endGesture = useCallback((gesture, pointerId) => {
    gestureRef.current = null;
    if (gesture.element && gesture.element.hasPointerCapture &&
      gesture.element.hasPointerCapture(pointerId)) {
      gesture.element.releasePointerCapture(pointerId);
    }
    setDraggingRef(null);
    setDropTarget(null);
  }, []);

  const dragPointerUp = useCallback((event) => {
    const gesture = gestureRef.current;
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    const claimed = gesture.claimed;
    // The RELEASE position is the drop, not the last move: a browser need not precede `pointerup` with
    // a `pointermove`, and a swipe that reversed and lifted would otherwise drop where it used to be.
    const target = claimed ? dropTargetAt(event.clientX, event.clientY, gesture.ref) : null;
    endGesture(gesture, event.pointerId);
    if (claimed && target) applyDrop(gesture.ref, target);
  }, [applyDrop, endGesture]);

  const dragPointerCancel = useCallback((event) => {
    const gesture = gestureRef.current;
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    // A gesture the platform took away is not a drop: fail toward leaving the backlog alone.
    endGesture(gesture, event.pointerId);
  }, [endGesture]);

  // --- render --------------------------------------------------------------------------------------

  const shownColumns = phone
    ? columns.filter((column) => column.state === activeColumn)
    : columns;
  const moveTargets = phone
    ? BOARD_COLUMNS.filter((column) => column.state !== activeColumn)
    : [];

  const renderColumn = (column) => {
    const capped = column.state === "done" && !showAllDone &&
      column.entries.length > DONE_VISIBLE_LIMIT;
    const visible = capped
      ? column.entries.slice(column.entries.length - DONE_VISIBLE_LIMIT)
      : column.entries;
    // A capped column still has cards above the first visible one, and `moveWithinColumn` resolves the
    // neighbour against the WHOLE column — so the menu's up/down must be judged there too, or the first
    // visible card of a capped `done` would refuse a move that is perfectly expressible.
    const hidden = column.entries.length - visible.length;
    const over = Boolean(draggingRef && dropTarget && dropTarget.state === column.state);
    return html`
      <section key=${column.state} class=${"board-column" + (over ? " board-drop-target" : "")}
               data-state=${column.state} aria-label=${column.label}>
        <header class="board-column-head">
          <h2>${column.label}</h2>
          <span>${column.entries.length}</span>
        </header>
        ${/* The cards are direct children of the column: an intermediate <ul> would need a class of
              its own to lose the UA's bullets, and the shared vocabulary has no name for one. */ ""}
        ${visible.map((entry, index) => html`
            <${TaskCard}
              key=${entry.ref}
              entry=${entry}
              sessions=${sessionsByTask.get(entry.ref) || []}
              active=${routeId === entry.ref}
              dragging=${draggingRef === entry.ref}
              moveTargets=${moveTargets}
              canMoveUp=${hidden + index > 0}
              canMoveDown=${hidden + index < column.entries.length - 1}
              onOpen=${openTask}
              onOpenSession=${openSession}
              onDelete=${removeTaskCard}
              onMoveState=${moveToState}
              onMoveUp=${moveUp}
              onMoveDown=${moveDown}
              onDragPointerDown=${dragPointerDown}
              onDragPointerMove=${dragPointerMove}
              onDragPointerUp=${dragPointerUp}
              onDragPointerCancel=${dragPointerCancel}
            />`)}
        ${column.state === "done" && column.entries.length > DONE_VISIBLE_LIMIT && html`
          <button type="button" class="button button-quiet board-show-all-done"
                  onClick=${() => setShowAllDone((shown) => !shown)}>
            ${showAllDone
              ? "Show the last " + DONE_VISIBLE_LIMIT
              : "Show all " + column.entries.length}
          </button>`}
      </section>`;
  };

  return html`
    <main class="board" aria-label="Task board">
      <header class="board-head">
        ${/* The only in-app way off this screen that does not require picking a session. On a desktop
              the browser's Back button covers it, but an installed PWA draws no browser chrome at all,
              and the two shell controls that could — the drawer opener and the palette opener — both
              live in the terminal header, which this screen unmounts. A real link, so a modified click
              still opens a tab; the ordinary one goes to the router. */ ""}
        <a id="go-to-sessions" class="button button-quiet" href=${SESSIONS_PATH}
           title="Back to the sessions" onClick=${leaveForSessions}>Sessions</a>
        <select class="board-project" aria-label="Project" value=${projectId || ""}
                disabled=${projects.length === 0}
                onChange=${(event) => setProjectId(event.target.value)}>
          ${projects.length === 0
            ? html`<option value="">No projects yet</option>`
            : projects.map((project) => html`
              <option key=${project.id} value=${project.id} title=${project.path || ""}>
                ${project.name || project.id}
              </option>`)}
        </select>
        ${/* The vocabulary class carries Task 28's rules; the generic `button` beneath it is a sane
              default that those rules override, because they are written later in the same file. */ ""}
        <button type="button" class="button board-new-task" disabled=${!projectId}
                onClick=${() => setForm("task")}>New task</button>
        <button type="button" class="button board-new-project"
                onClick=${() => setForm("project")}>New project</button>
      </header>

      ${/* The phone shows ONE column, so the switcher is how the other three are reachable at all.
            Above the breakpoint all four are on screen and this is not rendered. */ ""}
      ${phone && html`
        <nav class="board-column-switch" aria-label="Column">
          ${columns.map((column) => html`
            <button key=${column.state} type="button" class="button" data-state=${column.state}
                    aria-pressed=${column.state === activeColumn ? "true" : "false"}
                    onClick=${() => setActiveColumn(column.state)}>
              ${column.label} ${column.entries.length}
            </button>`)}
        </nav>`}

      <div class="board-columns">
        ${shownColumns.map(renderColumn)}
      </div>

      ${form === "task" && html`
        <${NewTaskForm} project=${projects.find((project) => project.id === projectId)}
                        onCreate=${submitTask} onClose=${() => setForm(null)} />`}
      ${form === "project" && html`
        <${NewProjectForm} basePath=${basePath} onCreate=${submitProject}
                           onClose=${() => setForm(null)} />`}
    </main>
  `;
}

/**
 * Create a task in the SELECTED project. The board is the one client with no session, so the project id
 * is explicit in the body — every other caller lets the daemon resolve it from the calling pane.
 */
function NewTaskForm({ project, onCreate, onClose }) {
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const titleRef = useRef(null);

  useEffect(() => { if (titleRef.current) titleRef.current.focus(); }, []);

  const submit = async (event) => {
    event.preventDefault();
    const trimmed = title.trim();
    if (!trimmed) {
      // Native `required` catches an empty field but not a whitespace-only one.
      setError("Give the task a title.");
      if (titleRef.current) titleRef.current.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onCreate(trimmed, body);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return html`
    <${Dialog} id="new-task-dialog" labelledBy="new-task-title" lightDismiss=${!busy} onClose=${onClose}>
      <form id="new-task-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="new-task-title">New task</h2>
            <p>${project ? "In " + (project.name || project.id) : "Pick a project first"}</p>
          </div>
          <button class="icon-button" type="button" aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <label class="field">
          <span>Title</span>
          <input id="new-task-title-input" type="text" required maxlength="200" ref=${titleRef}
                 value=${title} disabled=${busy} onInput=${(e) => setTitle(e.target.value)} />
        </label>

        <label class="field">
          <span>Description <small>optional</small></span>
          <textarea id="new-task-body" rows="5" value=${body} disabled=${busy}
                    onInput=${(e) => setBody(e.target.value)}></textarea>
        </label>

        ${error && html`<p class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button class="button button-quiet" type="button" onClick=${onClose}>Cancel</button>
          <button class="button button-primary" type="submit" disabled=${busy}>
            ${busy ? "Creating…" : "Create task"}
          </button>
        </div>
      </form>
    <//>
  `;
}

/**
 * The absolute directory a typed value names, given the Preferences base path: an absolute input is
 * itself, a relative one hangs off the base. This is deliberately the SAME join
 * `POST /directories/complete` applies on the daemon (`completionTarget`), so a name the suggestion list
 * offered is the name the create receives — a form whose completion resolved against the base while its
 * submit did not would list a real directory and then post a path the daemon refuses as relative.
 * With no base path configured a relative value stays relative and the submit refuses it below.
 */
export function resolveProjectPath(typed, basePath) {
  const input = String(typed || "").trim();
  if (input.charAt(0) === "/") return normalizePath(input);
  const base = normalizePath(basePath);
  if (!input || base.charAt(0) !== "/") return input;
  return normalizePath(joinPath(base, [input]));
}

/**
 * Adopt a directory as a project: the daemon writes `.kotgent.json` there and an existing file always
 * wins, so pointing this at a checkout that is already a project simply learns its uuid.
 *
 * The path field completes against the daemon's filesystem — a phone must complete paths on the Mac
 * that will hold the file, not on the phone. This is the same `POST /directories/complete` endpoint and
 * the same base path the New-session dialog uses: a project is created in the same tree sessions are
 * started in, so the field opens ON the base path and completes relative input against it rather than
 * making the operator retype an absolute prefix they already configured once. The picker is inlined
 * here rather than shared, because the dialog owns its one caller and this form has no import mode.
 */
function NewProjectForm({ basePath = "", onCreate, onClose }) {
  const base = normalizePath(basePath);
  // Seeded once, at mount: the form is mounted fresh for each open, so there is no draft to drift.
  const [path, setPath] = useState(base.charAt(0) === "/" ? base : "");
  const [name, setName] = useState("");
  const [query, setQuery] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const [focused, setFocused] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const pathRef = useRef(null);

  useEffect(() => { if (pathRef.current) pathRef.current.focus(); }, []);

  useEffect(() => {
    if (query === null) return undefined;
    const typed = query.trim();
    setSuggestions([]);
    setActiveSuggestion(-1);
    // A relative name completes only when there is a base path to hang it off — the endpoint answers
    // 400 for a relative input with no absolute base, so asking would be a round trip for an error.
    if (!typed || (typed.charAt(0) !== "/" && base.charAt(0) !== "/")) return undefined;

    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiRequest("/directories/complete", {
        method: "POST",
        signal: controller.signal,
        body: JSON.stringify({ basePath: base || null, input: typed }),
      })
        .then((response) => {
          if (controller.signal.aborted) return;
          setSuggestions(response && Array.isArray(response.paths)
            ? response.paths.filter((candidate) => typeof candidate === "string")
            : []);
        })
        .catch((e) => {
          if (!controller.signal.aborted && (!e || e.name !== "AbortError")) setSuggestions([]);
        });
    }, DIRECTORY_COMPLETION_DELAY_MS);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, base]);

  const choose = (candidate) => {
    setPath(candidate);
    setQuery(null); // selecting is not another typing event: keep the just-closed list closed
    setSuggestions([]);
    setActiveSuggestion(-1);
    if (pathRef.current) pathRef.current.focus();
  };

  const pathKeyDown = (event) => {
    if (!focused || suggestions.length === 0) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveSuggestion((index) => (index + 1) % suggestions.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveSuggestion((index) => (index <= 0 ? suggestions.length - 1 : index - 1));
    } else if (event.key === "Enter" && activeSuggestion >= 0) {
      event.preventDefault();
      choose(suggestions[activeSuggestion]);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setQuery(null);
      setSuggestions([]);
      setActiveSuggestion(-1);
    }
  };

  const submit = async (event) => {
    event.preventDefault();
    const typed = resolveProjectPath(path, base);
    if (typed.charAt(0) !== "/") {
      // Only reachable with no base path configured: with one, every non-empty value resolves above.
      setError("Give an absolute path to an existing directory.");
      if (pathRef.current) pathRef.current.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onCreate(typed, name.trim() || null);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return html`
    <${Dialog} id="new-project-dialog" labelledBy="new-project-title" lightDismiss=${!busy}
               onClose=${onClose}>
      <form id="new-project-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="new-project-title">New project</h2>
            <p>Writes <code>.kotgent.json</code> there. An existing one is adopted, never overwritten.</p>
          </div>
          <button class="icon-button" type="button" aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <div class="field">
          <label for="new-project-path">Directory</label>
          <div class="path-autocomplete">
            <input id="new-project-path" type="text" required spellcheck="false" autocomplete="off"
                   role="combobox" aria-autocomplete="list"
                   aria-expanded=${focused && suggestions.length > 0 ? "true" : "false"}
                   aria-controls="new-project-path-options"
                   aria-activedescendant=${activeSuggestion >= 0
                     ? "new-project-path-option-" + activeSuggestion
                     : null}
                   placeholder=${base.charAt(0) === "/" ? base + "/name" : "/path/to/project"}
                   ref=${pathRef} value=${path} disabled=${busy}
                   onInput=${(e) => { setPath(e.target.value); setQuery(e.target.value); }}
                   onKeyDown=${pathKeyDown}
                   onFocus=${() => setFocused(true)} onBlur=${() => setFocused(false)} />
            ${focused && suggestions.length > 0 && html`
              <ul id="new-project-path-options" class="path-suggestions" role="listbox">
                ${suggestions.map((candidate, index) => html`
                  <li id=${"new-project-path-option-" + index} key=${candidate} role="option"
                      class=${"path-suggestion" + (index === activeSuggestion ? " active" : "")}
                      aria-selected=${index === activeSuggestion ? "true" : "false"}
                      title=${candidate}
                      onMouseDown=${(event) => event.preventDefault()}
                      onMouseEnter=${() => setActiveSuggestion(index)}
                      onClick=${() => choose(candidate)}>${candidate}</li>`)}
              </ul>`}
          </div>
          ${base.charAt(0) === "/" && html`
            <small id="new-project-base-hint" class="field-hint">
              Starts at the Preferences base path ${base}; a name without a leading / is resolved under it.
            </small>`}
        </div>

        <label class="field">
          <span>Name <small>optional, defaults to the directory name</small></span>
          <input id="new-project-name" type="text" maxlength=${PROJECT_NAME_MAX_LENGTH}
                 value=${name} disabled=${busy}
                 onInput=${(e) => setName(e.target.value)} />
        </label>

        ${error && html`<p class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button class="button button-quiet" type="button" onClick=${onClose}>Cancel</button>
          <button class="button button-primary" type="submit" disabled=${busy}>
            ${busy ? "Creating…" : "Create project"}
          </button>
        </div>
      </form>
    <//>
  `;
}
