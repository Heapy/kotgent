/*
 * The session list: needs-attention triage on top, then every session — flat, or grouped by working
 * directory once a base path is configured.
 *
 * Rows are keyed by session id, so a live update from /events patches the existing row instead of
 * rebuilding the list. That is what keeps focus and scroll position while sessions change state.
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
import { displayName, isNeedsAttention, sessionSubline, stateBadge } from "../lib/sessions.js";

const PUSH_TRANSITION_TIMEOUT_MS = 10_000;

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

function SessionRow({ session, active, onSelect, onRestore }) {
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

function SessionGroup({ group, activeId, collapsed, onSelect, onToggle, onNewSession }) {
  // The head is a toggle BUTTON with the group's "+" as its sibling, not a child — a button inside a
  // button is invalid, and the "+" must not double as an expand.
  const hidingAttention = collapsed && group.sessions.some((s) => isNeedsAttention(s.state));

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
          <span class="group-title">${group.label}</span>
          <span class="group-count">${group.sessions.length}</span>
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
        <ul class="session-list group-sessions">
          ${group.sessions.map((s) => html`
            <${SessionRow} key=${s.id} session=${s} active=${s.id === activeId} onSelect=${onSelect} />
          `)}
        </ul>
      `}
    </li>
  `;
}

export function Sidebar({
  sessions, activeId, prefs, status, currentVersion, drawerOpen,
  onSelect, onNewSession, onOpenPrefs, onOpenHelp, onOpenPhone, onRestore, onCloseDrawer,
}) {
  const [showDone, setShowDone] = useState(false);
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
      queuePushTransition(
        syncedTransition,
        next,
        (context) => {
          const currentPermission = pushPermissionRef.current;
          return next
            ? (currentPermission.transition === syncedTransition && currentPermission.request
                ? pushSubscribe(currentPermission.request, context)
                : refreshPush(context))
            : pushUnsubscribe(context);
        },
        "kotgent: cross-tab push reconciliation failed",
      );
      return true;
    };
    window.addEventListener("storage", syncNotificationPreference);
    // Close the render→effect listener gap before the initial reconciliation: if storage changed there,
    // the sync owns the transition; otherwise queue the ordinary mount refresh exactly once.
    if (!syncNotificationPreference()) {
      const transition = ++pushTransitionIdRef.current;
      const desired = notifyOnRef.current;
      pushPermissionRef.current = { transition: transition, request: null };
      queuePushTransition(
        transition,
        desired,
        (context) => desired ? refreshPush(context) : pushUnsubscribe(context),
        "kotgent: push subscription refresh failed",
      );
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
    queuePushTransition(
      transition,
      next,
      (context) => next ? pushSubscribe(permission, context) : pushUnsubscribe(context),
      // A failed subscribe is a downgrade, not an error the operator must act on: the tab keeps notifying.
      "kotgent: push subscription transition failed",
    );
  };
  // Archived ("done") sessions are hidden from the working set — the attention queue, the session list,
  // and every count — and only surfaced under an explicit "Show done" toggle.
  const visible = sessions.filter((s) => !s.archived);
  const doneSessions = sessions.filter((s) => s.archived);
  const attention = visible.filter((s) => isNeedsAttention(s.state));
  const grouped = groupingEnabled(prefs);

  // `open` only means anything under the mobile media query, where this aside is a fixed overlay drawer;
  // above the breakpoint it is the same flex column it has always been.
  return html`
    <aside id="sidebar" class=${drawerOpen ? "open" : ""}>
      <header id="sidebar-head">
        <div class="brand-row">
          <h1>Kotgent</h1>
          <div class="brand-actions">
            <button
              id="new-session-button"
              class="button button-primary button-small"
              type="button"
              onClick=${() => onNewSession(null)}
            >New session</button>
            <button
              id="notify-toggle"
              class=${"icon-button icon-button-small notify-toggle" + (notifyOn ? " active" : "")}
              type="button"
              aria-label=${notifyOn ? "Turn notifications off" : "Turn notifications on"}
              aria-pressed=${notifyOn ? "true" : "false"}
              title=${notifyOn ? "Notifications on (this device) — click to turn off"
                : "Notifications off — click to turn on for this device"}
              onClick=${toggleNotifications}
            >${notifyOn ? "🔔" : "🔕"}</button>
            <button
              id="phone-button"
              class="icon-button icon-button-small"
              type="button"
              aria-label="Sign in from your phone"
              title="Sign in from your phone"
              onClick=${onOpenPhone}
            >📱</button>
            <button
              id="help-button"
              class="icon-button icon-button-small"
              type="button"
              aria-label="Help"
              title="How kotgent works"
              onClick=${onOpenHelp}
            >?</button>
            <button
              id="prefs-button"
              class="icon-button icon-button-small"
              type="button"
              aria-label="Preferences"
              title="Preferences"
              onClick=${onOpenPrefs}
            >⚙</button>
            ${/* Shown only under the mobile media query: the drawer's scrim covers the hamburger that
                  opened it, so without this the only way back is a tap outside. */ ""}
            <button
              id="drawer-close"
              class="icon-button icon-button-small drawer-close"
              type="button"
              aria-label="Close the session list"
              title="Close the session list"
              onClick=${onCloseDrawer}
            >✕</button>
          </div>
        </div>
        <div
          id="attention-count"
          class=${"attn-count" + (attention.length > 0 ? " active" : "")}
          title="Sessions needing attention"
        >
          <span id="attention-num">${attention.length}</span> need attention
        </div>
      </header>

      ${attention.length > 0 && html`
        <section id="attention-section">
          <h2 class="section-title attn">Needs attention</h2>
          <ul id="attention-list" class="session-list">
            ${attention.map((s) => html`
              <${SessionRow} key=${s.id} session=${s} active=${s.id === activeId} onSelect=${onSelect} />
            `)}
          </ul>
        </section>
      `}

      <section id="all-section">
        <h2 class="section-title">
          <span>Sessions</span>
          ${grouped && html`
            <button
              id="base-path-note"
              class="base-note"
              type="button"
              title=${"Grouping under " + prefs.basePath + " at level " + prefs.groupingLevel +
                " — click to change"}
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
                  activeId=${activeId}
                  collapsed=${collapsedGroups.has(g.path)}
                  onSelect=${onSelect}
                  onToggle=${toggleGroup}
                  onNewSession=${onNewSession}
                />
              `)
            : visible.map((s) => html`
                <${SessionRow} key=${s.id} session=${s} active=${s.id === activeId} onSelect=${onSelect} />
              `)}
        </ul>

        ${visible.length === 0 && html`
          <p id="empty-sessions" class="empty-sessions">
            No sessions yet. Start one to attach it here.
          </p>
        `}
      </section>

      ${doneSessions.length > 0 && html`
        <section id="done-section">
          <button
            id="show-done-toggle"
            class="show-done-toggle"
            type="button"
            aria-expanded=${showDone ? "true" : "false"}
            onClick=${() => setShowDone((v) => !v)}
          >${(showDone ? "▾ " : "▸ ") + "Show done (" + doneSessions.length + ")"}</button>
          ${showDone && html`
            <ul id="done-list" class="session-list done-list">
              ${doneSessions.map((s) => html`
                <${SessionRow}
                  key=${s.id}
                  session=${s}
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
        <p id="status-line" class=${"status-line" + (status.error ? " error" : "")}
           role="status" aria-live="polite">${status.text}</p>
        ${currentVersion && html`
          <span id="current-version" title="Kotgent version">${currentVersion}</span>
        `}
      </footer>
    </aside>
  `;
}
