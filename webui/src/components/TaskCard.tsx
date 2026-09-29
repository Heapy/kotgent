import type { JSX, TargetedMouseEvent, TargetedPointerEvent } from "preact";
import { sessionPath, taskPath } from "../lib/router.ts";
import { displayName, stateBadge } from "../lib/sessions.ts";
import type { Session } from "../lib/sessions.ts";
import type { Task } from "../lib/tasks.ts";

type DragPointerHandler = (event: TargetedPointerEvent<HTMLDivElement>, entry: Task) => void;

export interface TaskCardProps {
  entry: Task;
  sessions?: readonly Session[];
  active?: boolean;
  dragging?: boolean;
  dragOffset?: number;
  lifted?: boolean;
  style?: string | JSX.CSSProperties | null;
  onOpen: (ref: string) => void;
  onOpenSession: (id: string) => void;
  onDragPointerDown?: DragPointerHandler;
  onDragPointerMove?: DragPointerHandler;
  onDragPointerUp?: DragPointerHandler;
  onDragPointerCancel?: DragPointerHandler;
}

/** Modified clicks belong to the browser (new tab / new window / download), never to the router. */
function isPlainClick(event: TargetedMouseEvent<HTMLAnchorElement>) {
  return !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey &&
    (event.button === undefined || event.button === 0);
}

export function TaskCard({
  entry,
  sessions = [],
  active = false,
  dragging = false,
  dragOffset = 0,
  lifted = false,
  style = null,
  onOpen,
  onOpenSession,
  onDragPointerDown,
  onDragPointerMove,
  onDragPointerUp,
  onDragPointerCancel,
}: TaskCardProps) {
  const depCount = (entry.dependsOn || []).length;
  const href = taskPath(entry.ref);

  const openTask = (event: TargetedMouseEvent<HTMLAnchorElement>) => {
    if (!isPlainClick(event)) return;
    event.preventDefault();
    onOpen(entry.ref);
  };
  const openSession = (event: TargetedMouseEvent<HTMLAnchorElement>, id: string) => {
    if (!isPlainClick(event)) return;
    event.preventDefault();
    onOpenSession(id);
  };

  // The lifted copy is inert rather than aria-hidden: aria-hidden leaves its links in the tab order,
  // which puts keyboard focus somewhere assistive technology has been told not to describe.
  return (
    <article
      class={"task-card" + (dragging ? " is-dragging" : "") + (lifted ? " is-lifted" : "")}
      data-ref={lifted ? null : entry.ref}
      data-state={entry.state}
      inert={lifted ? true : undefined}
      style={style || (dragOffset ? { transform: "translateY(" + dragOffset + "px)" } : undefined)}
    >
      <div
        class="task-card-handle"
        style="touch-action: none"
        aria-hidden="true"
        title="Drag to move this task"
        onPointerDown={(event) => onDragPointerDown && onDragPointerDown(event, entry)}
        onPointerMove={(event) => onDragPointerMove && onDragPointerMove(event, entry)}
        onPointerUp={(event) => onDragPointerUp && onDragPointerUp(event, entry)}
        onPointerCancel={(event) => onDragPointerCancel && onDragPointerCancel(event, entry)}
      >⠿</div>

      <a class="task-card-title" href={href} title={entry.ref}
         aria-current={active ? "true" : undefined} onClick={openTask}>
        {entry.title || entry.ref}
      </a>

      <div class="task-card-meta">
        {depCount > 0 && (
          <span class="task-dep-count"
                title={depCount === 1 ? "depends on 1 task" : "depends on " + depCount + " tasks"}>
            {depCount}↑
          </span>)}
        {entry.blocked && (
          <span class="task-blocked" title="Waiting on a dependency that is not done">blocked</span>)}
        {sessions.length > 0 && (
          <ul class="task-sessions">
            {sessions.map((session) => {
              const badge = stateBadge(session.state);
              const label = session.archived ? badge.label + " · done" : badge.label;
              return (
                <li key={session.id}>
                  <a href={sessionPath(session.id)} title={displayName(session) + " — " + label}
                     onClick={(event) => openSession(event, session.id)}>
                    <span class="task-session-dot" data-state={session.state}
                          data-archived={session.archived ? "true" : null}
                          aria-label={label}></span>
                    {displayName(session)}
                  </a>
                </li>);
            })}
          </ul>)}
      </div>
    </article>
  );
}
