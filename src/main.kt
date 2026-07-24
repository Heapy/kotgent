package io.kotgent

import io.kotgent.cli.runCli
import kotlin.system.exitProcess

const val VERSION = "0.1.1"

fun versionLine(): String = "kotgent $VERSION"

/**
 * CLI entry point (plan Task 15): dispatch `argv` to a subcommand (`daemon`, `start`, `list`, `stop`,
 * `resume`, `interrupt`, `attach`, `install`/`uninstall`), keeping `--version`. Parsing + dispatch live
 * in [runCli] (`io.kotgent.cli`); this just forwards argv and maps the returned exit code to the process.
 */
fun main(args: Array<String>) {
    val code = runCli(args)
    if (code != 0) exitProcess(code)
}
