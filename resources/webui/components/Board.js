/* Positions are project-scoped, so the board intentionally has no cross-project ordering. Task rows
 * arrive from events; write responses merge into the same revision-ordered app state. */

import { html } from "htm/preact";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { apiRequest, errorMessage } from "../lib/api.js";
import { joinPath, normalizePath } from "../lib/paths.js";
import { navigate, sessionPath, taskPath } from "../lib/router.js";
import {
  createProject,
  createTask,
  moveTask,
  patchTask,
} from "../lib/tasks.js";
import { Dialog } from "./dialogs.js";
import { TaskCard } from "./TaskCard.js";

/** Mirrors `io.kotgent.task.TaskState` in board order. */
export const BOARD_COLUMNS = [
  { state: "todo", label: "To do" },
  { state: "in_progress", label: "In progress" },
  { state: "review", label: "Review" },
  { state: "done", label: "Done" },
];

/** Show only the recent tail of the unbounded done column by default. */
export const DONE_VISIBLE_LIMIT = 10;

const DRAG_SLOP_PX = 8;

/** Must match `.task-card`'s margin-bottom in style.css. */
const CARD_GAP_PX = 8;

/** Must match the single-column breakpoint in style.css. */
const PHONE_QUERY = "(max-width: 720px)";

const DIRECTORY_COMPLETION_DELAY_MS = 150;

/** Must match PROJECT_NAME_MAX_LENGTH in ProjectFile.kt. */
const PROJECT_NAME_MAX_LENGTH = 100;

const EMPTY_DRAG_LAYOUT = { shifts: new Map(), slot: null };

function phoneNow() {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" &&
    window.matchMedia(PHONE_QUERY).matches;
}

/** Resolve a captured pointer against live DOM geometry, excluding the dragged card itself. */
export function dropTargetAt(x, y, draggedRef) {
  if (typeof document === "undefined") return null;
  const columns = Array.prototype.slice.call(document.querySelectorAll(".board-column"));
  for (const column of columns) {
    const rect = column.getBoundingClientRect();
    if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) continue;
    const state = column.getAttribute("data-state");
    if (!state) return null;
    const contentY = y - rect.top + column.scrollTop;
    const cards = Array.prototype.slice.call(column.querySelectorAll(".task-card"));
    for (const card of cards) {
      const ref = card.getAttribute("data-ref");
      if (!ref || ref === draggedRef) continue;
      if (contentY < card.offsetTop + card.offsetHeight / 2) {
        return { state: state, beforeRef: ref };
      }
    }
    return { state: state, beforeRef: null };
  }
  return null;
}

function previewShifts(cardsByState, sourceState, draggedRef, target, slotSize) {
  const shifts = new Map();
  if (!target) return shifts;

  const source = cardsByState.get(sourceState) || [];
  const draggedIndex = source.indexOf(draggedRef);
  if (draggedIndex < 0) return shifts;

  const destination = (cardsByState.get(target.state) || [])
    .filter((ref) => ref !== draggedRef);
  const desiredIndex = target.beforeRef
    ? destination.indexOf(target.beforeRef)
    : destination.length;
  if (desiredIndex < 0) return shifts;

  const add = (ref, offset) => shifts.set(ref, (shifts.get(ref) || 0) + offset);
  for (let index = draggedIndex + 1; index < source.length; index += 1) {
    add(source[index], -slotSize);
  }
  for (let index = desiredIndex; index < destination.length; index += 1) {
    add(destination[index], slotSize);
  }
  return shifts;
}

function measureDragLayout(draggedRef, target) {
  if (typeof document === "undefined" || !draggedRef || !target) return EMPTY_DRAG_LAYOUT;

  const columns = Array.prototype.slice.call(document.querySelectorAll(".board-column"));
  const cardsByState = new Map();
  const elementsByState = new Map();
  let draggedCard = null;
  let sourceState = null;
  let destinationColumn = null;

  for (const column of columns) {
    const state = column.getAttribute("data-state");
    if (!state) continue;
    const cards = Array.prototype.slice.call(column.querySelectorAll(".task-card"));
    elementsByState.set(state, cards);
    cardsByState.set(state, cards.map((card) => card.getAttribute("data-ref")).filter(Boolean));
    if (state === target.state) destinationColumn = column;
    for (const card of cards) {
      if (card.getAttribute("data-ref") !== draggedRef) continue;
      draggedCard = card;
      sourceState = state;
    }
  }
  if (!draggedCard || !sourceState || !destinationColumn) return EMPTY_DRAG_LAYOUT;

  const allDestinationCards = elementsByState.get(target.state) || [];
  const rendered = allDestinationCards.filter((card) => card !== draggedCard);
  const desiredIndex = target.beforeRef
    ? rendered.findIndex((card) => card.getAttribute("data-ref") === target.beforeRef)
    : rendered.length;
  if (desiredIndex < 0) return EMPTY_DRAG_LAYOUT;

  const height = draggedCard.offsetHeight;
  const shifts = previewShifts(
    cardsByState,
    sourceState,
    draggedRef,
    target,
    height + CARD_GAP_PX,
  );

  let top;
  if (desiredIndex > 0) {
    const previous = rendered[desiredIndex - 1];
    const ref = previous.getAttribute("data-ref");
    top = previous.offsetTop + (shifts.get(ref) || 0) + previous.offsetHeight + CARD_GAP_PX;
  } else if (allDestinationCards.length > 0) {
    // The unfiltered first card is the source placeholder when it already owns the first slot.
    top = allDestinationCards[0].offsetTop;
  } else {
    const head = destinationColumn.querySelector(".board-column-head");
    if (!head) return EMPTY_DRAG_LAYOUT;
    top = head.offsetTop + head.offsetHeight + CARD_GAP_PX;
  }

  return {
    shifts: shifts,
    slot: { state: target.state, top: top, height: height },
  };
}

/** Plan the minimal PATCH-state then /move sequence; the endpoints cannot change both at once. */
export function dropPlan(entry, target, columnEntries) {
  if (!entry || !target || !target.state) return null;
  const others = columnEntries.filter((row) => row.ref !== entry.ref);
  const desired = target.beforeRef
    ? others.findIndex((row) => row.ref === target.beforeRef)
    : others.length;
  // Refuse rather than guess when the named neighbor vanished before release.
  if (desired < 0) return null;

  const stateChanged = target.state !== entry.state;
  let current;
  if (stateChanged) {
    // A state PATCH preserves the project-wide position.
    const at = others.findIndex((row) => row.position > entry.position);
    current = at < 0 ? others.length : at;
  } else {
    current = columnEntries.findIndex((row) => row.ref === entry.ref);
  }
  const needsMove = desired !== current;
  if (!stateChanged && !needsMove) return null;

  let move = null;
  if (needsMove) {
    if (target.beforeRef) move = { before: target.beforeRef };
    else if (others.length > 0) move = { after: others[others.length - 1].ref };
    // Empty columns have no neighbor, so only the backlog bottom is expressible.
    else move = { bottom: true };
  }
  return { state: stateChanged ? target.state : null, move: move };
}

export function Board({
  tasks = [],
  sessions = [],
  route = null,
  projects = [],
  projectId = null,
  basePath = "",
  newTaskRequest = 0,
  newProjectRequest = 0,
  drawerOpen = false,
  sidebarCollapsed = false,
  onTaskRow,
  onProjectCreated,
  onToggleDrawer,
  onToggleSidebar,
  onOpenPalette,
  onAnnounce,
}) {
  const [form, setForm] = useState(null);
  const [showAllDone, setShowAllDone] = useState(false);
  const [phone, setPhone] = useState(phoneNow);
  const [activeColumn, setActiveColumn] = useState(BOARD_COLUMNS[0].state);
  const [dragPreview, setDragPreview] = useState(null);
  const [dropTarget, setDropTarget] = useState(null);
  const [dragLayout, setDragLayout] = useState(EMPTY_DRAG_LAYOUT);
  const draggingRef = dragPreview ? dragPreview.ref : null;

  const say = useCallback((text, error) => {
    if (onAnnounce) onAnnounce(text, error);
  }, [onAnnounce]);

  const publishRow = useCallback((row) => {
    if (row && row.ref && onTaskRow) onTaskRow(row);
  }, [onTaskRow]);

  const routeId = route && route.id;
  const project = projects.find((row) => row.id === projectId) || null;

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return undefined;
    const query = window.matchMedia(PHONE_QUERY);
    const apply = () => setPhone(query.matches);
    apply();
    // Safari before 14 requires the deprecated MediaQueryList listener API.
    if (query.addEventListener) query.addEventListener("change", apply);
    else query.addListener(apply);
    return () => {
      if (query.removeEventListener) query.removeEventListener("change", apply);
      else query.removeListener(apply);
    };
  }, []);

  // Start at zero so a navigate-and-increment request is served on the board's first mount.
  const servedRequestRef = useRef(0);
  useEffect(() => {
    if (newTaskRequest === servedRequestRef.current) return;
    servedRequestRef.current = newTaskRequest;
    setForm("task");
  }, [newTaskRequest]);
  const servedProjectRequestRef = useRef(0);
  useEffect(() => {
    if (newProjectRequest === servedProjectRequestRef.current) return;
    servedProjectRequestRef.current = newProjectRequest;
    setForm("project");
  }, [newProjectRequest]);

  const shownProjectId = project ? project.id : null;
  const entries = useMemo(() => tasks
    .filter((task) => task.project === shownProjectId)
    .slice()
    .sort((a, b) => (a.position - b.position) || (a.createdAt - b.createdAt) ||
      (a.ref < b.ref ? -1 : a.ref > b.ref ? 1 : 0)), [tasks, shownProjectId]);
  const entriesRef = useRef(entries);
  entriesRef.current = entries;

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

  const openTask = useCallback((ref) => navigate(taskPath(ref)), []);
  const openSession = useCallback((id) => navigate(sessionPath(id)), []);
  const applyDrop = useCallback(async (ref, target) => {
    const entry = entriesRef.current.find((row) => row.ref === ref);
    if (!entry || !target) return;
    const column = entriesRef.current.filter((row) => row.state === target.state);
    const plan = dropPlan(entry, target, column);
    if (!plan) return;
    try {
      // State must land before position is resolved in the destination column.
      if (plan.state) publishRow(await patchTask(ref, { state: plan.state }));
      if (plan.move) publishRow(await moveTask(ref, plan.move));
    } catch (e) {
      say("Could not move " + ref + ": " + errorMessage(e), true);
    }
  }, [publishRow, say]);

  const submitTask = useCallback(async (title, body) => {
    const created = await createTask(shownProjectId, title, body);
    publishRow(created);
    setForm(null);
    say("Created " + ((created && created.ref) || "the task") + ".");
  }, [shownProjectId, publishRow, say]);

  const submitProject = useCallback(async (path, name) => {
    const created = await createProject(path, name);
    if (onProjectCreated) await onProjectCreated(created);
    setForm(null);
    say("Project " + ((created && created.name) || path) + " is ready.");
  }, [onProjectCreated, say]);

  const gestureRef = useRef(null);

  const dragPointerDown = useCallback((event, entry) => {
    if (event.isPrimary === false) return;
    if (event.button !== undefined && event.button !== 0) return;
    const element = event.currentTarget;
    gestureRef.current = {
      pointerId: event.pointerId,
      ref: entry.ref,
      startX: event.clientX,
      startY: event.clientY,
      claimed: false,
      element: element,
    };
    // Capture immediately: the tiny drag-only handle is left before pointer travel clears the slop.
    if (element && element.setPointerCapture) element.setPointerCapture(event.pointerId);
  }, []);

  const dragPointerMove = useCallback((event) => {
    const gesture = gestureRef.current;
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    if (!gesture.claimed) {
      if (Math.abs(event.clientX - gesture.startX) < DRAG_SLOP_PX &&
        Math.abs(event.clientY - gesture.startY) < DRAG_SLOP_PX) return;
      const card = gesture.element && gesture.element.closest
        ? gesture.element.closest(".task-card")
        : null;
      if (!card) return;
      const rect = card.getBoundingClientRect();
      gesture.claimed = true;
      gesture.cardRect = {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
      };
    }
    if (event.cancelable) event.preventDefault();
    setDragPreview({
      ref: gesture.ref,
      left: gesture.cardRect.left,
      top: gesture.cardRect.top,
      width: gesture.cardRect.width,
      height: gesture.cardRect.height,
      deltaX: event.clientX - gesture.startX,
      deltaY: event.clientY - gesture.startY,
    });
    const target = dropTargetAt(event.clientX, event.clientY, gesture.ref);
    setDropTarget(target);
    if (!target) setDragLayout(EMPTY_DRAG_LAYOUT);
  }, []);

  const endGesture = useCallback((gesture, pointerId) => {
    gestureRef.current = null;
    if (gesture.element && gesture.element.hasPointerCapture &&
      gesture.element.hasPointerCapture(pointerId)) {
      gesture.element.releasePointerCapture(pointerId);
    }
    setDragPreview(null);
    setDropTarget(null);
    setDragLayout(EMPTY_DRAG_LAYOUT);
  }, []);

  const dragPointerUp = useCallback((event) => {
    const gesture = gestureRef.current;
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    const claimed = gesture.claimed;
    // Pointerup need not be preceded by a move, so resolve the release position again.
    const target = claimed ? dropTargetAt(event.clientX, event.clientY, gesture.ref) : null;
    endGesture(gesture, event.pointerId);
    if (claimed && target) applyDrop(gesture.ref, target);
  }, [applyDrop, endGesture]);

  const dragPointerCancel = useCallback((event) => {
    const gesture = gestureRef.current;
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    endGesture(gesture, event.pointerId);
  }, [endGesture]);

  const shownColumns = phone
    ? columns.filter((column) => column.state === activeColumn)
    : columns;

  useLayoutEffect(() => {
    if (!draggingRef || !dropTarget) {
      setDragLayout(EMPTY_DRAG_LAYOUT);
      return;
    }
    setDragLayout(measureDragLayout(draggingRef, dropTarget));
  }, [draggingRef, dropTarget, entries, sessionsByTask, showAllDone, phone, activeColumn]);

  const draggedEntry = draggingRef
    ? entries.find((entry) => entry.ref === draggingRef) || null
    : null;
  const liftedStyle = dragPreview ? {
    left: dragPreview.left + "px",
    top: dragPreview.top + "px",
    width: dragPreview.width + "px",
    height: dragPreview.height + "px",
    transform: "translate(" + dragPreview.deltaX + "px, " + dragPreview.deltaY + "px)",
  } : null;

  const renderColumn = (column) => {
    const capped = column.state === "done" && !showAllDone &&
      column.entries.length > DONE_VISIBLE_LIMIT;
    const visible = capped
      ? column.entries.slice(column.entries.length - DONE_VISIBLE_LIMIT)
      : column.entries;
    const over = Boolean(draggingRef && dropTarget && dropTarget.state === column.state);
    const slot = dragLayout.slot && dragLayout.slot.state === column.state
      ? dragLayout.slot
      : null;
    return html`
      <section key=${column.state} class=${"board-column" + (over ? " board-drop-target" : "")}
               data-state=${column.state} aria-label=${column.label}>
        <header class="board-column-head">
          <h2>${column.label}</h2>
          <span>${column.entries.length}</span>
        </header>
        ${slot && html`
          <div class="board-drop-slot" aria-hidden="true"
               style=${{ top: slot.top + "px", height: slot.height + "px" }}></div>`}
        ${visible.map((entry) => html`
            <${TaskCard}
              key=${entry.ref}
              entry=${entry}
              sessions=${sessionsByTask.get(entry.ref) || []}
              active=${routeId === entry.ref}
              dragging=${draggingRef === entry.ref}
              dragOffset=${dragLayout.shifts.get(entry.ref) || 0}
              onOpen=${openTask}
              onOpenSession=${openSession}
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
    <main class=${"board" + (draggingRef ? " is-dragging" : "")} aria-label="Task board">
      <header class="board-head">
        <button
          id="drawer-toggle"
          class="icon-button icon-button-small drawer-toggle"
          type="button"
          aria-label="Show the project list"
          aria-expanded=${drawerOpen ? "true" : "false"}
          aria-controls="sidebar"
          title="Projects"
          onClick=${onToggleDrawer}
        >☰</button>
        <button
          id="sidebar-toggle"
          class="icon-button icon-button-small sidebar-toggle"
          type="button"
          aria-label=${sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-expanded=${sidebarCollapsed ? "false" : "true"}
          aria-controls="sidebar"
          title=${sidebarCollapsed ? "Expand sidebar (⌘.)" : "Collapse sidebar (⌘.)"}
          onClick=${onToggleSidebar}
        >${sidebarCollapsed ? "›" : "‹"}</button>
        <div class="board-identity">
          <span class="board-project">${project ? (project.name || project.id) : "No project"}</span>
          <span class="board-project-path" title=${(project && project.path) || ""}>
            ${(project && project.path) || "Adopt a directory to start a backlog"}
          </span>
        </div>
        <button type="button" class="button board-new-task" disabled=${!project}
                onClick=${() => setForm("task")}>New task</button>
        <button
          id="palette-button"
          class="icon-button icon-button-small palette-button"
          type="button"
          aria-label="Open command palette"
          title="Commands"
          onClick=${() => onOpenPalette("leader")}
        >⋯</button>
      </header>

      ${phone && html`
        <nav class="board-column-switch" aria-label="Column">
          ${columns.map((column) => html`
            <button key=${column.state} type="button" class="button" data-state=${column.state}
                    aria-pressed=${column.state === activeColumn ? "true" : "false"}
                    onClick=${() => setActiveColumn(column.state)}>
              <span>${column.label}</span> <span>${column.entries.length}</span>
            </button>`)}
        </nav>`}

      <div class="board-columns">
        ${shownColumns.map(renderColumn)}
      </div>

      ${draggedEntry && html`
        <${TaskCard}
          entry=${draggedEntry}
          sessions=${sessionsByTask.get(draggedEntry.ref) || []}
          active=${routeId === draggedEntry.ref}
          lifted=${true}
          style=${liftedStyle}
          onOpen=${openTask}
          onOpenSession=${openSession}
        />`}

      ${form === "task" && html`
        <${NewTaskForm} project=${project}
                        onCreate=${submitTask} onClose=${() => setForm(null)} />`}
      ${form === "project" && html`
        <${NewProjectForm} basePath=${basePath} onCreate=${submitProject}
                           onClose=${() => setForm(null)} />`}
    </main>
  `;
}

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
      // Native required accepts whitespace-only input.
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

/** Match directory completion's lexical base join; this is not a containment check. */
export function resolveProjectPath(typed, basePath) {
  const input = String(typed || "").trim();
  if (input.charAt(0) === "/") return normalizePath(input);
  const base = normalizePath(basePath);
  if (!input || base.charAt(0) !== "/") return input;
  return normalizePath(joinPath(base, [input]));
}

function NewProjectForm({ basePath = "", onCreate, onClose }) {
  // Freeze the base so cross-tab preference updates cannot reinterpret an open draft.
  const [base] = useState(() => normalizePath(basePath));
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
    setQuery(null);
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

  const placeholder = base.charAt(0) === "/" ? joinPath(base, ["name"]) : "/path/to/project";

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
                   placeholder=${placeholder}
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
              Starts at the Preferences base path ${base}; a name without a leading / resolves against it.
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
