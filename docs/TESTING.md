# Testing Strategy

This guide defines the desired testing model for Kotgent. It is a design target, not an inventory of the
current suite. Its purpose is to make changes safe by giving every important contract an executable,
trustworthy proof at the right boundary.

The goal is not to maximize the number of tests or eliminate all risk. The goal is to make a real regression
produce a fast, precise failure; let behavior-preserving refactors stay green; and rehearse upgrades in the
same shape in which users receive them.

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
| Browser-independent Web logic | JavaScript unit and state-machine tests | Routing, reducers, merge rules, retry classification, command selection, scheduling decisions |
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

### Browser-independent JavaScript

Pure modules and explicit state machines should run under a lightweight JavaScript test runner without a
browser or build step. This layer should cover routing, data merges, command matching, path handling, retry
classification, preference transitions, notification decisions, and reconnect scheduling.

Timers, visibility, network results, and storage events should enter through controlled inputs. Tests should
advance virtual time and inspect declared effects instead of sleeping or depending on wall time.

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

Run standards-based behavior in more than one browser engine. Reserve a real-device release checklist for
platform behavior that desktop automation cannot faithfully reproduce, such as installed-PWA lifecycle,
safe areas, software-keyboard geometry, touch physics, and notification permission prompts.

### Static assets and source-shape checks

Static-serving tests should verify externally observable facts: reachability, bytes, media types, cache
headers, revision addresses, path safety, and precedence over API routes.

Assertions that scan production Kotlin, JavaScript, HTML, or CSS with `contains`, `indexOf`, or regular
expressions do not prove execution. They can pass around unreachable or broken code and fail after a safe
refactor. Use source-shape assertions only when the exact source text is itself the external contract, and
keep that exception narrow.

When the requirement is architectural rather than behavioral, prefer a parser, linter, module-graph check,
or compiler-enforced boundary. When the requirement is visual, prefer browser assertions or focused visual
regression images. When it is interactive, execute the interaction.

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
- browser shell, service worker, and revisioned assets;
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

Fixtures should be minimal, sanitized, versioned when they represent an external format, and readable enough
to explain the scenario. Generated fixtures must have a documented generator and a reproducible source.

## Determinism and failure quality

Tests should not depend on wall-clock timing, random ports selected outside the owning process, the user's
home directory, global environment, network availability, provider services, or execution order.

Prefer injected clocks, virtual time, deterministic random sources, temporary directories, port `0`, unique
resource names, and explicit readiness signals. Use eventual assertions around genuine concurrency; do not
use arbitrary sleeps as synchronization.

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
- pure Kotlin and JavaScript tests;
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
