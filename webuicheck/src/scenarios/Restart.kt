package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

fun restartScenario(): Scenario = Scenario(
    name = "restart",
    seed = { fakes ->
        listOf("/w/restart-a", "/w/restart-b").forEach(fakes.projectFs::addDirectory)
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-restart-a",
                name = "restart-a",
                agent = "claude",
                cwd = "/w/restart-a",
                state = SessionState.running,
                createdAt = SEED_EPOCH_MS + 1,
                providerSessionId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                model = "claude-sonnet-4-5",
            ),
        )
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-restart-b",
                name = "restart-b",
                agent = "codex",
                cwd = "/w/restart-b",
                state = SessionState.ready,
                createdAt = SEED_EPOCH_MS + 2,
                providerSessionId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                model = "gpt-5-codex",
            ),
        )
    },
    terminalUpstream = deterministicUpstream(RESTART_BANNER),
)
