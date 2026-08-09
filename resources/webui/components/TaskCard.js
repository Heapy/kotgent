/*
 * One card on the board: title, blocked marker, every linked session with its state dot, the dependency
 * count, and a menu carrying delete (plus the move actions on a phone, where there is no dragging).
 *
 * Linked sessions are derived from the session list the app already holds — `session.taskRef` — rather
 * than fetched per card: that keeps the dots live off the ordinary `/events` traffic and keeps
 * `GET /tasks` one query per project.
 *
 * STUB: Task 24 of the task-backlog plan implements this component.
 */

export function TaskCard(props) {
  // Task 24.
  return null;
}
