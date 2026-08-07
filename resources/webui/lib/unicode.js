/*
 * Which Unicode table the web terminal measures cells with — the single registry for the modes, their
 * labels, and the on-demand loading of the addon each one needs.
 *
 * xterm ships Unicode 6 widths built in and nothing else; wider tables and grapheme clustering arrive as
 * separate addons. They are NOT interchangeable with the built-in table: a width kotgent's browser
 * computes differently from the width `tmux` computed when it laid the pane out puts every following cell
 * on that line in the wrong column, and the two disagree in opposite directions depending on the
 * character. That is why the built-in table stays the default and each addon is an explicit per-device
 * choice — the same scope as the terminal font size, since it changes only how THIS browser draws bytes
 * every other viewer of the same pane receives unchanged.
 *
 * The gate is the `import()` below, not a branch around an already-loaded script: the two addons are
 * 65 KB of width tables, and an operator who never turns one on never fetches either. They are ES
 * modules and the specifier is relative, so it resolves against THIS module's own `/_v/<rev>/` URL and
 * inherits the content revision — exactly the property the importmap's document-relative targets cannot
 * have (see index.html). The browser's module map caches the namespace, so a second terminal, or a
 * switch away and back, costs no fetch at all.
 */

/** The version xterm registers in its own constructor; restoring it needs no addon. */
export const BUILT_IN_UNICODE_VERSION = "6";

export const DEFAULT_TERMINAL_UNICODE = "default";

/**
 * `version` is what the mode sets `term.unicode.activeVersion` to. It is spelled out here rather than
 * left to the addon because only the graphemes addon sets it on `activate()`; `Unicode11Addon` merely
 * REGISTERS its provider, so loading it and nothing else would leave the terminal exactly as it was.
 *
 * The graphemes addon registers a second provider, plain `"15"` — Unicode 15 widths with no cluster
 * joining. It is deliberately not offered: clustering is the behaviour worth choosing, and a fourth
 * near-identical row would only make the choice harder. Adding it later costs one entry here and no new
 * download, since it comes out of the module `15-graphemes` already fetches.
 */
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

/**
 * Fetch what `value` needs, touching no terminal. Resolves to null for a mode that needs no addon.
 *
 * Loading and installing are deliberately SEPARATE calls, and the split is not cosmetic: `import()` is
 * asynchronous, so two mode changes in quick succession have two loads in flight at once and they can
 * resolve in either order. A single call that both fetched and installed would let the loser land last
 * and leave the terminal measuring under a mode nobody selected — and, worse, leave ITS disposer holding
 * a stale "previous version" to restore later. With the split, the caller re-checks that its request is
 * still the current one in between, and a superseded load simply mutates nothing.
 */
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

/**
 * The synchronous half: install a loaded provider on `term` and return the disposer that undoes it.
 *
 * The disposer restores whatever version was active at install time — that explicit restore is what
 * switching AWAY from Unicode 11 needs, since `Unicode11Addon.dispose()` is empty and a provider can
 * never be unregistered, only shadowed by making another one active.
 *
 * A provider decides how bytes are PARSED, so it governs what arrives after it becomes active and never
 * re-measures cells already in the buffer. A mode change therefore lands on the pane's next repaint
 * rather than on the screen in front of the operator, and a first attach whose load is still in flight
 * can parse the seed under the built-in table. Both heal on the next repaint, which under an agent TUI
 * is continuous.
 */
export function installTerminalUnicode(term, loaded) {
  const previousVersion = term.unicode.activeVersion;
  const addon = new loaded.Addon();
  term.loadAddon(addon);                                 // activate() registers the provider(s) here
  term.unicode.activeVersion = loaded.mode.version;
  return () => {
    try { addon.dispose(); } catch (_) { /* already torn down with the terminal */ }
    // Idempotent for the graphemes addon, whose dispose() restores the same value; load-bearing for
    // Unicode 11, whose dispose() does nothing at all.
    try { term.unicode.activeVersion = previousVersion; } catch (_) { /* terminal disposed */ }
  };
}
