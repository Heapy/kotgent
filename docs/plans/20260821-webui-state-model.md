# Web UI state model: signals, a pure-rule test tier, and one shared picker

## Overview

Three consecutive code reviews of Web UI work found 5, 6, and 15 issues. Almost none were typos. They
were coordination bugs of the same few shapes, produced again for each new feature. This plan removes the
shapes rather than the instances.

The problem is not Preact and not the no-build constraint. It is that `resources/webui/` hand-rolls a
concurrency model per feature, and has no test tier below a real browser, so pure rules ship unproven.

Three root causes, each with a fix in this plan:

1. **No fast tier for browser-independent logic.** `docs/TESTING.md:206-209` states it plainly: *"This
   layer does not exist yet. There is no JavaScript build step and no package manager in the repository,
   and no runner has been introduced for these modules, so their rules are proven one level up in the
   browser tier or not at all."* Every rule is therefore only provable through Kotlin + Playwright, which
   is expensive, so most rules are never proven at all.
2. **State is duplicated into refs so async callbacks can read it.** `resources/webui/` contains 116
   `Ref.current = ` writes and 8 pure state mirrors (`sessionsRef`, `tasksRef`, `projectsRef`,
   `activeRef`, `pendingRef`, `dialogRef`, `prefsRef`, `statusRef`). Two sources of truth, maintained by
   hand, by only some writers.
3. **Async coordination is re-invented per call site.** Several distinct idioms express run currency,
   liveness, and locking: a status-string comparison, `submittingRef`, `pendingRef`. Nine
   `useCallback(async …)` flows in `app.js` each own their own guard, lock, and cancellation.

The chosen approach is signals for state, one mutation runner for async flows, a pure-rule tier under
`node --test`, and one shared typeahead-listbox primitive. Backward compatibility is preserved: the
served asset contract, the import-map shape, and all existing Playwright tests stay green.

## Context (from discovery)

Files and components involved:

- `resources/webui/app.js` — 1373 lines. 22 `useState` (169-187, 243, 1103, 1108), 25 `useRef`
  (215-256, 584, 687, 732, 735, 1026), 14 `useEffect`, 60 `useCallback`, 9 async callbacks (443, 475,
  678, 849, 874, 926, 999, 1028, 1125). Of those nine, **six mutate** (475 `applyProjectArchive`, 849
  `startSession`, 874 `importSession`, 926 `controlSession`, 1028 `savePreferences`, 1125
  `linkSessionToTask`); 678 `fetchSessionRow` is a read, 999 `copyTmuxCommand` is clipboard-only, and
  443 `projectCreated` only awaits a reload.
- `resources/webui/components/dialogs.js` — 1480 lines. 34 `useState`, 20 `useRef`. `LinkTaskDialog` at
  827-1030 is the newest instance of the pattern.
- **Four typeahead implementations, in three files, with two different selection models.**
  Index-based with a `-1` sentinel: `CommandPalette.js:72`, the `NewProjectForm` path picker at
  `Board.js:752` (keys at 806-815), and the session-cwd path picker at `dialogs.js:273` — the last two
  are near-identical duplicates. Ref-based: `LinkTaskDialog`. There is no "board filter"; the board's
  typeahead is a path picker.
- `resources/webui/lib/sessions.js:25` — `sessionTaskLinkDisabledReason` already exists as the shared
  eligibility-and-refusal-reason function, consumed at `app.js:1128`, `app.js:1199`, `dialogs.js:824`,
  and `lib/commands.js:152`. Any link-rule work extends this, rather than starting a new home.
- `resources/webui/lib/` purity, precisely: `agents.js`, `commands.js`, `paths.js`, `sessions.js`, and
  `unicode.js` reference no browser globals and import nothing outside `lib/`. `tasks.js` references no
  browser globals **but** imports `apiRequest` from `./api.js` (`lib/tasks.js:3`), which touches
  `window.location` (`lib/api.js:22`) and `fetch` (`lib/api.js:65`) inside function bodies — so a Node
  import succeeds and its pure rules are testable, but the module is not itself pure. `lib/qr.js` is the
  only `lib/` module with a bare specifier (`"qrcode"`).
- `resources/webui/index.html:21` — the single durable record of vendored versions: *"Vendored: xterm
  6.0.0, addon-fit 0.11.0, preact 10.29.8, htm 3.1.1."*
- `resources/webui/index.html:26-36` — the import map; `test/transport/WebUiServingTest.kt:110-140`
  asserts its exact entries and that each target is served.
- `resources/webui/vendor/preact.module.js` — the stock minified Preact 10.29.8 dist build. It exports
  `Component`, `options`, `isValidElement`; `preact-hooks.module.js` exports `useMemo`, `useRef`,
  `useEffect`. Those are exactly the named imports `@preact/signals` needs.
- `webuicheck/` — **fixture data only. Its scenario files contain zero assertions**
  (`webuicheck/src/scenarios/*.kt` build `Scenario` values: fake sessions, tasks, project files). All
  browser assertions live in `webuitest/` Playwright classes. A gate that must *prove* something belongs
  in `webuitest`.
- `src/transport/WebUiAssets.kt:19` — `webUiRevision` digests **every file** under `resources/webui`.
  Any file placed there is served to browsers and changes the asset revision. JS unit tests must live
  outside that tree.
- `test/transport/WebUiServingTest.kt:804` — `locateWebUiDir()` walks up from the current directory to
  find `resources/webui`, because a repo-root CWD cannot be assumed in that module. Any new path
  resolution must do the same.
- `test/transport/WebUiServingTest.kt:534-538` — the `BOARD_VOCABULARY` scan reads exactly three
  sources: `Board.js`, `TaskCard.js`, `TaskDetail.js`, with two-directional completeness through
  `BOARD_OWNED_CLASSES` at 788-802.
- `webuitest/module.yaml` — JVM test-only module for Playwright. JUnit discovery requires every test
  class name to match `.*Tests?`; other names silently yield no tests. It records that "Playwright
  embeds its Node driver … so no npm setup is needed" — a property this plan changes.
- `resources/webui/lib/api.js:4` — `API_REQUEST_TIMEOUT_MS = 60_000`, added by commit `108392e`. This is
  what bounds any hold of the session-action lock across a request.
- `node --version` → v24.18.0. The built-in `node --test` runner needs no npm install and no build,
  which satisfies CLAUDE.md's "The Web UI deliberately has no npm build".

Related patterns found:

- Revision-based newest-wins merging already lives in pure helpers (`lib/sessions.js`, `lib/tasks.js`)
  — the natural first subjects for the new test tier.
- `htm-preact.module.js` and `preact-hooks.module.js` already resolve bare specifiers through the import
  map. Vendoring signals is the same move, not a new mechanism.

Dependencies identified:

- `@preact/signals-core@1.8.0` and `@preact/signals@2.0.1`, ESM dist builds. The adapter imports
  `@preact/signals-core`, `preact`, and `preact/hooks` as bare specifiers — all resolvable through the
  existing import map. **Version and integrity are unverified offline**; Task 2's browser gate is the
  real check.
- The adapter reaches Preact internals through mangled property names (`.__`, `.__c`, `.__e`). The
  vendored build uses the same official mangle mapping, but only a real browser run proves it.

## Development Approach

- **testing approach**: TDD for pure rules under `resources/webui/lib/` (write the `node --test` case
  first, then the code). Regular for components and Playwright coverage (code first, then the test).
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task that changes code MUST include new/updated tests** for that code
  - tests are not optional — they are a required part of the checklist
  - write unit tests for new functions
  - write unit tests for modified functions
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
  - **exemption:** Tasks 7 and 19 change documentation only and carry no tests. Wave-closing tasks (2, 5,
    8, 10, 12, 14, 16, 18, 20) validate rather than write code, so they carry none either. No other task
    may claim this exemption.
- **CRITICAL: all tests must pass before starting the next task** — no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run `./kotlin build` before `./kotlin test` — tests execute the `ptycheck` and `webuicheck` binaries
  and the test command does not build them
- run `node --check <file>` for every changed JavaScript module
- keep aggregate test runs serial across worktrees; integration tests share the tmux socket label
  `kotgent-test`
- maintain backward compatibility

## Testing Strategy

- **unit tests**: required for every code-changing task (see Development Approach above)
- **JS pure-rule tests**: `node --test` over `webuitest/js/`, driven from a JVM test class so the
  existing `./kotlin test` gate runs them. New or changed pure rules in `resources/webui/lib/` are
  proven here first, at the lowest honest level per `docs/TESTING.md`.
- **e2e tests**: this project has real-browser tests in `webuitest/` (Playwright via Kotlin). `webuicheck/`
  supplies scenario fixtures and asserts nothing; do not place a proof there.
  - UI changes → add/update `webuitest` coverage in the same task as the UI code
  - treat them with the same rigor as unit tests (must pass before the next task)
  - drive real gestures (`tap()`, `hover()`), never `evaluate("el => el.click()")` — `docs/TESTING.md`
    rules that a synthesized event proves a listener runs, not that the platform routes the gesture
- **manual/real-device**: behavior Chromium cannot faithfully observe stays in the `docs/TESTING.md`
  checklist rather than becoming a synthesized assertion.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**State model: signals.** A signal is readable from any closure and never goes stale, so the eight
mirror refs and the class of bugs they cause disappear rather than being hidden behind a helper.
Session, task, project, selection, dialog, preference, and status state move out of `app.js`'s hook
list into signal modules under `resources/webui/state/`. Application-level derived values become
`computed()`. **Component-local** derivation uses `useComputed`/`useSignal` from `@preact/signals`, never
a bare `computed()` in a render body — a component that can be instantiated more than once (two path
pickers, a palette behind a dialog) would otherwise share one derived node across instances.

**Async model: one mutation runner, for mutations only.** `runMutation()` owns the pending lock and a
monotonic generation token for the six genuinely mutating flows. It replaces the status-string
comparison, `submittingRef`, and `pendingRef`. It deliberately does **not** absorb two other concerns
that look similar and are not:

- `selectionGenRef` (`app.js:224`, incremented at 549, sampled at 852 and 879-880, checked at 868)
  counts **user selection changes**, closing the A→B→A hole when an async flow conditionally
  auto-selects. A mutation can be current while the user has navigated away and back. Selection
  generation stays a property of selection state.
- `aliveRef` (`dialogs.js:705`, `dialogs.js:875`) is a component **unmount** guard protecting a dialog's
  own `setError`/`setBusyTaskRef`. Liveness is not currency.

**Readiness model: an explicit state, not a boolean.** `tasksReady`/`projectsReady`/`projectActive`
encode 64 combinations of which a handful are legal, and have no failure state at all — which is why a
single failed `GET /projects` strands the picker forever. Readiness becomes
`idle | loading | ready | failed`, with a retry path and a visible error.

**Interaction model: one typeahead-listbox primitive.** Enter, IME composition, arrow navigation,
scroll-into-view, hover, and input attributes are implemented once and consumed by the four existing
sites. The primitive must reconcile two selection models: index-based with a `-1` sentinel (palette,
both path pickers) and ref-based (link picker). That reconciliation is the task's real cost.

**Test model: prove pure rules with node.** Rules move from components into `resources/webui/lib/`,
where `node --test` runs them in milliseconds with no browser and no npm. The browser tier keeps only
what genuinely needs a browser.

### Design decisions and rationale

- **Signals over a `useStateWithRef` helper.** A helper hides the mirror; signals remove it. This was an
  explicit choice: the mirror class is the largest single source of the reviewed defects, and moving
  state out of `app.js` is far cheaper once it no longer travels through closures and props.
- **Vendored, not npm.** Signals ship as ESM dist files placed beside the existing vendored modules and
  wired through the import map. No build step is introduced.
- **Signals adoption is gated on a real browser run (Task 2), in `webuitest`.** Export compatibility is
  verified; the mangled-internals match is not, and cannot be from a static read. The gate must assert,
  so it cannot live in `webuicheck`.
- **The link-task badge re-read deliberately reverses a documented decision.** `app.js:1145-1146`
  currently reads: *"Only the mutating POST owns the global session-action lock. The targeted read is
  revision-safe and must not make unrelated controls wait for its transport timeout."* Finding
  `app.js:1147` shows the cost of that choice: releasing the lock before the re-read lets a second link
  overwrite the first. Task 6 holds the lock through the re-read instead, bounded by
  `API_REQUEST_TIMEOUT_MS` (60 s, `lib/api.js:4`). The comment is rewritten to record the new reasoning,
  not silently contradicted. Reads that are *not* part of a mutation stay outside the lock.
- **JS tests live outside `resources/webui/`.** `webUiRevision` digests the whole tree, so a test file
  placed there would be served to browsers and would churn the asset revision.
- **The JS tier is driven from JUnit, not a separate command.** One JVM class spawning `node --test`
  means `./kotlin test` remains the single gate and CI needs no new step. This introduces a system
  `node` prerequisite the repository did not have; it must fail loudly, never skip.

## Technical Details

**Vendored files.** `resources/webui/vendor/signals-core.module.js` and
`resources/webui/vendor/signals.module.js`. Versions are recorded in the existing single place,
`resources/webui/index.html:21`, extending the "Vendored: …" comment. Per-file headers are not added:
two records would drift.

**Import map additions** (`resources/webui/index.html`):

```json
"@preact/signals-core": "/_v/__REV__/vendor/signals-core.module.js",
"@preact/signals": "/_v/__REV__/vendor/signals.module.js"
```

`test/transport/WebUiServingTest.kt:110-140` asserts the map's entries and that each target is served;
both new entries are added to that assertion map.

**Signal modules.** `resources/webui/state/` — one module per concern (`sessions.js`, `tasks.js`,
`projects.js`, `selection.js`, `dialog.js`, `prefs.js`, `status.js`). Each exports signals plus the
functions that write them, so a writer cannot bypass the merge rule. The existing revision-based
newest-wins helpers in `lib/sessions.js` and `lib/tasks.js` remain the merge implementation; the state
modules only own the current value.

**`runMutation(name, fn)`.** Returns the result or throws. Owns the pending signal, a monotonic
generation counter captured at entry, and an `isCurrent()` predicate the caller uses before writing any
user-visible outcome. Scope: the six mutating flows only.

**`Readiness`.** `{ state: "idle" | "loading" | "ready" | "failed", error: string | null }` with a
`retry()` action. The link picker renders a retry affordance in the `failed` state instead of an
indefinite "Reading open tasks…".

**Case folding.** Query matching uses `toLowerCase()`, not `toLocaleLowerCase()`. Task refs are ASCII,
and a Turkish/Azeri locale folds `I` to dotless `ı`, which removes matches that exist. Both call sites
are in `dialogs.js`: `normalizedQuery` at 857 and the `results` filter at 861.

**JS test layout.** `webuitest/js/*.test.js`, importing modules by relative path
(`../../resources/webui/lib/tasks.js`). A JVM class named to match `.*Tests?` spawns
`node --test <dir>` and fails with the runner's output attached. The directory is resolved by walking up
from the current directory, mirroring `locateWebUiDir()` at `test/transport/WebUiServingTest.kt:804`. A
missing `node` fails loudly with a named prerequisite, per `docs/TESTING.md:450-452`.

## Findings addressed

The 15 open review findings on the uncommitted link-task diff are fixed by the structure that removes
their class, not by individual patches. Findings discovered while executing the plan are appended to
the table below.

| Finding | Task |
|---|---|
| `TaskCommandsTest.kt:557` — no readiness barrier, test can hang | 1 |
| `TaskCommandsTest.kt:650` — `el.click()` instead of a real tap | 1 |
| `dialogs.js:857`, `:861` — `toLocaleLowerCase()` breaks search in `tr`/`az` | 6 |
| `docs/TESTING.md:265` — no real-device checklist entry for the picker | 7 |
| `app.js:649` — `sessionsRef` maintained by 3 of 7 writers | 9 |
| `app.js:667` — dropped mark-read retry on equal-revision frames | 9 |
| `app.js:1147` — lock released before the badge re-read; double link overwrites | 11 |
| `app.js:1166` — status sentence used as a generation token | 11 |
| `app.js:388` — one failed `GET /projects` strands the picker forever | 13 |
| `app.js:403` — project list frozen for the screen's lifetime | 13 |
| `dialogs.js:876` — active row reconciled in an effect; Enter misfires | 15 |
| `dialogs.js:870` — scroll flag latches and fires on a later hover | 15 |
| `dialogs.js:925` — no `isComposing` guard; IME Enter links a wrong task | 15 |
| `dialogs.js:968` — hover erases the link failure message | 15 |
| `dialogs.js:1007` — `spellCheck=${false}` never reaches the DOM | 15 |
| `lib/commands.js:301`, `:321` — `toLocaleLowerCase()` breaks command-palette search in `tr`/`az` | 15 |

## Validation Commands

Run **only** by a wave-closing task. Wave participants run nothing from this section.

```
./kotlin build     # required before test: the suite execs ptycheck and webuicheck,
                   # which `./kotlin test` does not link
./kotlin test
# fast loops, not a substitute for the aggregate:
#   ./kotlin task :kotgent:testMacosArm64Debug
#   ./kotlin task :webuitest:testJvm
node --test 'webuitest/js/**/*.test.js'   # from the repository root; available from wave 2 onward.
                                          # A bare directory argument is treated as a glob that matches
                                          # only itself, fails to load as a module, and exits 1.
```

## Параллельное исполнение волнами

Each task carries `**Wave:** K` and `**Depends:** <numbers|—>`. Wave-closing passes are real
`### Task N: Закрытие волны K` sections with their own checkboxes, so an ordinary sequential
`/planning:exec` executes this plan correctly: sections run in order and the closer catches up on
validation and the commit. The markup is a strict superset, not a fork. Wave mode requires an **explicit**
instruction at launch, because `SKILL.md` is not in the override chain and its batch-spawn prohibition is
not lifted on its own.

Participant rules come from `.claude/exec-plan/prompts/task.md`: address the task **by name**, do not edit
the plan file, do not run `git` mutations, do not run `./kotlin`, and touch only the paths in the task's
`**Files:**` block. A participant returns `DONE:` and `FILES:` blocks; the closer marks the checkboxes.

Wave grouping is deliberately conservative here. `app.js`, `dialogs.js`,
`test/transport/WebUiServingTest.kt`, and `webuitest/test/TaskCommandsTest.kt` are each touched by four to
six tasks, so most waves hold a single task. Only waves 2 and 3 have genuinely disjoint file ownership.

| Wave | Participants | Closer | Why they may run together |
|---|---|---|---|
| 1 | 1 | 2 | alone — the commit also lands the pre-existing working tree |
| 2 | 3, 4 | 5 | disjoint: vendor + `index.html` + `WebUiServingTest.kt` vs `webuitest/js/` + `docs/TESTING.md` |
| 3 | 6, 7 | 8 | disjoint: `lib/sessions.js` + `dialogs.js` vs `docs/TESTING.md` |
| 4 | 9 | 10 | alone — `app.js`, `state/`, `CLAUDE.md` |
| 5 | 11 | 12 | alone — `app.js` |
| 6 | 13 | 14 | alone — `app.js`, `dialogs.js` |
| 7 | 15 | 16 | alone — `dialogs.js`, `Board.js`, `CommandPalette.js`, `style.css` |
| 8 | 17 | 18 | alone — `app.js`, `state/` |
| 9 | 19 | 20 | alone — final documentation, acceptance verification, plan retirement |

## What Goes Where

- **Implementation Steps** (`[ ]` checkboxes): code, tests, and documentation changes in this repository
- **Post-Completion** (no checkboxes): manual and real-device verification that automation cannot
  faithfully observe

## Implementation Steps

### Task 1: Stabilize and commit the current link-task feature

**Wave:** 1 · **Depends:** —

The working tree holds a 1054-line uncommitted diff. Committing it first keeps vendor files and
refactoring out of the same commits, and the two Playwright fixes make the suite trustworthy before it
becomes every later wave's gate. Finding `TaskCommandsTest.kt:557` is a hang, not a failure — left in
place it burns wall-clock in every subsequent validation run.

**Files:**
- Modify: `webuitest/test/TaskCommandsTest.kt`

- [x] make `openLinkTaskPicker()` wait for actual readiness (a `.link-picker-option` row, or
      `#link-task-status` detaching) instead of only dialog visibility and input focus
- [x] replace `tapped.evaluate("el => el.click()")` at `TaskCommandsTest.kt:650` with `tapped.tap()`,
      and the synthesized `MouseEvent` dispatches at 636-637 with `hover()`
- [x] verify the scroll assertion measures the populated option list, not the placeholder

### Task 2: Закрытие волны 1

**Wave:** 1 · **Depends:** 1

The wave's commit deliberately includes the pre-existing uncommitted link-task feature alongside the two
test fixes — that is what makes this wave the plan's baseline.

- [x] reconcile `git status --porcelain` against the participant's `FILES:` block plus the pre-existing
      link-task diff, and stop with a report on any path neither accounts for
- [x] record that this wave needs no registry edit
- [x] run `## Validation Commands` in full, in order
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 3: Vendor @preact/signals and prove it in a real browser

**Wave:** 2 · **Depends:** 1

Gate task. Export compatibility is verified; the adapter's use of mangled Preact internals is not. The
proof must be an assertion, so it belongs in `webuitest`, not `webuicheck`, whose scenario files contain
zero assertions.

**Fallback:** if the browser proof fails, replace the signal model with a single `useStateWithRef`
primitive in `resources/webui/lib/hooks.js`. Every later task keeps its shape and its findings mapping;
only the state mechanism changes. Record the switch as a `[deviation]` line.

**Files:**
- Create: `resources/webui/vendor/signals-core.module.js`
- Create: `resources/webui/vendor/signals.module.js`
- Create: `webuitest/test/SignalsVendorTests.kt`
- Modify: `resources/webui/index.html`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] vendor the `@preact/signals-core` and `@preact/signals` ESM dist builds
- [x] extend the "Vendored: …" comment at `resources/webui/index.html:21` with both packages and their
      exact versions — this is the single version record; do not add per-file headers
- [x] add the two import-map entries to `resources/webui/index.html`
- [x] extend the assertion map in `test/transport/WebUiServingTest.kt:110-140` with both specifiers
- [x] assert the adapter imports the bare `preact`, `preact/hooks`, and `@preact/signals-core`
      specifiers, mirroring the existing `htm-preact` check at `WebUiServingTest.kt:173-179`; record in
      the test that this fits the closed source-shape exception at `docs/TESTING.md:291-298` — an
      agreement between two files that never read each other
- [x] write `webuitest/test/SignalsVendorTests.kt`: in the page realm, dynamically import the served
      vendor module URLs, render a small Preact tree bound to a signal, write the signal, and assert the
      DOM updated. **No throwaway signal UI is added to `resources/webui/`**
- [x] run `node --check` on every changed JavaScript module

### Task 4: A node --test tier for browser-independent web logic

**Wave:** 2 · **Depends:** 1

**Files:**
- Create: `webuitest/js/sessions.test.js`
- Create: `webuitest/js/tasks.test.js`
- Create: `webuitest/test/WebUiLogicTests.kt`
- Modify: `docs/TESTING.md`
- Modify: `webuitest/module.yaml`

- [x] create `webuitest/js/` outside `resources/webui/` so `webUiRevision` does not digest or serve it
- [x] create `webuitest/test/WebUiLogicTests.kt` (name must match `.*Tests?`, or JUnit silently finds
      nothing) that spawns `node --test <dir>` and fails with the runner output attached
- [x] resolve the JS directory by walking up from the current directory, mirroring `locateWebUiDir()` at
      `test/transport/WebUiServingTest.kt:804` — a repo-root CWD cannot be assumed
- [x] fail loudly with a named prerequisite when `node` is absent or the runner reports zero tests, per
      `docs/TESTING.md:450-452`; never skip
- [x] write tests for the revision merge rules in `resources/webui/lib/sessions.js`: newer revision wins,
      equal revision is a no-op, out-of-order arrival converges
- [x] write tests for `resources/webui/lib/tasks.js` pure rules: upsert, patch, remove, open-state
      classification
- [x] write error and edge cases: missing revision, unknown ref, empty collection
- [x] rewrite `docs/TESTING.md:206-209` — the paragraph asserting this layer does not exist — to name
      the runner and its location, and record that it adds no build step
- [x] record the new system `node` prerequisite in `webuitest/module.yaml`, whose comment currently
      states that no external tooling is needed

### Task 5: Закрытие волны 2

**Wave:** 2 · **Depends:** 3, 4

- [x] reconcile `git status --porcelain` against the union of both participants' `FILES:` blocks
- [x] record that this wave needs no registry edit, or apply it if `webuitest/module.yaml` requires one
- [x] run `## Validation Commands` in full, in order, including `node --test webuitest/js/`
- [x] if Task 3's browser proof failed, record the fallback decision and its consequence for later waves
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 6: Extend the shared link rules and fix locale-sensitive matching (TDD)

**Wave:** 3 · **Depends:** 4

`sessionTaskLinkDisabledReason` at `resources/webui/lib/sessions.js:25` is already the shared home for
link eligibility, with four consumers. This task extends it rather than creating a fifth home.

Note that `app.js:1128-1131` and `dialogs.js:895` are **not** duplicates and must not be merged:
`app.js` performs the authoritative pre-POST re-check (active session, expected project, task open
state), while `dialogs.js` guards UI availability (`!task || submittingRef.current || changed ||
projectUnavailable || !ready`). They are different rules that share a matcher. The new pre-POST predicate
is exported here but **wired into `app.js` in Task 9**, which owns that file — do not edit `app.js`.

**Files:**
- Modify: `resources/webui/lib/sessions.js`
- Modify: `resources/webui/components/dialogs.js`
- Create: `webuitest/js/link-rules.test.js`

- [x] write failing tests first for the existing `sessionTaskLinkDisabledReason` rules, pinning current
      behavior at the node tier before anything moves
- [x] write failing tests first for the pre-POST re-check conditions in `app.js:1128-1131`, and export
      that predicate from `lib/sessions.js` under its own name so the two guards stay distinguishable
- [x] write a failing test for query matching including the `tr`/`az` case: a task titled "Index the API"
      must match the query "index" under any locale
- [x] add the query matcher to `lib/sessions.js` folding case with `toLowerCase()`
- [x] replace `normalizedQuery` at `dialogs.js:857` and the `results` filter at `dialogs.js:861` with the
      shared matcher — this is the step that actually fixes the finding
- [x] write tests for the outcome-message rules (linked, linked-but-unreadable, linked-elsewhere)
- [x] run `node --check` on changed modules

### Task 7: Record the link picker's real-device checks

**Wave:** 3 · **Depends:** —

Finding `docs/TESTING.md:265`. The picker has the busy-dismissal and coarse-pointer properties the
checklist already itemises for the project dialogs, plus two the checklist does not yet cover: an
`<input type="search">` inside a `<dialog>`, where one Escape press has two competing native behaviors —
clearing the field and the dialog's cancel path — and a `78vh` scroll port the software keyboard shrinks.
The picker is not the only such input: `CommandPalette.js:136` puts one inside the same shared `Dialog`,
so whichever behavior an engine takes is the app's behavior in both places. Documentation only; no
tests, per the exemption in Development Approach.

**Files:**
- Modify: `docs/TESTING.md`

- [x] add the link picker to the real-device checklist: swipe handle, compensated padding, and backdrop
      dismissal, with swipe and backdrop dismissal disabled while a link request runs and the footer and
      header close controls staying enabled
- [x] add the platform close request against a busy picker on every engine — Escape or the system back
      gesture dismisses without cancelling the mutation
- [x] add the search input's Escape behavior inside a dialog, on every engine
- [x] add the keyboard-shrunk scroll port on a short phone viewport

### Task 8: Закрытие волны 3

**Wave:** 3 · **Depends:** 6, 7

- [x] reconcile `git status --porcelain` against the union of both participants' `FILES:` blocks
- [x] record that this wave needs no registry edit
- [x] run `## Validation Commands` in full, in order
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 9: Move session, task, and project state to signals

**Wave:** 4 · **Depends:** 3, 4, 6

Removes the mirror class (finding `app.js:649`) and rewrites `applySessionPatch`, so the equal-revision
rule from finding `app.js:667` is decided **here**, once, rather than pinned now and unpinned later. Also
wires the pre-POST predicate Task 6 exported.

**Files:**
- Create: `resources/webui/state/sessions.js`
- Create: `resources/webui/state/tasks.js`
- Create: `resources/webui/state/projects.js`
- Create: `webuitest/js/state-sessions.test.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`
- Modify: `CLAUDE.md`

- [x] write failing tests first: a functional-style write followed by a merge composes rather than
      discarding (the interleave finding `app.js:649` describes), and out-of-order and duplicate frames
      converge
- [x] write a failing test for the equal-revision rule: a redelivered or equal-revision `session_update`
      still reaches `markReadIfViewing`, so a failed read POST is retried even when unread and seq do
      not change — while a genuinely unchanged frame does not cause a render loop
- [x] create signal modules exporting the current value plus the only writer functions, so every writer
      goes through the same merge
- [x] replace `sessions`/`sessionsRef`, `tasks`/`tasksRef`, `projects`/`projectsRef` in `app.js` with
      those signals, and route all seven session writers (`applySessionRow`, `applySessionPatch`,
      `startSession` 849, `importSession` 874 and 897/913, `controlSession` 926) through them
- [x] implement the equal-revision rule decided above, with a comment recording why the redundancy is
      deliberate
- [x] call the pre-POST predicate exported by Task 6 from `app.js:1128-1131`
- [x] replace effect-reconciled application-level derived values with `computed()`
- [x] delete the now-unused mirror refs
- [x] register the three new served modules in `daemonServesTheComponentAndLibModules`
      (`test/transport/WebUiServingTest.kt:143-155`) — a hand-maintained list that will not fail on its own
- [x] update the CLAUDE.md bullet that reads "`app.js` owns global shortcuts, session/task state
      merging, and screen selection" to record the new ownership, in the task that changes it
- [x] run `node --check` on changed modules

### Task 10: Закрытие волны 4

**Wave:** 4 · **Depends:** 9

- [x] reconcile `git status --porcelain` against the participant's `FILES:` block
- [x] record that this wave needs no registry edit
- [x] run `## Validation Commands` in full, in order
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 11: One mutation runner with a generation token

**Wave:** 5 · **Depends:** 9

Removes the ad-hoc currency idioms: findings `app.js:1147` and `app.js:1166`. Scope is the six mutating
flows. `fetchSessionRow` (678), `copyTmuxCommand` (999), and `projectCreated` (443) are not mutations
and stay outside the lock. Task 6 also exported `sessionTaskLinkOutcome` from `lib/sessions.js` and left
it deliberately unwired: the badge re-read below is its only caller, so phrase the link's outcome through
it when the lock is held through that read.

**Files:**
- Create: `resources/webui/lib/mutation.js`
- Create: `webuitest/js/mutation.test.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`
- Modify: `webuitest/test/TaskCommandsTest.kt`

- [x] write failing tests first for `runMutation`: one flow at a time, a superseded run cannot write an
      outcome, a failed run releases the lock, and the lock survives the mutation's own follow-up read
- [x] create `resources/webui/lib/mutation.js` with the pending signal, a monotonic generation counter,
      and `isCurrent()`
- [x] convert the six mutating flows: 475 `applyProjectArchive`, 849 `startSession`, 874
      `importSession`, 926 `controlSession`, 1028 `savePreferences`, 1125 `linkSessionToTask`
- [x] hold the session-action lock through the link's badge re-read so a second link cannot start and
      overwrite the first, and **rewrite the comment at `app.js:1145-1146`** to record the new reasoning
      and that `API_REQUEST_TIMEOUT_MS` (60 s, `lib/api.js:4`) bounds the hold
- [x] replace the `statusRef.current.text !== refreshing` comparison with the generation token
- [x] delete `pendingRef` only. **Keep `selectionGenRef`** (selection generation, not run currency) —
      it is not replaceable by `isCurrent()`. `submittingRef` and `aliveRef` are not in `app.js` at all;
      both live in `components/dialogs.js`, outside this task's Files block. `submittingRef` is Task 15's
      to remove when it rewrites the picker, and `aliveRef` stays by design as an unmount guard.
- [x] register the new served module in `daemonServesTheComponentAndLibModules`
- [x] add a Playwright test proving a second link attempt is refused while the first is still settling
- [x] add a Playwright test proving an unrelated control is not blocked by a non-mutating read
- [x] run `node --check` on changed modules

### Task 12: Закрытие волны 5

**Wave:** 5 · **Depends:** 11

- [x] reconcile `git status --porcelain` against the participant's `FILES:` block
- [x] record that this wave needs no registry edit
- [x] run `## Validation Commands` in full, in order
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 13: Readiness as an explicit state with failure and retry

**Wave:** 6 · **Depends:** 9

Removes the stuck-forever and stale-project classes: findings `app.js:388` and `app.js:403`.

**Files:**
- Create: `resources/webui/lib/readiness.js`
- Create: `webuitest/js/readiness.test.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `test/transport/WebUiServingTest.kt`
- Modify: `webuitest/test/TaskCommandsTest.kt`
- Modify: `resources/webui/state/projects.js`
- Modify: `resources/webui/state/tasks.js`
- Modify: `webuitest/js/state-sessions.test.js`

- [x] write failing tests first for the readiness transitions, including `loading → failed → loading`
      on retry, and that no path reaches a terminal state with no user-visible outcome
- [x] create `resources/webui/lib/readiness.js` with `idle | loading | ready | failed` plus `retry()`
- [x] replace `tasksReady`, `projectsReady`, and the derived `ready`/`projectUnavailable` booleans
- [x] refresh the project list when the link picker opens, so a project created or archived after page
      load is judged against live data
- [x] render an error with a retry control in the `failed` state instead of "Reading open tasks…"
- [x] register the new served module in `daemonServesTheComponentAndLibModules`
- [x] add a Playwright test: a failing `GET /projects` shows an error and a retry, and the retry recovers
- [x] add a Playwright test: a project created after page load is linkable without a reload
- [x] run `node --check` on changed modules

### Task 14: Закрытие волны 6

**Wave:** 6 · **Depends:** 13

- [x] reconcile `git status --porcelain` against the participant's `FILES:` block
- [x] record that this wave needs no registry edit
- [x] run `## Validation Commands` in full, in order
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 15: One shared typeahead-listbox primitive

**Wave:** 7 · **Depends:** 3, 13

Removes the interaction class: findings `dialogs.js:876`, `870`, `925`, `968`, and `1007`. The four real
consumers are `CommandPalette.js:72`, the `NewProjectForm` path picker at `Board.js:752` (keys 806-815),
the session-cwd path picker at `dialogs.js:273`, and `LinkTaskDialog`. The first three use index-based
`activeSuggestion` with a `-1` sentinel; the last is ref-based. Reconciling those two selection models
is the primitive's contract and this task's real cost. The primitive ships as two modules because node
resolves no bare specifier: `lib/typeahead.js` holds the selection rules framework-free so the node tier
can prove them, and `components/Typeahead.js` binds them to Preact in the `useTypeahead` hook.

**Files:**
- Create: `resources/webui/lib/typeahead.js`
- Create: `resources/webui/components/Typeahead.js`
- Create: `webuitest/js/typeahead.test.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `resources/webui/components/CommandPalette.js`
- Modify: `resources/webui/components/Board.js`
- Modify: `resources/webui/lib/commands.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`
- Modify: `webuitest/test/TaskCommandsTest.kt`

- [x] write failing tests first for the pure selection rules: which row is active for a given result set
      and previous selection, how arrow movement wraps, and how index-based and ref-based callers map
      onto one representation
- [x] create `resources/webui/components/Typeahead.js` deriving the active row with `useComputed`/
      `useSignal` per instance — **not** a bare `computed()` in the render body, which would share one
      derived node across the two path-picker instances
- [x] guard Enter with `event.isComposing` and `keyCode === 229` so an IME candidate commit is not
      treated as a selection
- [x] make hover activation passive: it moves the highlight but does not clear an error message
- [x] arm scroll-into-view unconditionally per navigation so the flag cannot latch across activations
- [x] write `spellcheck="false"` in lowercase, matching `Board.js:860`, `dialogs.js:413`, `:430`, `:1115`
- [x] adopt the primitive in all four sites
- [x] register `/components/Typeahead.js` in `daemonServesTheComponentAndLibModules`
      (`test/transport/WebUiServingTest.kt:143-155`), which lists both `/lib/` and `/components/` paths
- [x] if any `board-*`/`task-*` emission moves into `Typeahead.js`, add that file to the `sources` map at
      `test/transport/WebUiServingTest.kt:534-538` — adding a word to `BOARD_VOCABULARY` is not the fix;
      register genuinely new structural classes there and give each a rule in `style.css`
- [x] add a Playwright test for the IME path: an Enter with `isComposing` does not submit
- [x] add a Playwright test: hovering the option list does not erase a link failure message
- [x] add a DOM assertion that the served search input carries `spellcheck="false"`
- [x] fold the palette's query with the shared matcher exported from `lib/sessions.js` (or with
      `toLowerCase()`) at `lib/commands.js:301` and `:321`, where `toLocaleLowerCase()` makes a command
      containing `I` unfindable under a `tr`/`az` browser locale, and cover that locale at the node tier
- [x] run `node --check` on changed modules

### Task 16: Закрытие волны 7

**Wave:** 7 · **Depends:** 15

- [x] reconcile `git status --porcelain` against the participant's `FILES:` block
- [x] record that this wave needs no registry edit
- [x] run `## Validation Commands` in full, in order
- [x] on failure, attribute each error to the owning task rather than repairing broadly
- [x] mark `[x]` on every checkbox of every task in this wave, including this one
- [x] write the wave's progress block
- [x] make exactly one commit for the wave, including the plan file

### Task 17: Move the remaining state groups to signals

**Wave:** 8 · **Depends:** 9, 11

Selection, dialog, status, and preference state are the last hook-and-mirror pairs in `app.js`. This
task does not claim to fully decompose `app.js` — 60 `useCallback` flows remain, and moving them is not
justified by any finding. It is deferrable: if earlier waves overrun, skip to wave 9 and record a
`[deviation]`.

**Files:**
- Create: `resources/webui/state/selection.js`
- Create: `resources/webui/state/dialog.js`
- Create: `resources/webui/state/status.js`
- Create: `resources/webui/state/prefs.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] move selection state, including the selection generation counter from `app.js:224`, into
      `state/selection.js`, where it becomes a property of selection rather than a loose ref
- [ ] move dialog, status, and preference state into their signal modules
- [ ] keep `resources/webui/lib/router.js` the only owner of browser history
- [ ] register the four new served modules in `daemonServesTheComponentAndLibModules`
- [ ] write node-tier tests for every rule that moved, including the A→B→A auto-select case the
      selection counter exists to close
- [ ] run `node --check` on every changed module

### Task 18: Закрытие волны 8

**Wave:** 8 · **Depends:** 17

- [ ] reconcile `git status --porcelain` against the participant's `FILES:` block
- [ ] record that this wave needs no registry edit
- [ ] run `## Validation Commands` in full, in order
- [ ] on failure, attribute each error to the owning task rather than repairing broadly
- [ ] mark `[x]` on every checkbox of every task in this wave, including this one
- [ ] write the wave's progress block
- [ ] make exactly one commit for the wave, including the plan file

### Task 19: Update documentation

**Wave:** 9 · **Depends:** 17

Documentation only; no tests, per the exemption in Development Approach.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `docs/TESTING.md`

- [ ] confirm the CLAUDE.md ownership bullet edited in Task 9 still matches the shipped code
- [ ] add to `CLAUDE.md`: signals as the state model with the `useComputed`-per-instance rule,
      `runMutation` as the only mutation-currency idiom and what it deliberately does not cover, the node
      test tier's location and why it lives outside `resources/webui/`, and the shared typeahead primitive
- [ ] update `README.md` if the vendored dependency list is stated there
- [ ] confirm `docs/TESTING.md` carries the durable testing policy from this plan, including the rewritten
      `206-209` paragraph and the real-device checklist entries

### Task 20: Закрытие волны 9 — acceptance verification and plan retirement

**Wave:** 9 · **Depends:** 19

Wave 6 shipped three judgement calls the acceptance pass must verify against the code rather than
against this plan's original wording:

- Readiness is **sticky at `ready`**. `begin()` over an already-loaded list keeps it `ready`, and
  `fail()` over a `ready` source is declined, so the picker never flickers its rows away on a
  revalidation. The cost is that a project list which loaded once and then fails to refresh stays
  silently stale. Finding `app.js:403` therefore survives, narrowed: the frozen-list class is gone for
  the first read and for every successful refresh, and what remains is the failed-revalidation case.
  Judge the finding against that, not against "the list is never stale".
- The task list can never reach `failed`. It arrives on the events socket, which retries forever and
  announces its own outage, so `tasksReadiness` only ever moves `idle → ready`. The `failed` arm of the
  picker is reachable through the project read alone.
- The Playwright test for "a project created after page load is linkable without a reload" uses
  `project-restore` as its trigger, because the webuicheck harness has no project-create command. Same
  live-list-versus-mount-time-list mechanism, different trigger.

- [ ] reconcile `git status --porcelain` against the participant's `FILES:` block
- [ ] verify all 15 review findings are addressed, by re-reading each cited line
- [ ] verify the three root causes are removed: no state mirror refs remain, mutation currency has one
      implementation, and pure rules have node-tier coverage
- [ ] verify the concerns deliberately kept separate still work: selection generation closes A→B→A, and
      an unmounted dialog is not written to
- [ ] verify no behavior regressed: the served asset contract, import map, and revision handling are
      unchanged in shape
- [ ] run `## Validation Commands` in full, in order
- [ ] run `node --test` directly over `webuitest/js/` and confirm the JVM wrapper reports the same result
- [ ] verify the wrapper fails loudly when `node` is unavailable, by temporarily shadowing it
- [ ] verify test sensitivity: break one merge rule and one eligibility rule and confirm the node tier
      fails, then revert
- [ ] run `./kotlin run -m webuicheck -- --self-check` (never `kotgent daemon` or a real agent command)
- [ ] mark `[x]` on every checkbox of every task in this wave, including this one
- [ ] write the wave's progress block
- [ ] per `CLAUDE.md`, migrate this plan's durable product intent, architecture constraints, and
      testing policy to their authoritative homes, carry any still-intended work into an active plan or
      the backlog, then **delete this plan file** — completed plans are not archived in this repository
- [ ] make exactly one commit for the wave, including the plan deletion

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification:**

- the real-device checklist added in Task 7, on a physical phone across engines: dialog dismissal while
  busy, the search input's Escape path, coarse-pointer padding, and the keyboard-shrunk scroll port
- push permission and safe-area behavior, unchanged by this plan but adjacent to the dialogs it touches
- a long live session with a real agent, confirming the signal-backed session list still merges
  WebSocket frames and targeted reads correctly under sustained traffic

**External system updates:**

- none — this plan changes only this repository, adds no build step, and introduces no npm dependency.
  It does add a system `node` prerequisite for `./kotlin test`; confirm CI images provide it.

**Follow-up worth considering:**

- extend the node tier to `lib/commands.js`, `lib/paths.js`, and `lib/unicode.js`, which are already pure
  and currently proven only through the browser
- `lib/qr.js` is the only `lib/` module with a bare specifier and stays browser-tier until that changes
- decomposing the 60 `useCallback` flows still in `app.js` — no current finding justifies it, so it is
  not in this plan
