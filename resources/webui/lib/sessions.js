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
