/*
 * The session list: needs-attention triage on top, then every session — flat, or grouped by working
 * directory once a base path is configured.
 *
 * Rows are keyed by session id, so a live update from /events patches the existing row instead of
 * rebuilding the list. That is what keeps focus and scroll position while sessions change state.
 */

import { html } from "htm/preact";
import { useState } from "preact/hooks";
import { groupSessions } from "../lib/paths.js";
import { groupingEnabled } from "../lib/prefs.js";
import { displayName, isNeedsAttention, sessionSubline, stateBadge } from "../lib/sessions.js";

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

function SessionGroup({ group, activeId, onSelect, onNewSession }) {
  return html`
    <li class="session-group">
      <div class="group-head">
        <span class="group-title" title=${group.path}>${group.label}</span>
        <span class="group-count">${group.sessions.length}</span>
        ${group.path &&
          html`<button
            type="button"
            class="icon-button icon-button-small group-new"
            title=${"New session in " + group.path}
            aria-label=${"New session in " + group.path}
            onClick=${() => onNewSession(group.path)}
          >+</button>`}
      </div>
      <ul class="session-list group-sessions">
        ${group.sessions.map((s) => html`
          <${SessionRow} key=${s.id} session=${s} active=${s.id === activeId} onSelect=${onSelect} />
        `)}
      </ul>
    </li>
  `;
}

export function Sidebar({
  sessions, activeId, prefs, status,
  onSelect, onNewSession, onOpenPrefs, onOpenHelp, onOpenPhone, onRestore,
}) {
  const [showDone, setShowDone] = useState(false);
  // Archived ("done") sessions are hidden from the working set — the attention queue, the session list,
  // and every count — and only surfaced under an explicit "Show done" toggle.
  const visible = sessions.filter((s) => !s.archived);
  const doneSessions = sessions.filter((s) => s.archived);
  const attention = visible.filter((s) => isNeedsAttention(s.state));
  const grouped = groupingEnabled(prefs);

  return html`
    <aside id="sidebar">
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
                  onSelect=${onSelect}
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

      <p id="status-line" class=${"status-line" + (status.error ? " error" : "")}
         role="status" aria-live="polite">${status.text}</p>
    </aside>
  `;
}
