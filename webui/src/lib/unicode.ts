/*
 * Unicode width must match tmux's layout, so addons are opt-in and device-local. Dynamic imports are the
 * download gate.
 */

import type { ITerminalAddon, Terminal } from "@xterm/xterm";

export type TerminalUnicodeModeValue = "default" | "11" | "15-graphemes";

type AddonConstructor = new () => ITerminalAddon;
type UnicodeAddonExports = {
  Unicode11Addon?: AddonConstructor;
  UnicodeGraphemesAddon?: AddonConstructor;
};

export type TerminalUnicodeMode = {
  value: TerminalUnicodeModeValue;
  label: string;
  hint: string;
  version: string;
} & (
  | { load: null; export: null }
  | { load: () => Promise<UnicodeAddonExports>; export: keyof UnicodeAddonExports }
);

export interface LoadedTerminalUnicode {
  mode: TerminalUnicodeMode;
  Addon: AddonConstructor;
}

export const BUILT_IN_UNICODE_VERSION = "6";

export const DEFAULT_TERMINAL_UNICODE = "default";

// Unicode11Addon registers a provider but does not activate it, so every mode names its version here.
export const TERMINAL_UNICODE_MODES: [TerminalUnicodeMode, ...TerminalUnicodeMode[]] = [
  {
    value: DEFAULT_TERMINAL_UNICODE,
    label: "Built-in — Unicode 6 widths",
    hint: "What xterm.js measures with out of the box, and what kotgent has always shipped.",
    load: null,
    export: null,
    version: BUILT_IN_UNICODE_VERSION,
  },
  {
    value: "11",
    label: "Unicode 11 widths",
    hint: "Modern double-width ranges — CJK and most emoji stop being measured one cell wide.",
    load: () => import("@xterm/addon-unicode11"),
    export: "Unicode11Addon",
    version: "11",
  },
  {
    value: "15-graphemes",
    label: "Unicode 15 widths + grapheme clusters",
    hint: "Adds Unicode 15 and joins combining marks, flags and ZWJ emoji into one cell each.",
    load: () => import("@xterm/addon-unicode-graphemes"),
    export: "UnicodeGraphemesAddon",
    version: "15-graphemes",
  },
];

export function isTerminalUnicodeMode(value: unknown): value is TerminalUnicodeModeValue {
  return TERMINAL_UNICODE_MODES.some((mode) => mode.value === value);
}

export function terminalUnicodeMode(value: unknown) {
  return TERMINAL_UNICODE_MODES.find((mode) => mode.value === value) || TERMINAL_UNICODE_MODES[0];
}

// Loading and installation stay separate so the caller can reject an out-of-order dynamic import.
export async function loadTerminalUnicode(value: unknown): Promise<LoadedTerminalUnicode | null> {
  const mode = terminalUnicodeMode(value);
  if (!mode.load) return null;
  const namespace = await mode.load();
  const Addon = namespace[mode.export];
  if (typeof Addon !== "function") {
    throw new Error("Unicode mode " + mode.value + " does not export " + mode.export);
  }
  return { mode: mode, Addon: Addon };
}

// Unicode11Addon.dispose() is empty, so the returned disposer must restore the previous active version.
export function installTerminalUnicode(term: Pick<Terminal, "unicode" | "loadAddon">, loaded: LoadedTerminalUnicode) {
  const previousVersion = term.unicode.activeVersion;
  const addon = new loaded.Addon();
  term.loadAddon(addon);
  term.unicode.activeVersion = loaded.mode.version;
  return () => {
    try { addon.dispose(); } catch (_) {}
    try { term.unicode.activeVersion = previousVersion; } catch (_) {}
  };
}
