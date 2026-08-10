package io.kotgent.webuicheck.scenarios

import io.kotgent.webuicheck.Scenario

/**
 * `empty` — nothing at all: no sessions, no tasks, no project files, no preferences written.
 *
 * Consumer: the first-run empty states. That makes "seeds nothing" the whole content, not an omission,
 * so the seed is written as an explicit no-op rather than left off the map: a first run is what the
 * operator sees before kotgent has ever done anything, and the UI has real behaviour there (the
 * sidebar's direct "Start a session" action, the empty board, the palette with its session group
 * pruned). A fixture that seeded "just one harmless row" would hide every one of them.
 *
 * [Scenario.terminalUpstream] stays null, and here that is not a limitation: with no session row the
 * terminal WS refuses the upgrade at "no such session" before any bridge is asked for, so this
 * scenario can never reach a pty.
 *
 * The fakes are process-fresh, so there is deliberately no "clear everything" step to write — and a
 * `restart` reuses the same fakes, which means an empty harness stays empty across one.
 */
fun emptyScenario(): Scenario = Scenario(
    name = "empty",
    seed = { },
)
