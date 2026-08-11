package io.kotgent.webuicheck.scenarios

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.webuicheck.HarnessFakes
import io.kotgent.webuicheck.Scenario

internal const val SEED_EPOCH_MS: Long = 1_700_000_000_000L

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

internal suspend fun seedSessionRow(fakes: HarnessFakes, meta: SessionMeta) {
    fakes.events.upsertSession(meta)
    if (meta.state.isAlive) fakes.tmux.seedPane(meta.id.value)
}

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

fun sessionsScenario(): Scenario = Scenario(
    name = "sessions",
    seed = { fakes ->
        listOf("/a", "/a/b", "/a/c", "/d").forEach(fakes.projectFs::addDirectory)
        SESSION_ROWS.forEach { seedSessionRow(fakes, it) }
    },
    terminalUpstream = deterministicUpstream(SESSIONS_BANNER),
)
