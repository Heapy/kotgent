import { SidebarToggle } from "./SidebarToggle.tsx";
import type { JSX } from "preact";
import { useEffect, useState } from "preact/hooks";
import { formatMutexElapsed, isHeldLong, mutexElapsed, sessionMutexPills } from "../lib/mutexes.ts";
import type { MutexEntry, ReceivedMutexListing } from "../lib/mutexes.ts";
import { MUTEXES_PATH, navigate, sessionPath } from "../lib/router.ts";
import { displayName } from "../lib/sessions.ts";
import { mutexes } from "../state/mutexes.ts";
import { findSession } from "../state/sessions.ts";

export interface MutexesScreenProps {
  drawerOpen: boolean;
  sidebarCollapsed: boolean;
  onToggleDrawer: JSX.MouseEventHandler<HTMLButtonElement>;
  onToggleSidebar: JSX.MouseEventHandler<HTMLButtonElement>;
  onOpenPalette: (mode: "leader") => void;
  onForceRelease: (key: string, holderSessionId: string) => void;
}

const TICK_MS = 1000;

/** Preserve real-link behavior; route only plain clicks in-app. */
function routeClick(path: string): JSX.MouseEventHandler<HTMLAnchorElement> {
  return (event) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(path);
  };
}

export function sessionLabel(sessionId: string) {
  const session = findSession(sessionId);
  return session ? displayName(session) : sessionId;
}

function SessionLink({ sessionId }: { sessionId: string }) {
  const path = sessionPath(sessionId);
  return <a class="mutex-session" href={path} onClick={routeClick(path)}>{sessionLabel(sessionId)}</a>;
}

function Since({ stamp }: { stamp: number }) {
  const date = new Date(stamp);
  if (Number.isNaN(date.getTime())) return <span class="mutex-since">since an unknown time</span>;
  return <span class="mutex-since">since <time dateTime={date.toISOString()}>{date.toLocaleString()}</time></span>;
}

interface MutexRowProps {
  entry: MutexEntry;
  listing: ReceivedMutexListing;
  now: number;
  onForceRelease: (key: string, holderSessionId: string) => void;
}

function MutexRow({ entry, listing, now, onForceRelease }: MutexRowProps) {
  const holder = entry.holder;
  const held = holder ? mutexElapsed(listing, holder.acquiredAt, now) : null;
  const long = isHeldLong(held);
  const claiming = !holder && entry.waiters.some((waiter) => waiter.granted);
  return (
    <li class={"mutex-row" + (long ? " held-long" : "")} data-key={entry.key} data-long={long ? "true" : undefined}>
      <div class="mutex-row-head">
        <code class="mutex-key">{entry.key}</code>
        {holder ? (
          <>
            <span class="mutex-holder">held by <SessionLink sessionId={holder.sessionId} /></span>
            <Since stamp={holder.acquiredAt} />
            <span class="mutex-duration">{formatMutexElapsed(held)}</span>
            {long && <span class="mutex-long-flag">held over 15 min</span>}
            <button type="button" class="button button-small button-danger mutex-force-release"
                    onClick={() => onForceRelease(entry.key, holder.sessionId)}>Force release</button>
          </>
        ) : (
          <span class="mutex-holder mutex-free">{claiming ? "granted, awaiting its claim" : "free"}</span>
        )}
      </div>
      {entry.waiters.length > 0 && (
        <ol class="mutex-waiters" aria-label={"Waiting for " + entry.key}>
          {entry.waiters.map((waiter, index) => (
            <li key={waiter.sessionId + ":" + waiter.since + ":" + index} class="mutex-waiter"
                data-granted={waiter.granted ? "true" : undefined}>
              <span class="mutex-position">#{index + 1}</span>
              <SessionLink sessionId={waiter.sessionId} />
              <span class="mutex-waited">
                {waiter.granted ? "granted, claiming" : "waiting " +
                  formatMutexElapsed(mutexElapsed(listing, waiter.since, now))}
              </span>
            </li>))}
        </ol>)}
    </li>
  );
}

export function MutexesScreen({
  drawerOpen, sidebarCollapsed, onToggleDrawer, onToggleSidebar, onOpenPalette, onForceRelease,
}: MutexesScreenProps) {
  const listing = mutexes.value;
  const [now, setNow] = useState(() => performance.now());
  const active = !!listing && listing.mutexes.length > 0;
  useEffect(() => {
    setNow(performance.now());
    if (!active) return undefined;
    const timer = setInterval(() => setNow(performance.now()), TICK_MS);
    return () => clearInterval(timer);
  }, [active, listing]);

  const heldCount = listing ? listing.mutexes.filter((entry) => entry.holder).length : 0;
  const waitingCount = listing
    ? listing.mutexes.reduce((sum, entry) => sum + entry.waiters.filter((w) => !w.granted).length, 0)
    : 0;

  return (
    <main class="mutexes-screen" aria-label="Mutexes">
      <header class="mutexes-head">
        <button
          id="drawer-toggle"
          class="icon-button icon-button-small drawer-toggle"
          type="button"
          aria-label="Show the session list"
          aria-expanded={drawerOpen ? "true" : "false"}
          aria-controls="sidebar"
          title="Sessions"
          onClick={onToggleDrawer}
        >☰</button>
        <SidebarToggle collapsed={sidebarCollapsed} onToggle={onToggleSidebar} />
        <div class="mutexes-identity">
          <h1 class="mutexes-title">Mutexes</h1>
          <span id="mutexes-summary" class="mutexes-summary">
            {listing ? heldCount + " held · " + waitingCount + " waiting" : "Loading…"}
          </span>
        </div>
        <button
          id="palette-button"
          class="icon-button icon-button-small palette-button"
          type="button"
          aria-label="Open command palette"
          title="Commands"
          onClick={() => onOpenPalette("leader")}
        >⋯</button>
      </header>
      <div class="mutexes-body">
        {!listing && <p id="mutexes-loading" class="field-hint">Loading mutexes…</p>}
        {listing && listing.mutexes.length === 0 && (
          <p id="mutexes-empty" class="field-hint">
            No mutex is held or awaited. Sessions take one with <code>kotgent mutex run</code>.
          </p>)}
        {active && (
          <ul id="mutex-list" class="mutex-list">
            {listing.mutexes.map((entry) => (
              <MutexRow key={entry.key} entry={entry} listing={listing} now={now} onForceRelease={onForceRelease} />))}
          </ul>)}
      </div>
    </main>
  );
}

/** The open session's holdings and waits; each pill leads to the full list. */
export function MutexIndicator({ sessionId }: { sessionId: string }) {
  const pills = sessionMutexPills(mutexes.value, sessionId);
  if (pills.length === 0) return null;
  const waiting = pills.some((pill) => pill.kind === "waiting");
  const label = pills.map((pill) => pill.kind === "held" ? "Holds " + pill.key
    : "Waiting for " + pill.key + ", position " + pill.position).join("; ");
  return <span class="mutex-indicator" role="img" aria-label={label} title={label}>{waiting ? "⏳" : "🔒"}</span>;
}

export function MutexPills({ sessionId }: { sessionId: string | null }) {
  const pills = sessionMutexPills(mutexes.value, sessionId);
  if (pills.length === 0) return null;
  return (
    <span id="terminal-mutexes" class="mutex-pills">
      {pills.map((pill, index) => {
        const label = pill.kind === "held" ? "🔒 " + pill.key : "⏳ " + pill.key + " · #" + pill.position;
        const title = pill.kind === "held"
          ? "Holds mutex " + pill.key
          : "Waiting for mutex " + pill.key + ", position " + pill.position;
        return (
          <a key={pill.kind + ":" + pill.key + ":" + index} class="mutex-pill" data-kind={pill.kind}
             href={MUTEXES_PATH} title={title} aria-label={title} onClick={routeClick(MUTEXES_PATH)}>{label}</a>
        );
      })}
    </span>
  );
}
