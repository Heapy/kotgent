package io.kotgent.webuicheck

import io.kotgent.webuicheck.scenarios.attentionScenario
import io.kotgent.webuicheck.scenarios.boardScenarios
import io.kotgent.webuicheck.scenarios.emptyScenario
import io.kotgent.webuicheck.scenarios.restartScenario
import io.kotgent.webuicheck.scenarios.sessionsScenario
import io.kotgent.webuicheck.scenarios.terminalScenario

/*
 * The scenario registry — ONE map, and the only list of scenario names that exists.
 *
 * A second list (a `SCENARIO_NAMES` literal beside the map, a `when` in the argument parser, a copy in
 * the usage text) is the failure mode this file is shaped to prevent: it drifts silently, and the way
 * it announces itself is `--scenario=board-empty` exiting with "unknown scenario" long after somebody
 * wrote the scenario. So the names are DERIVED from the map, and a scenario becomes reachable by being
 * registered here and by nothing else.
 *
 * The board half lives in `scenarios/Board.kt` and is reached through `boardScenarios()`. Both halves
 * follow this repository's directory-mirrors-package rule (`src/core/` is `io.kotgent.core`,
 * `fakes/src/store/` is `io.kotgent.store`), so everything under `webuicheck/src/scenarios/` declares
 * `io.kotgent.webuicheck.scenarios` — the imports above are the only lines that depend on it.
 */

/**
 * Every scenario, keyed by the name `--scenario=<name>` takes. Insertion-ordered, so [SCENARIO_NAMES]
 * (and therefore any usage message built from it) reads in a deliberate order rather than a hash one:
 * the five session scenarios first, then the board's.
 */
private val SCENARIOS: Map<String, Scenario> = buildScenarioRegistry()

/**
 * The registered scenario names, in registration order. Derived from [SCENARIOS] — see the note above
 * on why this is not a literal.
 */
val SCENARIO_NAMES: List<String> = SCENARIOS.keys.toList()

/** The scenario called [name], or `null` when nothing is registered under it (an unknown `--scenario`). */
fun scenarioByName(name: String): Scenario? = SCENARIOS[name]

/**
 * Fold every scenario into one map, failing loudly on a duplicate name.
 *
 * The board scenarios arrive as a LIST from their own file rather than being spelled out here, because
 * they and this registry are written by different hands: naming them here would put their names in two
 * places again, one of which cannot see the other. A duplicate is a hard [IllegalStateException] at
 * first use rather than a silent last-one-wins, because the loser would simply never run — the harness
 * would start, serve the wrong fixture and every assertion over it would be about a screen nobody
 * meant.
 */
private fun buildScenarioRegistry(): Map<String, Scenario> {
    val all = listOf(
        emptyScenario(),
        sessionsScenario(),
        attentionScenario(),
        restartScenario(),
        terminalScenario(),
    ) + boardScenarios()
    val registry = LinkedHashMap<String, Scenario>(all.size)
    for (scenario in all) {
        val clash = registry.put(scenario.name, scenario)
        check(clash == null) {
            "two scenarios are registered under the name '${scenario.name}' — one of them would never " +
                "run and the harness would silently serve the other"
        }
    }
    return registry
}
