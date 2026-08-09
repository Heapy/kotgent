/*
 * One card on the board: title, blocked marker, every linked session with its state dot, the dependency
 * count, and a menu carrying delete (plus the move actions on a phone, where there is no dragging).
 *
 * Linked sessions are derived from the session list the app already holds — `session.taskRef` — rather
 * than fetched per card: that keeps the dots live off the ordinary `/events` traffic and keeps
 * `GET /tasks` one query per project. `Board.js` does the matching once for the whole board and hands
 * each card its own slice, so a project with fifty cards still walks the session list once.
 *
 * EVERY class name here comes from the plan's "Board CSS vocabulary", which is the entire contract with
 * Task 28's `style.css` — nothing here ships CSS of its own. The one exception is deliberate and is not
 * styling: `touch-action: none` on the handle is what makes the pointer drag possible at all (the browser
 * claims a touch gesture otherwise), so it travels with the gesture the way `installSwipeScroll`'s does.
 * The stylesheet says the same thing about `.task-card-handle`; agreeing twice is free, and the inline
 * copy means the gesture cannot be broken by a rule landing on the wrong selector.
 *
 * The card is an `<article>` sitting directly inside its column — deliberately not a `<li>` in a `<ul>`,
 * because that `<ul>` would need a class of its own to lose the UA's bullets and indent, and the CSS
 * vocabulary this file must not add to has no name for it. The only keyboard-reachable controls in it
 * are real ones: the title is an `<a href="/tasks/{ref}">` so middle-click and "open in new tab" work
 * against a path the daemon really serves, each linked session is an `<a href="/s/{id}">`, and the menu
 * is a native `<details>` — no invented focus handling, no outside-click listener.
 */

import { html } from "htm/preact";
import { sessionPath, taskPath } from "../lib/router.js";
import { displayName, stateBadge } from "../lib/sessions.js";

/** Modified clicks belong to the browser (new tab / new window / download), never to the router. */
function isPlainClick(event) {
  return !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey &&
    (event.button === undefined || event.button === 0);
}

export function TaskCard({
  entry,
  sessions = [],
  active = false,
  dragging = false,
  moveTargets = [],
  canMoveUp = false,
  canMoveDown = false,
  onOpen,
  onOpenSession,
  onDelete,
  onMoveState,
  onMoveUp,
  onMoveDown,
  onDragPointerDown,
  onDragPointerMove,
  onDragPointerUp,
  onDragPointerCancel,
}) {
  const depCount = (entry.dependsOn || []).length;
  const href = taskPath(entry.ref);

  const openTask = (event) => {
    if (!isPlainClick(event)) return;
    event.preventDefault();
    onOpen(entry.ref);
  };
  const openSession = (event, id) => {
    if (!isPlainClick(event)) return;
    event.preventDefault();
    onOpenSession(id);
  };

  return html`
    <article
      class=${"task-card" + (dragging ? " is-dragging" : "")}
      data-ref=${entry.ref}
      data-state=${entry.state}
    >
      ${/* The drag handle, and the ONLY element that takes `touch-action: none` — reserving the whole
            card would cost the phone its column scroll for a gesture that column does not even have. */ ""}
      <div
        class="task-card-handle"
        style="touch-action: none"
        aria-hidden="true"
        title="Drag to move this task"
        onPointerDown=${(event) => onDragPointerDown && onDragPointerDown(event, entry)}
        onPointerMove=${(event) => onDragPointerMove && onDragPointerMove(event, entry)}
        onPointerUp=${(event) => onDragPointerUp && onDragPointerUp(event, entry)}
        onPointerCancel=${(event) => onDragPointerCancel && onDragPointerCancel(event, entry)}
      >⠿</div>

      <a class="task-card-title" href=${href} title=${entry.ref}
         aria-current=${active ? "true" : null} onClick=${openTask}>
        ${entry.title || entry.ref}
      </a>

      <div class="task-card-meta">
        ${depCount > 0 && html`
          <span class="task-dep-count"
                title=${depCount === 1 ? "depends on 1 task" : "depends on " + depCount + " tasks"}>
            ${depCount}↑
          </span>`}
        ${entry.blocked && html`
          <span class="task-blocked" title="Waiting on a dependency that is not done">blocked</span>`}
        ${/* Every linked session, not just the first: a task may be worked by any number of sessions —
              kotgent cannot enforce exclusivity, so the board shows the truth instead of pretending. */ ""}
        ${sessions.length > 0 && html`
          <ul class="task-sessions">
            ${sessions.map((session) => {
              const badge = stateBadge(session.state);
              return html`
                <li key=${session.id}>
                  <a href=${sessionPath(session.id)} title=${displayName(session) + " — " + badge.label}
                     onClick=${(event) => openSession(event, session.id)}>
                    <span class="task-session-dot" data-state=${session.state}
                          aria-label=${badge.label}></span>
                    ${displayName(session)}
                  </a>
                </li>`;
            })}
          </ul>`}
      </div>

      ${/* A native <details>: it opens on click and on Enter, closes on Esc, and needs no
            outside-click listener of its own. Delete is always here; the move actions appear only in
            the phone branch, where they are what replaces dragging. */ ""}
      <details class="task-card-menu">
        <summary aria-label=${"Actions for " + (entry.title || entry.ref)}>⋯</summary>
        <div>
          ${moveTargets.length > 0 && html`
            <button type="button" class="button button-quiet" disabled=${!canMoveUp}
                    onClick=${() => onMoveUp(entry)}>Move up</button>
            <button type="button" class="button button-quiet" disabled=${!canMoveDown}
                    onClick=${() => onMoveDown(entry)}>Move down</button>
            ${moveTargets.map((target) => html`
              <button key=${target.state} type="button" class="button button-quiet"
                      onClick=${() => onMoveState(entry, target.state)}>
                Move to ${target.label}
              </button>`)}`}
          <button type="button" class="button button-quiet"
                  onClick=${() => onDelete(entry)}>Delete task</button>
        </div>
      </details>
    </article>
  `;
}
