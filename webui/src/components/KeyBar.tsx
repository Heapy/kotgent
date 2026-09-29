import type { RefObject, TargetedPointerEvent } from "preact";

interface TerminalKey {
  label: string;
  name: string;
  bytes: number[];
  wide?: boolean;
  releasesCtrl?: boolean;
}

export interface KeyBarProps {
  barRef: RefObject<HTMLDivElement>;
  sendBytesRef: RefObject<(bytes: Uint8Array) => void>;
  ctrlActive: boolean;
  onToggleCtrl: () => void;
  onReleaseCtrl: () => void;
}

const NAVIGATION_KEYS: TerminalKey[] = [
  { label: "Esc", name: "Escape", bytes: [0x1b] },
  { label: "Tab", name: "Tab", bytes: [0x09] },
  { label: "⇧Tab", name: "Shift Tab", bytes: [0x1b, 0x5b, 0x5a], wide: true },
  { label: "↑", name: "Up arrow", bytes: [0x1b, 0x5b, 0x41] },
  { label: "↓", name: "Down arrow", bytes: [0x1b, 0x5b, 0x42] },
  { label: "←", name: "Left arrow", bytes: [0x1b, 0x5b, 0x44] },
  { label: "→", name: "Right arrow", bytes: [0x1b, 0x5b, 0x43] },
  { label: "⇧←", name: "Shift Left arrow", bytes: [0x1b, 0x5b, 0x31, 0x3b, 0x32, 0x44], wide: true },
];

const CONTROL_KEYS: TerminalKey[] = [
  { label: "^C", name: "Control C", bytes: [0x03], wide: true, releasesCtrl: true },
];

export function KeyBar({ barRef, sendBytesRef, ctrlActive, onToggleCtrl, onReleaseCtrl }: KeyBarProps) {
  const send = (key: TerminalKey) => {
    const sendBytes = sendBytesRef.current;
    if (sendBytes) sendBytes(Uint8Array.from(key.bytes));
    if (key.releasesCtrl) onReleaseCtrl();
  };
  // Keep xterm's hidden textarea and the phone keyboard focused.
  const preserveTerminalFocus = (event: TargetedPointerEvent<HTMLDivElement>) => event.preventDefault();
  const renderKey = (key: TerminalKey) => (
    <button key={key.name} class={"key-bar-key" + (key.wide ? " key-bar-wide" : "")}
            type="button" aria-label={key.name}
            onClick={() => send(key)}>{key.label}</button>
  );

  return (
    <div class="key-bar" role="toolbar" aria-label="Terminal special keys" ref={barRef}
         onPointerDown={preserveTerminalFocus}>
      {NAVIGATION_KEYS.map(renderKey)}
      <button id="key-bar-ctrl" class="key-bar-key key-bar-wide" type="button"
              aria-label="Control modifier" aria-pressed={ctrlActive ? "true" : "false"}
              onClick={onToggleCtrl}>Ctrl</button>
      {CONTROL_KEYS.map(renderKey)}
    </div>
  );
}
