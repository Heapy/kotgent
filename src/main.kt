package io.kotgent

import io.kotgent.cli.runCli
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = runCli(args)
    if (code != 0) exitProcess(code)
}
