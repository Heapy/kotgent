// Keep the canonical state set in step with io.kotgent.core.SessionState.

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

export function isNeedsAttention(state) {
  return state === "needs_approval" || state === "needs_answer";
}

export function isAliveState(state) {
  return state === "running" || state === "ready" ||
    state === "needs_approval" || state === "needs_answer";
}

export function displayName(s) {
  if (s.name && s.name.length > 0) return s.name;
  if (s.tmuxSession && s.tmuxSession.length > 0) return s.tmuxSession;
  return s.id;
}

export function tmuxAttachCommand(tmuxSession) {
  return "tmux -u -L kotgent attach -t " + tmuxSession;
}

export function capitalize(text) {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function sessionSubline(s) {
  const agent = s.agent || "?";
  const detail = [s.model, s.cliVersion].filter(Boolean).join(" · ");
  return detail ? agent + " · " + detail : agent + " · " + (s.cwd || "");
}

// A dangling task reference remains visible so a delete/link race is not hidden.
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

// HTTP and WebSocket observations share the daemon's monotonic revision order.
export function upsertIfNewer(list, row) {
  const index = list.findIndex((s) => s.id === row.id);
  if (index < 0) return list.concat([row]);
  if (!(row.rev > list[index].rev)) return list;
  const next = list.slice();
  next[index] = row;
  return next;
}

// Stamp the patch revision and apply nullable fields verbatim; null is an authoritative clear.
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
    // A daemon older than this field omits it; the snapshot's stamp stays authoritative then.
    updatedAt: msg.updatedAt || prev.updatedAt,
    rev: msg.rev,
  });
  return next;
}

// Done sessions read newest-first: the archive stamp is the last thing that happened to the row.
export function byRecentChange(list) {
  return list.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}
