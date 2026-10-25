# Testing Strategy

This guide defines the desired testing model for Kotgent. It is a design target, not an inventory of the
current suite. Its purpose is to make changes safe by giving every important contract an executable,
trustworthy proof at the right boundary.

The goal is not to maximize the number of tests or eliminate all risk. The goal is to make a real regression
produce a fast, precise failure; let behavior-preserving refactors stay green; and rehearse upgrades in the
same shape in which users receive them.

Where a target is already met, this guide names the module that meets it, because the shape those parts
settled on is itself part of the target. Everything left unnamed is still a direction.

## What makes a test valuable

A good test protects observable behavior or a stable external contract.

A valuable test:

- fails when behavior visible to a user or caller breaks;
- stays green when equivalent code is moved, renamed, reformatted, or refactored;
- exercises the lowest real boundary that can honestly prove the contract;
- has one clear reason to fail and reports enough evidence to diagnose that failure;
- is deterministic, isolated, bounded by a timeout where it can block, and safe to repeat;
- proves its own sensitivity: breaking or deleting the behavior under test makes it fail;
- earns its maintenance cost by covering a meaningful risk.

If red does not imply a broken contract and green does not imply working behavior, the test provides a
misleading signal and should be replaced or rewritten.

## Choose the lowest honest test level

Place a regression test at the lowest level that can reproduce the failure without simulating away its cause.

1. If the behavior is a pure rule, write a unit, property, or model-based test.
2. If the behavior coordinates ports, state, cancellation, or retries, write a component test with controlled
   fakes and a deterministic clock or scheduler.
3. If the behavior depends on SQL, HTTP routing, serialization, a WebSocket, a process, a filesystem, `tmux`,
   a PTY, or a browser, exercise that real boundary.
4. If the behavior crosses several independently meaningful boundaries, write a focused integration test.
5. If the behavior is a critical user journey or an upgrade, run the assembled application and verify the
   outcome end to end.

**Do not move a browser race into a source-text assertion, a database transaction into an in-memory fake, or an
operating-system quirk into a mock merely because the lower-fidelity test is easier to run.**

## Desired test portfolio

Kotgent is boundary-heavy. Its portfolio should resemble a test trophy rather than a mock-heavy pyramid:
many fast tests for pure rules, substantial component and integration coverage at risky boundaries, a small
set of critical end-to-end journeys, and manual checks only for behavior that automation cannot faithfully
observe.

| Area | Primary evidence | Contracts to prove |
|---|---|---|
| Domain and reducers | Unit, property-based, model-based | State transitions, replay, invariants, identifiers, ordering, dependency graphs |
| Application orchestration | Component tests with stateful fakes | Effects, compensation, cancellation, retries, idempotency, commit-before-publish ordering |
| Persistence | Integration against a real file-backed database | Transactions, revisions, reopen behavior, migrations, concurrency, connection-local semantics |
| HTTP and WebSocket transport | Integration through a real server and client | Authentication, Origin rules, routing, status codes, DTOs, frame order, reconnect behavior |
| Provider adapters | Fixture and contract tests | Launch arguments, hook normalization, on-disk discovery, versioned provider formats |
| Process and OS edges | Isolated subprocess integration | `tmux`, PTY, signals, descriptors, locale, filesystem permissions, teardown ordering |
| Browser-independent Web logic | TypeScript unit and state-machine tests | Routing, reducers, merge rules, retry classification, command selection, scheduling decisions |
| Browser UI | Component and real-browser tests | DOM behavior, focus, keyboard input, dialogs, accessibility, reconnects, service workers |
| CLI | Parser, protocol, and executable tests | Arguments, JSON output, exit codes, errors, daemon requests, terminal attach behavior |
| Installation and packaging | Artifact-level smoke tests | Launch configuration, bundled resources, permissions, executable discovery, startup and shutdown |
| Upgrade compatibility | Previous-version-to-current tests | Database, config, hooks, protocol, cached browser state, and live-session continuity |
| Security and resilience | Adversarial, fault-injection, and bounded load tests | Invalid input, path handling, authorization, cancellation, timeouts, duplication, backpressure |

## Functional core

Pure domain rules should be expressed as functions over immutable values whenever practical. Their tests
should be fast enough to run on every change and broad enough to explore more than hand-picked examples.

Use example-based unit tests for named business cases. Use property-based tests for general invariants, such
as:

- replaying the same event log always produces the same projection;
- control actions do not mutate persisted event history;
- identifier validation accepts every valid value and rejects every forbidden shape;
- a dependency graph never contains a cycle after an accepted edit;
- ordering remains stable across insertion, movement, deletion, and renormalization;
- derived flags always agree with their source state.

Use model-based tests for stateful protocols. Generate sequences of events and commands, compare the system
under test with a small reference model, and check invariants after every operation. Persist the random seed
on failure so the sequence is exactly reproducible.

Line coverage is not proof of these properties. Periodically use mutation testing or deliberate code changes
to verify that the tests reject incorrect transitions, comparisons, and omitted branches.

## Application orchestration

Application services should coordinate behavior through explicit ports for storage, processes, clocks,
randomness, filesystems, network calls, and schedulers. Component tests should replace those ports with
stateful fakes that model observable semantics rather than mocks that merely count method calls.

These tests should prove:

- which durable state exists after success, failure, timeout, or cancellation;
- which effects are attempted, completed, compensated, or deliberately retained;
- that retries are bounded and idempotent;
- that duplicate and out-of-order signals converge on the intended state;
- that committed state is published only after the commit succeeds;
- that a partial failure cannot create an invisible or unrecoverable resource;
- that concurrent commands serialize only where the domain requires it.

Assert call order only when order is itself a contract, such as persist-before-publish or close-after-reader
join. Avoid specifying incidental collaboration between private methods.

Every important fake should share a contract suite with its real implementation where practical. A fake that
does not preserve the semantics relied upon by production code makes component tests prove a fictional
system.

## Persistence

Persistence tests should use the real database engine and, whenever connection or restart semantics matter,
a file-backed database rather than an in-memory shortcut.

The persistence suite should cover:

- atomic writes spanning every row that forms one logical commit;
- monotonic revision and sequence behavior;
- writer and reader connection semantics;
- reopen and replay after process termination;
- concurrent reads and serialized writes;
- zero-row conditional updates and stale-write rejection;
- migration from representative historical schemas;
- idempotent reopening after a migration has already completed;
- preservation of user data on failed and successful upgrades.

Migration fixtures should be produced by released schemas or binaries, kept immutable, and opened by the
assembled current application. A test that creates only a fresh current schema does not prove upgrade safety.

## Transport and protocols

Transport tests should send real HTTP requests and WebSocket frames through an actual server. They should
assert the public protocol: status, headers, body, authentication decision, serialization, ordering, and
connection lifecycle.

Cover at least:

- authenticated and unauthenticated requests;
- Host and Origin decisions at every relevant method and handshake;
- literal routes taking precedence over catch-all routes;
- stable error categories and machine-readable bodies;
- every serialized frame carrying its discriminator and complete required fields;
- snapshot, row, and patch ordering;
- slow, cancelled, malformed, oversized, and disconnected requests;
- reconnect baselines and stale-frame rejection;
- backpressure and conflation without invisible resource loss.

Do not assert private handler structure when a request can prove the same rule. Exact JSON or text snapshots
are appropriate only when those exact bytes are a published compatibility contract.

## Provider adapters

Each provider boundary should be governed by a reusable adapter contract. Tests should feed adapters
sanitized fixtures captured from representative provider versions and assert the normalized domain result.

The contract should cover:

- new and resumed launch arguments;
- session-scoped hook configuration;
- hook payload validation and normalization;
- provider-session identifier discovery and precedence;
- transcript, rollout, index, and directory parsing;
- model discovery;
- missing, partial, malformed, duplicated, and delayed provider data;
- compatibility with every provider format the application promises to support.

Fixture tests protect known formats. A separate scheduled compatibility probe may inspect supported provider
CLIs without starting a model turn, so upstream format drift is detected before a release depends on it.

Usage capture has separate executable boundaries: the generated Claude script preserves raw input,
stdout and exit status while capture stalls or fails; ingress tests use real HTTP and SQLite; Codex
fixtures exercise bounded rollout tails, record ordering and unit conversion.

Live probes on 2026-09-11 used Claude Code 2.1.268 and Codex CLI 0.154.0. They confirmed that a Claude
permission-mode change can repeat cached quota without a provider request, CLI settings override the
user status command, and a Codex quota record was visible when its Stop hook ran. That single Codex
observation does not establish a flush deadline. The hour-long cross-session Claude check, conflicting
project/local status commands, and a long turn through the assembled feature were not exercised live.
Cursor and Junie quota capture were not probed. These findings are version-specific evidence, not
provider timing or compatibility guarantees.

A live Junie check on 2026-09-28 started on Junie 26.9.22, which updated itself to 26.10.5 on the first
resume. It confirmed launch and attach, the provider id bound by `SessionStart` at launch, running and
ready transitions, rebinding after `/new` (26.9.22) and `/history` (26.10.5), and resume reopening the
last chat used. A `PermissionRequest` produced `needs_approval` while Junie's own dialog stayed
unanswered; the operator's answer ran the command, and the next tool call cleared the state. The hook's
exit-1 fall-through warning reached only Junie's log file, not the TUI. Not exercised live: the trust
prompt in an unseen project, `StopFailure`, the push notification, a daemon restart reconciling a dead
session to `resumable`, import without `--cwd`, and starting from the Web UI's Junie card. The recorded
model was `gpt-4.1-mini-2025-04-14` while the TUI showed "Junie Mix", so whether model capture picks the
primary model rather than a helper is unconfirmed.

Codex hook trust hashes were checked on 2026-09-24 against `hooks/list` from `codex app-server` for
Codex CLI 0.146.1, 0.150.1, 0.153.0 and 0.156.1; the fixture values come from 0.156.1. The probe runs
`codex -c '<hooks value>' app-server` over stdio, sends `initialize`, `initialized` and `hooks/list`, and
expects every `sessionFlags` entry to be `trusted`; it needs no model turn. Keep stdin open until the
`hooks/list` response arrives, because the server exits on end of input without answering.
`realCodexIfInstalledTrustsExactlyTheGeneratedHooks` runs this probe against the installed Codex with an
empty temporary `CODEX_HOME`, so a Codex upgrade that changes the hash identity fails `./kotlin test`
instead of sending Kotgent's hooks to Codex's startup review. It is skipped where Codex is not installed,
including CI.

Before release, also launch a session with the operator's custom status line and check its visual output;
edit the user-scope command and verify the next session picks up the change.

## Process, filesystem, and operating-system edges

Behavior that depends on the host must be tested against the host. Use terminating helper executables and
isolated real resources instead of mocking system calls whose semantics are the subject of the test.

System integration tests should verify:

- process arguments and environment as observed by the child;
- descriptor inheritance and close-on-exec behavior;
- PTY input, output, resize, exit, and teardown ordering;
- signal delivery and second-signal fail-safe behavior;
- `tmux` server isolation, options, hooks, pane identity, copy mode, and attach behavior;
- file creation, permissions, atomic publication, collision handling, and cleanup;
- locale and path behavior under a minimal daemon environment.

Every test resource should be unique to the run, bounded by a timeout, and removed in `finally`. Shared
machine-global resources should use an explicit lock or a unique injectable namespace. Tests must never use
the operator's real sessions, configuration, provider home, or long-lived daemon.

## Web UI

Web testing should be divided by what must actually execute.

### Browser-independent TypeScript

Pure modules and explicit state machines should run under a lightweight JavaScript test runner without a
browser. This layer should cover routing, data merges, command matching, path handling, retry
classification, preference transitions, notification decisions, and reconnect scheduling.

Timers, visibility, network results, and storage events should enter through controlled inputs. Tests should
advance virtual time and inspect declared effects instead of sleeping or depending on wall time.

This layer runs under Node's built-in runner, `node --test`. Its TypeScript tests live in `webuitest/js/`
and import the TypeScript sources in `webui/src/`; Node runs both through type stripping, so both must use
erasable-only syntax. `npm run typecheck --prefix webui` checks the tests against
`webuitest/js/tsconfig.json`, which keeps the Web UI's strict settings and adds Node's types. Packages
resolve from `webui/node_modules`, making `npm ci --prefix webui` a prerequisite. Tests stay outside the served build in `resources/webui/`. The service-worker tests
execute the built `resources/webui/sw.js` in a Node VM, so the full tier also requires
`npm run build --prefix webui`.

`WebUiLogicTest` in `webuitest` spawns the runner, so the aggregate suite covers this tier; the direct loop
is `node --test 'webuitest/js/**/*.test.ts'` from the repository root. The prerequisite is **Node v24 or
newer**, the same floor `README.md` and `webuitest/module.yaml` state. Name the files through a quoted pattern
rather than by their directory: Node treats every positional argument as a glob, and a bare directory matches
only itself and then fails to load as a module. A pattern that matches nothing exits zero, so the wrapper
fails on an empty run, a missing `node`, or missing dependencies rather than skipping. It passes
`--test-reporter=tap` and `--test-timeout=30000`: Node's runner has no default per-test timeout, so a promise
that never settles would hang `./kotlin test` rather than fail it. A test cut off that way is reported as
*cancelled* rather than failed, so the cancelled count is asserted alongside the failure count. A separate
120-second process timeout bounds a hung runner or module import.

`lib/reattach.ts` models terminal reconnect scheduling as state, events, current environment, and declared
effects. `lib/refresh.ts` serializes reads of unversioned sources through explicit ports. Their transition
and ordering rules belong in this tier; real sockets, daemon restart, and background-tab behavior remain
browser tests.

A module belongs here only when importing it touches no browser global and every specifier resolves under
Node. DOM-dependent modules and TSX components stay in the browser tier.

### Components and DOM behavior

Component tests should render the real component tree and interact through user-visible roles, labels,
focus, keyboard events, pointer events, and form submission. Prefer semantic queries over class names and
private component structure.

This layer should prove:

- accessible names, roles, and focus movement;
- keyboard and pointer interaction;
- dialog dismissal and busy-state protection;
- validation and error presentation;
- conditional rendering from application state;
- ownership and cleanup of listeners, timers, and subscriptions.

### Real-browser journeys

A browser automation layer should run the served application against an assembled daemon and a deterministic
fake provider. The fake provider should execute inside the real process and terminal path, emit controlled
ANSI output, accept input, and produce scheduled hooks without contacting a model service.

The browser suite should cover a small set of high-value journeys:

1. Authenticate and load the application shell.
2. Start a session and observe it through the global event stream.
3. Attach a terminal, receive output, send input, resize, detach, and reattach.
4. Enter and leave an attention state and surface the corresponding notification decision.
5. Lose the daemon connection, restart it, and recover the session list and terminal.
6. Interrupt, stop, resume, finish, archive, and restore a session.
7. Import a provider session and resume it.
8. Create, order, link, review, and complete task work.
9. Open session and task deep links from a cold page and after reconnect.
10. Exercise service-worker and cached-shell update behavior.

`webuitest` drives Chromium through Playwright against the `webuicheck` fixture binary. The fixture assembles
the real server over shared doubles, serves a real PTY running a deterministic shell snippet, and accepts
scenario commands over standard input. Assertions belong in `webuitest`, never in fixture scenarios. Each
test uses an ephemeral port, signs in through the real single-use form, and leaves no state outside the
checkout. The fixture serves Vite's output in `resources/webui/`; run `npm run build --prefix webui` before
`./kotlin build`, which links the fixture binary before `./kotlin test`. Playwright supplies its own Node
driver.

`WorkspaceTest` records the terminal socket's resize frames to prove the workspace's one-xterm slot: a tab
switch keeps the same socket and xterm element, a parked terminal reports nothing through a viewport
change, and showing it reports exactly once. Its negative windows wait past the 120 ms refit debounce.
It also shrinks the visual viewport while leaving the layout viewport tall, then checks the complete
workspace, footer, reported terminal rows, offset changes, transient zeroes and keyboard dismissal.
All seven primary keys must remain at least 44×44 at 320px width; extra keys open above them.
The compact header, tab picker and column menu are exercised through their real controls. These checks
prove the layout response to viewport events, not physical iOS keyboard or safe-area reporting.

Browser tests that need a module-level probe use `routeWebUiProbe` in `webuitest/test/HarnessFixture.kt`.
It builds an entry from `webuitest/probes/` with Vite at test time and serves the bundle through Playwright
routing. Probe code never enters `resources/webui/` or the shipped build.

The usage fixture adds a real in-memory SQLite store and a `usage` scenario command. `UsageStripTest`
proves snapshots, live updates, durations, listener restart snapshots and stale-heartbeat behavior. It also
checks the current-time marker against rendered bar geometry, progress without provider frames, phone
clock skew, unknown reset/duration, and preserving usage at the reset deadline. A clock correction from one
window must update every marker without refreshing silent providers. Tooltip checks cover hover, keyboard
dismissal without losing focus, and touch layout without moving the sidebar contents.
The `usage-clock` command advances the daemon clock without publishing a reading. A resume regression moves
that clock and the browser wall clock while leaving its monotonic clock paused, then requires a fresh socket
snapshot to correct the marker and age the unchanged reading. Coordinator tests cover bounded batching,
follow-up invalidations, snapshot completion, retired callbacks, timeouts, backoff, and disposal.
Install Playwright's advancing clock before navigation, and let all initial snapshots arrive before pausing
it; freezing it before navigation also freezes the effects being tested. Script, ingress, socket and browser
coverage is compositional, not evidence of a live long-running provider turn through the assembled feature.

The `mutexes` scenario runs the real mutex coordinator over in-memory holdings on the usage fixture's clock.
`mutex-acquire <session> <key>` takes a free key; `mutex-wait <session> <key>` queues and keeps a long-poll
open, as `kotgent mutex run` does, so a release hands the key over; `mutex-release <key>` and
`mutex-end <session>` release. `MutexesTest` covers force release through the in-app dialog, a holder that
changes while the operator confirms, terminal-head pills, a cold deep link, a reconnect snapshot, and the
15-minute highlight by daemon age and then by monotonic time with the page's wall clock hours off.

The `plan-review` scenario exercises authored plans and durable review rounds. `PlanReviewTest` covers
viewed marks, questions, inline edits, stale drafts, approval, safe Markdown, narrow columns and reconnect
recovery. The `plan-findings` and `plan-findings-auto` scenarios add execution reviews;
`FindingDecisionTest` covers complete supervised batches, fixed review modes, a real 409 after an
amendment, and opening/reusing/retiring a Claude investigator without closing the parent task. The
`plan-finding-amend` harness command changes and reverifies a finding while a decision draft is open.
These launches use fake sessions and prompt storage; no provider is started.

`SqlitePlanStoreTest` reopens a legacy database during review and after execution feedback, checking
the full durable document and event history. `PlanExecutionTest` proves cursor replay, worker loss at
startup and during assignment, investigator cleanup retries, orphaned launches, and disconnected launch
completion. `SessionManagerTest` and adapter tests cover retaining read-only options on resume and
update relaunch. Mutex tests cover holder death and forwarded termination signals; SIGKILL of the
runner itself cannot run cleanup and leaves the documented session-end/force-release recovery path.

Plugin skills live in the separate Kortex repository. Validate their frontmatter and use isolated Git
fixtures for resume, serial merge and rebase behavior. A simulated CLI/provider transcript checks the
instructions against their command contracts; it does not replace the manual live-orchestration check.

For early resets, real SQLite tests cover source admission, atomic history, generations, receipt-time
migration and reopen. Clock-correction tests must include producer-to-receipt delay and unchanged cached
renders; making capture and receipt clocks equal hides a stuck watermark. A slow-subscriber socket test
drops one window's final hint during another window's burst and still requires both authoritative values.
`UsageResetNotifierTest` covers eligibility, retries, interrupted acknowledgement and slow workers;
`DaemonUsageStartupTest` verifies that recovered inbox data is readable over HTTP before its wake runs,
including push-disabled startup and failure cleanup. The actual classic service worker runs under a
Node VM to prove typed display, deadlines, fallback and click routing. These checks do not establish
delivery by a device's push service.

Standards-based behavior should be run in more than one browser engine, but only where the difference is
measured rather than assumed. Playwright's WebKit does not deliver touch pointers to the page at all, so
every gesture test is Chromium-only today, and a second engine is worth adding for a given test on the day
that test measurably diverges. Synthesizing an event rather than dispatching a real one is not a substitute
for either engine: it proves that a listener runs, not that the platform routes the gesture to it.

Reserve a real-device release checklist for platform behavior that desktop automation cannot faithfully
reproduce, such as installed-PWA lifecycle, safe areas, software-keyboard geometry, touch physics, and
notification permission prompts.

Real-device release checklist:

- On macOS, open two Safari PWA windows on different sessions. Check their titles in the Window
  menu and Mission Control: each page supplies `project — session — Kotgent`, using the cwd folder
  when no project is available. Switch and rename sessions, then open the backlog and return; titles
  must follow the visible content independently. Verify the installed app separately: browser DOM
  checks do not prove how Safari displays page titles in native window chrome.
- On a phone in Safari and the installed PWA, swipe vertically over the header and past both ends
  of the terminal, drawer and workspace panels. The header must stay on screen while inner content
  scrolls. Repeat with the software keyboard open and after dismissal, and check that dialog inputs
  remain visible above the keyboard.
- On iOS 27, cold-launch the installed PWA and check that the terminal title, status badge and
  header buttons stay sharp below the status bar. When changing `apple-mobile-web-app-status-bar-style`,
  check both an existing Home Screen installation and one removed and re-added from a freshly loaded
  Safari page: iOS may retain the old installation's status-bar mode across page reloads and daemon
  updates. Re-enable push after reinstallation if needed; server-side sessions and tasks remain intact.
  Repeat after opening/closing the drawer and a
  dialog, switching to the board, rotating, and showing/hiding the keyboard. The status-bar surface
  must not paint a strip, intercept taps, add top spacing or obscure the last terminal row. Repeat
  on an older iOS version and in a normal Safari tab; desktop WebKit cannot prove the native blur.
- Tap a usage bar in the installed phone PWA: its current/reset/observation times and remaining duration
  must be readable inside the sidebar; tapping outside dismisses the tooltip. Return from a suspended app
  and check that the time marker refreshes with the resumed clock and incoming usage snapshot.
- Sleep and wake a Mac with the Safari PWA open and providers silent. The countdown and stale indication
  must recover after a fresh snapshot, including when the event socket survived sleep. Repeat while the
  daemon is temporarily unreachable, then restore it. Automated clock simulation does not prove OS wake
  event delivery or installed-PWA behavior.
- An installed PWA receives an early weekly-reset push whose text includes the percent spent before the
  reset and when that reading was seen. Tapping it opens the root view from a closed app and from an
  already open task/session; attention notifications still open their session. Check repeated wakes on
  two devices: reading on one must not consume the other's item.
- ADHD mode on a phone: the pin on a session row and on a folder head must be reachable by thumb without
  selecting the row, and both must stay visible, because a touch screen has no hover to reveal them. On a
  notched phone the header must keep the archive, ADHD and notification toggles plus the close button on
  one line and clear of the safe-area inset. On an iPad with a trackpad, which reports hover but is
  touched, the first tap on a row must select it, never pin it. Chromium on a touchscreen laptop or
  Chromebook is a known, accepted exception: a tap sets `:hover` before its click, so a first tap on a
  row's right edge can pin the row, and one on a folder head's right edge can hit its pin or `+`. One
  more tap undoes it, so do not fail the check for it.
- On iOS and Android, long-press a board card's title and linked-session name; neither a text-selection
  menu nor a link callout should appear, while a tap should still open the task or session.
- Both project dialogs on a phone and a tablet: the swipe handle, the compensated padding, and backdrop
  dismissal. While a delete or restore request runs, swipe and backdrop dismissal must stop, while the
  footer dismissal button and the header close button must stay enabled.
- The platform close request against a busy project dialog on every engine. Escape or the system back
  gesture must dismiss it without cancelling the mutation, and the outcome of the still-running request
  must appear in the status line.
- The task link picker on a phone and a tablet: the swipe handle, the compensated padding, and backdrop
  dismissal. While a link request runs, swipe and backdrop dismissal must stop, while the footer
  dismissal button and the header close button must stay enabled.
- The platform close request against a busy task link picker on every engine. Escape or the system back
  gesture must dismiss it without cancelling the mutation, and the outcome of the still-running request
  must appear in the status line.
- Escape inside the picker's search field on every engine. A search input inside a dialog has two
  competing native behaviors — clearing the field and the dialog's cancel path — and the picker handles
  neither itself. Record which one a first press takes, and require that a repeated press still closes
  the picker; Escape must never be swallowed into a state where only a pointer can dismiss it.
- The picker's results list on a short phone viewport with the software keyboard raised. Its scroll port
  is a viewport fraction the keyboard does not resize, so the list must still scroll, an option must
  stay reachable and tappable, and the footer dismissal button must not be stranded under the keyboard.
- The task link picker's search field with a real IME — Japanese, Chinese, or Korean — on a phone and on
  a desktop engine. Typing a candidate and pressing Enter to *commit the candidate* must select text in
  the field and link nothing; only an Enter pressed after the composition has ended may commit the link.
  Chromium cannot reproduce this: an automated run can only synthesize a `KeyboardEvent` carrying
  `isComposing`, which proves the branch is read, not that a real input method sets it. This is the one
  picker behavior on this page with no faithful automated proof.
- The rename dialog on a phone: the name input must stay visible above the raised software keyboard, and
  the dialog's bottom safe-area inset must still hold while the keyboard covers part of the viewport.
- The name field with a real mobile keyboard on iOS and Android. It must offer neither autocorrect nor
  automatic capitalisation, so a name is stored exactly as typed.
- An installed PWA reaching the board and deleting a project from the palette button rather than `⌘K`.
- The session workspace on a phone: with several tabs, a sideways swipe scrolls the tab strip without
  scrolling the page or the terminal, and a tap still selects a tab. Switch a two-column tab to its task
  column and back with the column switcher, then tap the terminal: the software keyboard must open, the
  key bar must return, and the last terminal row must stay visible above both. Rotate while the terminal
  is parked in another tab; showing it again must fit the new width without a reconnect.
- The workspace divider on a tablet: a finger drag and a trackpad drag both resize the columns without
  scrolling the page or selecting text, and neither column can be dragged narrower than the width that
  would hide it.

### Static assets and source checks

Static-serving tests verify externally observable facts: reachability, bytes, media types, cache headers,
content-encoding negotiation, path safety, and precedence over API routes. Every reference in the built
shell must resolve to a served file, and every public non-map file under `assets/` must have a
content-hashed name and be served immutable.
Precompressed `.br`/`.gz` siblings must decompress to their originals; negotiation preserves the original
media type and cache policy and adds `Vary: Accept-Encoding` even for identity responses. No acceptable
representation including identity means 406 with `Vary` and no `immutable`. Direct sibling URLs are 404;
watch builds do not precompress. Chromium Resource Timing must prove JS and CSS were decoded. Source
maps stay uncompressed and revalidate because their names follow the chunk, not their own bytes; the
shell, service worker, manifest, icons, and all other static files must revalidate too.

Assertions that scan production Kotlin, TypeScript/TSX, JavaScript, HTML, or CSS with `contains`,
`indexOf`, or regular expressions do not prove execution and do not count as coverage. They pass around
unreachable or broken code and fail after a safe refactor. Do not add one. Prove behavior with an
integration test that exercises it, and prove pure, tangled logic with a unit test of the module that owns
it. When a requirement is architectural, prefer a parser, linter, module-graph check, or compiler-enforced
boundary.

`webuitest/js/module-graph.test.ts` checks that state modules and Preact signals resolve the same
signals-core file. The service worker imports shared API paths from `webui/src/lib/api-paths.ts`; Vite
builds `webui/src/sw.ts` as a classic IIFE at `/sw.js`, and Node VM tests execute that output, including
notification deep links. Behavioral coverage for history and shared-list ownership, name caps mirrored
from the daemon, dialog padding, and the board's class vocabulary remains backlog work. The PWA install
metadata is checked in `index.html`: installation is a real-device journey Chromium cannot prove.

## CLI

CLI tests should separate parsing, daemon protocol, and executable behavior.

- Parser tests should map arguments to typed commands and reject invalid forms without starting external
  resources.
- Rendering tests should prove stdout, stderr, JSON shape, hints, and exit codes as public contracts.
- Client tests should use a controlled HTTP server to verify paths, authentication, timeouts, and errors.
- Executable smoke tests should run the packaged CLI for help, version, installation checks, and selected
  terminating commands.
- Terminal attach tests should use a real PTY and fake session rather than a provider model.

Exact output assertions are appropriate for documented machine-readable output. Human prose should be
tested by meaning or stable required fragments unless every byte is intentionally part of the contract.

## Upgrade and compatibility testing

Upgrade safety is a first-class feature. The release suite should rehearse the transition from each supported
previous version to the candidate version using released artifacts and immutable fixtures.

A representative upgrade scenario should:

1. Start the previous daemon with its database and configuration.
2. Create a deterministic session whose process survives daemon shutdown.
3. Persist events, tasks, preferences, credentials, and provider metadata.
4. Keep a previous-version hook and browser shell capable of contacting the daemon.
5. Stop the previous daemon without destroying the managed session.
6. Start the candidate packaged daemon over the same state.
7. Run migrations and reconciliation.
8. Verify data preservation, session continuity, hook compatibility, protocol behavior, and browser recovery.
9. Restart the candidate again to prove migration idempotency.

Compatibility tests should explicitly cover the promises made across these boundaries:

- database schema and persisted event vocabulary;
- configuration and private-file permissions;
- long-lived hook scripts;
- CLI-to-daemon HTTP protocol;
- browser shell, service worker, and content-hashed assets;
- provider on-disk formats;
- packaged launch configuration.

When a boundary is intentionally incompatible, the test should prove the designed failure and recovery path,
such as a clear version error, forced reload, or preserved pre-migration backup.

## Security, resilience, and performance

Security tests should exercise the public boundary with adversarial input rather than assert that validation
code exists. Cover authentication, authorization, Origin and Host policy, ticket exhaustion, cookie scope,
path traversal, filename validation, symlink collisions, body limits, malformed JSON, and untrusted provider
payloads.

Fault-injection tests should fail each effect at meaningful points and verify durable outcomes. Include
timeouts, cancellation, partial writes, dropped connections, duplicated hooks, reordered frames, unavailable
processes, stale files, and restart between adjacent writes.

Performance tests should protect measured budgets that affect usability or safety, such as startup,
reconciliation, event replay, task-board size, terminal output bursts, frame coalescing, slow subscribers,
memory growth, and teardown latency. A benchmark should name the workload and threshold it protects; it
should not merely record a number.

## Test doubles and fixtures

Use the least powerful double that preserves the semantics required by the test:

- a value fixture for pure input;
- a stub for one predetermined response;
- a stateful fake for a port with meaningful behavior;
- a spy only when an externally important effect cannot otherwise be observed;
- a real implementation when its semantics are the risk.

Avoid deep mocks of implementation structure. They make refactoring expensive and tend to verify that the
code calls itself in the expected way rather than that the application produces the expected result.

A double with more than one consumer should live in one place instead of being copied. `fakes` is a real
module for exactly that reason: the native test fragment and the browser fixture depend on the same
in-memory event store, task store, push store, `tmux` control, and project filesystem, and two copies would
be free to drift apart while both stayed green.

There is exactly one fake per store, and a test shapes it rather than replacing it. Seed helpers write rows
without going through the guarded path. Named race hooks (`beforeCreate`, `afterNextCandidate`,
`beforeConditionalClear`, …) run outside the store's lock, which is what makes them able to expose a race.
Everything else is a `FakeStoreInterceptor` around the locked call: `RecordingInterceptor` journals it,
`ForbiddingInterceptor` proves the subject never reaches a method, and `FailingInterceptor` injects a
failure. Interceptors chain, and one instance may be shared by two stores to assert lock ordering across
them. A forbidden call raises an `Error`, not an exception, so a subject that swallows per-row failures
cannot hide it.

Fixtures should be minimal, sanitized, versioned when they represent an external format, and readable enough
to explain the scenario. Generated fixtures must have a documented generator and a reproducible source. A
fixture that stands in for a whole subsystem should also be safe by construction: the browser harness binds
an ephemeral port, mints its credentials in memory, and replaces every writing edge, so a stray run cannot
touch the operator's home, `tmux` server, or database.

## Determinism and failure quality

Tests should not depend on wall-clock timing, random ports selected outside the owning process, the user's
home directory, global environment, network availability, provider services, or execution order.

Prefer injected clocks, virtual time, deterministic random sources, temporary directories, port `0`, unique
resource names, and explicit readiness signals. Use eventual assertions around genuine concurrency; do not
use arbitrary sleeps as synchronization.

Coroutine-only timeout and retry tests use `kotlinx-coroutines-test` virtual time: start queued work with
`runCurrent()`, check the state before its deadline, then advance to the deadline and check completion or
cleanup before awaiting the result. This covers `CodexUsageCaptureTest`, the fake-reader queue in
`DiffServiceTest`, retries and recovery in `UsageResetNotifierTest`, fake provider discovery in
`SessionManagerTest`, and the in-memory body reader in `AuthRoutesTest`. Keep real sockets, subprocesses,
tmux and browser fixtures on real time: a virtual scheduler cannot advance those external operations.

In a browser, the readiness signal is observable state — a rendered node, an attribute, a request that was
made — never the assumption that an earlier input has already been processed. Effects that run after paint
are overtaken by the next keystroke, and a test that types blind is testing its own timing.

Every potentially blocking test must have a finite timeout. Failure output should include the relevant state,
events, process output, protocol frames, and seed while avoiding secrets. A flaky test is a defect in the
feedback system and should be fixed at its synchronization or isolation boundary, not retried until green.

## Regression workflow

For every defect:

1. Identify the observable contract that was violated.
2. Reproduce the defect at the lowest honest level.
3. Confirm that the new test fails for the intended reason before applying the fix.
4. Apply the smallest behavior change that makes it pass.
5. Refactor while keeping the behavioral test unchanged.
6. Run the integration and journey tests for every crossed boundary.
7. Remove any weaker test that checked only the implementation shape of the same contract.

Do not automatically place a regression in the test file nearest the edited production file. Place it where
the cause can be reproduced and the outcome can be observed.

## Quality gates

Use layered gates so feedback is both fast and representative.

### Every change

- compilation and static analysis;
- pure Kotlin and TypeScript tests;
- component tests for affected application services;
- focused persistence and transport tests;
- syntax and schema validation for changed artifacts.

### Integration gate

- real database tests;
- real Ktor HTTP and WebSocket tests;
- isolated `tmux`, PTY, process, signal, and filesystem tests;
- browser journeys for affected flows;
- security and fault-injection scenarios relevant to the change.

### Release gate

- tests against packaged artifacts rather than source-tree assumptions;
- previous-version-to-candidate upgrades;
- supported browser-engine journeys;
- installation, startup, shutdown, and restart smoke tests;
- provider-format compatibility probes;
- the real-device checklist for irreducible platform behavior.

Machine-global integration resources must be serialized or namespaced. Independent pure and component tests
should remain parallelizable.

Two mechanics of the current gate are worth stating, because both are easy to get backwards. The browser
tier serves the Vite build and drives `webuicheck`, a separate executable that no test task links, so run
`npm ci --prefix webui`, `npm run typecheck --prefix webui`, `npm run build --prefix webui`,
`./kotlin build`, then `./kotlin test`; missing build output or a missing binary fails loudly rather than
skipping. The tiers already run concurrently, so the browser tier's cost to the gate is the difference
between it and the native suite rather than its own duration. Inside the browser tier,
`webuitest/testResources/junit-platform.properties` runs test classes four at a time while the methods of
one class share a thread: every test owns its own `webuicheck` process, port, ticket and Chromium, and
Playwright objects belong to the thread that created them. Per-module test tasks remain the fast local
loop, and neither replaces the aggregate.

## Definition of done for a behavior change

A behavior change is ready when:

- its observable contract is stated clearly;
- the regression test lives at the lowest honest level;
- the test was seen failing against the broken or pre-change behavior;
- behavior-preserving refactoring does not require rewriting the test;
- relevant failure, cancellation, duplication, and restart cases are covered;
- changed persistence or protocol boundaries have compatibility evidence;
- affected critical journeys pass against the assembled application;
- no source-text assertion substitutes for executable behavior;
- cleanup, diagnostics, and timeouts make failures safe and actionable;
- user data has a tested upgrade and recovery path.

The suite should make internal change inexpensive while making externally visible breakage difficult to ship.

## Native platform matrix

The full native/JVM/browser/TypeScript suite runs on macOS ARM64 and Ubuntu 22.04 x64, with explicit
`-p macosArm64 -p jvm` or `-p linuxX64 -p jvm` selectors on build and test. ARM64 Linux has no native
compiler host: CI cross-compiles its application, harness and native test binaries on x64, then executes
them on `ubuntu-22.04-arm`. Native suites include the real PTY, SQLite, transport, provider and signing
boundaries; browser journeys run on macOS and Linux x64. No Linux runtime assertion is replaced by a
successful cross-compilation.

Kotlin's bundled QEMU 5.1 is useful for optional smoke checks, but cannot validate spawn error reporting:
it [emulates `vfork` using `fork`](https://github.com/qemu/qemu/blob/v5.1.0/linux-user/syscall.c), so the
child cannot return an exec failure through glibc's shared memory. A standalone C `posix_spawn` probe
reproduces success plus child exit 127 for a missing executable under that emulator, where native Linux
returns `ENOENT`. Keep `PtyTest.spawningANonexistentCommandThrows` enabled on the real ARM64 runner.

Linux tests require tmux, Perl, OpenSSL, Python 3, systemd-analyze, SQLite and libcurl. The TLS fixture
uses an ephemeral loopback HTTPS server and a disposable certificate to prove successful delivery,
certificate rejection and request deadlines. It never contacts a push provider or modifies system trust.

`systemdcheck-linux` renders the production service unit for `scripts/test-systemd.sh`. That separate CI
check uses a bounded shell helper, a uniquely named user unit and an isolated tmux socket. It verifies
literal paths containing spaces, quotes, percent and dollar characters, restart/crash recovery, and live
session survival after stop. It requires an available user manager and fails when that prerequisite is
missing; it does not operate the real daemon. Installer tests additionally cover environment capture,
atomic private files, reinstall/uninstall and recoverable command failures.

Release archives are built and smoke-tested in CI; release publication reuses those verified artifacts
only after every platform gate passes. Smoke tests verify checksums, executable permissions, version/help
outside the checkout, bundled resources and Linux shared-library resolution. No release test runs a real
provider or the operator's long-lived daemon.

Structured plan review checks on a real phone or tablet:

- Open a `plan.review` push notification from the installed PWA while it is closed. The task's Plan view
  should open directly and retain its round after reconnecting to the daemon.
- Edit a long block with the software keyboard raised, scroll the latest-version conflict panel, and
  save or cancel without losing text to viewport resizing. Check the approval dialog's focus and buttons.
- In a Terminal · Plan tab, switch columns and return to the terminal. Its socket, buffer and keyboard
  behavior should survive. A narrow Plan column uses the contents dropdown without sideways page scroll.

Live structured-plan orchestration is a manual check, using a disposable project and an explicitly
started daemon and providers. Automation must not start them. Record the versions, task reference,
commit IDs, results and any remaining blockers when performing this check:

- Run `kotgent:make-plan`, submit an edit and question from desktop and phone, and approve the revised
  round. Let a review wait return pending and retry it; restart the daemon during an open round and
  confirm that the same round and discussion remain available.
- Run `kotgent:exec-plan` with two independent tasks and a dependent task. Confirm separate worker
  worktrees, parent/child grouping, concurrency/dependency limits, and serialized Kotlin builds through
  `kotgent mutex run kotlin-build -- ./kotlin …`.
- Review a supervised finding on a phone, open its Claude investigator, and return to the same finding.
  Resume the investigator and confirm plan permission mode remains selected. Amend and reverify the
  finding, decide it, then send the whole batch; confirm the investigator is archived and the task stays
  open. Switch execution mode during review and check it takes effect only in the next iteration.
- Restart during worker feedback and resume from a retained cursor, then from a replayed cursor. Confirm
  no duplicate fixes, reviews or merges. End a worker and recover its preserved work from blocked state;
  interrupt the orchestrator and resume without replacing live workers or discarding dirty worktrees.
- Exercise a worker rebase conflict and a merge completed before its status write. Check that the exact
  reviewed commits are integrated once with fast-forward merges, and only clean, integrated worktrees
  are removed after their children are stopped and archived.
- Check the final integrated review, independent Codex opinion and critical review, including a final
  base rebase that needs a fix and fresh checks. Verify accepted deferred findings become backlog tasks,
  the plan completes, and the original task moves to human review with evidence rather than closing.

## Git Diff viewer

`DiffTest` also checks List/Tree switching, nested folder disclosure, arrow-key navigation,
filter-driven expansion and reading-state restoration. It checks that sticky tree counters stay at the
visible right edge without painting outside the selected row, and that Branches can be hidden and
restored across tab remounts. Image checks use real PNGs through the server to cover equal-scale
Before/After views, overlay opacity, mobile stacking, missing sides and decoding failures.
`DiffRoutesTest` checks binary image bytes, MIME headers, authentication and stale snapshots, including
changes past the old 2 MiB boundary. Reader tests cover the inclusive 20 MiB image limit.
`ImagePreviewTest` checks raster headers and dimension limits; `GitDiffReaderTest` checks image bytes
across local scopes, pinned history, renames, additions, deletions, size limits and cache restart.
Held worktree/patch responses assert unchanged
patch bounds during refresh and file selection, including the phone refresh path. `diff-tree.test.ts`
covers duplicate Git paths, rename/deletion hierarchy, literal unusual names and 5,000-file inventories.

`GitDiffReaderTest` exercises real disposable Git repositories; `DiffRoutesTest` exercises authenticated
HTTP reads, service admission, snapshot/session binding and failures. The real-Git shallow regression
uses a depth-one clone plus a local feature commit: endpoint ancestry produces a patch (or an empty
reverse comparison), while missing commit parents and uncertain ancestry remain unavailable. A local
inventory exceeding 10 MiB verifies that unrelated binary/text files do not block the list or selected
patches, per-file limits stay explicit, and same-size binary edits with restored mtime invalidate snapshots.
TypeScript Diff tests cover selection,
Refresh, cancellation and history paging races, including non-HEAD branch cursors, moved/deleted
branches and late replies after switching logs. Branch and commit filtering use the Turkish-fold
probe to enforce locale-independent matching. `DiffTest` drives the assembled Vite UI and real Git reader
through the `diff` and `diff-history` harness scenarios. Compiled Kotlin builds the commit graph with
one `git fast-import`, without a Python or JVM scripting startup in each test. `diff` has four commits;
only the pagination test uses `diff-history` with 105. Both preserve the real merge, distinct parent
trees and branch bases. Git auto-maintenance is disabled in the fixture environment so no detached
maintenance writes race with cleanup. Each command retains its existing bounded deadline and reports
the failed command/stage; cleanup failures are suppressed on the original startup error.
The scenario creates one private temporary directory containing all
repositories/worktrees, uses fake session stores and a deterministic PTY, and removes only that directory
on normal shutdown or startup failure. It never starts the product daemon or a provider. `diff-edit`,
`diff-head` and `diff-unlink` acknowledge completion before the browser continues. The real reader remains
read-only; only those fixture operations write Git state.

For an opt-in startup stress check after the normal Web UI/native build, run
`kotgent mutex run kotlin-build -- kotlinr scripts/repro-diff-startup.main.kts --parallelism=16 --rounds=3`.
The standalone script needs the Kotlin scripting runner (`kotlinr`), uses only JDK APIs, and stores
per-process stdout/stderr and timings in its printed temporary diagnostics directory. Its first run
compiles the script before measurements begin. Use `--scenario=diff` for the small fixture or a pre-fix
binary, and `--binary=/absolute/path/to/harness` to compare binaries. It starts only isolated test
harnesses, closes stdin for normal shutdown and bounds each process lifetime. It does not change CI
parallelism or production timeouts; elapsed times are diagnostic evidence, not CI performance assertions.
On the local macOS ARM64 host, the same 16-worker script reproduced 16/16 startup failures in the old
binary at 10.9 seconds. The batched 105-commit fixture passed 48/48 starts across three rounds at
0.61–1.05 seconds, without increasing the process or handshake deadlines.

Browser assertions cover All/Staged/Unstaged (including staged reversal and the separate untracked
inventory), a worktree without a session, clean locked/detached and unavailable worktrees, pinned history
paging, root/merge-parent selection, branch/base selection, visible local/remote branch navigation,
non-HEAD logs, commit filtering and keyboard activation, file filtering, Viewed, Refresh, selected patch
and scroll preservation, desktop Split, phone Files/Patch/Back, task entry, Diff tab reuse and unchanged
terminal socket/xterm identity during workspace/source changes. Task-board navigation may reconnect
the terminal on return; the phone test establishes its attachment baseline after task entry.
Controlled response publication exercises late patch and task-discovery
responses. Desktop width hiding checks 0.7/0.3 saved shares at workspace widths 1200 and 900 pixels.
The task-discovery test releases responses from a MutationObserver at task/link render, before passive
effects. Its sensitivity check temporarily restored the old passive cleanup and reproduced navigation
away from task B; the fixed layout cleanup passed. These are Chromium assertions, not proof of
real-phone Safari or Linux native runtime.

Manual Safari/iOS and installed-PWA checks, still required:

- Open Diff from a session and a task; return to the terminal and raise the software keyboard. Confirm
  the terminal remains attached, its last row and key bar stay visible, and focus follows Files/Back.
- Scroll a long patch and file list independently; try Unified/Split, horizontal scrolling, wrap, search
  with the keyboard raised, rotation and safe-area insets. Confirm controls stay reachable without page
  overflow or accidental terminal input.
- Background/resume the PWA and reconnect after a network interruption. Confirm last-good content is
  labelled stale when appropriate, Refresh resolves current HEAD/base, and Viewed resets on new evidence.
- Repeat in macOS Safari with keyboard-only navigation and VoiceOver. Chromium automation does not
  establish Safari layout, mobile keyboard, accessibility announcements or installed-PWA behavior.

For each verification run record the commit, host/architecture, browser engine and actual checks. A macOS
ARM64 run does not validate Linux x64/ARM64; use the native CI matrix before claiming those platforms.

The 2026-10-12 local:520 browser run used macOS 26.6.2 ARM64, Node 24.18.0 and Chromium
151.0.7922.34. All 263 JVM browser/logic tests passed, including the seven `DiffTest` journeys; the
Node/TypeScript suite ran through `WebUiLogicTest`. The same verification passed `npm ci`, typecheck
and the Vite build, followed by serialized `./kotlin build -p macosArm64 -p jvm` and
`./kotlin test -p macosArm64 -p jvm` through the Kotlin mutex, including the native real-Git and
authenticated HTTP suites. No Linux runtime, Safari, real-phone or installed-PWA manual result is claimed. For a focused rerun after building the Web UI and native harness, use
`kotgent mutex run kotlin-build -- ./kotlin test -m webuitest --include-tag diff-viewer`; the lifecycle
race alone has tag `diff-task-race`.

### Opt-in Diff performance fixture

`DiffReadBenchmark.temporaryFixture` returns immediately unless `KOTGENT_DIFF_BENCH=1`.
It creates and removes its own temporary Git repository, with 25 tracked text files and 12 MiB
of untracked binary files. It never opens an operator checkout or starts a daemon/provider.
The fixture and scenario list are intentionally reusable for further reader and disk-cache work.

Run the root module test with `KOTGENT_DIFF_BENCH=1` and optionally
`KOTGENT_DIFF_BENCH_RUNS=5` (minimum five), through
`kotgent mutex run kotlin-build -- ./kotlin test -m <root-module> -p macosArm64
--include-classes io.kotgent.diff.DiffReadBenchmark -v debug`, then repeat with `-v release`.
The root module name follows the checkout directory; use `./kotlin show modules` through the
same mutex to discover it. On Linux select `linuxX64`. Keep before/after on the same fixture,
host and build variant; retain stdout and the exact source revision for each run.

`DIFF_BENCH` emits each sample in nanoseconds with Git invocation/output-byte counts,
worktree bytes returned by the safe reader, and bytes passed to the reader's SHA-256 helper.
These byte counts represent application reads and hash inputs, not physical disk I/O or Git's
internal hashing. `CAPTURE_BENCH` splits `/usr/bin/true` capture into dispatch/thread creation,
spawn, drain/exit observation, reap, and residual return/cleanup/thread teardown.
`DIFF_SUMMARY` reports count and nearest-rank p50/p95. Allocation plus hashing has a separate
16 MiB scenario. Baseline release instrumentation also records `closeNs`, a component of
`returnNs`; runtime-owned workers have no per-call close, so that component is zero after reuse.
There are no wall-clock performance assertions in CI. The OS page cache is uncontrolled;
these runs must not be described as cold disk reads. This initial fixture has no application
payload cache, so later cache scenarios must explicitly identify empty-cache and warm-cache runs.

Capture concurrency is bounded at four in one runtime-owned `Dispatchers.IO` view. Semaphore admission
suspends before dispatch, within the command deadline; saturated queued requests can cancel or expire
without waiting for a worker. The existing monotonic checks, nonblocking dual-pipe draining, independent
caps and kill-before-reap cleanup remain. The process runtime owns IO worker lifetime; capture never
closes the shared dispatcher. `CapturedProcessTest` exercises saturated admission using four real process
groups with PID-file readiness, then proves queued timeout/cancel never launches a fifth command and
released permits remain usable. It also checks repeated parallel output isolation and descriptor counts.

On macOS 26.6.2 ARM64, task local:522 measured the v1 fixture before/after worker reuse
against baseline `1ac6703ed7838dbc0a68baa728e5c2513f86c473` (Kotlin 2.4.20,
coroutines 1.11.0). Five samples per scenario and variant gave these p50/p95 milliseconds:

| Scenario | Debug before | Debug after | Release before | Release after |
|---|---:|---:|---:|---:|
| Ten trivial captures | 1734 / 1785 | 11 / 33 | 1671 / 1716 | 11 / 32 |
| Reader All files | 2778 / 2985 | 531 / 885 | 2471 / 2700 | 195 / 274 |
| Reader All patch | 4368 / 4439 | 991 / 1303 | 3612 / 3841 | 334 / 526 |
| Service All files | 4017 / 4228 | 596 / 889 | 3779 / 4031 | 267 / 368 |
| Service All patch | 5623 / 5803 | 1038 / 1215 | 4926 / 5048 | 416 / 432 |

All measured command/output/read/hash counts matched before and after. Reader All files/patch
used 15/22 Git commands and returned 12,583,174/25,166,348 worktree bytes; service calls used
23/30 commands with the same worktree byte totals. In 50 baseline release captures, median explicit
thread-close time was 163.5 ms, versus 0.66 ms spawn and 4.00 ms drain/exit observation. This
isolates the removed cost; it is not evidence that Git or hashing got faster. Allocation plus hashing
16 MiB still took a debug median of 539 ms after the change. Local fingerprints and repeated Git
validation remain measurable costs for subsequent work. These are fixture-specific measurements,
with no application payload cache and an uncontrolled OS page cache, not CI timing requirements.

The next reader optimization keeps the v1 repository contents and original benchmark scenarios intact.
Additional scenarios measure a supplied history cursor (the v1 root-only history has an empty next
page), service inventory/history, and a selected root-commit patch. All scenarios still require at
least five samples. The final integration matrix must separately exercise a nonempty second page,
merge parents, branch comparisons and multiple worktrees.

Reader metadata uses one `diff --raw --numstat --no-abbrev -z` invocation. The parser consumes raw
paths by status arity before finding the numstat section; rename/copy paths may themselves begin with
colons or contain tabs/newlines. Real-Git tests compare combined records with separate Git output,
including copies, renames, binary and mode-only records. Conflict handling, staged reversals and
strict path decoding remain covered. Repository discovery combines the common-directory and bare
flag reads, parsing the fixed boolean suffix so newlines in a directory remain valid.
If combined metadata exceeds stdout's 8 MiB cap, the reader retries raw and numstat separately,
each with the original cap, and uses the same concatenated NUL stream for parsing/fingerprinting.
It never retries stderr overflow, timeout or cancellation. Real-Git coverage includes 5,000 entries
with 800-byte paths (each separate output fits while their sum does not), selected patch access,
and an inventory whose individual raw output exceeds the cap. A controlled Git wrapper verifies
identical fingerprints on the normal/fallback paths and that stderr overflow never starts fallback.

Service resolution reuses its just-read inventory only when the selected path is that inventory's
current root. Other worktrees still receive independent common-directory/root validation, and every
reader operation retains fresh verification. First history pages reuse the full HEAD object validation
already performed in that request; caller-supplied cursors still validate pinned objects afresh.
Local HEAD/index/status/content observations are retained before and after a selected patch, including
unrelated binary bytes. A real-Git wrapper regression edits an unrelated file after Git emits the
patch and proves the second observation rejects it. Authenticated HTTP regressions also cover
same-size binary edits with restored mtime, index mode changes, moved worktrees, and foreign clones.
No payload cache or larger snapshot metadata registry is introduced.

Compared with the task-1 reader at `9e19a506282f73a783463db83e83df103aeeaaa9`, the same v1
fixture consistently reduces invocation counts:

| Scenario | Before | After |
|---|---:|---:|
| Inventory | 4 | 3 |
| First history page | 11 | 8 |
| Reader local files / patch (each scope) | 15 / 22 | 13 / 19 |
| Service local files / patch | 23 / 30 | 16 / 22 |
| Root-commit files | 12 | 10 |

All/Unstaged file and patch reads retain exactly 12,583,174 and 25,166,348 worktree bytes;
Staged retains zero worktree-content bytes. Combining the metadata fingerprints removes 65 bytes
from each final snapshot-hash input, not from the file content hashed. Reader All Git output remains
6,332/12,315 bytes; service output falls from 6,800/12,783 to 6,566/12,549 bytes, and first-page history
output falls from 709 to 529 bytes.

Five-sample task-2 measurements on the same host/variants gave these p50/p95 milliseconds;
the before columns are the earlier task-1 measurements above:

| Scenario | Debug before | Debug after | Release before | Release after |
|---|---:|---:|---:|---:|
| Reader All files | 531 / 885 | 629 / 647 | 195 / 274 | 216 / 427 |
| Reader All patch | 991 / 1303 | 1135 / 1154 | 334 / 526 | 466 / 711 |
| Service All files | 596 / 889 | 649 / 687 | 267 / 368 | 377 / 498 |
| Service All patch | 1038 / 1215 | 1219 / 1274 | 416 / 432 | 545 / 689 |

These samples prove fewer invocations, not an incremental latency improvement. A follow-up release
run restored the prior reader/service, then ran the preserved changed release binary immediately
afterward (five samples each, same fixture, serialized mutex). Service files were 348/761 → 472/556 ms
and patches 532/974 → 547/693 ms; reader All files were 215/419 → 357/451 ms and patches
347/787 → 508/751 ms. First service history improved from 246/345 → 99/235 ms in that run.
Run-to-run timing variation is substantial, and these measurements do not isolate its cause.
The deterministic command reduction is the established result; final integration must remeasure
latency rather than infer a speedup from counts. Global content hashing remains required work.

After the stdout-overflow fallback correction, all 48 focused diff/parser/service/HTTP tests passed
in debug and release. Five further release samples retained exact command/output/read/hash totals
for all 17 benchmark scenarios. Reader All files/patch measured 184/344 and 313/340 ms p50/p95;
service files/patch measured 195/242 and 319/435 ms. These later results reinforce the need to retain
all runs and report timing variation rather than select only a favorable measurement.

### Integrated Diff performance matrix

`DiffMatrixBenchmark.temporaryMatrix` is separately opt-in with `KOTGENT_DIFF_MATRIX=1` and
`KOTGENT_DIFF_BENCH_RUNS=5` (minimum five). Run it through the same mutex and root-module command
as above, selecting `--include-classes io.kotgent.diff.DiffMatrixBenchmark`, in both debug and release.
It owns a temporary repository with 109 commits, a nonempty second history page, a two-parent merge,
a base branch and two worktrees. Each worktree has 25 changed tracked text files and 12 MiB of
unrelated untracked binary content. Fixed commit identities and dates make recreated fixtures comparable.

The matrix measures history and its next page, All/Staged/Unstaged files and patches, root and ordinary
commits, both merge parents, and branch files/patches in each worktree. Each scenario reports five
samples and nearest-rank p50/p95. `uncached` disables the application payload cache; `cold` starts with
a separate empty cache for every sample/scenario; `warm` primes that scenario outside the timer and
constructs a fresh reader over its disk records. Patch snapshots are prepared with an uncached reader
outside the timed operation. Thus cold patch includes its own metadata publication, while warm patch
can reuse both metadata and selected hunks. Local comparisons intentionally bypass payload caching.
Fixture setup, priming and untimed snapshot preparation are excluded from the reported operation.
The OS page cache is uncontrolled in every state. Worktree entries are isolated, not shared hits.

`DIFF_MATRIX` reports the same additive byte counters as `DIFF_BENCH`, including cache writes
separately; they describe logical application work, not physical I/O or Git's internal reads/hashing.
Warm immutable samples deterministically assert zero `logCommands` and `diffCommands`; latency has
no CI threshold. For a pre-cache baseline, use identical fixture/scenario code with the reader factory
adapted to omit the unavailable cache and run only `uncached`; retain that adaptation and scalar
instrumentation with the raw logs. A baseline without a cache has no distinct cold/warm cache state.

`DiskCacheFilesTest.cancellationAfterStagingWritePreventsPublicationAndReleasesLock` cancels the active
writer through its byte-accounting callback after a successful staging write. It proves cancellation
escapes, the target is not published, staging is removed, and a fresh instance can acquire the lock and
publish. This complements cancellation before admission and during Git dependency validation.

### First-pass integrated measurements (2026-10-12, local:522)

The v2 matrix compares baseline `1ac6703ed7838dbc0a68baa728e5c2513f86c473` with the integrated
implementation at `9d4045131a6ac1337b065d2f4bf83ec44e27e6f3` plus the reusable matrix and active
publication-cancellation test. Host: macOS 26.6.2 ARM64, Git 2.54.0, Kotlin 2.4.20 / Toolchain 0.13.0.
Both revisions recreate the same root and HEAD OIDs. Each variant has 180 baseline samples and 540
optimized samples (36 operations across both worktrees, five repeats, three final cache states).
Baseline has no payload cache. All timed operations succeeded; every warm immutable sample skipped
`log` and `diff`. The following representative first-worktree rows are p50/p95 milliseconds:

**Debug**

| Operation | Baseline | Final uncached | Final empty cache | Final warm |
|---|---:|---:|---:|---:|
| history | 1703 / 1820 | 99 / 505 | 146 / 150 | 91 / 115 |
| next-page | 1218 / 1247 | 88 / 121 | 141 / 160 | 80 / 100 |
| local-all-files | 2783 / 2865 | 531 / 645 | 504 / 529 | 509 / 512 |
| local-all-patch | 4324 / 4450 | 999 / 1150 | 956 / 992 | 976 / 1148 |
| local-staged-files | 2324 / 2535 | 150 / 223 | 124 / 137 | 148 / 467 |
| local-unstaged-patch | 4414 / 4523 | 955 / 1408 | 973 / 1372 | 986 / 1019 |
| root-files | 1939 / 2103 | 99 / 412 | 257 / 662 | 165 / 171 |
| root-patch | 1993 / 2005 | 88 / 334 | 303 / 390 | 154 / 198 |
| commit-files | 1880 / 2109 | 99 / 234 | 257 / 266 | 174 / 192 |
| merge-parent1-patch | 1451 / 1486 | 86 / 177 | 328 / 451 | 196 / 232 |
| merge-parent2-patch | 1453 / 1692 | 59 / 140 | 270 / 290 | 170 / 192 |
| branch-files | 2252 / 2487 | 137 / 421 | 291 / 370 | 183 / 200 |
| branch-patch | 1390 / 1611 | 60 / 156 | 276 / 313 | 168 / 172 |

Across both worktrees, local-all-files empty-cache p50 improved 5.5–5.6× over baseline.
Across both worktrees, local-all-patch empty-cache p50 improved 4.5–4.6× over baseline.

**Release**

| Operation | Baseline | Final uncached | Final empty cache | Final warm |
|---|---:|---:|---:|---:|
| history | 2468 / 3925 | 227 / 234 | 173 / 276 | 136 / 270 |
| next-page | 1813 / 2050 | 164 / 190 | 188 / 324 | 102 / 259 |
| local-all-files | 3325 / 3768 | 381 / 440 | 202 / 346 | 201 / 313 |
| local-all-patch | 5003 / 5484 | 498 / 551 | 457 / 520 | 303 / 556 |
| local-staged-files | 3235 / 3601 | 290 / 342 | 223 / 659 | 172 / 397 |
| local-unstaged-patch | 4886 / 5116 | 480 / 732 | 395 / 495 | 479 / 646 |
| root-files | 2751 / 2801 | 249 / 589 | 349 / 412 | 182 / 305 |
| root-patch | 2566 / 2629 | 156 / 242 | 403 / 527 | 284 / 380 |
| commit-files | 2455 / 2632 | 188 / 627 | 386 / 600 | 224 / 606 |
| merge-parent1-patch | 1888 / 2151 | 146 / 201 | 384 / 1171 | 252 / 364 |
| merge-parent2-patch | 1991 / 2120 | 107 / 150 | 468 / 700 | 211 / 241 |
| branch-files | 3092 / 3771 | 229 / 298 | 386 / 796 | 203 / 288 |
| branch-patch | 1975 / 2104 | 132 / 180 | 407 / 456 | 262 / 294 |

Across both worktrees, local-all-files empty-cache p50 improved 14.4–16.5× over baseline.
Across both worktrees, local-all-patch empty-cache p50 improved 8.7–11.0× over baseline.

Local All/Unstaged files and patches still return 12,583,174 and 25,166,348 worktree-content bytes;
Staged returns zero. All three local cache states execute identical work and bypass payload storage;
differences between their timings are run-to-run variation, not a local-cache speedup. Commands fall
from 15/22 to 13/19 for each local files/patch pair. Combining
metadata removes 65 bytes per observation from the final snapshot hash input, with no removed content
bytes. Warm immutable responses still read Git dependency output and cache records and hash their
inputs; zero `log`/`diff` does not mean zero I/O. The complete emitted rows include these costs and
cache writes separately. These results establish the aggregate improvement over the original process
implementation, not a separate universal cache latency improvement. Small comparisons often remain
cheaper uncached. Run order, OS page cache and host load are uncontrolled; do not compare debug and
release as if they were adjacent runs under identical load.

Final verification passed the required npm install/typecheck/build → host/JVM build → host/JVM test
order, with 1,948 Native method pass records and 275 JVM passes (263 browser/logic, 10 build-info,
2 SQLDelight plugin). The focused release Diff/HTTP and native cache tests also passed. `sysnative`
production and test sources compiled for macOS ARM64, Linux x64 and Linux ARM64. This is not Linux
runtime evidence. Safari/iOS/PWA manual checks remain outstanding.

No new compiler warnings were introduced. Existing warnings are five Native test unused returns and
seven unchanged `PlanFindings` harness unused returns. The unchanged Web UI emits a Vite chunk-size
notice; npm audit reports the existing `source-map-js` advisory GHSA-68fv-2mgg-jv7q. Dependency updates
were outside this optimization scope. Raw samples, baseline instrumentation, exact commands, warning
logs and the complete two-worktree matrix accompany the review handoff.

### Cache corruption and sibling placement regressions

Independent review of `dbc92cabeeff1684e9a471b2b5e9f49e9157e551` identified two additional cases.
`DiffDiskCacheTest.multibyteChecksumHeaderIsAMiss` writes a real record whose 133-byte header decodes
to only 131 characters; it reproduced an out-of-bounds checksum slice. The companion
`invalidUtf8PayloadWithValidChecksumIsAMiss` reproduced an uncaught Native `CharacterCodingException`.
Both now assert a miss and successful replacement. Fixed headers are checked as ASCII before string
offsets are used, and strict payload decoding failure is caught narrowly; cancellation still escapes.

`GitDiffReaderTest.cacheInsideASiblingWorktreeIsBypassedBeforeAnyAccess` reproduced cache creation
inside the main checkout while a linked sibling was selected. It now exercises both selection directions
through history, files and patches twice, asserting successful ordinary Git reads, computation counters,
and no cache-directory creation. The reader carries all roots from its existing fresh verification into
request-local cache context; it adds no discovery command or persistent placement decision. The placement
guard runs before reads because even cache lookup can create its private directory and lock.

After these fixes, the required ordered host/JVM gates passed again: 1,951 Native method passes and
275 JVM passes. All 65 focused release Diff/HTTP tests passed, including the three new regressions.
The unchanged `sysnative` cross-compilation and release checks above remain applicable. No new
compiler warnings appeared. Both corrected variants repeated all 540 samples, retaining the original
baseline and first-pass logs separately. First-worktree p50/p95 milliseconds after correction:

| Variant / operation | Uncached | Empty application cache | Warm application cache |
|---|---:|---:|---:|
| debug / history | 98 / 165 | 185 / 203 | 145 / 148 |
| debug / local-all-files | 557 / 715 | 555 / 681 | 569 / 759 |
| debug / local-all-patch | 1199 / 1650 | 1032 / 1073 | 1018 / 1138 |
| debug / root-files | 193 / 239 | 406 / 614 | 154 / 290 |
| debug / root-patch | 127 / 258 | 324 / 729 | 182 / 372 |
| release / history | 131 / 215 | 283 / 478 | 148 / 256 |
| release / local-all-files | 228 / 386 | 274 / 793 | 233 / 339 |
| release / local-all-patch | 440 / 472 | 493 / 706 | 424 / 529 |
| release / root-files | 169 / 219 | 315 / 424 | 248 / 330 |
| release / root-patch | 116 / 258 | 437 / 481 | 280 / 398 |

Corrected debug empty-cache p50 improvement across both worktrees: local-all-files: 4.5–5.0×; local-all-patch: 3.7–4.2×.
Corrected release empty-cache p50 improvement across both worktrees: local-all-files: 8.6–12.2×; local-all-patch: 7.3–10.1×.

All 1,440 baseline/corrected samples passed the evidence checks: five per scenario/state/variant,
identical fixture OIDs, preserved local read bytes and command reductions, and zero `log`/`diff`/write
calls on every warm immutable sample. Cache read/hash work is still included. The timing limitations
and small-comparison cache tradeoffs above also apply to this corrected run.
