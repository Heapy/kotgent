import { html } from "htm/preact";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { UsageStrip } from "./UsageStrip.js";
import { groupEntries, groupSessions, orderGroupsByRecentChange } from "../lib/paths.js";
import { adhdFolderOf, isPathAdhd, isSessionInAdhd } from "../lib/adhd.js";
import {
  groupingEnabled,
  loadAttentionCollapsed,
  loadCollapsedGroups,
  persistAttentionCollapsed,
  persistCollapsedGroups,
} from "../lib/prefs.js";
import { FAILED, IDLE_STATUS, READY } from "../lib/readiness.js";
import { ensurePermission, isEnabled as notifyEnabled, setEnabled as setNotifyEnabled } from "../lib/notify.js";
import {
  PUSH_REPAIR_SIGNAL_KEY,
  refreshActive as refreshPush,
  subscribe as pushSubscribe,
  syncWorkerPushPreference,
  unsubscribe as pushUnsubscribe,
} from "../lib/push.js";
import {
  byRecentChange,
  displayName,
  isNeedsAttention,
  sessionSubline,
  stateBadge,
  taskBadge,
} from "../lib/sessions.js";
import {
  SCREEN_SESSIONS,
  SCREEN_TASKS,
  navigate,
  routePath,
  taskPath,
} from "../lib/router.js";

const PUSH_TRANSITION_TIMEOUT_MS = 10_000;

const TASKS_PATH = routePath({ screen: SCREEN_TASKS, id: null });

/** Preserve real links; route only plain clicks in-app. */
function NavSwitch({ screen, sessionsPath }) {
  const links = [
    { screen: SCREEN_SESSIONS, path: sessionsPath, label: "Sessions" },
    { screen: SCREEN_TASKS, path: TASKS_PATH, label: "Tasks" },
  ];
  const go = (path) => (event) => {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (event.button !== undefined && event.button !== 0) return;
    event.preventDefault();
    navigate(path);
  };
  return html`
    <nav class="nav-switch" aria-label="Screen">
      ${links.map((link) => html`
        <a
          key=${link.screen}
          class=${"nav-link" + (screen === link.screen ? " active" : "")}
          href=${link.path}
          aria-current=${screen === link.screen ? "page" : null}
          onClick=${go(link.path)}
        >${link.label}</a>`)}
    </nav>`;
}

const NOTIFY_MUTE_SLASH = "M4.7 4.7L19.7 19.7";

const NOTIFY_BELL_BODY =
  "M12 2.65a1.35 1.35 0 0 0-1.35 1.35C7.6 4.75 6.15 7 6.15 10.1c0 3.4-.65 5.5-1.9 6.7-.75.7-.25 1.8.75 " +
  "1.8h14c1 0 1.5-1.1.75-1.8-1.25-1.2-1.9-3.3-1.9-6.7 0-3.1-1.45-5.35-4.5-6.1A1.35 1.35 0 0 0 12 2.65z";
const NOTIFY_BELL_CLAPPER = "M9.75 19.9a2.25 2.25 0 0 0 4.5 0z";

const notifyBell = (mask) => html`
  <g mask=${mask}>
    <path d=${NOTIFY_BELL_BODY} />
    <path d=${NOTIFY_BELL_CLAPPER} />
  </g>`;

function NotifyIcon({ on }) {
  return html`
    <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true" fill="currentColor">
      ${on ? notifyBell(null) : html`
        <mask id="notify-mute-cut" maskUnits="userSpaceOnUse" x="0" y="0" width="24" height="24">
          <rect x="0" y="0" width="24" height="24" fill="#fff" />
          <path d=${NOTIFY_MUTE_SLASH} fill="none" stroke="#000" stroke-width="4.4" stroke-linecap="round" />
        </mask>
        ${notifyBell("url(#notify-mute-cut)")}
        <path
          d=${NOTIFY_MUTE_SLASH}
          fill="none"
          stroke="currentColor"
          stroke-width="1.9"
          stroke-linecap="round"
        />`}
    </svg>`;
}

const ADHD_PIN_HEAD =
  "M19.25,10,14,4.75a3,3,0,0,0-.5,2.75l-3,3a3.4,3.4,0,0,0-4,0l7,7c.81-1.33,1-2.78,0-4l3-3A2.79,2.79,0,0,0,19.25,10Z";

function AdhdIcon({ on }) {
  return html`
    <svg
      viewBox="0 0 24 24"
      focusable="false"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path d=${ADHD_PIN_HEAD} fill=${on ? "currentColor" : "none"} />
      <line x1="9.87" y1="14.12" x2="4.75" y2="19.25" />
    </svg>`;
}

function stopRowActivation(event) {
  event.stopPropagation();
}

const DONE_BOX_LID =
  "M3.6 4.4h16.8a1.4 1.4 0 0 1 1.4 1.4v2.2a1.4 1.4 0 0 1-1.4 1.4H3.6A1.4 1.4 0 0 1 2.2 8V5.8a1.4 1.4 0 0 1 1.4-1.4z";
const DONE_BOX_BODY = "M4.3 10.6h15.4v7.2a2.2 2.2 0 0 1-2.2 2.2H6.5a2.2 2.2 0 0 1-2.2-2.2z";
const DONE_BOX_SLOT = "M9.6 13.9h4.8";

function DoneIcon() {
  return html`
    <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true" fill="currentColor">
      <mask id="done-slot-cut" maskUnits="userSpaceOnUse" x="0" y="0" width="24" height="24">
        <rect x="0" y="0" width="24" height="24" fill="#fff" />
        <path d=${DONE_BOX_SLOT} fill="none" stroke="#000" stroke-width="2.2" stroke-linecap="round" />
      </mask>
      <path d=${DONE_BOX_LID} />
      <path d=${DONE_BOX_BODY} mask="url(#done-slot-cut)" />
    </svg>`;
}

/** Project counts include only open tasks and derive from the live task list. */
function ProjectRow({ project, open, active, onSelect }) {
  const select = () => onSelect(project.id);
  const onKeyDown = (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      select();
    }
  };
  const name = project.name || project.id;
  return html`
    <li
      class=${"project-row" + (active ? " active" : "")}
      data-id=${project.id}
      tabIndex="0"
      role="button"
      aria-label=${"Show the backlog of " + name}
      aria-current=${active ? "true" : null}
      title=${project.path || ""}
      onClick=${select}
      onKeyDown=${onKeyDown}
    >
      <div class="project-main">
        <div class="project-name">${name}</div>
        <div class="project-sub">${project.path || ""}</div>
      </div>
      ${open > 0 &&
        html`<span class="pill project-count" title=${open + " open task(s)"}>${open}</span>`}
    </li>
  `;
}

/** Time out serialized push work without making it stale; only a newer generation does that. */
function boundedPushTransition(operation, isGenerationCurrent, repairLatest, onController) {
  const controller = new AbortController();
  let timeout = null;
  const context = {
    isCurrent: isGenerationCurrent,
    repairLatest: repairLatest,
    signal: controller.signal,
  };
  onController(controller, controller);
  const task = Promise.resolve()
    .then(() => operation(context))
    .finally(() => onController(null, controller));
  const deadline = new Promise((_, reject) => {
    timeout = setTimeout(() => {
      reject(new Error("push subscription transition timed out"));
    }, PUSH_TRANSITION_TIMEOUT_MS);
    controller.signal.addEventListener("abort", () => {
      onController(null, controller);
      reject(new Error("push subscription transition cancelled"));
    }, { once: true });
  });
  return Promise.race([task, deadline]).finally(() => {
    if (timeout !== null) clearTimeout(timeout);
  });
}

/** Stop task-badge clicks from also selecting the containing session row. */
function TaskBadge({ session, tasks }) {
  const task = taskBadge(session, tasks);
  if (!task) return null;
  const open = (event) => {
    event.stopPropagation();
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(taskPath(task.ref));
  };
  return html`
    <a
      class=${"task-badge" + (task.known ? "" : " task-badge-unknown")}
      href=${taskPath(task.ref)}
      title=${task.tooltip}
      onClick=${open}
    >
      <span class="task-session-dot" data-state=${session.state}></span>${task.label}
    </a>
  `;
}

// A session under a marked folder head is listed without its own mark. Its pin says so, instead of offering
// to add something that is already in, and a click gives it a mark that outlives the folder's.
function adhdPinState(session, prefs) {
  const name = displayName(session);
  if (session.adhd === true) {
    return {
      cls: " active",
      on: true,
      label: "Remove " + name + " from ADHD mode",
      title: "In ADHD mode — click to remove",
    };
  }
  const folder = adhdFolderOf(session.cwd, prefs);
  if (folder !== null) {
    return {
      cls: " covered",
      on: true,
      label: "Pin " + name + " on its own; it is already in ADHD mode through " + folder,
      title: "In ADHD mode through " + folder + " — click to pin it on its own",
    };
  }
  return { cls: "", on: false, label: "Add " + name + " to ADHD mode", title: "Add to ADHD mode" };
}

function SessionRow({ session, tasks, active, onSelect, onRestore, onMark, prefs }) {
  const badge = stateBadge(session.state);
  const pin = onMark ? adhdPinState(session, prefs) : null;
  const select = () => onSelect(session.id);
  const onKeyDown = (event) => {
    // Enter on an inner button or link bubbles here; cancelling it would select instead of activating it.
    if (event.target !== event.currentTarget) return;
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      select();
    }
  };

  return html`
    <li
      class=${"session-row" + (active ? " active" : "")}
      data-id=${session.id}
      tabIndex="0"
      role="button"
      aria-label=${"Open " + displayName(session) + ", " + badge.label}
      aria-current=${active ? "true" : null}
      title=${session.cwd || ""}
      onClick=${select}
      onKeyDown=${onKeyDown}
    >
      <div class="session-main">
        <div class="session-name">${displayName(session)}</div>
        <div class="session-sub">${sessionSubline(session)}</div>
        <${TaskBadge} session=${session} tasks=${tasks} />
      </div>
      ${session.unread > 0 &&
        html`<span class="pill unread-pill" title=${session.unread + " unread event(s)"}>
          ${session.unread}
        </span>`}
      ${pin && html`
        <div class="row-actions">
          <button
            type="button"
            class=${"icon-button icon-button-small row-adhd" + pin.cls}
            aria-pressed=${session.adhd === true ? "true" : "false"}
            aria-label=${pin.label}
            title=${pin.title}
            onClick=${(e) => { stopRowActivation(e); onMark(e, session); }}
          ><${AdhdIcon} on=${pin.on} /></button>
        </div>`}
      ${onRestore
        ? html`<button
            type="button"
            class="button button-quiet button-small session-restore"
            title="Bring this session back to the sidebar"
            onClick=${(e) => { e.stopPropagation(); onRestore(session.id); }}
          >Restore</button>`
        : html`<span class=${"pill badge " + badge.cls}>${badge.label}</span>`}
    </li>
  `;
}

function Chevron({ collapsed }) {
  return html`<span class="group-chevron" aria-hidden="true">${collapsed ? "▸" : "▾"}</span>`;
}

function groupNeedsAttention(group) {
  return group.sessions.some((s) => isNeedsAttention(s.state)) ||
    group.children.some(groupNeedsAttention);
}

function SessionGroup({
  group, tasks, activeId, collapsedGroups, onSelect, onToggle, onNewSession, onRestore, onMark,
  onMarkFolder, prefs, done = false,
}) {
  const folderMarked = isPathAdhd(group.path, prefs.adhdPaths);
  // The archive tree mirrors the live one, so its folders need collapse keys of their own.
  const collapseKey = (done ? "done:" : "") + group.path;
  const collapsed = collapsedGroups.has(collapseKey);
  const hidingAttention = !done && collapsed && groupNeedsAttention(group);

  return html`
    <li class=${"session-group" + (collapsed ? " collapsed" : "")}>
      <div class="group-head">
        <button
          type="button"
          class="group-toggle"
          aria-expanded=${collapsed ? "false" : "true"}
          title=${(collapsed ? "Expand " : "Collapse ") + (group.path || group.label)}
          onClick=${() => onToggle(collapseKey)}
        >
          <${Chevron} collapsed=${collapsed} />
          <span class="group-title" title=${group.path || group.label}>${group.label}</span>
          <span class="group-count">${group.sessionCount}</span>
          ${hidingAttention &&
            html`<span class="attn-dot" title="A session in this group needs attention"></span>`}
        </button>
        ${onMarkFolder && group.path &&
          html`<button
            type="button"
            class=${"icon-button icon-button-small group-adhd" + (folderMarked ? " active" : "")}
            aria-pressed=${folderMarked ? "true" : "false"}
            aria-label=${folderMarked
              ? "Remove " + group.path + " from ADHD mode"
              : "Add " + group.path + " to ADHD mode"}
            title=${folderMarked
              ? group.path + " is in ADHD mode — click to remove"
              : "Add " + group.path + " to ADHD mode"}
            onClick=${(e) => onMarkFolder(e, group.path, !folderMarked)}
          ><${AdhdIcon} on=${folderMarked} /></button>`}
        ${!done && group.path &&
          html`<button
            type="button"
            class="icon-button icon-button-small group-new"
            title=${"New session in " + group.path}
            aria-label=${"New session in " + group.path}
            onClick=${() => onNewSession(group.path)}
          >+</button>`}
      </div>
      ${!collapsed && html`
        <ul class="session-list group-contents">
          ${groupEntries(group).map((entry) => (entry.session
            ? html`
              <${SessionRow} key=${entry.session.id} session=${entry.session} tasks=${tasks}
                             active=${entry.session.id === activeId} onSelect=${onSelect}
                             onRestore=${onRestore} onMark=${onMark} prefs=${prefs} />`
            : html`
              <${SessionGroup}
                key=${entry.group.path}
                group=${entry.group}
                tasks=${tasks}
                activeId=${activeId}
                collapsedGroups=${collapsedGroups}
                onSelect=${onSelect}
                onToggle=${onToggle}
                onNewSession=${onNewSession}
                onRestore=${onRestore}
                onMark=${onMark}
                onMarkFolder=${onMarkFolder}
                prefs=${prefs}
                done=${done}
              />`))}
        </ul>
      `}
    </li>
  `;
}

export function Sidebar({
  screen = SCREEN_SESSIONS,
  sessions, tasks, projects = [], projectId = null, activeId, prefs, status, currentVersion,
  drawerOpen, collapsed, showDone, sessionsReady, prefsStatus = IDLE_STATUS, onRetryPrefs,
  onSelect, onSelectProject, onNewSession, onNewProject, onOpenPrefs, onRestore, onCloseDrawer,
  onToggleShowDone, onMarkSession, onMarkFolder, adhdMode = false, onToggleAdhdMode, onAnnounce,
}) {
  const [collapsedGroups, setCollapsedGroups] = useState(loadCollapsedGroups);
  const [attentionCollapsed, setAttentionCollapsed] = useState(loadAttentionCollapsed);
  const [notifyOn, setNotifyOn] = useState(notifyEnabled());
  const notifyOnRef = useRef(notifyOn);
  const pushTransitionRef = useRef(Promise.resolve());
  const pushTransitionIdRef = useRef(0);
  const pushTransitionAbortRef = useRef(new Set());
  const pushRepairGenerationRef = useRef(null);
  const pushPermissionRef = useRef({ transition: 0, request: null });
  const repairPushRef = useRef(() => {});
  const adhdToggleRef = useRef(null);
  // A passive effect runs after paint, so a reload right after a toggle could drop the write.
  useLayoutEffect(() => { persistCollapsedGroups(collapsedGroups); }, [collapsedGroups]);
  useLayoutEffect(() => { persistAttentionCollapsed(attentionCollapsed); }, [attentionCollapsed]);
  const queuePushTransition = useCallback((transition, desired, operation, warning) => {
    // Local generations order this tab; the stored preference orders tabs.
    const isGenerationCurrent = () =>
      transition === pushTransitionIdRef.current && notifyEnabled() === desired;
    pushTransitionRef.current = pushTransitionRef.current
      .then(() => {
        if (!isGenerationCurrent()) return undefined;
        return boundedPushTransition(
          operation,
          isGenerationCurrent,
          () => repairPushRef.current(),
          (controller, owner) => {
            if (controller) pushTransitionAbortRef.current.add(controller);
            else pushTransitionAbortRef.current.delete(owner);
          },
        );
      })
      .catch((e) => console.warn(warning, e));
  }, []);
  // Mount reconciliation must not mint newer intent than a click or storage event.
  repairPushRef.current = () => {
    const transition = pushTransitionIdRef.current;
    const desired = notifyEnabled();
    const repairGeneration = transition + ":" + desired;
    if (pushRepairGenerationRef.current === repairGeneration) return;
    pushRepairGenerationRef.current = repairGeneration;
    if (notifyOnRef.current !== desired) {
      notifyOnRef.current = desired;
      setNotifyOn(desired);
    }
    queuePushTransition(
      transition,
      desired,
      (context) => {
        if (pushRepairGenerationRef.current === repairGeneration) {
          pushRepairGenerationRef.current = null;
        }
        if (!context.isCurrent()) return undefined;
        const permission = pushPermissionRef.current;
        return desired
          ? (permission.transition === transition && permission.request
              ? pushSubscribe(permission.request, context)
              : refreshPush(context))
          : pushUnsubscribe(context);
      },
      "kotgent: push subscription repair failed",
    );
  };
  const toggleGroup = useCallback((path) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (!next.delete(path)) next.add(path);
      return next;
    });
  }, []);
  // Reconcile dropped subscriptions through the same queue as clicks and cross-tab storage changes.
  useEffect(() => {
    const syncNotificationPreference = (event = null) => {
      const next = notifyEnabled();
      syncWorkerPushPreference();
      const repairSignalled = event && event.key === PUSH_REPAIR_SIGNAL_KEY;
      const preferenceChanged = next !== notifyOnRef.current;
      if (!preferenceChanged && !repairSignalled) return false;
      if (preferenceChanged) {
        notifyOnRef.current = next;
        setNotifyOn(next);
      }
      const permission = pushPermissionRef.current;
      const syncedTransition = ++pushTransitionIdRef.current;
      // Preserve an already-claimed permission gesture across a repair-only generation change.
      pushPermissionRef.current = {
        transition: syncedTransition,
        request: next && !preferenceChanged ? permission.request : null,
      };
      pushRepairGenerationRef.current = null;
      Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort());
      repairPushRef.current();
      return true;
    };
    window.addEventListener("storage", syncNotificationPreference);
    // Close the render-to-effect storage-listener gap before reconciling.
    if (!syncNotificationPreference()) {
      repairPushRef.current();
    }
    return () => {
      window.removeEventListener("storage", syncNotificationPreference);
      pushTransitionIdRef.current += 1;
      repairPushRef.current = () => {};
      Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort());
    };
  }, [queuePushTransition]);
  const toggleNotifications = () => {
    const next = !notifyOnRef.current;
    notifyOnRef.current = next;
    // The per-device in-tab preference must survive push-handshake failure.
    setNotifyEnabled(next);
    setNotifyOn(next);
    // Claim iOS permission synchronously before queued work loses the user gesture.
    const permission = next ? ensurePermission() : null;
    syncWorkerPushPreference();
    const transition = ++pushTransitionIdRef.current;
    pushPermissionRef.current = { transition: transition, request: permission };
    pushRepairGenerationRef.current = null;
    // PushManager mutations cannot abort; late completion repairs the newest generation.
    Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort());
    repairPushRef.current();
  };
  // An unpin in ADHD mode can hide the row or head holding the focused pin, and focus would fall to the
  // body. Preact commits the answer in a microtask, so one task later the DOM says whether the pin left.
  const unpin = (event, name, work) => {
    const pin = event.currentTarget;
    const hadFocus = pin === document.activeElement;
    if (!adhdMode) return work();
    return Promise.resolve(work()).then(() => setTimeout(() => {
      if (pin.isConnected) return;
      onAnnounce(name + " removed from ADHD mode and hidden.");
      const toggle = adhdToggleRef.current;
      if (hadFocus && toggle && document.activeElement === document.body) toggle.focus();
    }, 0));
  };
  const markSession = (event, session) => (session.adhd === true
    ? unpin(event, displayName(session), () => onMarkSession(session.id, false))
    : onMarkSession(session.id, true));
  const markFolder = (event, path, adhd) => (adhd
    ? onMarkFolder(path, true)
    : unpin(event, path, () => onMarkFolder(path, false)));
  const onTasks = screen === SCREEN_TASKS;
  const prefsReady = prefsStatus.state === READY;
  // Until the daemon answers, `adhdPaths` and the grouping are placeholders, so a reduction would drop
  // folder-covered rows.
  const awaitingPins = adhdMode && !onTasks && !prefsReady;
  const live = useMemo(() => sessions.filter((s) => !s.archived), [sessions]);
  // Everything below renders the reduced list; only the empty states read `live`, so a reduced-to-empty
  // sidebar says why instead of claiming there are no sessions.
  const visible = useMemo(() => {
    if (!adhdMode || onTasks) return live;
    // The selected session keeps its row, or unmarking it strands the terminal with no row to return to.
    return live.filter((s) => s.id === activeId || (prefsReady && isSessionInAdhd(s, prefs)));
  }, [activeId, adhdMode, live, onTasks, prefsReady, prefs.adhdPaths, prefs.basePath, prefs.groupingLevel]);
  const nothingPinned = useMemo(
    () => !onTasks && !live.some((s) => isSessionInAdhd(s, prefs)),
    [live, onTasks, prefs.adhdPaths, prefs.basePath, prefs.groupingLevel],
  );
  // Every live frame replaces the sessions array, so the archive derivations below key on this
  // signature instead: it moves only when an archived row does. The id length keeps it unambiguous.
  const [doneSessions, doneSignature] = useMemo(() => {
    const done = [];
    let signature = "";
    for (const session of sessions) {
      if (!session.archived) continue;
      done.push(session);
      signature += `${session.id.length}:${session.id}:${session.updatedAt}:${session.rev};`;
    }
    return [done, signature];
  }, [sessions]);
  const attention = useMemo(
    () => (adhdMode ? [] : visible.filter((s) => isNeedsAttention(s.state))),
    [adhdMode, visible],
  );
  const grouped = groupingEnabled(prefs);
  const liveGroups = useMemo(
    () => grouped && !onTasks
      ? groupSessions(visible, prefs.basePath, prefs.groupingLevel)
      : [],
    [grouped, onTasks, prefs.basePath, prefs.groupingLevel, visible],
  );
  const doneGroups = useMemo(
    () => grouped && showDone && !onTasks
      ? orderGroupsByRecentChange(groupSessions(doneSessions, prefs.basePath, prefs.groupingLevel))
      : [],
    [doneSignature, grouped, onTasks, prefs.basePath, prefs.groupingLevel, showDone],
  );
  // Live rows keep the daemon's order; the flat archive answers "what did I just finish" instead.
  const flatDoneSessions = useMemo(
    () => !grouped && showDone && !onTasks ? byRecentChange(doneSessions) : [],
    [doneSignature, grouped, onTasks, showDone],
  );
  const sessionsPath = routePath({ screen: SCREEN_SESSIONS, id: activeId || null });
  const openPerProject = new Map();
  if (onTasks) {
    for (const task of tasks) {
      if (!task || !task.project || task.state === "done") continue;
      openPerProject.set(task.project, (openPerProject.get(task.project) || 0) + 1);
    }
  }

  return html`
    <aside id="sidebar"
           class=${[drawerOpen ? "open" : "", collapsed ? "collapsed" : ""].filter(Boolean).join(" ")}>
      <header id="sidebar-head">
        <div class="brand-row">
          <h1>Kotgent</h1>
          <div class="brand-actions">
            ${!onTasks && doneSessions.length > 0 && html`
              <button
                id="show-done-toggle"
                class=${"icon-button icon-button-small show-done-toggle" + (showDone ? " active" : "")}
                type="button"
                aria-pressed=${showDone ? "true" : "false"}
                aria-label=${"Done sessions (" + doneSessions.length + ")"}
                title=${"Done sessions (" + doneSessions.length + ")"}
                onClick=${onToggleShowDone}
              ><${DoneIcon} /></button>
            `}
            ${!onTasks && html`
              <button
                id="adhd-toggle"
                ref=${adhdToggleRef}
                class=${"icon-button icon-button-small adhd-toggle" + (adhdMode ? " active" : "")}
                type="button"
                aria-pressed=${adhdMode ? "true" : "false"}
                aria-label=${adhdMode ? "Leave ADHD mode" : "Enter ADHD mode"}
                title=${adhdMode
                  ? "ADHD mode on — click to show every session"
                  : "ADHD mode off — click to show only pinned sessions and folders"}
                onClick=${onToggleAdhdMode}
              ><${AdhdIcon} on=${adhdMode} /></button>
            `}
            <button
              id="notify-toggle"
              class=${"icon-button icon-button-small notify-toggle" + (notifyOn ? " active" : "")}
              type="button"
              aria-label=${notifyOn ? "Turn notifications off" : "Turn notifications on"}
              aria-pressed=${notifyOn ? "true" : "false"}
              title=${notifyOn ? "Notifications on (this device) — click to turn off"
                : "Notifications off — click to turn on for this device"}
              onClick=${toggleNotifications}
            ><${NotifyIcon} on=${notifyOn} /></button>
            <button
              id="drawer-close"
              class="icon-button icon-button-small drawer-close"
              type="button"
              aria-label="Close the sidebar"
              title="Close the sidebar"
              onClick=${onCloseDrawer}
            >✕</button>
          </div>
        </div>
        <${NavSwitch} screen=${screen} sessionsPath=${sessionsPath} />
      </header>

      <div id="sidebar-scroll">
        ${onTasks && html`
          <section id="projects-section">
            <h2 class="section-title">
              <span>Projects</span>
              <button
                id="sidebar-new-project"
                class="button button-quiet button-small"
                type="button"
                title="Adopt a directory as a project"
                onClick=${() => onNewProject()}
              >+ New</button>
            </h2>
            <ul id="project-list" class="project-list">
              ${projects.map((project) => html`
                <${ProjectRow}
                  key=${project.id}
                  project=${project}
                  open=${openPerProject.get(project.id) || 0}
                  active=${project.id === projectId}
                  onSelect=${onSelectProject}
                />`)}
            </ul>
            ${projects.length === 0 && html`
              <div id="empty-projects" class="empty-sessions">
                <p>No projects yet. Adopt a directory to start a backlog in it.</p>
                <button id="empty-new-project-button" class="button button-primary" type="button"
                        onClick=${() => onNewProject()}>New project</button>
              </div>
            `}
          </section>
        `}

      ${!onTasks && attention.length > 0 && html`
        <section id="attention-section" class=${attentionCollapsed ? "collapsed" : ""}>
          <h2 class="section-title attn">
            <button
              id="attention-toggle"
              class="group-toggle section-toggle"
              type="button"
              aria-expanded=${attentionCollapsed ? "false" : "true"}
              title=${attentionCollapsed ? "Show sessions needing attention" : "Hide sessions needing attention"}
              onClick=${() => setAttentionCollapsed((c) => !c)}
            >
              <${Chevron} collapsed=${attentionCollapsed} />
              <span>Needs attention</span>
              <span id="attention-num" class="pill attn-num">${attention.length}</span>
            </button>
          </h2>
          ${!attentionCollapsed && html`
            <ul id="attention-list" class="session-list">
              ${attention.map((s) => html`
                <${SessionRow} key=${s.id} session=${s} tasks=${tasks}
                               active=${s.id === activeId} onSelect=${onSelect}
                               onMark=${markSession} prefs=${prefs} />
              `)}
            </ul>
          `}
        </section>
      `}

      ${!onTasks && html`
      <section id="all-section">
        <h2 class="section-title">
          <span>Sessions</span>
          ${grouped && html`
            <button
              id="base-path-note"
              class="base-note"
              type="button"
              title=${"Directory tree under " + prefs.basePath + ", up to " + prefs.groupingLevel +
                " level(s) deep — click to change"}
              onClick=${onOpenPrefs}
            >${prefs.basePath}</button>
          `}
        </h2>

        <ul id="session-list" class=${"session-list" + (grouped ? " grouped" : "")}>
          ${grouped
            ? liveGroups.map((g) => html`
                <${SessionGroup}
                  key=${g.path}
                  group=${g}
                  tasks=${tasks}
                  activeId=${activeId}
                  collapsedGroups=${collapsedGroups}
                  onSelect=${onSelect}
                  onToggle=${toggleGroup}
                  onNewSession=${onNewSession}
                  onMark=${markSession}
                  onMarkFolder=${markFolder}
                  prefs=${prefs}
                />
              `)
            : visible.map((s) => html`
                <${SessionRow} key=${s.id} session=${s} tasks=${tasks}
                               active=${s.id === activeId} onSelect=${onSelect}
                               onMark=${markSession} prefs=${prefs} />
              `)}
        </ul>

        ${live.length === 0 && !sessionsReady && html`
          <div id="sessions-loading" class="empty-sessions">
            <p>Loading sessions…</p>
          </div>
        `}
        ${live.length === 0 && sessionsReady && html`
          <div id="empty-sessions" class="empty-sessions">
            <p>No sessions yet. Start one to attach it here.</p>
            <button id="empty-new-session-button" class="button button-primary" type="button"
                    onClick=${() => onNewSession(null)}>Start a session</button>
          </div>
        `}
        ${awaitingPins && live.length > 0 && prefsStatus.state !== FAILED && html`
          <div id="adhd-loading" class="empty-sessions">
            <p>Loading pinned sessions…</p>
          </div>
        `}
        ${awaitingPins && live.length > 0 && prefsStatus.state === FAILED && html`
          <div id="adhd-failed" class="empty-sessions">
            <p>${prefsStatus.error}</p>
            <p>ADHD mode cannot tell what is pinned until preferences load.</p>
            <button id="adhd-retry" class="button" type="button" onClick=${onRetryPrefs}>Try again</button>
          </div>
        `}
        ${adhdMode && prefsReady && live.length > 0 && nothingPinned && html`
          <div id="empty-adhd" class="empty-sessions">
            <p>No live session is pinned. ADHD mode is hiding ${live.length - visible.length} session(s).</p>
            <button id="empty-adhd-show-all" class="button button-primary" type="button"
                    onClick=${onToggleAdhdMode}>Show all</button>
          </div>
        `}
      </section>
      `}

      ${!onTasks && showDone && doneSessions.length > 0 && html`
        <section id="done-section">
          <h2 class="section-title">
            <span>Done</span>
            <span id="done-count" class="done-count">${doneSessions.length}</span>
          </h2>
          <ul id="done-list" class=${"session-list done-list" + (grouped ? " grouped" : "")}>
            ${grouped
              ? doneGroups.map((g) => html`
                  <${SessionGroup}
                    key=${g.path}
                    group=${g}
                    tasks=${tasks}
                    activeId=${activeId}
                    collapsedGroups=${collapsedGroups}
                    onSelect=${onSelect}
                    onToggle=${toggleGroup}
                    onRestore=${onRestore}
                    prefs=${prefs}
                    done=${true}
                  />
                `)
              : flatDoneSessions.map((s) => html`
                  <${SessionRow}
                    key=${s.id}
                    session=${s}
                    tasks=${tasks}
                    active=${s.id === activeId}
                    onSelect=${onSelect}
                    onRestore=${onRestore}
                  />
                `)}
          </ul>
        </section>
      `}

      </div>

      <footer id="sidebar-footer">
        <${UsageStrip} />
        <div class="sidebar-footer-row">
          ${!onTasks && html`
            <p id="status-line" class=${"status-line" + (status.error ? " error" : "")}
               role="status" aria-live="polite">${status.text}</p>`}
          ${currentVersion && html`
            <span id="current-version" title="Kotgent version">${currentVersion}</span>
          `}
        </div>
      </footer>
    </aside>
  `;
}
