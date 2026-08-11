/* Detail mutations re-fetch the HTTP-only feed and derived state. Live event rows merge with fetched
 * rows by revision, live session links outrank stale fetched links, and dirty drafts outrank updates. */

import { html } from "htm/preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { errorMessage } from "../lib/api.js";
import { SCREEN_TASKS, navigate, routePath, sessionPath, taskPath } from "../lib/router.js";
import { displayName, stateBadge } from "../lib/sessions.js";
import { deleteTask, editTaskDependency, fetchTaskDetail, patchTask } from "../lib/tasks.js";

/** Mirrors `io.kotgent.task.TaskState` in board order. */
export const TASK_STATES = ["todo", "in_progress", "review", "done"];

export const TASK_STATE_LABELS = {
  todo: "To do",
  in_progress: "In progress",
  review: "Review",
  done: "Done",
};

const ACTIVITY_FALLBACK = {
  created: "created the task",
  comment: "commented",
  transition: "changed state",
  linked: "linked a session",
  unlinked: "unlinked a session",
};

function stateLabel(state) {
  return TASK_STATE_LABELS[state] || state || "unknown";
}

export function activityTime(ts) {
  if (typeof ts !== "number" || !Number.isFinite(ts)) return "";
  const at = new Date(ts);
  return Number.isNaN(at.getTime()) ? "" : at.toLocaleString();
}

/** Guard before toISOString, which throws for invalid dates. */
export function activityTimestampAttr(ts) {
  if (typeof ts !== "number" || !Number.isFinite(ts)) return null;
  const at = new Date(ts);
  return Number.isNaN(at.getTime()) ? null : at.toISOString();
}

export function activityText(row) {
  if (!row) return "";
  if (row.kind === "transition") {
    const move = stateLabel(row.fromState) + " → " + stateLabel(row.toState);
    return row.text ? move + " — " + row.text : move;
  }
  if (row.text) return row.text;
  return ACTIVITY_FALLBACK[row.kind] || row.kind || "";
}

/** Merge racing HTTP and event observations; missing live state may only mean no baseline yet. */
export function newerEntry(live, fetched) {
  if (!live) return fetched || null;
  if (!fetched) return live;
  return fetched.rev > live.rev ? fetched : live;
}

/** Live links override fetched rows; fetched rows fill only sessions absent from live state. */
export function linkedSessions(ref, sessions, detail) {
  const held = sessions || [];
  const live = held.filter((s) => s && s.taskRef === ref);
  const ids = new Set(held.map((s) => s && s.id));
  const fetched = ((detail && detail.sessions) || []).filter((s) => s && !ids.has(s.id));
  return live.concat(fetched);
}

/** Route plain clicks in-app while preserving browser behavior for modified clicks. */
function routeClick(path) {
  return (event) => {
    if (event.defaultPrevented || event.button !== 0) return;
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(path);
  };
}

export function TaskDetail({
  taskRef,
  entry: liveEntry = null,
  sessions,
  onTaskRow,
  onTaskRemoved,
  onStartSession,
  onAnnounce,
}) {
  const [detail, setDetail] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [bodyDraft, setBodyDraft] = useState("");
  const [depDraft, setDepDraft] = useState("");
  // Prevent superseded reads from overwriting a newer ref or post-write refresh.
  const generationRef = useRef(0);
  // Stable refs keep parent callback identity changes from retriggering the fetch effect.
  const onTaskRowRef = useRef(onTaskRow);
  onTaskRowRef.current = onTaskRow;
  const onTaskRemovedRef = useRef(onTaskRemoved);
  onTaskRemovedRef.current = onTaskRemoved;

  const publishRow = useCallback((row) => {
    if (row && row.ref && onTaskRowRef.current) onTaskRowRef.current(row);
  }, []);

  const load = useCallback(async () => {
    const generation = ++generationRef.current;
    try {
      const next = await fetchTaskDetail(taskRef);
      if (generationRef.current !== generation) return;
      setDetail(next);
      setLoadError(null);
      publishRow(next && next.task);
    } catch (e) {
      if (generationRef.current !== generation) return;
      setDetail(null);
      setLoadError(errorMessage(e));
    }
  }, [taskRef, publishRow]);

  useEffect(() => {
    setDetail(null);
    setLoadError(null);
    setDepDraft("");
    load();
    return () => {
      generationRef.current += 1;
    };
  }, [taskRef, load]);

  /** Re-read after writes because the feed and blocked state are server-derived. */
  const run = useCallback(async (label, work, reload = true) => {
    setBusy(true);
    try {
      await work();
      if (reload) await load();
    } catch (e) {
      onAnnounce(label + ": " + errorMessage(e), true);
    } finally {
      setBusy(false);
    }
  }, [load, onAnnounce]);

  const entry = newerEntry(liveEntry, detail && detail.task);
  const dependsOn = (entry && entry.dependsOn) || (detail && detail.dependsOn) || [];
  const dependents = (detail && detail.dependents) || [];
  const activity = (detail && detail.activity) || [];
  const linked = linkedSessions(taskRef, sessions, detail);
  const dirty = !!entry && (titleDraft !== (entry.title || "") || bodyDraft !== (entry.body || ""));

  // Only a live row seen and then lost proves deletion; initial absence may precede the baseline.
  const seenLiveRef = useRef(null);
  if (liveEntry) seenLiveRef.current = taskRef;
  const vanished = !busy && seenLiveRef.current === taskRef && !liveEntry;

  // External updates replace only drafts still equal to the previously seeded values.
  const draftRef = useRef({ title: "", body: "" });
  draftRef.current = { title: titleDraft, body: bodyDraft };
  const entryTitle = (entry && entry.title) || "";
  const entryBody = (entry && entry.body) || "";
  const seededRef = useRef({ ref: null, title: "", body: "" });
  useEffect(() => {
    const seeded = seededRef.current;
    seededRef.current = { ref: taskRef, title: entryTitle, body: entryBody };
    const untouched =
      draftRef.current.title === seeded.title && draftRef.current.body === seeded.body;
    if (seeded.ref !== taskRef || untouched) {
      setTitleDraft(entryTitle);
      setBodyDraft(entryBody);
    }
  }, [taskRef, entryTitle, entryBody]);

  const backToBoard = useCallback(() => {
    navigate(routePath({ screen: SCREEN_TASKS, id: null }));
  }, []);

  const saveEdits = (event) => {
    if (event) event.preventDefault();
    if (!entry || !dirty) return;
    const title = titleDraft.trim();
    if (!title) {
      onAnnounce("A task needs a title.", true);
      return;
    }
    const patch = {};
    if (title !== (entry.title || "")) patch.title = title;
    if (bodyDraft !== (entry.body || "")) patch.body = bodyDraft;
    if (!("title" in patch) && !("body" in patch)) return;
    run("Could not save " + taskRef, async () => {
      const saved = await patchTask(taskRef, patch);
      publishRow(saved);
      onAnnounce("Saved " + taskRef + ".");
    });
  };

  const revertEdits = () => {
    if (!entry) return;
    setTitleDraft(entry.title || "");
    setBodyDraft(entry.body || "");
  };

  const changeState = (event) => {
    const next = event.target.value;
    if (!entry || next === entry.state) return;
    run("Could not move " + taskRef, async () => {
      const moved = await patchTask(taskRef, { state: next });
      publishRow(moved);
      onAnnounce(taskRef + " → " + stateLabel(next) + ".");
    });
  };

  const addDependency = (event) => {
    if (event) event.preventDefault();
    const on = depDraft.trim();
    if (!on) return;
    run("Could not add the dependency", async () => {
      const edited = await editTaskDependency(taskRef, "add", on);
      publishRow(edited);
      setDepDraft("");
      onAnnounce(taskRef + " now depends on " + on + ".");
    });
  };

  const removeDependency = (on) => {
    run("Could not remove the dependency", async () => {
      const edited = await editTaskDependency(taskRef, "remove", on);
      publishRow(edited);
      onAnnounce(taskRef + " no longer depends on " + on + ".");
    });
  };

  const removeTask = () => {
    if (!window.confirm(
      "Delete " + taskRef + "? Every session linked to it is unlinked and its activity is removed.",
    )) return;
    // Do not turn a successful delete into a follow-up 404.
    run("Could not delete " + taskRef, async () => {
      await deleteTask(taskRef);
      onAnnounce("Deleted " + taskRef + ".");
      // Delete answers with no row to merge, so drop the card here instead of awaiting its frame.
      if (onTaskRemovedRef.current) onTaskRemovedRef.current(taskRef);
      backToBoard();
    }, false);
  };

  const startSession = () => {
    onStartSession((detail && detail.projectPath) || null, taskRef);
  };

  const head = (children) => html`
    <div class="task-detail-head">
      ${children}
      <button id="task-detail-close" class="icon-button" type="button"
              aria-label="Back to the board" onClick=${backToBoard}>×</button>
    </div>
  `;

  // A known deletion is more precise than the resulting 404 load error.
  if (vanished) {
    return html`
      <section class="task-detail" aria-label=${"Task " + taskRef}>
        ${head(html`<h2 id="task-detail-title">${taskRef}</h2>`)}
        <p id="task-detail-gone" class="field-hint" role="status">
          ${taskRef} has been deleted. Nothing here can be edited any more.
        </p>
      </section>
    `;
  }

  if (loadError) {
    return html`
      <section class="task-detail" aria-label=${"Task " + taskRef}>
        ${head(html`<h2 id="task-detail-title">${taskRef}</h2>`)}
        <p id="task-detail-error" class="form-error" role="alert">
          Could not load ${taskRef}: ${loadError}
        </p>
      </section>
    `;
  }

  // Dependencies, activity, and project path require the detail response, not just a live row.
  if (!detail || !entry) {
    return html`
      <section class="task-detail" aria-label=${"Task " + taskRef}>
        ${head(html`<h2 id="task-detail-title">${taskRef}</h2>`)}
        <p id="task-detail-loading" class="field-hint">Loading ${taskRef}…</p>
      </section>
    `;
  }

  return html`
    <section class="task-detail" aria-labelledby="task-detail-title">
      ${head(html`
        <div id="task-detail-ident">
          <h2 id="task-detail-title">${taskRef}</h2>
          <p id="task-detail-project" class="field-hint">
            ${detail.projectName || entry.project}${detail.projectPath ? " · " + detail.projectPath : ""}
          </p>
        </div>
        <div id="task-detail-tools">
          <select id="task-detail-state" aria-label="State" disabled=${busy}
                  value=${entry.state} onChange=${changeState}>
            ${TASK_STATES.map((state) => html`
              <option key=${state} value=${state} selected=${state === entry.state}>
                ${stateLabel(state)}
              </option>
            `)}
          </select>
          ${entry.blocked && html`
            <span class="task-blocked" title="A dependency is not done yet">Blocked</span>`}
          <button id="task-detail-start" class="button button-primary" type="button" disabled=${busy}
                  onClick=${startSession}>Start session</button>
        </div>
      `)}

      <form id="task-detail-form" onSubmit=${saveEdits}>
        <label class="field">
          <span>Title</span>
          <input id="task-detail-title-input" type="text" maxlength="200" disabled=${busy}
                 value=${titleDraft} onInput=${(e) => setTitleDraft(e.target.value)} />
        </label>
        <label class="field">
          <span>Description</span>
          <textarea id="task-detail-body" class="task-detail-body" rows="8" disabled=${busy}
                    value=${bodyDraft} onInput=${(e) => setBodyDraft(e.target.value)}></textarea>
        </label>
        <div id="task-detail-actions">
          <button id="task-detail-save" class="button button-primary" type="submit"
                  disabled=${busy || !dirty}>Save</button>
          <button id="task-detail-revert" class="button button-quiet" type="button"
                  disabled=${busy || !dirty} onClick=${revertEdits}>Revert</button>
          <button id="task-detail-delete" class="button button-quiet" type="button"
                  disabled=${busy} onClick=${removeTask}>Delete</button>
        </div>
      </form>

      <section class="task-deps" aria-label="Dependencies">
        <h3>Depends on <span class="task-dep-count">${dependsOn.length}</span></h3>
        ${dependsOn.length === 0
          ? html`<p class="field-hint">Nothing — this task is ready whenever its column is.</p>`
          : html`
            <ul>
              ${dependsOn.map((ref) => html`
                <li key=${ref}>
                  <a href=${taskPath(ref)} onClick=${routeClick(taskPath(ref))}>${ref}</a>
                  <button class="button button-quiet button-small" type="button" disabled=${busy}
                          aria-label=${"Remove the dependency on " + ref}
                          onClick=${() => removeDependency(ref)}>Remove</button>
                </li>
              `)}
            </ul>
          `}
        <form id="task-detail-dep-form" onSubmit=${addDependency}>
          <label class="field">
            <span>Add a dependency <small>the ref of a task in this project</small></span>
            <input id="task-detail-dep-input" type="text" spellcheck="false" autocomplete="off"
                   placeholder="local:42" disabled=${busy}
                   value=${depDraft} onInput=${(e) => setDepDraft(e.target.value)} />
          </label>
          <button id="task-detail-dep-add" class="button" type="submit"
                  disabled=${busy || depDraft.trim().length === 0}>Add</button>
        </form>
        ${dependents.length > 0 && html`
          <p id="task-detail-dependents" class="field-hint">
            Blocks:
            ${dependents.map((ref) => html`
              <a key=${ref} href=${taskPath(ref)} onClick=${routeClick(taskPath(ref))}>${ref}</a>
            `)}
          </p>`}
      </section>

      <section class="task-sessions" aria-label="Linked sessions">
        <h3>Sessions</h3>
        ${linked.length === 0
          ? html`<p class="field-hint">No session is working on this task.</p>`
          : html`
            <ul>
              ${linked.map((s) => html`
                <li key=${s.id}>
                  <span class="task-session-dot" data-state=${stateBadge(s.state).cls}
                        title=${stateBadge(s.state).label} aria-hidden="true"></span>
                  <a href=${sessionPath(s.id)} onClick=${routeClick(sessionPath(s.id))}>
                    ${displayName(s)}
                  </a>
                  <small>${s.agent || "?"} · ${stateBadge(s.state).label}</small>
                </li>
              `)}
            </ul>
          `}
      </section>

      <section class="task-activity" aria-label="Activity">
        <h3>Activity</h3>
        ${activity.length === 0
          ? html`<p class="field-hint">Nothing has happened to this task yet.</p>`
          : activity.map((row) => html`
            <div class="task-activity-row" data-kind=${row.kind} key=${row.id}>
              <time datetime=${activityTimestampAttr(row.ts)}>${activityTime(row.ts)}</time>
              <strong>${row.author}</strong>
              <span>${activityText(row)}</span>
            </div>
          `)}
      </section>
    </section>
  `;
}
