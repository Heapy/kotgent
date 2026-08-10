/*
 * The app's one sidebar, on every screen.
 *
 * Its head is fixed — the brand row, then the two links that ARE the app's navigation — and its body is
 * whichever list belongs to the screen the router has put on:
 *
 *   sessions   needs-attention triage on top, then every session — flat, or arranged as a nested
 *              working-directory tree once a base path is configured.
 *   tasks      the projects the board can be pointed at, one of which is always selected.
 *
 * Rows are keyed by session id, so a live update from /events patches the existing row instead of
 * rebuilding the list. That is what keeps focus and scroll position while sessions change state.
 *
 * ## Why the sidebar is shell furniture rather than the session view's own panel
 * It used to be rendered inside the session branch, which cost three things. The board had to grow a
 * link of its own to get back (an installed PWA draws no Back button); it had no way to reach the
 * project list except a `<select>` in its header, i.e. a second navigation idiom on the one screen that
 * needed it least; and the mobile drawer could be left open over a screen that no longer contained it.
 * One sidebar answers all three: the two links are reachable from anywhere, the project list is a list
 * of rows exactly like the session list, and the drawer can never be orphaned because it never unmounts.
 *
 * The body is BRANCHED, not merely filtered: every session-only control below reads the session list or
 * the selection, and the board's selection is a leftover the operator cannot see — the same reason
 * `lib/commands.js` builds the whole `session` command group away on the board rather than disabling it.
 */

import { html } from "htm/preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { groupSessions } from "../lib/paths.js";
import { groupingEnabled, loadCollapsedGroups, persistCollapsedGroups } from "../lib/prefs.js";
import { ensurePermission, isEnabled as notifyEnabled, setEnabled as setNotifyEnabled } from "../lib/notify.js";
import {
  PUSH_REPAIR_SIGNAL_KEY,
  refreshActive as refreshPush,
  subscribe as pushSubscribe,
  syncWorkerPushPreference,
  unsubscribe as pushUnsubscribe,
} from "../lib/push.js";
import { displayName, isNeedsAttention, sessionSubline, stateBadge, taskBadge } from "../lib/sessions.js";
import {
  SCREEN_SESSIONS,
  SCREEN_TASKS,
  navigate,
  routePath,
  taskPath,
} from "../lib/router.js";

const PUSH_TRANSITION_TIMEOUT_MS = 10_000;

/** The board, spelled by the router rather than as a literal — the same rule every in-app path follows. */
const TASKS_PATH = routePath({ screen: SCREEN_TASKS, id: null });

/**
 * The app's navigation: two links, always in the head, so neither screen is reachable only from the
 * other. They are real `<a href>` elements, which is what makes ⌘-click, middle-click and "copy link"
 * behave; only the plain left click is stolen and handed to the router.
 *
 * The Sessions link names the selected session when there is one, rather than always going to `/`. The
 * two are the same screen, but the URL is what a reload, a bookmark and a shared link resolve, so the
 * address bar should describe the terminal that is actually on it. With no selection `/` is exactly
 * right, and that is what `routePath` answers for a null id.
 */
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

/** The diagonal the muted state is struck with, spelled once: the stroke and the gap it is cut from. */
const NOTIFY_MUTE_SLASH = "M4.8 20.2L19.6 5.4";

/**
 * The mark itself: a node, and the two arcs it broadcasts. Both arcs are struck on the node's OWN
 * centre, which is what makes them read as one signal leaving one source rather than as a stack of
 * unrelated curves — the same relation the app icon draws between its two nodes and the strokes that
 * reach them (`icons/logo.svg`).
 *
 * The node is filled rather than ringed. At 17px a 2.5-radius ring reads as the letter `o`, and once the
 * muted state adds a diagonal beside it the pair resolves as a glyph — `9`, `g`, `%` depending on the
 * angle. Filled, it stays a dot at every size this button is ever drawn at.
 */
const notifySignal = (mask) => html`
  <g mask=${mask}>
    <circle cx="7.5" cy="12" r="2.5" fill="currentColor" stroke="none" />
    <path d="M11.74 7.76A6 6 0 0 1 11.74 16.24" />
    <path d="M14.57 4.93A10 10 0 0 1 14.57 19.07" />
  </g>`;

/**
 * The notifications toggle's mark, drawn rather than typed.
 *
 * A system emoji is the one glyph in this shell that cannot be told to match it: it arrives in the
 * vendor's palette at the vendor's weight, so the bell read as a yellow sticker beside a purple accent
 * while the struck-through bell differed from it mostly in hue — the state was carried by a colour
 * kotgent does not own. (Neither is SPELLED here: the guard that keeps them out of this file is a plain
 * text search, so it reads comments too — the same rule the phone drawer's blur note follows.) Drawn,
 * it takes `currentColor`, which is what lets the button's `.active` class do the colouring from the
 * stylesheet (`--accent` on, `--muted` off) with one component serving both states.
 *
 * Off strikes the signal through with a diagonal, and the diagonal is CUT OUT of what it crosses rather
 * than laid over it: at this size a slash drawn straight over the arcs merges with them into a knot,
 * because it runs nearly parallel to the curves it is meant to negate. The mask's gap is what keeps the
 * two readable as separate marks. Its id is a constant because `app.js` renders exactly one `Sidebar`,
 * so this svg is unique in the document; a second copy would only point at an identical mask anyway.
 */
function NotifyIcon({ on }) {
  return html`
    <svg
      viewBox="0 0 24 24"
      focusable="false"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
    >
      ${on ? notifySignal(null) : html`
        ${/* The region is spelled out because the default one is the masked box grown by 10%, which the
              arcs' own stroke very nearly fills: any later change to their radius would push a cap
              outside it and silently clip the mark rather than fail. */ ""}
        <mask id="notify-mute-cut" maskUnits="userSpaceOnUse" x="0" y="0" width="24" height="24">
          <rect x="0" y="0" width="24" height="24" fill="#fff" stroke="none" />
          <path d=${NOTIFY_MUTE_SLASH} stroke="#000" stroke-width="4.4" />
        </mask>
        ${notifySignal("url(#notify-mute-cut)")}
        <path d=${NOTIFY_MUTE_SLASH} />`}
    </svg>`;
}

/**
 * One project, in the session row's shape: name, its directory beneath, and a count on the right.
 *
 * The count is OPEN tasks, not every task — `done` grows forever and would quickly say nothing. It is
 * computed from the live task list rather than fetched, which is why a stale project row (the list is
 * re-read on entry to the board, never polled) still carries a fresh number.
 */
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
        html`<span class="project-count" title=${open + " open task(s)"}>${open}</span>`}
    </li>
  `;
}

/**
 * Bound how long one push transition occupies the serialized queue. A deadline releases the next choice,
 * but ONLY a newer generation makes the operation stale. A later user choice aborts cancelable reads;
 * non-cancelable browser mutations may settle later and repair the newest desired state.
 */
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

/**
 * The row's task badge, or nothing when the session is linked to no task.
 *
 * It is a real `<a href>` so ⌘-click, middle-click and "copy link" all behave: a plain left click is the
 * only one this steals, handing it to the router instead of a reload. `stopPropagation` is load-bearing —
 * the whole row is a click target that selects the session, and without it opening a task would select
 * the session underneath at the same time.
 *
 * The markup is duplicated in TerminalPane's header rather than extracted: a shared component would be a
 * fourth file, and the two badges differ in what surrounds them. What they must NOT differ in is the
 * text, which is why [taskBadge] and not a local string is the source of it.
 */
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

function SessionRow({ session, tasks, active, onSelect, onRestore }) {
  const badge = stateBadge(session.state);
  const select = () => onSelect(session.id);
  const onKeyDown = (event) => {
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
      ${isNeedsAttention(session.state) &&
        html`<span class="attn-dot" title="Needs attention"></span>`}
      <div class="session-main">
        <div class="session-name">${displayName(session)}</div>
        <div class="session-sub">${sessionSubline(session)}</div>
        <${TaskBadge} session=${session} tasks=${tasks} />
      </div>
      ${session.unread > 0 &&
        html`<span class="unread-pill" title=${session.unread + " unread event(s)"}>
          ${session.unread}
        </span>`}
      ${onRestore
        ? html`<button
            type="button"
            class="button button-quiet button-small session-restore"
            title="Bring this session back to the sidebar"
            onClick=${(e) => { e.stopPropagation(); onRestore(session.id); }}
          >Restore</button>`
        : html`<span class=${"badge " + badge.cls}>${badge.label}</span>`}
    </li>
  `;
}

function groupNeedsAttention(group) {
  return group.sessions.some((s) => isNeedsAttention(s.state)) ||
    group.children.some(groupNeedsAttention);
}

function SessionGroup({
  group, tasks, activeId, collapsedGroups, onSelect, onToggle, onNewSession,
}) {
  // The head is a toggle BUTTON with the group's "+" as its sibling, not a child — a button inside a
  // button is invalid, and the "+" must not double as an expand.
  const collapsed = collapsedGroups.has(group.path);
  const hidingAttention = collapsed && groupNeedsAttention(group);

  return html`
    <li class=${"session-group" + (collapsed ? " collapsed" : "")}>
      <div class="group-head">
        <button
          type="button"
          class="group-toggle"
          aria-expanded=${collapsed ? "false" : "true"}
          title=${(collapsed ? "Expand " : "Collapse ") + (group.path || group.label)}
          onClick=${() => onToggle(group.path)}
        >
          <span class="group-chevron" aria-hidden="true">${collapsed ? "▸" : "▾"}</span>
          <span class="group-title" title=${group.path || group.label}>${group.label}</span>
          <span class="group-count">${group.sessionCount}</span>
          ${hidingAttention &&
            html`<span class="attn-dot" title="A session in this group needs attention"></span>`}
        </button>
        ${group.path &&
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
          ${group.sessions.map((s) => html`
            <${SessionRow} key=${s.id} session=${s} tasks=${tasks}
                           active=${s.id === activeId} onSelect=${onSelect} />
          `)}
          ${group.children.map((child) => html`
            <${SessionGroup}
              key=${child.path}
              group=${child}
              tasks=${tasks}
              activeId=${activeId}
              collapsedGroups=${collapsedGroups}
              onSelect=${onSelect}
              onToggle=${onToggle}
              onNewSession=${onNewSession}
            />
          `)}
        </ul>
      `}
    </li>
  `;
}

export function Sidebar({
  screen = SCREEN_SESSIONS,
  sessions, tasks, projects = [], projectId = null, activeId, prefs, status, currentVersion,
  drawerOpen, collapsed, showDone, sessionsReady,
  onSelect, onSelectProject, onNewSession, onNewProject, onOpenPrefs, onRestore, onCloseDrawer,
  onToggleShowDone,
}) {
  const [collapsedGroups, setCollapsedGroups] = useState(loadCollapsedGroups);
  const [notifyOn, setNotifyOn] = useState(notifyEnabled());
  const notifyOnRef = useRef(notifyOn);
  const pushTransitionRef = useRef(Promise.resolve());
  const pushTransitionIdRef = useRef(0);
  const pushTransitionAbortRef = useRef(new Set());
  const pushRepairGenerationRef = useRef(null);
  const pushPermissionRef = useRef({ transition: 0, request: null });
  const repairPushRef = useRef(() => {});
  useEffect(() => { persistCollapsedGroups(collapsedGroups); }, [collapsedGroups]);
  const queuePushTransition = useCallback((transition, desired, operation, warning) => {
    // The local generation orders this tab; the stored preference orders every tab. Same-target operations
    // may overlap safely because subscribe/unsubscribe and the daemon writes are all idempotent.
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
  // Clicks, storage events, and mount reconciliation publish intent, then enter this one decision path.
  // In particular, observing current state on mount must not mint newer intent than a click already did.
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
      return next;   // a new Set every time: Preact compares state by identity
    });
  }, []);
  // A subscription can vanish without this page being told (the browser drops it, site data is cleared),
  // and a stale "push is on" belief would silence the in-tab notifications too. Put this reconciliation
  // through the same queue as clicks so it cannot overwrite a newer subscribe/unsubscribe transition.
  // The preference is origin-wide localStorage, so another open client must supersede this one's work too.
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
      // A stale-mutation signal can interrupt this tab's own ON prompt without changing the preference.
      // Keep that already-claimed user gesture attached to the replacement generation.
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
    // Close the render→effect listener gap before the initial reconciliation: if storage changed there,
    // the sync publishes that newer intent; otherwise reconcile without superseding an earlier click.
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
    // Flip the stored preference first: the toggle is the per-device in-tab setting and must land whatever
    // the push handshake does. Push is the upgrade on top of it, not a precondition.
    setNotifyEnabled(next);
    setNotifyOn(next);
    // Claim the iOS permission prompt synchronously in THIS click, before queueing behind an older network
    // transition. Awaiting the queue first would lose the user gesture and Safari would refuse to prompt.
    const permission = next ? ensurePermission() : null;
    syncWorkerPushPreference();
    const transition = ++pushTransitionIdRef.current;
    pushPermissionRef.current = { transition: transition, request: permission };
    pushRepairGenerationRef.current = null;
    // A fetch can be cancelled; a PushManager mutation cannot, and will repair the newest generation later.
    Array.from(pushTransitionAbortRef.current).forEach((controller) => controller.abort());
    repairPushRef.current();
  };
  // Archived ("done") sessions are hidden from the working set — the attention queue, the session list,
  // and every count — and only surfaced under an explicit "Show done" toggle.
  const visible = sessions.filter((s) => !s.archived);
  const doneSessions = sessions.filter((s) => s.archived);
  const attention = visible.filter((s) => isNeedsAttention(s.state));
  const grouped = groupingEnabled(prefs);
  const onTasks = screen === SCREEN_TASKS;
  // The attention count is the one head element that is NOT fixed: on the board it would report on a
  // list this sidebar is not showing, next to a link that already carries the same news nowhere.
  const sessionsPath = routePath({ screen: SCREEN_SESSIONS, id: activeId || null });
  // One walk of the task list for every project row, rather than a filter per row.
  const openPerProject = new Map();
  if (onTasks) {
    for (const task of tasks) {
      if (!task || !task.project || task.state === "done") continue;
      openPerProject.set(task.project, (openPerProject.get(task.project) || 0) + 1);
    }
  }

  // `open` only means anything under the mobile media query, where this aside is a fixed overlay drawer;
  // above the breakpoint it is the same flex column it has always been.
  return html`
    <aside id="sidebar"
           class=${[drawerOpen ? "open" : "", collapsed ? "collapsed" : ""].filter(Boolean).join(" ")}>
      <header id="sidebar-head">
        <div class="brand-row">
          <h1>Kotgent</h1>
          <div class="brand-actions">
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
            ${/* Shown only under the mobile media query: the drawer's scrim covers the hamburger that
                  opened it, so without this the only way back is a tap outside. */ ""}
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
        ${!onTasks && html`
          <div
            id="attention-count"
            class=${"attn-count" + (attention.length > 0 ? " active" : "")}
            title="Sessions needing attention"
          >
            <span id="attention-num">${attention.length}</span> need attention
          </div>`}
      </header>

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
        <section id="attention-section">
          <h2 class="section-title attn">Needs attention</h2>
          <ul id="attention-list" class="session-list">
            ${attention.map((s) => html`
              <${SessionRow} key=${s.id} session=${s} tasks=${tasks}
                             active=${s.id === activeId} onSelect=${onSelect} />
            `)}
          </ul>
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
            ? groupSessions(visible, prefs.basePath, prefs.groupingLevel).map((g) => html`
                <${SessionGroup}
                  key=${g.path}
                  group=${g}
                  tasks=${tasks}
                  activeId=${activeId}
                  collapsedGroups=${collapsedGroups}
                  onSelect=${onSelect}
                  onToggle=${toggleGroup}
                  onNewSession=${onNewSession}
                />
              `)
            : visible.map((s) => html`
                <${SessionRow} key=${s.id} session=${s} tasks=${tasks}
                               active=${s.id === activeId} onSelect=${onSelect} />
              `)}
        </ul>

        ${visible.length === 0 && !sessionsReady && html`
          <div id="sessions-loading" class="empty-sessions">
            <p>Loading sessions…</p>
          </div>
        `}
        ${visible.length === 0 && sessionsReady && html`
          <div id="empty-sessions" class="empty-sessions">
            <p>No sessions yet. Start one to attach it here.</p>
            <button id="empty-new-session-button" class="button button-primary" type="button"
                    onClick=${() => onNewSession(null)}>Start a session</button>
          </div>
        `}
      </section>
      `}

      ${!onTasks && doneSessions.length > 0 && html`
        <section id="done-section">
          <button
            id="show-done-toggle"
            class="show-done-toggle"
            type="button"
            aria-expanded=${showDone ? "true" : "false"}
            onClick=${onToggleShowDone}
          >${(showDone ? "▾ " : "▸ ") + "Show done (" + doneSessions.length + ")"}</button>
          ${showDone && html`
            <ul id="done-list" class="session-list done-list">
              ${doneSessions.map((s) => html`
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
          `}
        </section>
      `}

      <footer id="sidebar-footer">
        ${/* The session view's renderer for `status`. The board has its own — the `.board-status` toast
              in `app.js` — and keeps it now that both are mounted at once: on a phone this footer is
              inside a CLOSED drawer, so a refused drag announced only here would be announced nowhere.
              Rendering it on one screen each is what stops the two from doubling up. */ ""}
        ${!onTasks && html`
          <p id="status-line" class=${"status-line" + (status.error ? " error" : "")}
             role="status" aria-live="polite">${status.text}</p>`}
        ${currentVersion && html`
          <span id="current-version" title="Kotgent version">${currentVersion}</span>
        `}
      </footer>
    </aside>
  `;
}
