import type { RefObject, TargetedPointerEvent } from "preact";
import { useLayoutEffect, useRef, useState } from "preact/hooks";
import { Icon } from "./Icon.tsx";

interface TerminalKey {
  label: string;
  name: string;
  bytes: number[];
  wide?: boolean;
  releasesCtrl?: boolean;
}

export interface KeyBarProps {
  barRef: RefObject<HTMLDivElement>;
  sendBytesRef: RefObject<(bytes: Uint8Array<ArrayBuffer>) => void>;
  ctrlActive: boolean;
  onToggleCtrl: () => void;
  onReleaseCtrl: () => void;
}

const NAVIGATION_KEYS: TerminalKey[] = [
  { label: "Esc", name: "Escape", bytes: [0x1b] },
  { label: "Tab", name: "Tab", bytes: [0x09] },
  { label: "↑", name: "Up arrow", bytes: [0x1b, 0x5b, 0x41] },
  { label: "↓", name: "Down arrow", bytes: [0x1b, 0x5b, 0x42] },
];

const EXTRA_KEYS: TerminalKey[] = [
  { label: "⇧Tab", name: "Shift Tab", bytes: [0x1b, 0x5b, 0x5a], wide: true },
  { label: "←", name: "Left arrow", bytes: [0x1b, 0x5b, 0x44] },
  { label: "→", name: "Right arrow", bytes: [0x1b, 0x5b, 0x43] },
  { label: "⇧←", name: "Shift Left arrow", bytes: [0x1b, 0x5b, 0x31, 0x3b, 0x32, 0x44], wide: true },
];

const CONTROL_KEYS: TerminalKey[] = [
  { label: "^C", name: "Control C", bytes: [0x03], wide: true, releasesCtrl: true },
];

export function KeyBar({ barRef, sendBytesRef, ctrlActive, onToggleCtrl, onReleaseCtrl }: KeyBarProps) {
  const [more, setMore] = useState(false);
  const moreButton = useRef<HTMLButtonElement>(null);
  useLayoutEffect(() => {
    if (!more) return undefined;
    const outside = (event: Event) => {
      if (event.target instanceof Node && !barRef.current?.contains(event.target)) setMore(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      setMore(false);
      if (barRef.current?.contains(document.activeElement)) moreButton.current?.focus();
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape, true);
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", escape, true);
    };
  }, [more, barRef]);
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
      {NAVIGATION_KEYS.slice(0, 2).map(renderKey)}
      <button id="key-bar-ctrl" class="key-bar-key key-bar-wide" type="button"
              aria-label="Control modifier" aria-pressed={ctrlActive ? "true" : "false"}
              onClick={onToggleCtrl}>Ctrl</button>
      {NAVIGATION_KEYS.slice(2).map(renderKey)}
      {CONTROL_KEYS.map(renderKey)}
      <button ref={moreButton} class="key-bar-key" type="button" aria-label="More terminal keys"
              aria-expanded={more} aria-controls="key-bar-extra" onClick={() => setMore(!more)}><Icon name="more" /></button>
      {more && <div id="key-bar-extra" class="key-bar-extra" role="group" aria-label="More terminal keys">
        {EXTRA_KEYS.map(renderKey)}
      </div>}
    </div>
  );
}
