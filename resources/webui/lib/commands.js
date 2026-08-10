/*
 * The Web UI's one command registry and its pure search ordering.
 *
 * Components render these descriptors; they do not carry a second list of actions or mnemonics. The
 * closures keep routes and session-id handling in app.js, while this browser-free module owns which
 * commands exist, when they apply, and how they are found.
 *
 * **The registry is SCREEN-AWARE, because the app has two screens and only one of them shows a
 * session.** `/tasks` replaces the session view entirely, so while the board is up the palette used to
 * offer nine commands aimed at a session nobody could see: ⌘K a set `attachedId` with no `TerminalPane`
 * mounted (nothing happened at all), ⌘K e announced a detach from a terminal that was not on screen, and
 * Interrupt/Stop/Done acted on whatever row happened to be selected before the operator went to groom
 * the backlog. `onBoard` therefore drops the whole `session` group and the sidebar-only "show done"
 * toggle, and turns the one board mnemonic around: `o` opens the board from the session view and leads
 * back out of it from the board, where "Open the task board" was a command that did nothing.
 *
 * Session ROWS stay on both screens. Selecting one is navigation — app.js's `showSession` navigates to
 * `/s/{id}` — so on the board the search view is also the way back to a particular session.
 *
 * That division is why the three task commands call `actions.openBoard` / `actions.newTask` /
 * `actions.openSessionTask` rather than building a route or looking a session up here: navigation and the
 * session list are app state, and reaching for either would make this file a second holder of it as well
 * as the registry. The one session fact this file reads is the `taskRef` already on the descriptor's
 * `activeSession`, and it reads it only to decide whether the command applies.
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

/**
 * "Open this session's task" is a no-op for a session that carries no `taskRef`, so it is refused on
 * exactly that condition rather than offered as a chord that does nothing. Liveness is deliberately not
 * part of it: a stopped or archived session still points at the task it was working on, and reading that
 * task is precisely what an operator does after the agent finished.
 */
function disabledWhenNoSessionTask(session) {
  if (!session) return "no session is selected";
  return session.taskRef ? null : "the selected session is not linked to a task";
}

// app.js holds ONE pendingAction across every request it serialises, so there are two different
// questions here and the commit that introduced only the first got the second wrong.
//
// `disabledWhilePending` is for the four commands that go through `controlSession`, which refuses a
// second call outright whatever session it names — so offering one spends a chord on a request the app
// drops. (Restore reaches the same refusal from a sidebar row, but it is not a palette command.)
function disabledWhilePending(pendingAction) {
  return pendingAction ? "another action is still in progress" : null;
}

/**
 * Whether the in-flight action will write `attachedId` when it settles: stop and done detach, resume
 * attaches, and the import flow ends in a selection that does both. Interrupt and Restore never touch
 * the attachment, so blocking Attach/Detach during those refuses an operation that cannot conflict —
 * the operator is left unable to detach a live terminal because an unrelated archived row is being
 * restored. app.js guards the two handlers with this same rule, so the button and the handler agree.
 */
export function affectsAttachment(pendingAction) {
  return pendingAction === "stop" || pendingAction === "done" ||
    pendingAction === "resume" || pendingAction === "import";
}

// Attach and Detach are local state writes, NOT controlSession calls — the app cannot drop them, so
// their reason is the narrower one: only an action that will itself rewrite the attachment conflicts.
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

/**
 * The commands that act on the session the operator is LOOKING at, in the order the leader grid draws
 * them. They are built only for the session view: every one of them reads `activeSession`, and on the
 * board that row is a leftover selection behind a screen that shows a backlog (see the module header).
 *
 * `pendingAction` is a first-class disabled reason here, and always the FIRST one — an in-flight request
 * outranks every per-session condition — but in two strengths, because two different things are being
 * protected: see the pair of helpers above.
 */
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

/**
 * The commands that belong to the shell rather than to either screen — creating work, navigating between
 * the two screens, and the device-level dialogs.
 *
 * Two of them read [onBoard], and for opposite reasons. `general.task-board` is ONE mnemonic for "the
 * other screen": leaving `o` pointing at the board while the board is on screen spends the letter on a
 * navigation that is already done, and the board's own "Sessions" link is then the only way out — which
 * on a phone means finding a text link instead of the palette every other action lives in. Reusing the
 * letter rather than adding a second one is not only economy: `leaderKeyDown` resolves a letter
 * first-match-wins over the whole list, so two descriptors claiming `o` would make one of them a visible
 * grid row its own key can never reach, and the registry's chord uniqueness is asserted from the Kotlin
 * side as a property of this source text. `general.show-done` is dropped outright on the board because
 * the sidebar it toggles is precisely what the board screen unmounts.
 */
function generalCommands(onBoard, actions) {
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
      // Chordless on purpose: the board draws its own "New project" button, so this is the palette's
      // copy of an action the operator performs once per repository — the search list is where a rare
      // command belongs, and the leader grid is the small set worth memorising.
      id: "general.new-project", group: "general", chord: null,
      title: "New project",
      subtitle: "goes to the board and opens its new-project form",
      hint: null,
      disabled: null,
      run: () => actions.newProject(),
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
  // Filtered rather than conditionally spread so every descriptor above keeps one shape and one
  // indentation: the Kotlin-side contracts read this file as TEXT, and a descriptor that moves under an
  // `onBoard ? [] : [...]` arm changes where its slice ends without changing anything about the command.
  return onBoard ? commands.filter((command) => command.id !== "general.show-done") : commands;
}

/**
 * Build every command from app-owned actions. Keep this as the only command/mnemonic registry.
 *
 * `actions` contains closures rather than route names: the app remains responsible for confirmation,
 * status reporting, dialog state, and the active session changing between renders. [onBoard] is which
 * screen the router has put on, and it is the app's answer rather than this module's, for the same
 * reason: the route is app state (see the module header for what it decides here).
 *
 * Session rows deliberately take no pending reason: selecting a session is navigation, and `showSession`
 * writes the attachment coherently with the selection it just made.
 */
export function buildCommands({
  sessions = [], activeSession = null, attachedId = null, pendingAction = null,
  onBoard = false, actions,
}) {
  return [
    ...sessionRows(sessions, actions),
    ...(onBoard ? [] : sessionCommands(activeSession, attachedId, pendingAction, actions)),
    ...generalCommands(onBoard, actions),
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
