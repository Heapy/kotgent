package io.kotgent.webuicheck.scenarios

import io.kotgent.webuicheck.Scenario

fun emptyScenario(): Scenario = Scenario(
    name = "empty",
    seed = { },
)
