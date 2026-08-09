/*
 * One task in full: editable title and body, the dependency editor, the linked-session list, the
 * activity feed (fetched with the task, never from the socket) and delete.
 *
 * "Start session" opens the ORDINARY New-session dialog, pre-filled with the project cwd and the task,
 * submitting the single `POST /api/v1/sessions` with `taskRef`. There is deliberately no second launch
 * path.
 *
 * Props `app.js` passes, which Task 25 may rely on and may not change:
 *   taskRef         the ref from the route; fetch the detail (entry + deps + sessions + activity) here.
 *   sessions        SessionDto[] — the linked list is `session.taskRef === taskRef`, not a fetch.
 *   onStartSession  (cwd, taskRef) → opens the ordinary New-session dialog with both pre-filled. This
 *                   IS the no-second-launch-path rule: the dialog puts `taskRef` in its submitted body
 *                   and `app.js` POSTs that body verbatim to `/api/v1/sessions`. Task 25 also owns
 *                   `components/dialogs.js`, so the matching half — `NewSessionDialog`'s new
 *                   `initialTaskRef` prop, already passed at the call site — belongs to it too.
 *   onAnnounce      (text, isError) — the existing announcement channel.
 *
 * All styles live in `style.css` (Task 28); the class names are in the plan's "Board CSS vocabulary".
 * Nothing here invents one: the head, body, dependency editor, session list and feed carry
 * `task-detail*` / `task-deps` / `task-activity*` / `task-sessions` and nothing else, and the controls
 * reuse the app-wide `button` / `icon-button` / `field-hint` / `form-error` families that already exist.
 * Every element a test or a stylesheet needs to reach without a class carries an `id` instead.
 *
 * ## Three decisions worth naming
 *
 * **The feed rides HTTP, so a mutation re-reads the whole detail.** `TaskDetailDto` is one response —
 * entry, deps, sessions, activity — and only the ENTRY part of it also travels on the `/events` socket.
 * A view that patched its own copy from a response would hold a feed that never grows and a `blocked`
 * marker that only its own edits move; re-fetching after every write is one request per deliberate
 * click and keeps the whole screen consistent with the daemon. The fetch is generation-guarded, so a
 * superseded answer (a second click, a route change) never overwrites a newer one.
 *
 * **The session list is LIVE first and fetched second.** `detail.sessions` is the daemon's answer at
 * fetch time; the `sessions` prop is the same truth kept current by the `/events` socket, which is why
 * the dots move without a poll. So the list is built from the prop (`session.taskRef === taskRef`) and
 * only *unions in* a fetched row for a session the browser does not hold at all. A fetched row the
 * browser DOES hold but which no longer names this ref is genuinely unlinked and is dropped — trusting
 * the stale copy there would show a session working on a task it was released from.
 *
 * **There is no comment box.** `POST /tasks/{ref}/comment` requires session identity (the pane header or
 * an explicit id) because an activity row must be attributable, and the browser has neither — it would
 * be a control that can only ever fail. Comments come from an agent or the CLI; the browser reads them.
 * The state `<select>` is the one write the plan's CSS vocabulary asks this head to carry, and it is
 * also the only way to move a task on a phone, where the board has no dragging.
 */

import { html } from "htm/preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { errorMessage } from "../lib/api.js";
import { SCREEN_TASKS, navigate, routePath, sessionPath, taskPath } from "../lib/router.js";
import { displayName, stateBadge } from "../lib/sessions.js";
import { deleteTask, editTaskDependency, fetchTaskDetail, patchTask } from "../lib/tasks.js";

/** The four workflow states, in board order. Mirrors `io.kotgent.task.TaskState`. */
export const TASK_STATES = ["todo", "in_progress", "review", "done"];

/** The label each state is shown under — the board's column titles. */
export const TASK_STATE_LABELS = {
  todo: "To do",
  in_progress: "In progress",
  review: "Review",
  done: "Done",
};

/** What a feed row says when it carries no text of its own (`ActivityKind`). */
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

/** A feed row's timestamp in the viewer's own locale; an unparseable one renders as nothing. */
export function activityTime(ts) {
  if (typeof ts !== "number" || !Number.isFinite(ts)) return "";
  const at = new Date(ts);
  return Number.isNaN(at.getTime()) ? "" : at.toLocaleString();
}

/**
 * The machine-readable half of the same stamp. `Date.prototype.toISOString` THROWS on an invalid date
 * rather than answering `"Invalid Date"`, so a feed row with a corrupt `ts` would take the whole screen
 * down through the render — hence the guard, and hence a null (the attribute is then simply absent).
 */
export function activityTimestampAttr(ts) {
  if (typeof ts !== "number" || !Number.isFinite(ts)) return null;
  const at = new Date(ts);
  return Number.isNaN(at.getTime()) ? null : at.toISOString();
}

/** One feed row's sentence. A transition always names both ends, with its optional message appended. */
export function activityText(row) {
  if (!row) return "";
  if (row.kind === "transition") {
    const move = stateLabel(row.fromState) + " → " + stateLabel(row.toState);
    return row.text ? move + " — " + row.text : move;
  }
  if (row.text) return row.text;
  return ACTIVITY_FALLBACK[row.kind] || row.kind || "";
}

/**
 * Every session this task is linked from: the live list first, then any fetched row for a session this
 * browser does not hold. See the header — a fetched row whose live copy points elsewhere is dropped.
 */
export function linkedSessions(ref, sessions, detail) {
  const held = sessions || [];
  const live = held.filter((s) => s && s.taskRef === ref);
  const ids = new Set(held.map((s) => s && s.id));
  const fetched = ((detail && detail.sessions) || []).filter((s) => s && !ids.has(s.id));
  return live.concat(fetched);
}

/** An in-app link: keep the href (copy, middle-click, open-in-tab) but route the ordinary click. */
function routeClick(path) {
  return (event) => {
    if (event.defaultPrevented || event.button !== 0) return;
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(path);
  };
}

export function TaskDetail({ taskRef, sessions, onStartSession, onAnnounce }) {
  const [detail, setDetail] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [bodyDraft, setBodyDraft] = useState("");
  const [depDraft, setDepDraft] = useState("");
  // Every read is stamped with a generation and only the newest one may land. An AbortController would
  // work too, but the thing that actually matters is not the socket — it is that a stale answer (an
  // older ref's, or the read that a write's own refresh superseded) cannot overwrite a newer one.
  const generationRef = useRef(0);

  const load = useCallback(async () => {
    const generation = ++generationRef.current;
    try {
      const next = await fetchTaskDetail(taskRef);
      if (generationRef.current !== generation) return;
      const entry = (next && next.task) || {};
      setDetail(next);
      setLoadError(null);
      setTitleDraft(entry.title || "");
      setBodyDraft(entry.body || "");
    } catch (e) {
      if (generationRef.current !== generation) return;
      setDetail(null);
      setLoadError(errorMessage(e));
    }
  }, [taskRef]);

  useEffect(() => {
    setDetail(null);
    setLoadError(null);
    setDepDraft("");
    load();
    // Retiring the generation on the way out is what makes an unmount (or a new ref) final: the read
    // still completes, but it can no longer write into a screen that has moved on.
    return () => {
      generationRef.current += 1;
    };
  }, [taskRef, load]);

  /**
   * One mutation: run it, then re-read the detail so the feed and the derived `blocked` marker are the
   * daemon's, not this view's guess. A refusal (a cycle, a cross-project edge, a vanished task) is
   * surfaced through the announcement channel — the same one the board uses — rather than swallowed.
   */
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

  const entry = (detail && detail.task) || null;
  const dependsOn = (entry && entry.dependsOn) || (detail && detail.dependsOn) || [];
  const dependents = (detail && detail.dependents) || [];
  const activity = (detail && detail.activity) || [];
  const linked = linkedSessions(taskRef, sessions, detail);
  const dirty = !!entry && (titleDraft !== (entry.title || "") || bodyDraft !== (entry.body || ""));

  const backToBoard = useCallback(() => {
    navigate(routePath({ screen: SCREEN_TASKS, id: null }));
  }, []);

  const saveEdits = (event) => {
    if (event) event.preventDefault();
    if (!entry || !dirty) return;
    const title = titleDraft.trim();
    if (!title) {
      // The one requirement worth refusing here rather than round-tripping: a blank title would leave
      // the board with a card nobody can identify, and `patch.title` uses "present" rather than
      // "truthy" below precisely so an empty string would otherwise be sent.
      onAnnounce("A task needs a title.", true);
      return;
    }
    const patch = {};
    if (title !== (entry.title || "")) patch.title = title;
    if (bodyDraft !== (entry.body || "")) patch.body = bodyDraft;
    if (!("title" in patch) && !("body" in patch)) return;
    run("Could not save " + taskRef, async () => {
      await patchTask(taskRef, patch);
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
      await patchTask(taskRef, { state: next });
      onAnnounce(taskRef + " → " + stateLabel(next) + ".");
    });
  };

  const addDependency = (event) => {
    if (event) event.preventDefault();
    const on = depDraft.trim();
    if (!on) return;
    run("Could not add the dependency", async () => {
      await editTaskDependency(taskRef, "add", on);
      setDepDraft("");
      onAnnounce(taskRef + " now depends on " + on + ".");
    });
  };

  const removeDependency = (on) => {
    run("Could not remove the dependency", async () => {
      await editTaskDependency(taskRef, "remove", on);
      onAnnounce(taskRef + " no longer depends on " + on + ".");
    });
  };

  const removeTask = () => {
    if (!window.confirm(
      "Delete " + taskRef + "? Every session linked to it is unlinked and its activity is removed.",
    )) return;
    // No reload: the ref is gone, and re-reading it would answer 404 and paint a load error over a
    // successful delete. The board is where a deleted task leaves you.
    run("Could not delete " + taskRef, async () => {
      await deleteTask(taskRef);
      onAnnounce("Deleted " + taskRef + ".");
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

  if (!entry) {
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
        <div>
          <h2 id="task-detail-title">${taskRef}</h2>
          <p class="field-hint">
            ${detail.projectName || entry.project}${detail.projectPath ? " · " + detail.projectPath : ""}
          </p>
        </div>
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
          <p class="field-hint">
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
                  ${/* The dot's data-state carries stateBadge's own value rather than a second
                        mapping invented here, so the sidebar's badge and this dot cannot drift. */ ""}
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
