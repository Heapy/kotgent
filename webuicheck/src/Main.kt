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

/**
 * `webuicheck` — the scenario harness the browser tier drives.
 *
 * ## The contract with the driver, in three lines
 * ```
 * PORT=<n>
 * TICKET=<code>
 * READY
 * ```
 * Those are the ONLY bytes that ever reach stdout (plus one repeated `READY` per [HarnessContext.restart],
 * and `SUMMARY total=… failed=…` in `--self-check` mode). Everything else — diagnostics, Ktor's own
 * logging, a scenario's chatter — goes to stderr. That is not a convention the code is asked to
 * remember: [claimStdout] moves the real stdout to a private descriptor and points fd 1 at stderr
 * before anything else runs, so a stray `println` ANYWHERE in this process (ours, Ktor's, a future
 * scenario's) lands on stderr and cannot corrupt the driver's parser.
 *
 * ## Modes
 *  - `--self-check` runs the cinterop-dependent checks in-process, prints `SUMMARY total=N failed=M`
 *    and exits. It reads NO stdin, which is what lets the native suite drive it through `popen`
 *    (`ProcessRunner` cannot write to a child's stdin — `src/tmux/ProcessRunner.kt`).
 *  - `--scenario=<name> --webui-dir=<abs> [--exit-after-ms=<n>]` is the working mode: seed, bind,
 *    handshake, then one command per stdin line until EOF.
 *
 * ## Failing loudly
 * A fixture that keeps going after being asked for something it does not understand turns a driver bug
 * into a mysterious browser assertion twenty seconds later. An unknown argument, an unknown scenario
 * and an unrecognised stdin line therefore each print one stderr line and exit non-zero.
 */
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
    // One blocking region for both, so the ticket is minted against the token the server just started
    // under and the three handshake lines are written back-to-back with nothing between them.
    val (context, ticket) = runBlocking { harness.start() to harness.issueTicket() }
    writeStdoutLine("PORT=${context.port}")
    writeStdoutLine("TICKET=$ticket")
    writeStdoutLine(READY_LINE)
    options.exitAfterMs?.let { startWatchdog(it) }

    val exitCode = readCommands(context)
    runBlocking { harness.stop() }
    exitProcess(exitCode)
}

/**
 * Read one command per line until EOF, dispatching through `handleCommand`.
 *
 * Deliberately NOT inside a `runBlocking`: `handleCommand` is a plain function that has to bridge into
 * suspending work itself, and `KotgentServer.stop()` runs a nested `runBlocking` — starting that chain
 * from a coroutine on the main thread's single-threaded event loop is the one shape that can deadlock.
 * Read on the bare thread and let each command own its own blocking region.
 */
private fun readCommands(context: HarnessContext): Int {
    while (true) {
        val line = readlnOrNull() ?: return EXIT_OK // EOF: the driver went away, shut down cleanly.
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

/**
 * Exit after [afterMs] whatever else is happening.
 *
 * A driver that is killed (a crashed test JVM, a cancelled CI job) never closes this process's stdin,
 * so the EOF that would normally end the run never arrives and the harness sits on its port forever.
 * The watchdog exits IMMEDIATELY rather than attempting a graceful stop: the very situation it exists
 * for is one where something is already wedged, and a graceful path that could itself hang would defeat
 * it. Process exit releases the port and the pty regardless.
 */
private fun startWatchdog(afterMs: Long) {
    watchdogScope.launch {
        delay(afterMs)
        eprintln("webuicheck: --exit-after-ms=$afterMs elapsed with no EOF on stdin; exiting")
        exitProcess(EXIT_WATCHDOG)
    }
}

/** Its own scope on [Dispatchers.Default] so the timer runs while the main thread blocks in `readln`. */
private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private class Options(
    val selfCheck: Boolean,
    val scenario: String,
    val webUiDir: String?,
    val exitAfterMs: Long?,
)

/** Parse the four accepted arguments, or print why not and answer `null`. */
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
    // KotgentServer.resolveWebUiDir is `internal`, so the harness cannot anchor a relative path against
    // its own executable the way the daemon does. The driver knows the repo root; it passes it in.
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

/** Everything the driver asked for happened. */
const val EXIT_OK: Int = 0

/** A `--self-check` check failed. */
const val EXIT_SELF_CHECK_FAILED: Int = 1

/** The arguments do not describe a run this binary can perform. */
const val EXIT_USAGE: Int = 2

/** A line arrived on stdin that no command claimed — the fixture refuses to guess. */
const val EXIT_BAD_INPUT: Int = 3

/** `--exit-after-ms` fired: the driver is gone and never closed stdin. */
const val EXIT_WATCHDOG: Int = 4

/**
 * The private duplicate of the process's real stdout. Everything the driver parses is written here with
 * a raw `write(2)`; fd 1 itself points at stderr from [claimStdout] onwards.
 */
private var handshakeFd: Int = STDOUT_FILENO

/**
 * Take stdout away from the rest of the process.
 *
 * `dup` the real stdout to a private descriptor, then point fd 1 at stderr. After this, every ordinary
 * write to stdout — `println` in this module, Ktor's `KtorSimpleLogger` (which prints on Kotlin/Native),
 * anything a future scenario adds — is delivered to stderr, where it is diagnostics rather than
 * protocol. The alternative, "remember never to print", is a rule that only has to be broken once, and
 * the failure it produces is a driver hanging on a handshake it can no longer parse.
 *
 * The saved descriptor is marked close-on-exec for the same reason every other descriptor in this
 * project is: spawned children (the pty's `/bin/sh`) inherit stdio and must inherit nothing else.
 *
 * If `dup` or `dup2` fails, the handshake keeps using fd 1 unchanged — degraded (a stray print could
 * still corrupt it) but functional, which is better than a fixture that refuses to start.
 */
@OptIn(ExperimentalForeignApi::class)
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

/**
 * Write one line to the driver's stdout, bypassing stdio entirely.
 *
 * This is the ONLY way anything reaches stdout after [claimStdout]. Use it for the handshake, the
 * repeated `READY` and the self-check `SUMMARY` — nothing else; every other message belongs on stderr.
 */
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
