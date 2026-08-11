package io.kotgent.webuicheck

import io.kotgent.cli.eprintln
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.write

// Driver protocol: only PORT, TICKET, READY (and self-check SUMMARY) reach stdout. Diagnostics and
// stray println calls are redirected to stderr before any other work begins.
fun main(args: Array<String>) {
    claimStdout()

    val options = parseArgs(args) ?: exitProcess(EXIT_USAGE)
    if (options.selfCheck) exitProcess(runSelfCheck(selfCheckCases()))

    val scenario = scenarioByName(options.scenario)
    if (scenario == null) {
        eprintln("webuicheck: unknown scenario '${options.scenario}'")
        eprintln("webuicheck: known scenarios: ${SCENARIO_NAMES.joinToString(", ")}")
        exitProcess(EXIT_USAGE)
    }

    val harness = Harness(scenario, options.webUiDir)
    val (context, ticket) = runBlocking { harness.start() to harness.issueTicket() }
    writeStdoutLine("PORT=${context.port}")
    writeStdoutLine("TICKET=$ticket")
    writeStdoutLine(READY_LINE)
    options.exitAfterMs?.let { startWatchdog(it) }

    val exitCode = readCommands(context)
    runBlocking { harness.stop() }
    exitProcess(exitCode)
}

// Keep the stdin loop off a coroutine context: restart reaches server stop(), which nests runBlocking.
private fun readCommands(context: HarnessContext): Int {
    while (true) {
        val line = readlnOrNull() ?: return EXIT_OK
        val command = line.trim()
        if (command.isEmpty()) continue
        val handled = try {
            handleCommand(command, context)
        } catch (e: Throwable) {
            eprintln("webuicheck: command '$command' failed: ${e::class.simpleName}: ${e.message}")
            return EXIT_BAD_INPUT
        }
        if (!handled) {
            eprintln("webuicheck: unrecognised command '$command'")
            return EXIT_BAD_INPUT
        }
    }
}

// A crashed driver never closes stdin; force process exit so its port and pty cannot be orphaned.
private fun startWatchdog(afterMs: Long) {
    watchdogScope.launch {
        delay(afterMs)
        eprintln("webuicheck: --exit-after-ms=$afterMs elapsed with no EOF on stdin; exiting")
        exitProcess(EXIT_WATCHDOG)
    }
}

private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private class Options(
    val selfCheck: Boolean,
    val scenario: String,
    val webUiDir: String?,
    val exitAfterMs: Long?,
)

private fun parseArgs(args: Array<String>): Options? {
    var selfCheck = false
    var scenario: String? = null
    var webUiDir: String? = null
    var exitAfterMs: Long? = null

    for (arg in args) {
        when {
            arg == "--self-check" -> selfCheck = true
            arg.startsWith(SCENARIO_FLAG) -> scenario = arg.removePrefix(SCENARIO_FLAG)
            arg.startsWith(WEBUI_DIR_FLAG) -> webUiDir = arg.removePrefix(WEBUI_DIR_FLAG)
            arg.startsWith(EXIT_AFTER_FLAG) -> {
                val raw = arg.removePrefix(EXIT_AFTER_FLAG)
                val parsed = raw.toLongOrNull()
                if (parsed == null || parsed <= 0L) {
                    return usage("$EXIT_AFTER_FLAG takes a positive whole number of milliseconds, got '$raw'")
                }
                exitAfterMs = parsed
            }
            else -> return usage("unknown argument '$arg'")
        }
    }

    if (selfCheck) {
        if (scenario != null || webUiDir != null || exitAfterMs != null) {
            return usage("--self-check runs alone; it reads no stdin and serves no scenario")
        }
        return Options(selfCheck = true, scenario = "", webUiDir = null, exitAfterMs = null)
    }

    if (scenario.isNullOrBlank()) return usage("$SCENARIO_FLAG<name> is required")
    if (webUiDir.isNullOrBlank()) return usage("$WEBUI_DIR_FLAG<abs> is required")
    // The harness cannot call the root module's internal path resolver.
    if (!webUiDir.startsWith("/")) return usage("$WEBUI_DIR_FLAG must be ABSOLUTE, got '$webUiDir'")

    return Options(selfCheck = false, scenario = scenario, webUiDir = webUiDir, exitAfterMs = exitAfterMs)
}

private fun usage(problem: String): Options? {
    eprintln("webuicheck: $problem")
    eprintln("usage: webuicheck --self-check")
    eprintln("       webuicheck $SCENARIO_FLAG<name> $WEBUI_DIR_FLAG<abs> [$EXIT_AFTER_FLAG<n>]")
    return null
}

private const val SCENARIO_FLAG = "--scenario="
private const val WEBUI_DIR_FLAG = "--webui-dir="
private const val EXIT_AFTER_FLAG = "--exit-after-ms="

const val EXIT_OK: Int = 0

const val EXIT_SELF_CHECK_FAILED: Int = 1

const val EXIT_USAGE: Int = 2

const val EXIT_BAD_INPUT: Int = 3

const val EXIT_WATCHDOG: Int = 4

private var handshakeFd: Int = STDOUT_FILENO

@OptIn(ExperimentalForeignApi::class)
// Save the protocol fd, mark it close-on-exec, then make ordinary stdout writes diagnostic stderr.
private fun claimStdout() {
    val saved = dup(STDOUT_FILENO)
    if (saved < 0) return
    fcntl(saved, F_SETFD, FD_CLOEXEC)
    if (dup2(STDERR_FILENO, STDOUT_FILENO) < 0) {
        close(saved)
        return
    }
    handshakeFd = saved
}

@OptIn(ExperimentalForeignApi::class)
fun writeStdoutLine(text: String) {
    val bytes = (text + "\n").encodeToByteArray()
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val written = write(handshakeFd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
            if (written > 0) {
                offset += written
            } else if (written < 0 && errno == EINTR) {
                continue
            } else {
                return@usePinned
            }
        }
    }
}
