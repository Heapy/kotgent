import { stateBadge } from "../lib/sessions.ts";

/** The label remains available to assistive technology and as a hover tooltip. */
export function SessionStatus({ state }: { state: string }) {
  const badge = stateBadge(state);
  return <span class={"badge session-status-dot " + badge.cls} role="img"
    aria-label={badge.label} title={badge.label}><span class="visually-hidden">{badge.label}</span></span>;
}
