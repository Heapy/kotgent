package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

fun attentionScenario(): Scenario = Scenario(
    name = "attention",
    seed = { fakes ->
        listOf("/w/quiet", "/w/unread").forEach(fakes.projectFs::addDirectory)
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-quiet",
                name = "quiet",
                agent = "claude",
                cwd = "/w/quiet",
                state = SessionState.ready,
                createdAt = SEED_EPOCH_MS + 1,
                providerSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                model = "claude-sonnet-4-5",
            ),
        )
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-unread",
                name = "unread",
                agent = "codex",
                cwd = "/w/unread",
                state = SessionState.running,
                createdAt = SEED_EPOCH_MS + 2,
                providerSessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                model = "gpt-5-codex",
                lastSeq = 5,
                readCursor = 2,
            ),
        )
    },
    terminalUpstream = deterministicUpstream(ATTENTION_BANNER),
)
