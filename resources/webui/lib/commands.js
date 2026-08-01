/*
 * The Web UI's one command registry and its pure search ordering.
 *
 * Components render these descriptors; they do not carry a second list of actions or mnemonics. The
 * closures keep routes and session-id handling in app.js, while this browser-free module owns which
 * commands exist, when they apply, and how they are found.
 */

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

/**
 * Build every command from app-owned actions. Keep this as the only command/mnemonic registry.
 *
 * `actions` contains closures rather than route names: the app remains responsible for confirmation,
 * status reporting, dialog state, and the active session changing between renders.
 */
export function buildCommands({ sessions = [], activeSession = null, attachedId = null, actions }) {
  const alive = !!activeSession && isAliveState(activeSession.state);
  const attached = !!activeSession && activeSession.id === attachedId;
  const tmuxAvailable = alive && !!activeSession.tmuxSession;

  return [
    ...sessionRows(sessions, actions),

    {
      id: "session.interrupt", group: "session", chord: "i",
      title: "Interrupt current session",
      subtitle: "sends Ctrl-C and marks the session ready",
      hint: "⌘K i",
      disabled: disabledWhenNotAlive(activeSession),
      run: () => actions.interrupt(),
    },
    {
      id: "session.resume", group: "session", chord: "u",
      title: "Resume this session",
      subtitle: "restarts the selected conversation",
      hint: "⌘K u",
      disabled: disabledWhenAlive(activeSession),
      run: () => actions.resume(),
    },
    {
      id: "session.attach", group: "session", chord: null,
      title: "Attach current terminal",
      subtitle: "opens the selected live session",
      hint: null,
      disabled: !alive
        ? disabledWhenNotAlive(activeSession)
        : (attached ? "the selected terminal is already attached" : null),
      run: () => actions.attach(),
    },
    {
      id: "session.detach", group: "session", chord: null,
      title: "Detach current terminal",
      subtitle: "leaves the agent running in tmux",
      hint: null,
      disabled: attached ? null : "the selected terminal is not attached",
      run: () => actions.detach(),
    },
    {
      id: "session.stop", group: "session", chord: "s",
      title: "Stop current session…",
      subtitle: "stops the agent but keeps the conversation resumable",
      hint: "⌘K s",
      disabled: disabledWhenNotAlive(activeSession),
      run: () => actions.stop(),
    },
    {
      id: "session.done", group: "session", chord: "d",
      title: "Done current session…",
      subtitle: "stops and hides the selected session",
      hint: "⌘K d",
      disabled: disabledWhenNoSession(activeSession),
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

/**
 * Search by case-insensitive substring. Available matches always precede the disabled tail; with no
 * query, disabled commands disappear and attention sessions lead the deduplicated session list.
 */
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
