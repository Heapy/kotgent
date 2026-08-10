package io.kotgent.webuicheck.scenarios

import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.Scenario

/*
 * `deep-link` — the one scenario whose subject is the URL rather than the data.
 *
 * The SPA's router has three screens and the daemon serves the shell for `/`, `/tasks`, `/tasks/{ref}`
 * and `/s/{id}` (`isSpaRoute`). A browser that STARTS on one of the last two is a different code path
 * from one that navigates there: the route is parsed out of `location.pathname` in the initial
 * `useState`, before any socket frame or fetch has landed, and the screen has to resolve against a list
 * that is still empty. So this fixture exists to give both deep links something real to resolve TO, in
 * one scenario — a browser test cannot start on two scenarios at once, and asserting `/s/{id}` in one
 * fixture and `/tasks/{ref}` in another would leave the pair untested together.
 */

/** The uuid of the `deep-link` project. */
internal const val DEEP_LINK_PROJECT_ID: String = "55555555-5555-4555-8555-555555555555"

/** The session `/s/{id}` resolves to. Short and hand-typable, because it is the path a human pastes. */
internal const val DEEP_LINK_SESSION_ID: String = "deep-session"

/** The ref `/tasks/{ref}` resolves to. Its `:` is what makes the route's percent-encoding load-bearing. */
internal const val DEEP_LINK_TASK_REF: String = "local:7"

/**
 * `deep-link` — one project, two cards and one session, wired so the two deep links point at each other.
 *
 * The session holds [DEEP_LINK_TASK_REF], so `/s/deep-session` shows a task badge naming the card and
 * `/tasks/local:7` lists the session among the task's holders. That crossing is the point: each screen
 * has to have resolved the OTHER half of the fixture to render its own, which is exactly what a router
 * test cannot fake with a single row.
 *
 * Two details are deliberate:
 *  - **the ref is `local:7`, not `local:1`** — a ref whose key is also its position would let a test pass
 *    while the router mixed up a segment with an index;
 *  - **there is a second card** (`local:8`) so `/tasks/local:7` is a detail panel over a non-trivial
 *    board, not the only thing on screen. Back from the detail must land on a board that has content.
 *
 * The session is `resumable`, so starting the browser at `/s/deep-session` renders the session view and
 * its resume hint and opens NO terminal socket (see the note in `Board.kt`): the router is what is under
 * test here, and a pty is not part of it.
 */
internal fun deepLinkScenario(): Scenario = Scenario(
    name = "deep-link",
    seed = { fakes ->
        val project = fixtureProject(fakes, DEEP_LINK_PROJECT_ID, "Deep Link Fixture", "/repo/deep")
        val ref = TaskRef(DEEP_LINK_TASK_REF)
        fakes.tasks.seedTask(ref, project, "Route straight to me", state = TaskState.review)
        fakes.tasks.seedTask(TaskRef("local:8"), project, "The card behind it")
        // One feed row, so the detail panel a deep link opens has something below the header: an empty
        // feed and a feed that failed to load look alike on screen, and only one of them is correct.
        fakes.tasks.seedActivity(ref, ActivityKind.created, author = "board")

        fixtureSession(
            fakes, id = DEEP_LINK_SESSION_ID, name = "deep-link", agent = "claude", cwd = "/repo/deep",
            project = project, taskRef = ref,
        )
    },
)
