# Command palette, spacemacs leader, collapsible sidebar, dark-only mac-native shell

## Overview

The Web UI grew a button for every action: the sidebar header carries four icon buttons plus a primary
"New session" button, and the terminal header renders up to five action buttons at once. Most are pressed
once a week. This plan moves the rare ones behind a `⌘K` command palette that doubles as a
spacemacs-style leader menu, leaves exactly one button visible in the sidebar header (notifications — the
only daily one), collapses the sidebar on `⌘1`, and restyles the shell as a dark-only, mac-native
two-card layout with a Kotlin-purple accent.

Three stages ship independently and each keeps the suite green:

1. **Palette + leader + chrome cleanup** — the palette becomes the home of every rare action.
2. **Collapsible sidebar** — a desktop-only toggle button plus `⌘1`.
3. **Dark-only mac-native shell** — one theme, floating cards, pill rows, OLED-black on phones.

Two further stages are scoped but deliberately NOT designed here (see "Follow-up stages"): a free
terminal with no agent inside (`t`), and a listing of resumable provider sessions.

No daemon route, DTO, schema or CLI contract changes anywhere in stages 1–3. No new runtime dependency:
the palette is Preact + htm like everything else, and the matcher is plain JS.

## Context (from discovery)

- **Files involved:** `resources/webui/app.js` (owns nearly all mutable state), `components/Sidebar.js`,
  `components/TerminalPane.js`, `components/dialogs.js`, `lib/prefs.js`, `style.css`, `index.html`,
  and `test/transport/WebUiServingTest.kt`.
- **Patterns to follow:**
  - pure logic lives in `lib/` and is import-able outside a browser (`lib/paths.js`, `lib/sessions.js`,
    `lib/agents.js`); components stay presentational.
  - dialogs are native `<dialog>` behind the `Dialog` wrapper in `dialogs.js` — `showModal()` supplies
    Esc, focus trapping, the backdrop and top-layer stacking; open/closed is ordinary Preact state.
  - the listbox pattern already exists: the cwd autocomplete in `NewSessionDialog` (`role="combobox"`,
    `role="listbox"`, `aria-activedescendant`, arrow wrap-around) is the model to copy.
  - a control that exists on every screen but is used on one is kept in the DOM and hidden by CSS —
    `.drawer-toggle, .drawer-close, .drawer-scrim { display: none }` (`style.css:1014-1016`), with the
    media query turning them on. Stage 2 follows this rather than branching on `matchMedia` in JS.
  - per-device settings persist in `localStorage` through `lib/prefs.js` (terminal font size, collapsed
    groups); daemon-wide settings (`basePath`, `groupingLevel`) go through `GET/PUT /preferences`.
- **Dependencies identified:**
  - `app.js` owns most actions the palette needs: `controlSession` (`interrupt`/`resume`/`stop`/`done`/
    `undone`), `attach`, `detach`, `openNewSession`, `openPrefs`, `openHelp`, `openPhone`,
    `selectSession`.
  - ⚠️ **Copy tmux is the exception.** `copyTmuxCommand` is a local closure in
    `components/TerminalPane.js:306`, built on the module-private `writeClipboard`
    (`TerminalPane.js:33`, with a `document.execCommand` fallback) and driving local `copyResult` state
    for its "Copied tmux" / "Copy failed" label and `aria-live` status (`TerminalPane.js:97`, `:357`).
    It must be extracted before the palette can call it — see Task 2.
  - `showDone` lives in `Sidebar.js:145`. The palette's "Show/Hide done sessions" command mutates it, so
    it moves up to `app.js`. (This is not about listing archived sessions in the palette — those are
    listed unconditionally, independent of the sidebar toggle.)
  - `NewSessionDialog` already has an **Import existing** mode (`onImport` → `POST /sessions/import`),
    which is what the `r` mnemonic opens. Only the candidate listing is missing (stage 5).
- **Constraints discovered in existing tests** — each of these breaks if the plan is followed naively:
  - `WebUiServingTest.kt:614, 703, 729` pin `id="prefs-button"`, `id="help-button"` and
    `id="phone-button"` as living in `Sidebar.js`. Those buttons move into the palette, so the
    assertions move with them — they are not deleted.
  - `WebUiServingTest.kt:375-381` (`theAgentRadiosAreHiddenWithoutLeavingTheKeyboardOrTheDarkTheme`)
    asserts each `.agent-icon-<agent>` rule appears **exactly twice** — "once per colour scheme". Going
    dark-only makes it once. The assertion must be rewritten, not deleted.
  - `WebUiServingTest.kt:1673-1681` (`xtermFitSubtractsThePaddingThatFramesTerminalContent`) asserts
    `#terminal-host` has no `padding:` at all (the vendored FitAddon measures the parent and subtracts
    padding only from `.xterm`), and slices the mobile CSS as "from the FIRST `@media (max-width: 720px)`
    to the next `@media`". A second 720px block inserted earlier in the file silently breaks it.
  - `style.css:88-99`: `#sidebar` carries `min-width: 240px`, `padding: 12px` and `border-right`.
  - `dialogs.js:150-158`: `switchMode` deliberately clears `cwd` when entering import mode, because a
    non-empty cwd is sent as an **explicit override** of the daemon's transcript discovery and the codex
    probe ignores cwd entirely — the wrong project dir would be stored permanently.
  - `#app` (`index.html:46`) is the Preact render container passed to `render()` (`app.js:948`); it is
    never part of the vdom, so no rendered component can put a class on it.

## Development Approach

- **testing approach**: Regular (code first, then contract assertions). The project has no JavaScript
  test harness by design — browser behaviour is a manual checklist, and source/serving contracts live in
  `test/transport/WebUiServingTest.kt`. "Write tests" in this plan therefore means two concrete things:
  1. `node --check <file>` on every changed ES module;
  2. new, moved or rewritten assertions in `WebUiServingTest` — every newly served module must be
     registered in `daemonServesTheComponentAndLibModules`, and every behaviour observable in the served
     source (an id, a wired route, a key binding) gets an assertion.
- **assert invariants, not decoration.** Every existing CSS assertion in the suite guards something a
  silent failure would hide (FitAddon padding accounting, the drawer's display rules, the
  focusable-but-invisible radio). Pin the same class of thing here — "no `prefers-color-scheme` block
  survives", "the blur sits inside the desktop media query", "`#terminal-host` still has no padding" —
  and do **not** pin literal colours, radii or alphas, which would only create change-detector tests.
- complete each task fully before moving to the next
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting the next task** — `./kotlin build` BEFORE
  `./kotlin test` (the suite's `PtyTest` execs the `ptycheck` binary, and `./kotlin test` never links a
  main binary)
- **CRITICAL: update this plan file when scope changes during implementation**

## Testing Strategy

- **unit tests**: `WebUiServingTest` assertions per task. `lib/commands.js` is pure and its behaviour
  (grouping, disabled-tail ordering, the chord table) is asserted through the served source text, the
  same way `lib/paths.js` and `lib/prefs.js` are pinned today.
- **e2e tests**: the project has none and adds none — there is no Playwright/Cypress harness and adding
  one is out of scope. Browser behaviour lands in the Post-Completion manual checklist instead.
- **regression baseline**: the last recorded green run was **844 native tests / 0 skipped** (plus the
  build-info plugin's 7 JVM tests and 11 `ptycheck` checks — see `docs/plans/20260730-junie-provider.md`).
  Record the actual number on the first green run of this work and compare at the end.
- **implementation baseline (2026-07-31)**: **844 native tests / 0 skipped**, plus 7 build-info JVM
  tests and all 11 `ptycheck` checks through `PtyTest`.
- **acceptance result (2026-07-31)**: **851 native tests / 0 skipped**, seven above the implementation
  baseline, plus 7 build-info JVM tests and all 11 `ptycheck` checks through `PtyTest`.
- **automation limits** (from `CLAUDE.md`): never start the daemon, `./kotlin run`, a real
  `claude`/`codex`/`junie`, or `launchctl` while implementing.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview

**One registry, two views.** `lib/commands.js` is the single source of every command. The palette's
search mode renders it as a filtered list; the leader mode renders the subset carrying a `chord` as a
mnemonic grid. A second command list cannot drift into existence because there is only one.

**The palette is a native `<dialog>`.** It reuses the `Dialog` wrapper already in `dialogs.js` (which
must be exported), inheriting Esc, focus trapping, backdrop dismissal and top-layer stacking without a
z-index fight.

**One document-level key listener, plus the palette's own dialog-scoped handler.** `app.js` registers
exactly one `keydown` handler on `document` in the **capture** phase, for `⌘K` / `⌃⇧K` / `⌘1`. Capture is
not stylistic: xterm.js listens on its own hidden textarea and calls `stopPropagation()` on keys it
handles, so a bubbling listener would never see them. Once the palette is open, `showModal()` has already
moved focus into the top layer — nothing reaches the terminal — so leader-mode letters are handled inside
the palette component, where they belong.

**Actions are closures, not strings.** `buildCommands` receives handlers the app already owns and stores
them as `run`. The palette never knows a route or an id format.

**Disabled commands are visible, not hidden.** A command that does not apply right now keeps a
`disabled` reason. Search shows those as a dimmed tail *after* the available results, so typing "resume"
answers "yes, it exists, but not now" instead of "no results". With an empty query the tail is hidden.

## Technical Details

### Command descriptor

```js
{
  id: "session.interrupt",        // stable key for Preact and for the serving test
  group: "sessions" | "session" | "general",
  title: "Interrupt current session",
  subtitle: "sends Ctrl-C and marks the session ready",
  hint: "needs approval",         // right edge: state badge for a session row, chord chip for a command
  chord: "i" | null,              // leader mnemonic, single lowercase letter
  disabled: null | "session is running",
  run: () => void,
}
```

### Matching

`filterCommands(items, query)` is a case-insensitive **substring** match over `title + subtitle` (and,
for session rows, `name + cwd + tags + agent`), ordered by: match at a word start first, then match
position, then the registry's own order. A fuzzy subsequence scorer was considered and rejected — with
roughly a dozen commands plus the session list, it is machinery without an observable benefit.

Output order:

1. available items;
2. with a non-empty query only: disabled items, flagged so the view dims them.

With an empty query there is no matching at all: sessions first — **needs-attention ones, then the rest,
deduplicated** — then commands. Note the sidebar deliberately renders attention sessions *twice*
(`#attention-section` at `Sidebar.js:348` plus the unfiltered full list at `:374`); copying "sidebar
order" literally would list every waiting session twice in the palette.

Archived ("done") sessions are always included, carrying `hint: "done"`, independent of the sidebar's
"Show done" toggle. The palette is deliberately the one place a hidden session can be found.

### Modes

| state | `⌘K` (or `⌃⇧K`) | letter | `Esc` | `Backspace` |
|-------|------------------|--------|-------|-------------|
| closed | open in search | — | — | — |
| search | switch to leader | typed into the query | close | edit query |
| leader | switch back to search | run its command | close | back to search |

In leader mode mnemonics are read **without** checking modifiers — by the time the second `K` lands the
user may have released `⌘` — so the match is on bare `e.code` (`KeyN`, `KeyS`, …). `Space` needs an
explicit `preventDefault()` so it cannot activate the focused search row.

Running a command **closes the palette first, then calls `run()`**. The reverse order puts
`window.confirm` (Stop, Done) behind an open modal and makes `NewSessionDialog` call a second
`showModal()` while the palette still owns the focus trap. For the same reason `⌘K` is **ignored while
another dialog is open** — stacking two `showModal()` dialogs is not a state this UI should reach.

### Mnemonic table

| key | command | availability |
|-----|---------|--------------|
| `n` | New session | always |
| `i` | Interrupt current session | needs a live session |
| `r` | Resume a conversation started outside kotgent… | always |
| `t` | New free terminal | disabled until stage 4 — "not implemented yet" |
| `s` | Stop current session… | needs a live session |
| `d` | Done current session… | needs a session |
| `c` | Copy tmux command | needs a live session with a tmux name |
| `h` | Help | always |
| `m` | Sign in from your phone | always |
| `b` | Toggle notifications | disabled until the push controller moves into `lib/` |
| `p` | Preferences | always |

`t` and `b` are reserved and rendered disabled from day one so muscle memory never has to be relearned.

Two commands must not be confused, so their titles disambiguate explicitly: `r` opens the **import**
mode of `NewSessionDialog` ("Resume a conversation started outside kotgent…"), while the session-level
control from `controlSession("resume")` is titled "Resume this session" and carries **no** chord — it
only applies to a dead session and is reachable by search.

Attach and Detach also carry no chord; they are search-only.

### Import mode must open with an empty cwd

`app.js:866-868`'s `openNewSession` always computes a cwd (explicit dir → selected session's cwd →
`basePath`), and `dialogs.js:55` seeds `useState(initialCwd || "")`. `switchMode` clears it on purpose
(`dialogs.js:158`) — import sends a non-empty cwd as an explicit override of transcript discovery, and
the codex probe ignores cwd, so the wrong project dir would be recorded for good. Therefore the new
`initialMode` prop must seed `cwd` empty whenever it is `"import"`, and the serving test must pin that
seeding — today's assertion (`WebUiServingTest.kt:505-508`) covers only the `switchMode` path.

### Entry points

- desktop: `⌘K` / `⌃⇧K`, plus the `⋯` button in the terminal header.
- phone: the `⋯` button opens the palette **directly in leader mode** — large touch targets, no software
  keyboard. Tapping the search row at the top switches to search and focuses the input.
- ⚠️ `⌃⇧K` is Firefox's Web Console shortcut and cannot be `preventDefault()`-ed there — same class of
  hazard as `⌘1` below. `⌘K` is the primary binding; `⌃⇧K` is the non-mac courtesy.

### Chrome after the cleanup

| surface | desktop | phone |
|---------|---------|-------|
| sidebar header | title + 🔔 (`#notify-toggle`) | same, plus `#drawer-close` |
| empty state | `#empty-sessions` gains a "Start a session" button | same |
| group row | unchanged (`+` per group, grouping on only) | unchanged |
| base-path note | unchanged — still opens Preferences on click | unchanged |
| terminal header | `#sidebar-toggle` (stage 2) + name + badge + `⋯` | `☰` + name + badge + action icons + `⋯` |

`onOpenPrefs` and `onNewSession` stay `Sidebar` props: `#base-path-note` still opens preferences, and the
per-group `+` still starts a session.

⚠️ The per-group `+` exists only when grouping is enabled (`Sidebar.js:121`, `:375`), and the default
`basePath` is empty. On a fresh daemon with no base path and no sessions, removing `#new-session-button`
would leave "New session" reachable only through `⌘K`/`⋯` — undiscoverable for a first run. Hence the
`#empty-sessions` affordance above; it is a first-run rescue, not a return of the header icon.

`#palette-button` must render **outside** the `${session && …}` guard that wraps `#session-actions`
(`TerminalPane.js:349`), otherwise the palette has no button exactly when no session exists yet.

Hiding `#session-actions` on desktop also hides the `role="status" aria-live` region at
`TerminalPane.js:357` that announces the copy result — so the palette's copy command reports through the
sidebar status line (`say`) instead.

### Sidebar collapse (stage 2)

A **new** `#sidebar-toggle` button is added to the terminal header and hidden below the mobile
breakpoint, mirroring how `#drawer-toggle` is hidden above it. `#drawer-toggle` and its `onToggleDrawer`
prop are left completely untouched — `WebUiServingTest.kt:1509-1512` and `:1531-1534` pin them, and
overloading one button by breakpoint would need a `matchMedia` branch that has no precedent here.

The collapsed class goes on `#sidebar` (the pattern `Sidebar.js:282` already uses for `drawerOpen`),
**not** on `#app`, which is the render container and not part of the vdom.

The collapse rule must zero `min-width`, `padding` and `border-right` alongside `width`, and add
`overflow: hidden` — `style.css:88-99` sets all three, and `min-width: 240px` alone would clamp the
animation at 240px.

`.drawer-toggle, .drawer-close, .drawer-scrim { display: none }` (`style.css:1014-1016`) is left alone;
`#sidebar-toggle` gets its own inverse rule (visible by default, `display: none` inside the 720px block).

No resize plumbing is needed: `TerminalPane` already observes `#terminal-host` with a `ResizeObserver`,
which fires `fitAndReport` (debounced 120 ms) and sends a resize frame upstream.

⚠️ **Risk:** in an ordinary Chrome/Safari tab `⌘1…⌘9` is reserved for tab switching — the page either
never receives the event or its `preventDefault()` is ignored. The binding is reliable in the installed
PWA (`display: standalone`). `#sidebar-toggle` is the guaranteed path; the shortcut is a bonus.

### Dark-only shell (stage 3)

- **Three** `@media (prefers-color-scheme: dark)` blocks fold into their base rules, not two:
  `style.css:22` (root variables), `style.css:396` (state badges) and `style.css:769` (agent chips).
- Folding the third one changes each `.agent-icon-<agent>` rule from two occurrences to one, which
  **breaks** `theAgentRadiosAreHiddenWithoutLeavingTheKeyboardOrTheDarkTheme`
  (`WebUiServingTest.kt:375-381`). That assertion is rewritten to expect one declaration and to keep its
  real intent: every chip is declared, once, in the single theme.
- accent: `--accent: #8B62FF` (borders, focus rings, active row, accent text). Filled buttons use a
  denser `#7A4FF5`: white on `#8B62FF` measures ≈3.9:1, borderline for small text.
- phones: the OLED override (`--bg: #000`, near-black panels) goes **inside the existing**
  `@media (max-width: 720px)` block at `style.css:1018`. A new earlier 720px block would change what
  `xtermFitSubtractsThePaddingThatFramesTerminalContent` slices and break it.
- `theme-color` in `index.html` follows the new shell.
- geometry: `#app` holds the background; sidebar and terminal pane become cards with **margin** and
  `border-radius`. The inset must never be expressed as `padding` on `#terminal-host` — the FitAddon
  measures that element as the parent and the suite asserts it carries no padding
  (`WebUiServingTest.kt:1673-1676`).
- `backdrop-filter: blur(20px)` on the translucent sidebar is **desktop only**; on a phone it costs a
  composite pass per frame behind a constantly repainting terminal.
- rows become pills: inset, rounded, active row filled with the accent at low alpha, neutral hover.
  Section headings become small uppercase muted labels.

## What Goes Where

- **Implementation Steps** (`[ ]` checkboxes): the new modules, the edits to `app.js`/components/
  `style.css`, and the `WebUiServingTest` contracts.
- **Post-Completion** (no checkboxes): manual browser verification — mobile keyboard, iOS PWA, blur
  performance, `⌘1` in a real browser tab — which no automated test in this project can cover.

## Implementation Steps

### Task 1: Add the pure command registry and matcher

**Files:**
- Create: `resources/webui/lib/commands.js`

- [x] create `buildCommands(ctx)` returning the descriptor array from Technical Details, with
      `ctx = {sessions, activeSession, attachedId, actions}`; group `sessions`, then `session`, then
      `general`
- [x] compute `disabled` per command from the rules the terminal header uses today (`isAliveState`,
      `session.id === attachedId`, `session.tmuxSession` present), each with a short human reason
- [x] attach the chord letters from the mnemonic table, marking `t` and `b` disabled with an explicit
      "not implemented yet" reason, and give the two resume-ish commands their disambiguating titles
- [x] implement `filterCommands(items, query)`: case-insensitive substring, word-start matches first,
      available items first, disabled ones as a tail omitted entirely for an empty query
- [x] for an empty query, emit needs-attention sessions first and then the remainder **deduplicated**,
      including archived sessions with `hint: "done"`
- [x] run `node --check resources/webui/lib/commands.js`
- [x] add `WebUiServingTest` assertions: the module is served and registered, exports both functions, and
      its source carries the full chord table including the reserved-disabled letters
- [x] run `./kotlin build && ./kotlin test` — must pass before task 2

### Task 2: Extract the clipboard helper so the palette can copy the tmux command

**Files:**
- Create: `resources/webui/lib/clipboard.js`
- Modify: `resources/webui/components/TerminalPane.js`

- [x] move `writeClipboard` (`TerminalPane.js:33`, including its `document.execCommand` fallback)
      verbatim into `lib/clipboard.js` and export it
- [x] have `TerminalPane.js` import it; leave `copyResult`, the button label and the `aria-live` status
      exactly as they are (the header button keeps its own feedback)
- [x] expose a copy action from `app.js`'s side that reports through the status line (`say`), since the
      palette closes before running and has no `aria-live` region of its own
- [x] run `node --check` on both modules
- [x] register `lib/clipboard.js` in `daemonServesTheComponentAndLibModules` and assert `TerminalPane.js`
      imports it rather than defining the helper
- [x] run `./kotlin build && ./kotlin test` — must pass before task 3

### Task 3: Add the palette component in search mode

**Files:**
- Create: `resources/webui/components/CommandPalette.js`
- Modify: `resources/webui/components/dialogs.js`

- [x] export the existing `Dialog` wrapper from `dialogs.js` (no behaviour change)
- [x] create `CommandPalette` with `id="command-palette"`: a query `<input>` with autofocus and a
      `<ul role="listbox">` of `role="option"` rows wired with `aria-activedescendant`
- [x] render a row as title + subtitle + right-edge `hint`, a chord chip whose `title` spells the full
      accord, and a dimmed style for rows carrying `disabled`
- [x] implement arrow navigation with wrap-around that skips the disabled tail, `Enter` to run,
      `scrollIntoView({block:"nearest"})` on the active row, and an active-index reset on query change
- [x] close the palette BEFORE invoking `run()`
- [x] run `node --check` on both changed modules
- [x] add `WebUiServingTest` assertions: the module is registered, imports `lib/commands.js`, and ships
      the listbox roles
- [x] run `./kotlin build && ./kotlin test` — must pass before task 4

### Task 4: Add leader mode to the palette

**Files:**
- Modify: `resources/webui/components/CommandPalette.js`
- Modify: `resources/webui/style.css`

- [x] accept `mode` (`"search" | "leader"`) plus `onModeChange`; in leader mode replace the input with a
      mnemonic grid built from the same descriptors (letter chip + title)
- [x] handle a bare `e.code` letter in leader mode (no modifier check) → run its command; ignore a
      disabled letter and surface its reason in the palette footer; `preventDefault()` on `Space`
- [x] `Backspace` returns to search, `Esc` closes (native)
- [x] render the search row at the top of leader mode as a tappable control that switches to search and
      focuses the input — the phone's path into search
- [x] style both modes: a grid of large touch targets in leader, a list in search
- [x] run `node --check resources/webui/components/CommandPalette.js`
- [x] add `WebUiServingTest` assertions for the mnemonic grid and the reserved-but-disabled letters
- [x] run `./kotlin build && ./kotlin test` — must pass before task 5

### Task 5: Wire the palette into app.js with the global key listener

**Files:**
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/components/dialogs.js`

- [x] lift `showDone` out of `Sidebar` into `app.js`, passing `showDone` and `onToggleShowDone` down
- [x] add palette state (`null | {mode}`) plus `openPalette(mode)` / `closePalette`
- [x] register ONE `document.addEventListener("keydown", handler, true)` in an effect: match
      `e.metaKey && e.code === "KeyK"` or `e.ctrlKey && e.shiftKey && e.code === "KeyK"`, then
      `preventDefault()` + `stopPropagation()`; closed → open in search, open → toggle mode; ignore the
      binding entirely while another dialog is open
- [x] build the command context from the existing handlers plus the extracted copy action, and pass
      `run` closures into `buildCommands`
- [x] add an `initialMode` prop to `NewSessionDialog` that seeds `cwd` **empty** when it is `"import"`,
      and wire `r` to open it
- [x] run `node --check` on every changed module
- [x] add `WebUiServingTest` assertions: `app.js` registers a capture-phase listener, matches on
      `e.code`, renders `CommandPalette`, and the dialog's import mode starts with an empty cwd
- [x] run `./kotlin build && ./kotlin test` — must pass before task 6

### Task 6: Clean up the visible chrome

**Files:**
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/style.css`

- [x] remove `#new-session-button`, `#phone-button`, `#help-button` and `#prefs-button` from the sidebar
      header, keeping `#notify-toggle` and `#drawer-close`, and keep the `onOpenPrefs` / `onNewSession`
      props for `#base-path-note` and the per-group `+`
- [x] add a "Start a session" button to the `#empty-sessions` empty state so a first run is not left
      without a discoverable path
- [x] add `#palette-button` (`⋯`) to the terminal header **outside** the `${session && …}` guard,
      opening the palette in `search` on desktop and `leader` under the mobile breakpoint
- ➕ [x] wire the `openPalette` handler added in Task 5 through `app.js` to the terminal header button;
      `app.js` is required for the button to work but was omitted from Task 6's file list
- [x] hide the terminal header's action buttons above the mobile breakpoint via CSS only (they stay in
      the markup and stay available on phones); refresh the stale wrap comment at `style.css:1077-1079`
- [x] run `node --check` on both components
- [x] move the `prefs-button` / `help-button` / `phone-button` assertions from `Sidebar.js` onto the
      palette registry, and assert the sidebar header now carries only the notification toggle
- [x] run `./kotlin build && ./kotlin test` — must pass before task 7

### Task 7: Persist the sidebar collapse preference

**Files:**
- Modify: `resources/webui/lib/prefs.js`

- [x] add `SIDEBAR_COLLAPSED_KEY` alongside the terminal-font-size and collapsed-groups keys, with
      `loadSidebarCollapsed()` / `persistSidebarCollapsed(value)`
- [x] keep it strictly per-device — it must never travel through `GET/PUT /preferences`, so a collapsed
      desktop sidebar cannot collapse the phone's drawer
- [x] tolerate absent or garbage storage values by falling back to "expanded"
- [x] run `node --check resources/webui/lib/prefs.js`
- [x] extend the `lib/prefs.js` assertions to pin the new key and both helpers
- [x] run `./kotlin build && ./kotlin test` — must pass before task 8

### Task 8: Collapse the sidebar with a desktop toggle and ⌘1

**Files:**
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/style.css`

- [x] hold `sidebarCollapsed` in `app.js`, seeded from `loadSidebarCollapsed()` and persisted on change
- [x] add `#sidebar-toggle` to the terminal header (a separate button from `#drawer-toggle`, which stays
      exactly as it is) and render `collapsed` as a class on `#sidebar`, not on `#app`
- [x] add the collapse rule zeroing `width`, `min-width`, `padding` and `border-right` with
      `overflow: hidden`, plus a transition; hide `#sidebar-toggle` inside the 720px block without
      touching the shared `.drawer-toggle, .drawer-close, .drawer-scrim` rule
- [x] extend the existing capture listener with `e.metaKey && e.code === "Digit1"`, with a comment
      recording that a plain browser tab reserves `⌘1` and only the installed PWA gets it reliably
- [x] run `node --check` on every changed module
- [x] add assertions for `#sidebar-toggle`, the collapse rule's four zeroed properties, and the
      untouched `onToggleDrawer` wiring (`WebUiServingTest.kt:1509`, `:1531` must still pass unchanged)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 9

### Task 9: Collapse the stylesheet to a single dark theme with the Kotlin accent

**Files:**
- Modify: `resources/webui/style.css`
- Modify: `resources/webui/index.html`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] fold **all three** `@media (prefers-color-scheme: dark)` blocks (`style.css:22`, `:396`, `:769`)
      into their base rules and delete the light values
- [x] rewrite `theAgentRadiosAreHiddenWithoutLeavingTheKeyboardOrTheDarkTheme`
      (`WebUiServingTest.kt:375-381`) to expect one `.agent-icon-<agent>` declaration instead of two,
      keeping its intent ("every chip is declared, once, in the single theme")
- [x] set `--accent: #8B62FF` and give filled buttons a denser `#7A4FF5` background
- [x] add the phone override (`--bg: #000`, near-black panels) **inside the existing**
      `@media (max-width: 720px)` block at `style.css:1018`, and update `theme-color` in `index.html`
- [x] grep to confirm no `prefers-color-scheme` rule survives
- [x] assert the invariant, not the palette: no `prefers-color-scheme` block remains, and the mobile
      block still contains the `#terminal-host .xterm` padding rule the fit test slices for
- [x] run `./kotlin build && ./kotlin test` — must pass before task 10

### Task 10: Float the sidebar and terminal as two cards

**Files:**
- Modify: `resources/webui/style.css`

- [x] move the background onto `#app` and give the sidebar and terminal pane **margins** (never padding
      on `#terminal-host`), `border-radius` and a soft shadow; drop the sidebar's right border
- [x] make the sidebar translucent with `backdrop-filter: blur(20px)` scoped to the desktop breakpoint
      only, so phones never pay the composite pass
- [x] verify the mobile drawer overlay and its scrim still line up with the new radii and insets, and
      that the stage-8 collapse rule still animates cleanly with the new margins
- [x] confirm `env(safe-area-inset-*)` padding still applies at the shell level — the new margins add to
      it rather than replacing it
- [x] assert `#terminal-host` still carries no `padding:` and that the blur sits inside the desktop
      media query
- [x] run `./kotlin build && ./kotlin test` — must pass before task 11

### Task 11: Turn session rows into pills

**Files:**
- Modify: `resources/webui/style.css`

- [x] inset and round session rows; fill the active row with the accent at low alpha and give hover a
      neutral fill
- [x] restyle section headings as small uppercase muted labels and align the group head, unread pill and
      attention dot to the new inset
- [x] restyle the palette rows to match so it reads as part of the same shell
- [x] confirm the focus ring stays visible on a pill (the session list is keyboard-navigable —
      `Sidebar.js:59-64`)
- [x] assert the focus-visible rule survives the restyle (an invariant; do not pin radii or alphas)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 12

### Task 12: Verify acceptance criteria

- [x] verify every command in the mnemonic table is reachable in both modes, and that `t` and `b` render
      disabled with their reasons
- [x] verify the sidebar header carries only the notification toggle, that Preferences is still reachable
      from `#base-path-note`, and that a fresh empty state still offers "Start a session"
- ➕ [x] pin the existing `#base-path-note` → `onOpenPrefs` entry point in `WebUiServingTest`; the
      acceptance audit found it was present in source but covered only by inspection
- [x] verify no assertion was deleted rather than moved or rewritten: `prefs-button`, `help-button`,
      `phone-button`, the agent-chip count and the FitAddon padding invariant are all still pinned
- [x] run the full suite: `./kotlin build && ./kotlin test`
- [x] confirm the native test count matches or exceeds the baseline recorded at the start, with 0 skips

### Task 13: [Final] Update documentation

- [x] update `CLAUDE.md`: the palette is the home of rare actions, `lib/commands.js` is the single
      command registry (a second list must not appear), the one capture-phase listener and the `e.code`
      rule, and the dark-only theme decision
- [x] move this plan to `docs/plans/completed/`

## Follow-up stages (scoped, not designed here)

### Stage 4 — free terminal (`t`)

A tmux session with no agent inside. Not a menu entry: it needs explicit rules in the daemon, because
today an agent-less session is impossible.

- `agentFactoryOf` has no adapter for it, so `create()` would throw `AgentBinaryNotFoundException`.
- No hooks fire, so no `AgentEvent` ever reaches the log: the projection stays `ready` forever and
  `needs_approval` is unreachable.
- `VendorStoreProbe` answers `false`, so a dead free terminal classifies as `crashed` and cannot be
  resumed — even though relaunching a bare shell is trivially legal.
- `Interrupt` already works unchanged (it is just `send-keys`), and neither the `.sq` schema nor the DTOs
  need changes (`agent` is already a string).

Open questions for its own brainstorm: which state a live free terminal reports, what Resume means, and
whether the reconciler should special-case the kind or the absence of a provider id.

### Stage 5 — listing resumable provider sessions

The import path already exists end to end: `POST /sessions/import`, `VendorSessionLocator` discovering
the `cwd` from the provider's own store for claude/codex/junie, and the Import mode in
`NewSessionDialog` that the `r` mnemonic opens from day one. What is missing is discovery: a route that
scans the vendor stores and returns candidates (`agent`, `providerSessionId`, `cwd`, timestamp, ideally
a first-message preview) so the operator picks from a list instead of typing a uuid. Its own brainstorm.

## Post-Completion

*Items requiring manual intervention — no checkboxes, informational only*

**Manual verification** (browser behaviour has no automated coverage in this project by design):

- desktop: `⌘K` opens the palette while the terminal has focus and no byte reaches the agent; a second
  `⌘K` switches to leader; `Esc` closes; `⌘K` under a Cyrillic layout still works; `⌘K` while
  Preferences is open does nothing.
- desktop: every mnemonic runs its command; `Stop`/`Done` still raise their confirm dialogs and the
  confirm is not trapped behind the palette; `i` interrupts and the row goes `ready`.
- desktop: `r` opens import mode with an **empty** working directory field.
- desktop: `⌘1` in an ordinary browser tab (expected to switch tabs — `#sidebar-toggle` must cover it)
  and in the installed PWA (expected to collapse the sidebar).
- phone/iOS PWA: `⋯` opens leader mode without raising the software keyboard; tapping the search row
  raises it; the terminal reflows correctly after the keyboard opens and closes (the `visualViewport`
  sizing path in `TerminalPane` is the fragile one).
- phone: OLED black actually renders black, and scrolling the session list stays smooth without blur.
- both: the palette finds an archived ("done") session and selecting it works.
- first run: a daemon with no sessions and no base path still offers a discoverable way to start one.

**External system updates**: none — no daemon route, DTO, schema or CLI contract changes in stages 1–3.
