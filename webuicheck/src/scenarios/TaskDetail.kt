package io.kotgent.webuicheck.scenarios

import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.Scenario


internal const val TASK_DETAIL_PROJECT_ID: String = "33333333-3333-4333-8333-333333333333"

internal const val TASK_LINKED_PROJECT_ID: String = "44444444-4444-4444-8444-444444444444"

internal fun taskDetailScenario(): Scenario = Scenario(
    name = "task-detail",
    seed = { fakes ->
        val project = fixtureProject(fakes, TASK_DETAIL_PROJECT_ID, "Detail Fixture", "/repo/detail")
        val tasks = fakes.tasks
        tasks.seedTask(TaskRef("local:1"), project, "Design the schema", state = TaskState.done)
        tasks.seedTask(TaskRef("local:2"), project, "Provision the runner", state = TaskState.in_progress)
        tasks.seedTask(TaskRef("local:3"), project, "Wire the detail panel")
        tasks.seedTask(TaskRef("local:4"), project, "Announce the rollout")

        tasks.seedDependency(TaskRef("local:3"), TaskRef("local:1"))
        tasks.seedDependency(TaskRef("local:3"), TaskRef("local:2"))
        tasks.seedDependency(TaskRef("local:4"), TaskRef("local:3"))

        tasks.seedActivity(TaskRef("local:1"), ActivityKind.created, author = BOARD_ACTOR)
        tasks.seedActivity(
            TaskRef("local:1"), ActivityKind.transition, author = BOARD_ACTOR,
            text = "Schema is in.", fromState = TaskState.todo, toState = TaskState.done,
        )
        tasks.seedActivity(TaskRef("local:3"), ActivityKind.created, author = BOARD_ACTOR)
        tasks.seedActivity(
            TaskRef("local:3"), ActivityKind.comment, author = BOARD_ACTOR,
            text = "The panel floats above the board; it must not squeeze the columns.",
        )
        tasks.seedActivity(
            TaskRef("local:3"), ActivityKind.comment, author = "s-detail-1",
            text = "Waiting on the runner before I start.",
        )
    },
)

internal fun taskLinkedSessionScenario(): Scenario = Scenario(
    name = "task-linked-session",
    seed = { fakes ->
        val project = fixtureProject(fakes, TASK_LINKED_PROJECT_ID, "Linked Fixture", "/repo/linked")
        fakes.tasks.seedTask(
            TaskRef("local:1"), project, "Land the push worker", state = TaskState.in_progress,
        )
        fakes.tasks.seedTask(TaskRef("local:2"), project, "Sweep the fixtures")

        fixtureSession(
            fakes, id = "s-linked-1", name = "push-worker", agent = "claude", cwd = "/repo/linked",
            createdAt = SEED_EPOCH_MS + 1, project = project, taskRef = TaskRef("local:1"),
        )
        fixtureSession(
            fakes, id = "s-linked-2", name = "sweeper", agent = "codex", cwd = "/repo/linked",
            createdAt = SEED_EPOCH_MS + 2, project = project,
        )
        fixtureSession(
            fakes, id = "s-linked-3", name = "ghost", agent = "shell", cwd = "/repo/linked",
            createdAt = SEED_EPOCH_MS + 3, project = project, taskRef = TaskRef("local:404"),
        )
    },
)

internal const val BOARD_ACTOR: String = "board"
