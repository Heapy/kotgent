package io.kotgent.webuicheck.scenarios

import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.Scenario

/*
 * The two scenarios that are about a task's SURROUNDINGS rather than its column: `task-detail` (a feed
 * and both directions of a dependency edge) and `task-linked-session` (the session→task badge).
 *
 * Both are consumed by tests that open one card and read what the daemon says about it, so the fixtures
 * are deliberately small and every row in them is there to make a specific rendering branch reachable.
 */

/** The uuid of the `task-detail` project. */
internal const val TASK_DETAIL_PROJECT_ID: String = "33333333-3333-4333-8333-333333333333"

/** The uuid of the `task-linked-session` project. */
internal const val TASK_LINKED_PROJECT_ID: String = "44444444-4444-4444-8444-444444444444"

/**
 * `task-detail` — four cards arranged so `local:3` has a dependency in each direction and a feed.
 *
 * ```
 * local:1  done         ─┐
 * local:2  in_progress  ─┼→ local:3  todo, BLOCKED  ←─ local:4  todo, BLOCKED
 * ```
 *
 * The two dependencies of `local:3` are deliberately in DIFFERENT states: `blocked` is "todo with some
 * dependency that is not done", so a fixture whose edges were all satisfied (or all unsatisfied) could
 * not tell the derivation from a constant. `local:4` exists because `GET /tasks/{ref}` carries
 * `dependents` as well as `dependsOn`, and a panel that rendered only one of the two lists would look
 * correct against a fixture that only had one.
 *
 * The feed carries three kinds, because the detail view maps each to its own sentence:
 *  - a `created` row, which every task has;
 *  - a `comment` authored by the board — the human's own note;
 *  - a `comment` authored by `s-detail-1`, a session id NO row exists for. That is not an oversight: an
 *    activity feed outlives the sessions that wrote into it (a session is archived, the daemon restarts,
 *    the feed stays), so the author string must render on its own, and a fixture whose every author
 *    resolved would never exercise that.
 *
 * `local:1` additionally carries a `transition` row with a message, the one activity kind that has a
 * from/to pair to render and the only one a `PATCH` with `-m` can produce.
 */
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

/**
 * `task-linked-session` — two cards and three sessions, one per badge outcome.
 *
 * ```
 * s-linked-1  push-worker  → local:1   the ordinary badge, resolved to a title
 * s-linked-2  sweeper      → (none)    the row a link test links FROM
 * s-linked-3  ghost        → local:404 a DANGLING ref: `task-badge-unknown`
 * ```
 *
 * The dangling ref is a modelled state, not a mistake: `sessions.task_ref` is a reference and not a
 * foreign key, so a task deleted while a link write was in flight leaves one behind, and `taskBadge`
 * renders it as the bare ref under a different class. A fixture with only resolvable links could not
 * reach that arm.
 *
 * `s-linked-2` carries no link because the browser has no link CONTROL — `POST /tasks/{ref}/link` needs a
 * caller identity the Web UI never sends, so a driver stages a link by naming `sessionId` in the request
 * body itself. That is a real, authenticated call and its `SessionUpdate` moves the badge with no reload,
 * which is the whole point of the scenario.
 *
 * The two unlink paths are reachable from the harness instead: `task local:1 done` runs the production
 * `TaskService.transition`, which unlinks every holder after closing the card, and `task-del local:1`
 * unlinks them before deleting it. Both clear `s-linked-1`'s badge live.
 */
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

/**
 * The `author` a seeded activity row records for a change with no session behind it.
 *
 * Spelled here rather than referenced from `TaskService.BOARD_AUTHOR` on purpose: this is FIXTURE data
 * describing a feed a human wrote, and binding it to the daemon's constant would make a rename of that
 * constant silently rewrite the fixture a browser assertion reads. A harness COMMAND deliberately signs
 * its own name instead (`TASK_COMMAND_AUTHOR`), so a feed row a test caused is never mistaken for one the
 * fixture shipped with.
 */
internal const val BOARD_ACTOR: String = "board"
