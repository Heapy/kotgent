package io.kotgent.webuicheck.scenarios

import io.kotgent.core.UsageObservation
import io.kotgent.webuicheck.Scenario

// Shared with the browser fixture by the scenario contract, independent of the machine's clock.
private const val USAGE_EPOCH: Long = 1_800_000_000_000L

fun usageScenario(): Scenario = Scenario(
    name = "usage",
    seed = { fakes ->
        val fiveHours = 5 * 60 * 60L
        val week = 7 * 24 * 60 * 60L
        for (observation in listOf(
            UsageObservation("claude", "five_hour", 23.0, USAGE_EPOCH + fiveHours * 1_000, fiveHours),
            UsageObservation("claude", "seven_day", 41.0, USAGE_EPOCH + week * 1_000, week),
            UsageObservation("codex", "primary", 58.0, USAGE_EPOCH + week * 1_000, week),
        )) {
            fakes.usage.observe(observation, observedAt = USAGE_EPOCH)
        }
    },
)
