package io.kotgent.webuicheck.scenarios

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.webuicheck.HarnessFakes
import io.kotgent.webuicheck.Scenario

/**
 * The wall clock every seeded row in every scenario is stamped from — `SEED_EPOCH_MS + n`, one `n` per
 * row. It is the ONE session-seeding clock; there is no second constant for the task scenarios (they
 * seed through [io.kotgent.webuicheck.scenarios.fixtureSession], which is a door onto [harnessSession]).
 *
 * A FIXED value rather than `Clock.System.now()`, because a scenario is a fixture and a fixture that
 * changes between two runs cannot be asserted against. Nothing in the Web UI renders a session
 * timestamp today (the sidebar shows name / agent / model / state, never an age), and the client sorts
 * nothing — the sidebar's order is the daemon's `listSessions()` order, which is `(created_at, id)`
 * (`Sessions.sq`, mirrored by `FakeEventStore`). So the ascending seeding order below IS the row order
 * the browser sees.
 */
internal const val SEED_EPOCH_MS: Long = 1_700_000_000_000L

/**
 * Build one seeded `sessions` row. Every scenario file in this package goes through it, so the shape a
 * browser test reads is written once: [id] is the value that lands in the `/s/{id}` route and the tmux
 * name follows the production `kt-<id>` spelling. `paneId`/`stateSource` stay null on the ROW — nothing
 * the browser can see depends on either (`SessionDto.alive` is derived from [state] alone, and
 * `stateSource` is not on the wire at all) — while the live pane an alive row implies is registered
 * with the fake tmux by [seedSessionRow], which is the door every scenario actually goes through.
 *
 * [createdAt] is mandatory and distinct per row on purpose: both `EventStore.listSessions` and
 * `EventStore.sessionsHoldingTask` order by `(created_at, id)`, so two rows sharing a timestamp would
 * make an ordering assertion depend on the id tie-break rather than on the fixture.
 */
internal fun harnessSession(
    id: String,
    name: String,
    agent: String,
    cwd: String,
    state: SessionState,
    createdAt: Long,
    providerSessionId: String? = null,
    model: String? = null,
    cliVersion: String? = null,
    tags: List<String> = emptyList(),
    lastSeq: Long = 0,
    readCursor: Long = 0,
    updatedAt: Long = createdAt,
): SessionMeta = SessionMeta(
    id = SessionId(id),
    name = name,
    tags = tags,
    agent = agent,
    providerSessionId = providerSessionId?.let(::ProviderSessionId),
    model = model,
    cliVersion = cliVersion,
    cwd = cwd,
    tmuxSession = "kt-$id",
    state = state,
    lastSeq = Seq(lastSeq),
    readCursor = Seq(readCursor),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Seed one session row — and, when it is ALIVE, the tmux pane that must exist behind it.
 *
 * Every scenario goes through this rather than calling `upsertSession` itself, because the pane is not
 * decoration. `FakeTmux.sendKeys` consults its pane set exactly as the real `Tmux.sendKeys` reads its
 * chain's answer (`src/tmux/Tmux.kt:327-332` throws on "no live server/session/pane"), so a `running` /
 * `ready` row with no pane behind it is a session whose Interrupt the DAEMON would refuse — and every
 * control action in the harness would then be succeeding for a reason production does not have.
 *
 * A dead row (`resumable`, `crashed`, …) deliberately gets none: that IS the state, and `resume` proves
 * it by finding no pane and launching.
 */
internal suspend fun seedSessionRow(fakes: HarnessFakes, meta: SessionMeta) {
    fakes.events.upsertSession(meta)
    if (meta.state.isAlive) fakes.tmux.seedPane(meta.id.value)
}

/**
 * The four rows of the `sessions` scenario, in the order the sidebar will list them.
 *
 * The shape is deliberate, not decorative — each column below is something a browser test reads:
 *
 *  - **The cwds `/a/b`, `/a/c`, `/d`** are what make a RECURSIVE directory tree possible at all. The
 *    sidebar only groups when the base-path preference is non-empty (`groupingEnabled` in
 *    `lib/prefs.js`), and with base `/` at level 2 these fold into a node `/a` (label `a`, no sessions
 *    of its own, aggregate count 3) holding children `/a/b` (two sessions) and `/a/c` (one), plus a
 *    sibling `/d` (one). Level 1 collapses that to `/a` (3) and `/d` (1). A test that wants the flat
 *    list simply leaves the preference alone — the fake's default `basePath` is `""`.
 *  - **Two sessions share `/a/b`** so the tree has one node with more than a single child; a tree where
 *    every leaf holds exactly one session cannot tell a per-node count apart from a per-row count.
 *  - **Four distinct states** cover the three partitions the UI branches on: alive-and-working
 *    (`running`), alive-and-idle (`ready`), needs-attention (`needs_approval`, which paints the row's
 *    attention dot and its group's) and dead-but-revivable (`resumable`, whose Resume action is the one
 *    that needs a provider id — hence one is set).
 *  - **Four distinct agent kinds**, all of them kinds `lib/agents.js` actually draws a mark for. An
 *    unknown kind would render a blank chip and make an icon assertion vacuous.
 *  - **`model` and `cliVersion` are set on some rows and not others**, because "absent" is the state
 *    the sidebar has to render without collapsing the row.
 *
 * No row is `archived` and none carries a `taskRef`: the archived filter and the task badge belong to
 * the board scenarios, and a hidden row here would silently change the count a smoke test asserts.
 */
private val SESSION_ROWS: List<SessionMeta> = listOf(
    harnessSession(
        id = "s-alpha",
        name = "alpha",
        agent = "claude",
        cwd = "/a/b",
        state = SessionState.running,
        createdAt = SEED_EPOCH_MS + 1,
        providerSessionId = "11111111-1111-4111-8111-111111111111",
        model = "claude-sonnet-4-5",
        cliVersion = "2.1.218",
        tags = listOf("api"),
    ),
    harnessSession(
        id = "s-beta",
        name = "beta",
        agent = "codex",
        cwd = "/a/b",
        state = SessionState.ready,
        createdAt = SEED_EPOCH_MS + 2,
        providerSessionId = "22222222-2222-4222-8222-222222222222",
        model = "gpt-5-codex",
    ),
    harnessSession(
        id = "s-gamma",
        name = "gamma",
        agent = "junie",
        cwd = "/a/c",
        state = SessionState.needs_approval,
        createdAt = SEED_EPOCH_MS + 3,
        // Junie ids are NOT uuids; using its real shape keeps the fixture honest about the one provider
        // whose id would fail a UUID check.
        providerSessionId = "session-260730-015553-1j1h",
        tags = listOf("ui"),
    ),
    harnessSession(
        id = "s-delta",
        name = "delta",
        agent = "shell",
        cwd = "/d",
        state = SessionState.resumable,
        createdAt = SEED_EPOCH_MS + 4,
        providerSessionId = "44444444-4444-4444-8444-444444444444",
    ),
)

/**
 * `sessions` — the general-purpose scenario: a populated sidebar, a directory tree waiting for a base
 * path, and four addressable `/s/{id}` routes. Consumers: the sidebar and its grouping, the client
 * router, and the smoke test.
 *
 * It declares a terminal upstream even though the terminal is not its subject, because SELECTING a row
 * attaches one: `app.js`'s `showSession` opens the terminal WS for any alive session, so a routing test
 * that clicks a row would otherwise depend on what the harness does with a scenario that declares no
 * upstream. Only `empty` (which has no session to attach) leaves it null.
 */
fun sessionsScenario(): Scenario = Scenario(
    name = "sessions",
    seed = { fakes ->
        // Make the cwds real directories in the fake project tree, so a resolver that consults it (the
        // project lookup behind `GET /api/v1/projects`, the New-session cwd field) sees a coherent
        // filesystem rather than four dangling strings. No `.kotgent.json` is written anywhere, so
        // every row resolves to NO project — the board scenarios own that half.
        listOf("/a", "/a/b", "/a/c", "/d").forEach(fakes.projectFs::addDirectory)
        SESSION_ROWS.forEach { seedSessionRow(fakes, it) }
    },
    terminalUpstream = deterministicUpstream(SESSIONS_BANNER),
)
