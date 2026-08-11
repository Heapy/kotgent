/*
 * Unicode width must match tmux's layout, so addons are opt-in and device-local. Dynamic imports are the
 * download gate and inherit this module's content-revision path.
 */

export const BUILT_IN_UNICODE_VERSION = "6";

export const DEFAULT_TERMINAL_UNICODE = "default";

// Unicode11Addon registers a provider but does not activate it, so every mode names its version here.
export const TERMINAL_UNICODE_MODES = [
  {
    value: DEFAULT_TERMINAL_UNICODE,
    label: "Built-in — Unicode 6 widths",
    hint: "What xterm.js measures with out of the box, and what kotgent has always shipped.",
    module: null,
    export: null,
    version: BUILT_IN_UNICODE_VERSION,
  },
  {
    value: "11",
    label: "Unicode 11 widths",
    hint: "Modern double-width ranges — CJK and most emoji stop being measured one cell wide.",
    module: "../vendor/addon-unicode11.module.js",
    export: "Unicode11Addon",
    version: "11",
  },
  {
    value: "15-graphemes",
    label: "Unicode 15 widths + grapheme clusters",
    hint: "Adds Unicode 15 and joins combining marks, flags and ZWJ emoji into one cell each.",
    module: "../vendor/addon-unicode-graphemes.module.js",
    export: "UnicodeGraphemesAddon",
    version: "15-graphemes",
  },
];

export function isTerminalUnicodeMode(value) {
  return TERMINAL_UNICODE_MODES.some((mode) => mode.value === value);
}

export function terminalUnicodeMode(value) {
  return TERMINAL_UNICODE_MODES.find((mode) => mode.value === value) || TERMINAL_UNICODE_MODES[0];
}

// Loading and installation stay separate so the caller can reject an out-of-order dynamic import.
export async function loadTerminalUnicode(value) {
  const mode = terminalUnicodeMode(value);
  if (!mode.module) return null;
  const namespace = await import(mode.module);
  const Addon = namespace[mode.export];
  if (typeof Addon !== "function") {
    throw new Error("vendored " + mode.module + " does not export " + mode.export);
  }
  return { mode: mode, Addon: Addon };
}

// Unicode11Addon.dispose() is empty, so the returned disposer must restore the previous active version.
export function installTerminalUnicode(term, loaded) {
  const previousVersion = term.unicode.activeVersion;
  const addon = new loaded.Addon();
  term.loadAddon(addon);
  term.unicode.activeVersion = loaded.mode.version;
  return () => {
    try { addon.dispose(); } catch (_) {}
    try { term.unicode.activeVersion = previousVersion; } catch (_) {}
  };
}
