package io.kotgent.webuicheck.scenarios

import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.Scenario


internal const val DEEP_LINK_PROJECT_ID: String = "55555555-5555-4555-8555-555555555555"

internal const val DEEP_LINK_SESSION_ID: String = "deep-session"

internal const val DEEP_LINK_TASK_REF: String = "local:7"

internal fun deepLinkScenario(): Scenario = Scenario(
    name = "deep-link",
    seed = { fakes ->
        val project = fixtureProject(fakes, DEEP_LINK_PROJECT_ID, "Deep Link Fixture", "/repo/deep")
        val ref = TaskRef(DEEP_LINK_TASK_REF)
        fakes.tasks.seedTask(ref, project, "Route straight to me", state = TaskState.review)
        fakes.tasks.seedTask(TaskRef("local:8"), project, "The card behind it")
        fakes.tasks.seedActivity(ref, ActivityKind.created, author = BOARD_ACTOR)

        fixtureSession(
            fakes, id = DEEP_LINK_SESSION_ID, name = "deep-link", agent = "claude", cwd = "/repo/deep",
            createdAt = SEED_EPOCH_MS + 1, project = project, taskRef = ref,
        )
    },
)
