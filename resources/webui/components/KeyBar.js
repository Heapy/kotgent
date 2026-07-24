/*
 * Phone-only terminal keys that software keyboards do not expose.
 *
 * The WebSocket remains owned by TerminalPane. This component receives its live sender as a ref so a
 * socket opening or being replaced does not require a render, and every command stays a binary terminal
 * frame rather than becoming a text resize frame.
 */

import { html } from "htm/preact";

const SPECIAL_KEYS = [
  { label: "Esc", name: "Escape", bytes: [0x1b] },
  { label: "Tab", name: "Tab", bytes: [0x09] },
  { label: "⇧Tab", name: "Shift Tab", bytes: [0x1b, 0x5b, 0x5a], wide: true },
  { label: "↑", name: "Up arrow", bytes: [0x1b, 0x5b, 0x41] },
  { label: "↓", name: "Down arrow", bytes: [0x1b, 0x5b, 0x42] },
  { label: "←", name: "Left arrow", bytes: [0x1b, 0x5b, 0x44] },
  { label: "→", name: "Right arrow", bytes: [0x1b, 0x5b, 0x43] },
  { label: "^C", name: "Control C", bytes: [0x03], releasesCtrl: true },
];

export function KeyBar({ barRef, sendBytesRef, ctrlActive, onToggleCtrl, onReleaseCtrl }) {
  const send = (key) => {
    const sendBytes = sendBytesRef.current;
    if (sendBytes) sendBytes(Uint8Array.from(key.bytes));
    if (key.releasesCtrl) onReleaseCtrl();
  };
  // Cancelling pointer focus keeps xterm's hidden textarea (and the phone keyboard) active. Commands
  // stay on click so keyboard/switch-control activation still works.
  const preserveTerminalFocus = (event) => event.preventDefault();

  return html`
    <div class="key-bar" role="toolbar" aria-label="Terminal special keys" ref=${barRef}
         onPointerDown=${preserveTerminalFocus}>
      ${SPECIAL_KEYS.slice(0, 7).map((key) => html`
        <button key=${key.name} class=${"key-bar-key" + (key.wide ? " key-bar-wide" : "")}
                type="button" aria-label=${key.name}
                onClick=${() => send(key)}>${key.label}</button>
      `)}
      <button id="key-bar-ctrl" class="key-bar-key key-bar-wide" type="button"
              aria-label="Control modifier" aria-pressed=${ctrlActive ? "true" : "false"}
              onClick=${onToggleCtrl}>Ctrl</button>
      ${SPECIAL_KEYS.slice(7).map((key) => html`
        <button key=${key.name} class="key-bar-key key-bar-wide" type="button" aria-label=${key.name}
                onClick=${() => send(key)}>${key.label}</button>
      `)}
    </div>
  `;
}
