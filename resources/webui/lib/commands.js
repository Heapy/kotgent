// The sole command and mnemonic registry; session actions are omitted on the task-board screen.

import { displayName, isAliveState, isNeedsAttention, stateBadge } from "./sessions.js";

function disabledWhenNoSession(session) {
  return session ? null : "no session is selected";
}

function disabledWhenNotAlive(session) {
  if (!session) return "no session is selected";
  return isAliveState(session.state) ? null : "the selected session is not running";
}

function disabledWhenAlive(session) {
  if (!session) return "no session is selected";
  return isAliveState(session.state) ? "the selected session is already running" : null;
}

function disabledWhenNoProject(projectId) {
  return projectId ? null : "no project is selected";
}

function disabledWhenNoSessionTask(session) {
  if (!session) return "no session is selected";
  return session.taskRef ? null : "the selected session is not linked to a task";
}

// Control requests serialize globally; local attach/detach conflicts only with actions that rewrite attachment.
function disabledWhilePending(pendingAction) {
  return pendingAction ? "another action is still in progress" : null;
}

export function affectsAttachment(pendingAction) {
  return pendingAction === "stop" || pendingAction === "done" ||
    pendingAction === "resume" || pendingAction === "import";
}

function disabledWhileAttachmentPending(pendingAction) {
  return affectsAttachment(pendingAction) ? "another action is still in progress" : null;
}

function sessionSubtitle(session) {
  const tags = Array.isArray(session.tags) ? session.tags : [];
  return [session.agent, session.cwd, ...tags].filter(Boolean).join(" · ");
}

function sessionRows(sessions, actions) {
  return sessions.map((session) => ({
    id: "sessions.open." + session.id,
    group: "sessions",
    title: displayName(session),
    subtitle: sessionSubtitle(session),
    hint: session.archived ? "done" : stateBadge(session.state).label,
    chord: null,
    disabled: null,
    sessionId: session.id,
    needsAttention: isNeedsAttention(session.state) && !session.archived,
    run: () => actions.selectSession(session.id),
  }));
}

function sessionCommands(activeSession, attachedId, pendingAction, actions) {
  const alive = !!activeSession && isAliveState(activeSession.state);
  const attached = !!activeSession && activeSession.id === attachedId;
  const tmuxAvailable = alive && !!activeSession.tmuxSession;

  return [
    {
      id: "session.interrupt", group: "session", chord: "i",
      title: "Interrupt current session",
      subtitle: "sends Ctrl-C and marks the session ready",
      hint: "⌘K i",
      disabled: disabledWhilePending(pendingAction) || disabledWhenNotAlive(activeSession),
      run: () => actions.interrupt(),
    },
    {
      id: "session.resume", group: "session", chord: "u",
      title: "Resume this session",
      subtitle: "restarts the selected conversation",
      hint: "⌘K u",
      disabled: disabledWhilePending(pendingAction) || disabledWhenAlive(activeSession),
      run: () => actions.resume(),
    },
    {
      id: "session.attach", group: "session", chord: "a",
      title: "Attach current terminal",
      subtitle: "opens the selected live session",
      hint: "⌘K a",
      disabled: disabledWhileAttachmentPending(pendingAction) || (!alive
        ? disabledWhenNotAlive(activeSession)
        : (attached ? "the selected terminal is already attached" : null)),
      run: () => actions.attach(),
    },
    {
      id: "session.detach", group: "session", chord: "e",
      title: "Detach current terminal",
      subtitle: "leaves the agent running in tmux",
      hint: "⌘K e",
      disabled: disabledWhileAttachmentPending(pendingAction)
        || (attached ? null : "the selected terminal is not attached"),
      run: () => actions.detach(),
    },
    {
      id: "session.stop", group: "session", chord: "s",
      title: "Stop current session…",
      subtitle: "stops the agent but keeps the conversation resumable",
      hint: "⌘K s",
      disabled: disabledWhilePending(pendingAction) || disabledWhenNotAlive(activeSession),
      run: () => actions.stop(),
    },
    {
      id: "session.done", group: "session", chord: "d",
      title: "Done current session…",
      subtitle: "stops and hides the selected session",
      hint: "⌘K d",
      disabled: disabledWhilePending(pendingAction) || disabledWhenNoSession(activeSession),
      run: () => actions.done(),
    },
    {
      id: "session.copy-tmux", group: "session", chord: "c",
      title: "Copy tmux command",
      subtitle: "copies a local command for the selected live session",
      hint: "⌘K c",
      disabled: !activeSession
        ? "no session is selected"
        : (!alive
          ? "the selected session is not running"
          : (tmuxAvailable ? null : "the selected session has no tmux name")),
      run: () => actions.copyTmux(),
    },
    {
      id: "session.upload-files", group: "session", chord: "f",
      title: "Upload files to current folder…",
      subtitle: activeSession && activeSession.cwd
        ? activeSession.cwd
        : "sends files to the selected session's working directory",
      hint: "⌘K f",
      disabled: disabledWhenNoSession(activeSession),
      run: () => actions.uploadFiles(),
    },
    {
      id: "session.open-task", group: "session", chord: "j",
      title: "Open this session's task",
      subtitle: "jumps to the task this session is linked to",
      hint: "⌘K j",
      disabled: disabledWhenNoSessionTask(activeSession),
      run: () => actions.openSessionTask(),
    },
  ];
}

// The board is the only screen with a project selection, so the two commands that read one exist
// there alone. "New project" deliberately stays on both screens: it navigates to the board itself.
// The sidebar-only done toggle is the mirror case — one id, so a comparison rather than a set.
const BOARD_ONLY = new Set([
  "general.delete-project",
  "general.restore-project",
]);
const SESSION_VIEW_ONLY = "general.show-done";

// `o` means “the other screen”; leader mnemonics are first-match-wins and must remain unique.
function generalCommands(onBoard, projectId, actions) {
  const commands = [
    {
      id: "general.new", group: "general", chord: "n",
      title: "New session",
      subtitle: "starts a managed agent conversation",
      hint: "⌘K n",
      disabled: null,
      run: () => actions.newSession(),
    },
    {
      id: "general.import", group: "general", chord: "r",
      title: "Resume a conversation started outside kotgent…",
      subtitle: "opens Import existing with transcript discovery",
      hint: "⌘K r",
      disabled: null,
      run: () => actions.importSession(),
    },
    {
      id: "general.free-terminal", group: "general", chord: "t",
      title: "New free terminal",
      subtitle: "opens a tmux shell without an agent",
      hint: "⌘K t",
      disabled: null,
      run: () => actions.freeTerminal(),
    },
    {
      id: "general.task-board", group: "general", chord: "o",
      title: onBoard ? "Back to sessions" : "Open the task board",
      subtitle: onBoard
        ? "leaves the board for the session view"
        : "shows the project backlog and what every session is working on",
      hint: "⌘K o",
      disabled: null,
      run: () => (onBoard ? actions.openSessions() : actions.openBoard()),
    },
    {
      id: "general.new-task", group: "general", chord: "w",
      title: "New task",
      subtitle: "goes to the board and opens its create form",
      hint: "⌘K w",
      disabled: null,
      run: () => actions.newTask(),
    },
    {
      id: "general.new-project", group: "general", chord: null,
      title: "New project",
      subtitle: "goes to the board and opens its new-project form",
      hint: null,
      disabled: null,
      run: () => actions.newProject(),
    },
    {
      id: "general.delete-project", group: "general", chord: null,
      title: "Delete project…",
      subtitle: "hides the selected project; its tasks and its .kotgent.json are kept",
      hint: null,
      disabled: disabledWhenNoProject(projectId),
      run: () => actions.deleteProject(),
    },
    {
      id: "general.restore-project", group: "general", chord: null,
      title: "Restore a deleted project…",
      subtitle: "lists the deleted projects and brings one back with its backlog",
      hint: null,
      // Never disabled: only the daemon knows whether any project was ever deleted, and the dialog
      // asking it is what says so honestly.
      disabled: null,
      run: () => actions.restoreProject(),
    },
    {
      id: "general.show-done", group: "general", chord: null,
      title: "Show or hide done sessions",
      subtitle: "toggles archived sessions in the sidebar",
      hint: null,
      disabled: null,
      run: () => actions.toggleShowDone(),
    },
    {
      id: "general.help", group: "general", chord: "h",
      title: "Help",
      subtitle: "shows keyboard shortcuts and operating notes",
      hint: "⌘K h",
      disabled: null,
      run: () => actions.help(),
    },
    {
      id: "general.phone", group: "general", chord: "m",
      title: "Sign in from your phone",
      subtitle: "shows the public sign-in address and QR code",
      hint: "⌘K m",
      disabled: null,
      run: () => actions.phone(),
    },
    {
      id: "general.notifications", group: "general", chord: "b",
      title: "Toggle notifications",
      subtitle: "changes this device's push preference",
      hint: "⌘K b",
      disabled: "not implemented yet",
      run: () => {},
    },
    {
      id: "general.preferences", group: "general", chord: "p",
      title: "Preferences",
      subtitle: "changes grouping and terminal font size",
      hint: "⌘K p",
      disabled: null,
      run: () => actions.preferences(),
    },
  ];
  return commands.filter((command) => (onBoard
    ? command.id !== SESSION_VIEW_ONLY
    : !BOARD_ONLY.has(command.id)));
}

export function buildCommands({
  sessions = [], activeSession = null, attachedId = null, pendingAction = null,
  onBoard = false, projectId = null, actions,
}) {
  return [
    ...sessionRows(sessions, actions),
    ...(onBoard ? [] : sessionCommands(activeSession, attachedId, pendingAction, actions)),
    ...generalCommands(onBoard, projectId, actions),
  ];
}

function matchOf(item, query) {
  const haystack = (item.title + " " + (item.subtitle || "")).toLocaleLowerCase();
  const index = haystack.indexOf(query);
  if (index < 0) return null;
  const wordStart = index === 0 || !/[a-z0-9]/i.test(haystack.charAt(index - 1));
  return { wordStart: wordStart, index: index };
}

function rankedMatches(items, query) {
  return items
    .map((item, order) => ({ item: item, order: order, match: matchOf(item, query) }))
    .filter((entry) => entry.match !== null)
    .sort((left, right) => {
      if (left.match.wordStart !== right.match.wordStart) return left.match.wordStart ? -1 : 1;
      if (left.match.index !== right.match.index) return left.match.index - right.match.index;
      return left.order - right.order;
    })
    .map((entry) => entry.item);
}

export function filterCommands(items, query) {
  const normalized = (query || "").trim().toLocaleLowerCase();
  if (normalized.length > 0) {
    const matches = rankedMatches(items, normalized);
    return matches.filter((item) => !item.disabled)
      .concat(matches.filter((item) => !!item.disabled));
  }

  const seenSessions = new Set();
  const sessionItems = items.filter((item) => item.group === "sessions");
  const orderedSessions = sessionItems.filter((item) => item.needsAttention)
    .concat(sessionItems.filter((item) => !item.needsAttention))
    .filter((item) => {
      if (seenSessions.has(item.sessionId)) return false;
      seenSessions.add(item.sessionId);
      return true;
    });
  const availableCommands = items.filter((item) => item.group !== "sessions" && !item.disabled);
  return orderedSessions.concat(availableCommands);
}
