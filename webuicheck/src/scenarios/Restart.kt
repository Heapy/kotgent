package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

/**
 * `restart` — two live sessions whose store keeps reporting them ALIVE after the harness restarts.
 * Consumer: the terminal reattach test.
 *
 * ## Why "still alive" is the whole content
 * The browser's reattach rule is a distinction between answers, not a retry loop: a definitive answer
 * about this session (a `4xx`) DESTROYS the one remembered candidate, while a transient condition (an
 * unreachable daemon, a control action in flight) KEEPS it. A restart is supposed to exercise the
 * second branch — socket dies, the events socket re-opens, that re-open grants an attempt, the attempt
 * reads liveness and reattaches. If the session came back dead or missing, the attempt would consume
 * the candidate through the DEFINITIVE branch instead, and the test would be quietly demonstrating the
 * opposite invariant while still going green.
 *
 * Nothing has to be done to achieve it, and that is worth saying out loud: a restart re-stands the
 * server over the SAME fakes, so the rows simply persist. `SessionDto.alive` is derived from the
 * state alone (`ControlRoutes.kt`), so `running` and `ready` are both alive with no tmux pane needed —
 * which is fortunate, because the harness runs no tmux server and `FakeTmux` only grows panes through
 * `newSession`. What this scenario must NOT do is seed a dead state, and the two rows below are the
 * two live shapes.
 *
 * ## Why there are two
 * "Switching to another session destroys the candidate" is one of the branches under test, and it
 * needs a second session to switch TO — one that is itself attachable, so the switch is a real
 * attachment and not a failed one. It also now moves the route (`/s/{id}`), which is part of the same
 * assertion.
 *
 * ## What the restart does to the terminal, and what may therefore be asserted
 * Stopping the server tears the terminal bridges down FIRST, which kills the pty child. So "the
 * terminal came back with its previous contents" is false by construction — the reattach opens a
 * BRAND NEW `/bin/sh`, which prints the banner again from scratch. The honest assertion is that new
 * bytes arrive: wait for [RESTART_BANNER] a second time. A driver that wants an unambiguous
 * discriminator can write a marker into the pty before the restart (`POST /sessions/{id}/input`, which
 * `cat` echoes) — the marker cannot survive a new child, while the banner cannot fail to reappear.
 */
fun restartScenario(): Scenario = Scenario(
    name = "restart",
    seed = { fakes ->
        listOf("/w/restart-a", "/w/restart-b").forEach(fakes.projectFs::addDirectory)
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-restart-a",
                name = "restart-a",
                agent = "claude",
                cwd = "/w/restart-a",
                state = SessionState.running,
                createdAt = SEED_EPOCH_MS + 1,
                providerSessionId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                model = "claude-sonnet-4-5",
            ),
        )
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-restart-b",
                name = "restart-b",
                agent = "codex",
                cwd = "/w/restart-b",
                state = SessionState.ready,
                createdAt = SEED_EPOCH_MS + 2,
                providerSessionId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                model = "gpt-5-codex",
            ),
        )
    },
    terminalUpstream = deterministicUpstream(RESTART_BANNER),
)
