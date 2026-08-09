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
 *
 * STUB: Task 25 of the task-backlog plan implements this component.
 */

export function TaskDetail(props) {
  // Task 25.
  return null;
}
