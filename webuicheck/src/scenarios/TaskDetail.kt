package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.Scenario


internal const val TASK_DETAIL_PROJECT_ID: String = "33333333-3333-4333-8333-333333333333"

internal const val TASK_LINKED_PROJECT_ID: String = "44444444-4444-4444-8444-444444444444"

internal const val TASK_LINK_PICKER_PROJECT_ID: String = "66666666-6666-4666-8666-666666666666"

internal const val TASK_LINK_PICKER_EMPTY_PROJECT_ID: String = "77777777-7777-4777-8777-777777777777"

internal const val TASK_LINK_PICKER_FOREIGN_PROJECT_ID: String = "88888888-8888-4888-8888-888888888888"

internal fun taskDetailScenario(): Scenario = Scenario(
    name = "task-detail",
    seed = { fakes ->
        val project = fixtureProject(fakes, TASK_DETAIL_PROJECT_ID, "Detail Fixture", "/repo/detail")
        val tasks = fakes.taskStore
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
        fakes.taskStore.seedTask(
            TaskRef("local:1"), project, "Land the push worker", state = TaskState.in_progress,
        )
        fakes.taskStore.seedTask(TaskRef("local:2"), project, "Sweep the fixtures")

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

internal fun taskLinkPickerScenario(): Scenario = Scenario(
    name = "task-link-picker",
    seed = { fakes ->
        val project = fixtureProject(
            fakes,
            TASK_LINK_PICKER_PROJECT_ID,
            "Link Picker Fixture",
            "/repo/link-picker",
        )
        val emptyProject = fixtureProject(
            fakes,
            TASK_LINK_PICKER_EMPTY_PROJECT_ID,
            "Empty Link Fixture",
            "/repo/link-empty",
        )
        val foreignProject = fixtureProject(
            fakes,
            TASK_LINK_PICKER_FOREIGN_PROJECT_ID,
            "Foreign Link Fixture",
            "/repo/link-foreign",
        )
        val tasks = fakes.taskStore
        tasks.seedTask(TaskRef("local:3"), project, "Zulu queued task", position = 3.0)
        tasks.seedTask(
            TaskRef("local:2"), project, "Beta same-rank task", position = 1.0,
            createdAt = SEED_EPOCH_MS + 1,
        )
        tasks.seedTask(
            TaskRef("local:1"), project, "Alpha same-rank task", position = 1.0,
            createdAt = SEED_EPOCH_MS + 2,
        )
        tasks.seedTask(
            TaskRef("local:4"), project, "Continue the index", state = TaskState.in_progress,
            position = 0.5,
        )
        tasks.seedTask(
            TaskRef("local:5"), project, "Review the parser", state = TaskState.review,
            position = 0.1,
        )
        tasks.seedTask(
            TaskRef("local:6"), project, "Already shipped", state = TaskState.done,
            position = 0.01,
        )
        tasks.seedTask(TaskRef("local:7"), foreignProject, "Another project's task")
        tasks.seedDependency(TaskRef("local:3"), TaskRef("local:4"))

        fixtureSession(
            fakes, id = "link-live", name = "linkable", agent = "codex", cwd = "/repo/link-picker",
            createdAt = SEED_EPOCH_MS + 1, project = project, state = SessionState.ready,
        )
        fixtureSession(
            fakes, id = "link-stopped", name = "stopped-link", agent = "claude",
            cwd = "/repo/link-picker", createdAt = SEED_EPOCH_MS + 2, project = project,
        )
        fixtureSession(
            fakes, id = "link-linked", name = "already-linked", agent = "claude",
            cwd = "/repo/link-picker", createdAt = SEED_EPOCH_MS + 3, project = project,
            taskRef = TaskRef("local:4"), state = SessionState.ready,
        )
        fixtureSession(
            fakes, id = "link-no-project", name = "projectless", agent = "shell", cwd = "/tmp",
            createdAt = SEED_EPOCH_MS + 4, state = SessionState.ready,
        )
        fixtureSession(
            fakes, id = "link-empty", name = "empty-project", agent = "shell", cwd = "/repo/link-empty",
            createdAt = SEED_EPOCH_MS + 5, project = emptyProject, state = SessionState.ready,
        )
    },
)

internal const val BOARD_ACTOR: String = "board"
