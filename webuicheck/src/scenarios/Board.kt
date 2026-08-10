package io.kotgent.webuicheck.scenarios

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.task.PROJECT_FILE_NAME
import io.kotgent.task.ProjectFile
import io.kotgent.task.TaskState
import io.kotgent.task.projectFileText
import io.kotgent.webuicheck.HarnessFakes
import io.kotgent.webuicheck.Scenario

/*
 * The five task-layer scenarios: `board`, `board-empty`, `task-detail`, `task-linked-session` and
 * `deep-link`. Their builders are spread over three files (this one, `TaskDetail.kt`, `DeepLink.kt`)
 * purely so three concerns read separately; [boardScenarios] is the single seam the harness's registry
 * folds in, and the only declaration here anything outside these files may name.
 *
 * ## The package mirrors the directory, and the harness's does not
 * These three files declare `package io.kotgent.webuicheck.scenarios`, following the repo's
 * directory-as-area rule, while `Scenario`/`HarnessFakes`/`HarnessContext` live one package up in
 * `io.kotgent.webuicheck` — so the registry's `import …scenarios.boardScenarios` is the one line that
 * couples the two halves, and `TaskCommands.kt` (a sibling of the harness, not of the fixtures) stays up
 * there with it. Every helper below is `internal` or file-`private`: nothing here may collide with a name
 * another scenario file in this package declares.
 *
 * ## What every scenario here deliberately does NOT do
 * **No session is seeded alive.** Selecting a live session in the Web UI sets `attachedId` and opens the
 * terminal socket (`app.js`), which would drive the harness's `TerminalBridge` — a real pty — from a
 * board test that has no business starting one. Every session below is therefore `resumable`: it renders
 * in the sidebar, carries its task badge, and answers `/s/{id}`, while the terminal path stays the
 * `terminal` scenario's alone. The visible cost is that the session view shows the resume hint instead of
 * a terminal, which is exactly what a router assertion wants anyway.
 *
 * **Positions are never spelled.** [io.kotgent.store.FakeTaskStore.seedTask] defaults a card to the end
 * of its PROJECT's column (not its state column), so seeding in listing order lays the whole project out
 * at `1.0, 2.0, 3.0, …` with a gap of `1.0` between neighbours. That is what makes a drag subdividable
 * (`positionBetween` lands at `x.5`) without ever reaching the renormalizing branch, and it means the
 * order a scenario reads in this file is the order the board renders.
 *
 * **Seeds emit nothing.** The `seed*` members of the fakes take no lock and publish no update: they run
 * while the scenario is assembled, before the server binds, so nothing here can be observed as if an
 * operator had just typed it. Every observable change in a browser test therefore comes from the browser
 * or from a `TaskCommands.kt` command, never from the fixture.
 */

/** The uuid of the `board` project — spelled out so a browser test can name it in a `?project=`. */
internal const val BOARD_PROJECT_ID: String = "11111111-1111-4111-8111-111111111111"

/** The uuid of the `board-empty` project. */
internal const val BOARD_EMPTY_PROJECT_ID: String = "22222222-2222-4222-8222-222222222222"

/**
 * Every task scenario the harness registry serves, in the order the plan lists them.
 *
 * The signature is frozen across the wave: the registry folds this list into its one
 * `Map<String, Scenario>`, so a rename here is a rename in a file this one may not open.
 */
fun boardScenarios(): List<Scenario> = listOf(
    boardScenario(),
    boardEmptyScenario(),
    taskDetailScenario(),
    taskLinkedSessionScenario(),
    deepLinkScenario(),
)

/**
 * `board` — one project with ten cards spread over all four columns.
 *
 * The shape is chosen by what a drag test needs rather than by what looks like a backlog:
 *  - **four cards in `todo`** so a within-column move has a neighbour above AND below the card it
 *    passes, which is the only arrangement in which a `before`/`after` target can be got wrong;
 *  - **two in `in_progress`, one in `review`, two in `done`** so every column is a live drop target and
 *    none of them is empty-state — an empty column and a populated one take different code paths in
 *    `Board.js`, and only the populated one can prove ordering;
 *  - **one blocked card** (`local:10`, waiting on the `in_progress` `local:5`) because `blocked` is
 *    DERIVED and there is no knob to force it: the marker exists only if the data supports it, and the
 *    card that carries it must be `todo` (the derivation's own precondition).
 *
 * `local:3` is the designated `task-race` target: nothing depends on it and it depends on nothing, so a
 * command-driven transition of it emits exactly ONE observation. A card with reverse dependents would
 * re-stamp them too, and the newest-rev-wins assertion could then pass on the wrong row's frame.
 */
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
        // Seeded last, but order-insensitive: `seedDependency` re-derives `blocked` across the whole
        // tree, so an edge may name a card either side of itself.
        tasks.seedDependency(TaskRef("local:10"), TaskRef("local:5"))
    },
)

/**
 * `board-empty` — a registered project with no cards at all.
 *
 * A separate scenario rather than "the `board` project's second, empty project": the board auto-selects
 * the alphabetically first project (`app.js` takes `projects[0]` of the name-sorted `GET /projects`), so
 * an empty second project would never be the one on screen, and a test would have to drive the selector
 * before it could assert the empty state. One project per scenario keeps the selection out of it.
 *
 * The project file is still published into the fake filesystem (see [fixtureProject]), so a "create a
 * project here" test at `/repo/empty` adopts this uuid instead of minting a second one.
 */
private fun boardEmptyScenario(): Scenario = Scenario(
    name = "board-empty",
    seed = { fakes ->
        fixtureProject(fakes, BOARD_EMPTY_PROJECT_ID, "Empty Fixture", "/repo/empty")
    },
)

/**
 * Register a project in the task store AND publish its `.kotgent.json` into the fake filesystem.
 *
 * Both halves, because the two are one fact about a project and a fixture that carried only the store row
 * would answer differently depending on which door a test came in by: `GET /projects` would know the
 * project while `POST /projects` at its own path would mint a SECOND uuid for the same directory —
 * precisely the convergence [io.kotgent.task.MemoryProjectFileWriter] models (an existing file always
 * wins, by being parsed). Writing the file also declares every ancestor a directory, which is what makes
 * the writer's `isDirectory` gate pass for [path].
 *
 * The bytes come from the production [projectFileText], not a hand-rolled literal: the fake writer parses
 * what it finds, so a fixture spelling its own JSON would be testing the fixture's spelling.
 */
internal fun fixtureProject(
    fakes: HarnessFakes,
    uuid: String,
    name: String,
    path: String,
): ProjectId {
    val id = ProjectId.of(uuid)
    fakes.tasks.seedProject(id, name, path)
    fakes.projectFs.writeFile("$path/$PROJECT_FILE_NAME", projectFileText(ProjectFile(id, name)))
    return id
}

/**
 * Seed one session row.
 *
 * [state] defaults to [SessionState.resumable] — dead, so the Web UI never attaches a terminal to it (see
 * the file header). A caller that wants a live row must say so, and owes the terminal upstream that goes
 * with it.
 *
 * The timestamps are fixed rather than read from a clock: the sidebar groups by cwd and sorts the tree by
 * path, so seed order is what decides a row's position within its group, and a wall-clock value would
 * only make a screenshot differ per run.
 */
internal suspend fun fixtureSession(
    fakes: HarnessFakes,
    id: String,
    name: String,
    agent: String,
    cwd: String,
    project: ProjectId? = null,
    taskRef: TaskRef? = null,
    state: SessionState = SessionState.resumable,
) {
    fakes.events.upsertSession(
        SessionMeta(
            id = SessionId(id),
            name = name,
            agent = agent,
            cwd = cwd,
            tmuxSession = "kt-$id",
            state = state,
            createdAt = FIXTURE_CLOCK_MS,
            updatedAt = FIXTURE_CLOCK_MS,
            taskRef = taskRef,
            projectId = project,
        ),
    )
}

/**
 * The one timestamp every seeded session row carries.
 *
 * It matches [io.kotgent.store.FakeTaskStore]'s own default clock so a seeded card and a seeded session
 * describe the same instant; nothing in the Web UI renders a session's `updatedAt` as an age, so the
 * value only has to be stable, not plausible.
 */
internal const val FIXTURE_CLOCK_MS: Long = 1_000L
