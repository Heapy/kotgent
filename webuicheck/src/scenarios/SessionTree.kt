package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

// Workers run in worktrees outside their orchestrator's folder, so grouping must place them by their parent.
private val TREE_ROWS = listOf(
    harnessSession(
        id = "t-orch",
        name = "orchestrator",
        agent = "claude",
        cwd = "/repo/app",
        state = SessionState.ready,
        createdAt = SEED_EPOCH_MS + 1,
    ),
    harnessSession(
        id = "t-one",
        name = "worker-one",
        agent = "claude",
        cwd = "/wt/one",
        state = SessionState.running,
        createdAt = SEED_EPOCH_MS + 2,
        parentSessionId = "t-orch",
    ),
    harnessSession(
        id = "t-two",
        name = "worker-two",
        agent = "codex",
        cwd = "/wt/two",
        state = SessionState.needs_approval,
        createdAt = SEED_EPOCH_MS + 3,
        parentSessionId = "t-orch",
    ),
    harnessSession(
        id = "t-finished",
        name = "finished",
        agent = "claude",
        cwd = "/repo/app",
        state = SessionState.stopped,
        createdAt = SEED_EPOCH_MS + 4,
        archived = true,
    ),
    harnessSession(
        id = "t-orphan",
        name = "orphan",
        agent = "claude",
        cwd = "/wt/three",
        state = SessionState.needs_answer,
        createdAt = SEED_EPOCH_MS + 5,
        parentSessionId = "t-finished",
    ),
    harnessSession(
        id = "t-solo",
        name = "solo",
        agent = "claude",
        cwd = "/repo/other",
        state = SessionState.ready,
        createdAt = SEED_EPOCH_MS + 6,
    ),
)

fun sessionTreeScenario(): Scenario = Scenario(
    name = "session-tree",
    seed = { fakes ->
        listOf("/repo", "/repo/app", "/repo/other", "/wt", "/wt/one", "/wt/two", "/wt/three")
            .forEach(fakes.projectFs::addDirectory)
        TREE_ROWS.forEach { seedSessionRow(fakes, it) }
    },
    terminalUpstream = deterministicUpstream(SESSIONS_BANNER),
)
