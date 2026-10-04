import type { Terminal } from "@xterm/xterm";

/**
 * xterm 6 exposes its host, but not the pixel bounds of its character grid. Keep this internal
 * selector at the integration boundary: host padding makes host bounds inaccurate for touch rows.
 * TerminalSwipeTest covers wheel coordinates and row banking when xterm is upgraded.
 */
export function terminalScreenBounds(term: Terminal): DOMRect | null {
  const host = term.element;
  if (!host) return null;
  return (host.querySelector<HTMLElement>(".xterm-screen") ?? host).getBoundingClientRect();
}
