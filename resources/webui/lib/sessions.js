/*
 * Session view helpers: the canonical state set as the sidebar shows it.
 *
 * The seven states and the alive/dead/needs-attention split mirror `io.kotgent.core.SessionState` —
 * keep them in step with it, not with whatever the UI happens to need.
 */

/** Map a canonical session state (7 values) to a display label + a CSS badge class. */
export function stateBadge(state) {
  switch (state) {
    case "running":       return { label: "running", cls: "badge-running" };
    case "ready":         return { label: "ready", cls: "badge-ready" };
    case "needs_approval":return { label: "needs approval", cls: "badge-attention" };
    case "needs_answer":  return { label: "needs answer", cls: "badge-attention" };
    case "stopped":       return { label: "stopped", cls: "badge-dead" };
    case "crashed":       return { label: "crashed", cls: "badge-crashed" };
    case "resumable":     return { label: "resumable", cls: "badge-resumable" };
    default:              return { label: state || "unknown", cls: "badge-dead" };
  }
}

/** The two states that block on the human (an approval or a forward-modeled answer). */
export function isNeedsAttention(state) {
  return state === "needs_approval" || state === "needs_answer";
}

/** States backed by a currently live agent process. */
export function isAliveState(state) {
  return state === "running" || state === "ready" ||
    state === "needs_approval" || state === "needs_answer";
}

export function displayName(s) {
  if (s.name && s.name.length > 0) return s.name;
  if (s.tmuxSession && s.tmuxSession.length > 0) return s.tmuxSession;
  return s.id;
}

/** The exact command a local terminal uses to join kotgent's dedicated tmux server. */
export function tmuxAttachCommand(tmuxSession) {
  return "tmux -u -L kotgent attach -t " + tmuxSession;
}

export function capitalize(text) {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * The sidebar sub-line: "agent · model · version". The model/version identify the running build; the cwd
 * is intentionally NOT shown here (it is legible from directory grouping) but stays available as the row's
 * title tooltip. Falls back to "agent · cwd" when neither model nor version is known yet.
 */
export function sessionSubline(s) {
  const agent = s.agent || "?";
  const detail = [s.model, s.cliVersion].filter(Boolean).join(" · ");
  return detail ? agent + " · " + detail : agent + " · " + (s.cwd || "");
}

/**
 * The session→task badge: what a session row and the terminal header show for `session.taskRef`.
 *
 * ONE builder, because both callers must degrade identically. Returns null when the session is linked to
 * nothing at all — the ordinary case, and the reason a caller renders nothing rather than an empty pill.
 *
 * [tasks] is the flat `BacklogEntryDto[]` the `/events` socket keeps current — the same list the board
 * renders — and is what turns a bare `local:42` into a title. Without it every badge would take the
 * unknown arm below, which is a post-delete fallback and not the normal case.
 *
 * A ref that names no known task still renders, as the bare ref: `sessions.task_ref` is a REFERENCE and
 * not a foreign key, so a task deleted while a link write was in flight leaves one dangling until the
 * next daemon start clears it. Hiding the badge there would hide the anomaly; showing the ref names it.
 *
 * `known` decides only which class the caller spells (`task-badge` vs `task-badge-unknown`) — a resolved
 * entry whose tracker row is momentarily missing arrives with its ref as the title (the daemon's own
 * `BacklogEntry.toDto` fallback) and is still `known`, because the backlog really does carry it.
 */
export function taskBadge(session, tasks) {
  const ref = session && session.taskRef ? session.taskRef : null;
  if (!ref) return null;
  const entry = (tasks || []).find((t) => t && t.ref === ref) || null;
  const title = entry && entry.title ? entry.title : "";
  return {
    ref: ref,
    label: title || ref,
    known: !!entry,
    tooltip: entry
      ? ref + (title && title !== ref ? " — " + title : "")
      : ref + " — no such task (it may have just been deleted)",
  };
}

/**
 * Replace-or-append a full server row into the list, newest-rev-wins: every observation of a session —
 * an HTTP DTO or a WS session_row frame — carries the daemon-stamped `rev`, so a stale response that
 * lands after a fresher frame compares older and cannot roll the row back, whatever order the network
 * delivered them in. Returns the SAME array when nothing changed, so a setSessions caller keeps identity.
 */
export function upsertIfNewer(list, row) {
  const index = list.findIndex((s) => s.id === row.id);
  if (index < 0) return list.concat([row]);
  if (!(row.rev > list[index].rev)) return list;
  const next = list.slice();
  next[index] = row;
  return next;
}

/**
 * Apply a session_update patch to the row it names, newest-rev-wins; an unknown id leaves the list
 * untouched (the daemon only patches sessions the socket already carried as full rows). The patch is
 * authoritative for every field it carries — `model` is taken VERBATIM, null included, so a cleared
 * suspect model (the provider-id rebind correction) clears here too — and the patch's `rev` is written
 * onto the row: without that, a later stale full row would compare against the old rev and win.
 *
 * `taskRef` and `projectId` follow the same verbatim rule, and that is what MOVES the task badge: a
 * `kotgent task claim` typed inside a pane writes `sessions.task_ref` and the daemon emits exactly this
 * patch, so dropping either field here would leave every badge frozen at whatever the last full row
 * said until a reload. An unlink arrives as `taskRef: null` and must clear the badge, not keep it.
 */
export function patchIfNewer(list, msg) {
  const index = list.findIndex((s) => s.id === msg.sessionId);
  if (index < 0) return list;
  const prev = list[index];
  if (!(msg.rev > prev.rev)) return list;
  const next = list.slice();
  next[index] = Object.assign({}, prev, {
    state: msg.state,
    needsAttention: msg.needsAttention,
    alive: isAliveState(msg.state),
    lastSeq: msg.lastSeq,
    unread: msg.unread,
    archived: msg.archived,
    model: msg.model,
    taskRef: msg.taskRef,
    projectId: msg.projectId,
    rev: msg.rev,
  });
  return next;
}
