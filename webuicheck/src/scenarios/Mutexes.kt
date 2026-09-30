package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

internal const val MUTEXES_BANNER: String = "KOTGENT-MUTEXES-READY"

/** Starts with no holdings: tests take and queue for mutexes through the `mutex-*` commands. */
fun mutexesScenario(): Scenario = Scenario(
    name = "mutexes",
    seed = { fakes ->
        listOf("builder", "tester", "deployer").forEachIndexed { index, name ->
            fixtureSession(
                fakes, id = "s-$name", name = name, agent = "claude", cwd = "/repo/mutexes",
                createdAt = SEED_EPOCH_MS + index + 1, state = SessionState.running,
            )
        }
    },
    terminalUpstream = deterministicUpstream(MUTEXES_BANNER),
)
