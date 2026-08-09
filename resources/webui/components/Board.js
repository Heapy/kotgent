/*
 * The kanban board: four columns (todo / in_progress / review / done) over ONE selected project.
 *
 * STUB: Task 24 of the task-backlog plan implements this component. It is declared now, with its final
 * export name, so `app.js` can route to it and `WebUiServingTest` can register the module. Rendering
 * null keeps the session view exactly as it is until the router's stub starts answering `tasks`.
 *
 * What Task 24 owes: a project selector with no "all projects" mode, a "new project" action backed by
 * DirectoryCompletion, a "new task" action posting the SELECTED project id (the browser has no session),
 * a `done` column capped at the last N with a "show all" toggle, desktop pointer dragging with an 8 px
 * slop and `touch-action: none` on the card HANDLE only, and a single-column switcher below the phone
 * breakpoint with move actions in the card menu instead of dragging. All styles live in `style.css`
 * (Task 28) — this component ships none of its own.
 */

export function Board(props) {
  // Task 24.
  return null;
}
