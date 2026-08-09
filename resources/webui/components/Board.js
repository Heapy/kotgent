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
 * (Task 28) — the class names are listed in the plan's "Board CSS vocabulary" and this component ships
 * no CSS of its own.
 *
 * Props `app.js` passes, which Task 24 may rely on and may not change:
 *   tasks           BacklogEntryDto[] across every project, already merged newest-rev-wins. Filter by
 *                   the selected project here; the app does not know which one is selected.
 *   sessions        SessionDto[] — the card's session dots come from `session.taskRef`, never a fetch.
 *   route           { screen, id } from `lib/router.js`.
 *   newTaskRequest  a monotonically increasing counter. It increments when the palette's "new task"
 *                   command fires; `0` means "never asked". Open the create form when it CHANGES (an
 *                   effect keyed on it), not when it is truthy — the same command can fire twice while
 *                   the board is already open, and a boolean would need a reset round-trip to do that.
 *   onAnnounce      (text, isError) — the existing announcement channel; surface a rejected mutation
 *                   through it rather than failing silently.
 */

export function Board(props) {
  // Task 24.
  return null;
}
