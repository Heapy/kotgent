package io.kotgent.webuicheck

import io.kotgent.webuicheck.scenarios.attentionScenario
import io.kotgent.webuicheck.scenarios.boardScenarios
import io.kotgent.webuicheck.scenarios.emptyScenario
import io.kotgent.webuicheck.scenarios.restartScenario
import io.kotgent.webuicheck.scenarios.sessionsMixedScenario
import io.kotgent.webuicheck.scenarios.sessionsScenario
import io.kotgent.webuicheck.scenarios.terminalScenario
import io.kotgent.webuicheck.scenarios.terminalX10Scenario


private val SCENARIOS: Map<String, Scenario> = buildScenarioRegistry()

val SCENARIO_NAMES: List<String> = SCENARIOS.keys.toList()

fun scenarioByName(name: String): Scenario? = SCENARIOS[name]

private fun buildScenarioRegistry(): Map<String, Scenario> {
    val all = listOf(
        emptyScenario(),
        sessionsScenario(),
        sessionsMixedScenario(),
        attentionScenario(),
        restartScenario(),
        terminalScenario(),
        terminalX10Scenario(),
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
