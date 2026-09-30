package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.Scenario


internal const val WORKSPACE_PROJECT_ID: String = "99999999-9999-4999-8999-999999999999"

internal const val WORKSPACE_BANNER: String = "KOTGENT-WORKSPACE-READY"

fun workspaceScenario(): Scenario = Scenario(
    name = "workspace",
    seed = { fakes ->
        val project = fixtureProject(fakes, WORKSPACE_PROJECT_ID, "Workspace Fixture", "/repo/workspace")
        fakes.taskStore.seedTask(
            TaskRef("local:1"), project, "Split the session screen", state = TaskState.in_progress,
        )
        fixtureSession(
            fakes, id = "s-work", name = "splitter", agent = "claude", cwd = "/repo/workspace",
            createdAt = SEED_EPOCH_MS + 1, project = project, taskRef = TaskRef("local:1"),
            state = SessionState.running,
        )
        fixtureSession(
            fakes, id = "s-free", name = "unlinked", agent = "claude", cwd = "/repo/workspace",
            createdAt = SEED_EPOCH_MS + 2, project = project, state = SessionState.running,
        )
    },
    terminalUpstream = deterministicUpstream(WORKSPACE_BANNER),
)
