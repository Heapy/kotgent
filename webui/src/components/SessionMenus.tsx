import type { JSX } from "preact";
import type { Command } from "../lib/commands.ts";
import { displayName, stateBadge, taskBadge } from "../lib/sessions.ts";
import type { Session } from "../lib/sessions.ts";
import type { Task } from "../lib/tasks.ts";
import { navigate, taskPath } from "../lib/router.ts";
import { sessionMutexPills } from "../lib/mutexes.ts";
import { mutexes } from "../state/mutexes.ts";
import { openPlanTab } from "../state/layout.ts";
import { HeaderPopover } from "./HeaderPopover.tsx";
import { Icon } from "./Icon.tsx";
import { MutexIndicator, MutexPills } from "./MutexesScreen.tsx";
import { SessionStatus } from "./SessionStatus.tsx";

interface SessionMenuProps {
  session: Session;
  commands: readonly Command[];
}

/** Use the same availability checks and handlers as the command palette. */
function CommandButton({ command, label, icon, shortcut, close, className = "session-menu-row" }: {
  command: Command | undefined;
  label: string;
  icon?: Parameters<typeof Icon>[0]["name"];
  shortcut?: string;
  close: () => void;
  className?: string;
}) {
  if (!command) return null;
  return <button type="button" class={className} disabled={!!command.disabled}
    title={command.disabled ?? undefined} onClick={() => { if (!command.disabled) { close(); command.run(); } }}>
    {icon && <Icon name={icon} />}<span>{label}</span>{shortcut && <kbd>{shortcut}</kbd>}
  </button>;
}

export function SessionDetails({ session, tasks, commands }: SessionMenuProps & { tasks: readonly Task[] }) {
  const badge = stateBadge(session.state);
  const task = taskBadge(session, tasks);
  const hasMutexes = sessionMutexPills(mutexes.value, session.id).length > 0;
  const openTask: JSX.MouseEventHandler<HTMLAnchorElement> = (event) => {
    if (!task || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(taskPath(task.ref));
  };
  return <HeaderPopover id="session-details" label="Session details"
    className="session-details-toggle" panelClass="session-details"
    trigger={<><SessionStatus state={session.state} /><span id="terminal-title">{displayName(session)}</span>
      <MutexIndicator sessionId={session.id} /><Icon name="caret" /></>}>
    {(close) => <>
      <h2 class="session-details-name">{displayName(session)}</h2>
      <dl class="session-details-values">
        <dt>Status</dt><dd id="terminal-state" class={"session-status-text " + badge.cls}>{badge.label}</dd>
        <dt>Agent</dt><dd>{[session.agent, session.model].filter(Boolean).join(" · ")}</dd>
        <dt>Folder</dt><dd id="terminal-cwd" title={session.cwd}>{session.cwd || "—"}</dd>
        <dt>Task</dt><dd>{task ? <a id="terminal-task" href={taskPath(task.ref)} title={task.tooltip}
          onClick={(event) => { openTask(event); if (event.defaultPrevented) close(); }}>{task.label}</a> : "Not linked"}</dd>
        {hasMutexes && <><dt>Mutex</dt><dd><MutexPills sessionId={session.id} /></dd></>}
      </dl>
      <div class="session-details-actions">
        <CommandButton command={commands.find(c => c.id === "session.open-task")} label="Open task"
          className="button" close={close} />
        <CommandButton command={commands.find(c => c.id === "session.rename")} label="Rename"
          className="button" close={close} />
      </div>
    </>}
  </HeaderPopover>;
}

export function SessionActions({ session, commands, onOpenPalette, onCopyCwd }: SessionMenuProps & {
  onOpenPalette: () => void;
  onCopyCwd: () => void;
}) {
  return <HeaderPopover id="session-actions" label="Session actions" className="session-actions-toggle"
    panelClass="session-actions-menu" trigger={<Icon name="more" />}>
    {(close) => <>
      <p class="session-menu-label">Session</p>
      <button id="palette-button" type="button" class="session-menu-row" aria-label="Open command palette"
        onClick={() => { close(); onOpenPalette(); }}><Icon name="command" /><span>Commands</span><kbd>⌘K</kbd></button>
      <CommandButton command={commands.find(c => c.id === "session.open-task")} label="Open linked task" icon="task" close={close} />
      <button type="button" class="session-menu-row" disabled={!session.taskRef}
        title={session.taskRef ? undefined : "This session is not linked to a task"}
        onClick={() => { close(); openPlanTab(session.id); }}><Icon name="columns" /><span>Review beside terminal</span></button>
      <hr />
      <CommandButton command={commands.find(c => c.id === "session.rename")} label="Rename session" icon="rename" close={close} />
      <button type="button" class="session-menu-row" disabled={!session.cwd}
        onClick={() => { close(); onCopyCwd(); }}><Icon name="copy" /><span>Copy working directory</span></button>
      <CommandButton command={commands.find(c => c.id === "general.mutexes")} label="Show mutexes" icon="lock" close={close} />
      <hr />
      <CommandButton command={commands.find(c => c.id === "session.interrupt")} label="Interrupt" icon="pause" shortcut="Ctrl C" close={close} />
      <CommandButton command={commands.find(c => c.id === "session.done")} label="Mark done…" icon="check" close={close} />
    </>}
  </HeaderPopover>;
}
