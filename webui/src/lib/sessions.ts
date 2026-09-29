// Keep the canonical state set in step with io.kotgent.core.SessionState.

import type { Project, Task } from "./tasks.ts";

import { isOpenTaskState } from "./tasks.ts";

export interface Session {
  id: string;
  name: string;
  tags: string[];
  agent: string;
  model: string | null;
  cliVersion: string | null;
  cliPath: string | null;
  providerSessionId: string | null;
  state: string;
  needsAttention: boolean;
  alive: boolean;
  cwd: string;
  tmuxSession: string;
  paneId: string | null;
  lastSeq: number;
  readCursor: number;
  unread: number;
  createdAt: number;
  updatedAt: number;
  archived: boolean;
  rev: number;
  taskRef: string | null;
  projectId: string | null;
  adhd: boolean;
}

export interface SessionUpdate {
  sessionId: string;
  state: string;
  needsAttention: boolean;
  lastSeq: number;
  unread: number;
  archived: boolean;
  model: string | null;
  name?: string | null;
  adhd?: boolean | null;
  rev: number;
  updatedAt: number;
  taskRef: string | null;
  projectId: string | null;
}

export function stateBadge(state: string | null | undefined) {
  switch (state) {
    case "running":       return { label: "running", cls: "badge-running" };
    case "ready":         return { label: "ready", cls: "badge-ready" };
    case "needs_approval":return { label: "needs approval", cls: "badge-attention" };
    case "needs_answer":  return { label: "needs answer", cls: "badge-attention" };
    case "stopped":       return { label: "stopped", cls: "badge-dead" };
    case "crashed":       return { label: "crashed", cls: "badge-crashed" };
    case "lost":          return { label: "lost", cls: "badge-lost" };
    case "resumable":     return { label: "resumable", cls: "badge-resumable" };
    default:              return { label: state || "unknown", cls: "badge-dead" };
  }
}

export function isNeedsAttention(state: string | null | undefined) {
  return state === "needs_approval" || state === "needs_answer";
}

export function isLostState(state: string | null | undefined) {
  return state === "lost";
}

export function isAliveState(state: string | null | undefined) {
  return state === "running" || state === "ready" ||
    state === "needs_approval" || state === "needs_answer";
}

export function sessionTaskLinkDisabledReason(session: Session | null | undefined, pendingAction: string | null = null) {
  if (pendingAction) return "another action is still in progress";
  if (!session) return "no session is selected";
  if (!isAliveState(session.state)) return "the selected session is not running";
  if (session.taskRef) return "the selected session is already linked to " + session.taskRef;
  return session.projectId ? null : "the selected session has no project";
}

// The picker's guard above answers whether linking is offered. This one re-checks the same world one
// statement before the POST, when the operator has already chosen a task and every input may have moved
// underneath the open dialog. The clause order is load-bearing: the refusal reason runs first, so a
// vanished session is refused rather than dereferenced.
export function sessionTaskLinkSubmitBlocked({
  session,
  pendingAction = null,
  activeSessionId,
  sessionId,
  expectedProjectId,
  projects,
  task,
}: {
  session: Session | null | undefined;
  pendingAction?: string | null;
  activeSessionId: string | null;
  sessionId: string;
  expectedProjectId: string | null;
  projects: readonly Project[] | null | undefined;
  task: Task | null | undefined;
}) {
  if (sessionTaskLinkDisabledReason(session, pendingAction) || !session) return true;
  if (activeSessionId !== sessionId) return true;
  if (session.projectId !== expectedProjectId) return true;
  if (!(projects || []).some((project) => project.id === expectedProjectId)) return true;
  if (!task || task.project !== expectedProjectId) return true;
  return !isOpenTaskState(task.state);
}

// Case folding here is locale-independent on purpose. `toLocaleLowerCase()` maps "I" to a dotless "ı"
// under a tr/az browser locale, so a task titled "Index the API" would stop matching a typed "index"
// for exactly the operators whose locale the picker never anticipated.
export function normalizeTaskQuery(query: string | null | undefined) {
  return (query || "").trim().toLowerCase();
}

export function taskMatchesQuery(task: Task | null | undefined, normalizedQuery: string) {
  if (!normalizedQuery) return true;
  if (!task) return false;
  return (task.ref + " " + (task.title || "")).toLowerCase().includes(normalizedQuery);
}

// What the committed link turned out to be, read back from the session row rather than assumed from the
// accepted request: the link response carries no row, so a racing frame can still have moved the badge.
export function sessionTaskLinkOutcome({ label, ref, fresh, winner }: { label: string; ref: string; fresh: boolean; winner: Session | null | undefined }) {
  if (!fresh) {
    return {
      text: "Linked " + label + " to " + ref +
        ", but the session could not be re-read. A live update may still bring the badge in.",
      error: true,
    };
  }
  if (!winner || winner.taskRef !== ref) {
    return {
      text: "The link request completed, but " + (winner ? displayName(winner) : label) +
        " is now linked to " + ((winner && winner.taskRef) || "no task") + ".",
      error: true,
    };
  }
  return { text: "Linked " + displayName(winner) + " to " + ref + ".", error: false };
}

export function displayName(s: Pick<Session, "id" | "name" | "tmuxSession">) {
  if (s.name && s.name.length > 0) return s.name;
  if (s.tmuxSession && s.tmuxSession.length > 0) return s.tmuxSession;
  return s.id;
}

export function tmuxAttachCommand(tmuxSession: string) {
  return "tmux -u -L kotgent attach -t " + tmuxSession;
}

export function capitalize(text: string) {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function sessionSubline(s: Pick<Session, "agent" | "model" | "cliVersion" | "cwd">) {
  const agent = s.agent || "?";
  const detail = [s.model, s.cliVersion].filter(Boolean).join(" · ");
  return detail ? agent + " · " + detail : agent + " · " + (s.cwd || "");
}

// A dangling task reference remains visible so a delete/link race is not hidden.
export function taskBadge(session: Session | null | undefined, tasks: readonly Task[] | null | undefined) {
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
export function upsertIfNewer<T extends Pick<Session, "id" | "rev">>(list: readonly T[], row: T): readonly T[] {
  const index = list.findIndex((s) => s.id === row.id);
  if (index < 0) return list.concat([row]);
  if (!(row.rev > list[index]!.rev)) return list;
  const next = list.slice();
  next[index] = row;
  return next;
}

// Stamp the patch revision and apply nullable fields verbatim; null is an authoritative clear. `name`
// and `adhd` are the exceptions: an absent one keeps the value the row already holds.
export function patchIfNewer(list: readonly Session[], msg: SessionUpdate): readonly Session[] {
  const index = list.findIndex((s) => s.id === msg.sessionId);
  if (index < 0) return list;
  const prev = list[index]!;
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
    // See the wire contract on `SessionUpdateDto` in src/transport/EventsWs.kt.
    name: msg.name != null ? msg.name : prev.name,
    adhd: msg.adhd != null ? msg.adhd : prev.adhd,
    updatedAt: msg.updatedAt || prev.updatedAt,
    rev: msg.rev,
  });
  return next;
}

// Done sessions read newest-first: the archive stamp is the last thing that happened to the row.
export function byRecentChange<T extends { updatedAt?: number }>(list: readonly T[]) {
  return list.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}
