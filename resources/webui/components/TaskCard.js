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
