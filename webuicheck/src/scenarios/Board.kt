package io.kotgent.webuicheck.scenarios

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.task.PROJECT_FILE_NAME
import io.kotgent.task.ProjectFile
import io.kotgent.task.TaskState
import io.kotgent.task.projectFileText
import io.kotgent.webuicheck.HarnessFakes
import io.kotgent.webuicheck.Scenario


internal const val BOARD_PROJECT_ID: String = "11111111-1111-4111-8111-111111111111"

internal const val BOARD_EMPTY_PROJECT_ID: String = "22222222-2222-4222-8222-222222222222"

internal const val BOARD_PROJECTS_SELECTED_ID: String = "33333333-3333-4333-8333-333333333333"

internal const val BOARD_PROJECTS_SPARE_ID: String = "44444444-4444-4444-8444-444444444444"

internal const val BOARD_PROJECTS_DELETED_ID: String = "55555555-5555-4555-8555-555555555555"

fun boardScenarios(): List<Scenario> = listOf(
    boardScenario(),
    boardEmptyScenario(),
    boardProjectsScenario(),
    taskDetailScenario(),
    taskLinkedSessionScenario(),
    deepLinkScenario(),
)

private fun boardScenario(): Scenario = Scenario(
    name = "board",
    seed = { fakes ->
        val project = fixtureProject(fakes, BOARD_PROJECT_ID, "Board Fixture", "/repo/board")
        val tasks = fakes.tasks
        tasks.seedTask(TaskRef("local:1"), project, "Write the drag handler")
        tasks.seedTask(TaskRef("local:2"), project, "Wire the socket frames")
        tasks.seedTask(TaskRef("local:3"), project, "Measure the swipe")
        tasks.seedTask(TaskRef("local:4"), project, "Ship the release notes")
        tasks.seedTask(TaskRef("local:5"), project, "Rebuild the board layout", state = TaskState.in_progress)
        tasks.seedTask(TaskRef("local:6"), project, "Trim the sidebar", state = TaskState.in_progress)
        tasks.seedTask(TaskRef("local:7"), project, "Audit the token rotation", state = TaskState.review)
        tasks.seedTask(TaskRef("local:8"), project, "Delete the grep tier", state = TaskState.done)
        tasks.seedTask(TaskRef("local:9"), project, "Pin the isolation test", state = TaskState.done)
        tasks.seedTask(TaskRef("local:10"), project, "Publish the plugin")
        tasks.seedDependency(TaskRef("local:10"), TaskRef("local:5"))
    },
)

private fun boardEmptyScenario(): Scenario = Scenario(
    name = "board-empty",
    seed = { fakes ->
        fixtureProject(fakes, BOARD_EMPTY_PROJECT_ID, "Empty Fixture", "/repo/empty")
    },
)

/* Distinct card counts cover delete selection, singular copy, and restoring a non-empty backlog. */
private fun boardProjectsScenario(): Scenario = Scenario(
    name = "board-projects",
    seed = { fakes ->
        val selected = fixtureProject(fakes, BOARD_PROJECTS_SELECTED_ID, "Alpha Fixture", "/repo/alpha")
        val spare = fixtureProject(fakes, BOARD_PROJECTS_SPARE_ID, "Beta Fixture", "/repo/beta")
        val deleted =
            fixtureProject(fakes, BOARD_PROJECTS_DELETED_ID, "Gamma Fixture", "/repo/gamma", archived = true)
        val tasks = fakes.tasks
        tasks.seedTask(TaskRef("local:1"), selected, "Rename the release branch")
        tasks.seedTask(TaskRef("local:2"), selected, "Sweep the descriptors")
        tasks.seedTask(TaskRef("local:3"), selected, "Bound the reader join", state = TaskState.in_progress)
        tasks.seedTask(TaskRef("local:4"), spare, "Hold the repaint")
        tasks.seedTask(TaskRef("local:5"), deleted, "Fold the leftover branch in")
        tasks.seedTask(TaskRef("local:6"), deleted, "Retire the old socket", state = TaskState.review)
    },
)

// Archived fixtures keep their project file because deletion tombstones only the database row.
internal fun fixtureProject(
    fakes: HarnessFakes,
    uuid: String,
    name: String,
    path: String,
    archived: Boolean = false,
): ProjectId {
    val id = ProjectId.of(uuid)
    fakes.tasks.seedProject(id, name, path, archived)
    fakes.projectFs.writeFile("$path/$PROJECT_FILE_NAME", projectFileText(ProjectFile(id, name)))
    return id
}

internal suspend fun fixtureSession(
    fakes: HarnessFakes,
    id: String,
    name: String,
    agent: String,
    cwd: String,
    createdAt: Long,
    project: ProjectId? = null,
    taskRef: TaskRef? = null,
    state: SessionState = SessionState.resumable,
) {
    seedSessionRow(
        fakes,
        harnessSession(
            id = id,
            name = name,
            agent = agent,
            cwd = cwd,
            state = state,
            createdAt = createdAt,
        ).copy(taskRef = taskRef, projectId = project),
    )
}
