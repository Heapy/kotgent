package io.kotgent.webuicheck

import io.kotgent.cli.eprintln
import io.kotgent.core.EventSource
import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
import io.kotgent.daemon.daemonEpochMillis
import kotlinx.coroutines.runBlocking

/**
 * Report why a recognised command could not run, and answer `false` so the harness exits non-zero.
 *
 * Not `private`: [handleTaskCommand] is the other half of this one stdin protocol and owes the same
 * contract, and a second copy of "print a reason, answer false" is a second chance to print none. It
 * lives HERE because this file owns the dispatch that turns the `false` into an exit code.
 */
fun reject(message: String): Boolean {
    eprintln("webuicheck: $message")
    return false
}

/**
 * Split on any run of whitespace, so a line pasted with a tab or a double space still parses.
 *
 * The ONE splitter for the whole stdin protocol: a line is split here and the resulting words are
 * handed to [handleTaskCommand], never re-split there. Two regexes over one line is two chances to
 * disagree about what a word is, and a fixture that parsed `task  local:1  done` one way in this file
 * and another way in its sibling would answer a driver differently depending on which verb it typed.
 */
private val WORDS = Regex("\\s+")

/**
 * Execute one stdin command line against the running harness.
 *
 * **Returns whether the line was handled.** `false` is the fixture's way of saying "I do not know what
 * you meant", and the caller turns it into a stderr line and a non-zero exit — a driver that mistypes a
 * command must not get a green run out of a harness that quietly did nothing. That is also why a
 * recognised verb with unusable arguments answers `false` as well, after printing the precise reason
 * itself: the exit code has to be the same either way, and only this function knows WHICH argument was
 * wrong.
 *
 * ## Why it is not `suspend`
 * Everything it does is suspending (a store write, a server restart), but the call site is a plain
 * `readlnOrNull()` loop on the process's main thread, so the coroutine boundary belongs HERE — one
 * [runBlocking] per command, on a thread that is not already inside a coroutine. It matters because
 * `KotgentServer.stop()` runs a nested `runBlocking` of its own to tear the terminal bridges down;
 * driving that from a coroutine on a single-threaded dispatcher is exactly the deadlock the harness is
 * built to avoid. Keeping the boundary at the command level keeps the whole stdin protocol out of
 * coroutine context by construction, rather than by everybody remembering.
 *
 * ## Delegation
 * Anything this file does not recognise is offered to [handleTaskCommand] — with the words this
 * function already split — before it is refused, so the board's `task` / `task-add` / `task-del` verbs
 * need no entry here and the two halves of the command table can be written independently.
 */
fun handleCommand(line: String, ctx: HarnessContext): Boolean {
    // `firstOrNull`, not `words[0]`: a blank line splits to nothing, and the read loop already skips
    // those (`Main.kt` trims and continues), so a guard here would be an unreachable branch pretending
    // to be a policy. Falling through to the `else` keeps the one policy that IS reachable — an
    // unrecognised line is refused, loudly — true for the impossible line too.
    val words = line.trim().split(WORDS).filter { it.isNotEmpty() }
    return when (words.firstOrNull()) {
        "restart" -> handleRestart(words, ctx)
        "emit" -> handleEmit(words, ctx)
        else -> handleTaskCommand(words, ctx)
    }
}

/**
 * `restart` — stop and re-stand the server on the same port, keeping the same token, tickets, fakes
 * and task service, so every already-logged-in page survives it.
 *
 * The work belongs to the harness core (it owns the server handle and the handshake); this is only the
 * verb. In particular NOTHING is printed here: the restart prints the second `READY` itself, and a line
 * from this side would corrupt a stdout stream whose whole contract is three lines and then one more.
 */
private fun handleRestart(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size != 1) {
        return reject("restart takes no arguments (got '${words.drop(1).joinToString(" ")}')")
    }
    runBlocking { ctx.restart() }
    return true
}

/**
 * `emit <session-id> <state>` — move a seeded session to [SessionState] and let the resulting
 * `SessionUpdate` travel the real path to the browser: the store stamps a fresh `rev`, publishes on
 * `sessionUpdates`, and `/api/v1/events` ships it as a `session_update` patch.
 *
 * It writes through `updateSessionState` rather than fabricating a `SessionUpdate`, so the row the next
 * snapshot or `GET /sessions` answers with AGREES with the frame the socket just sent. A hand-built
 * signal would drift from the row behind it and the browser's newest-rev-wins merge would then be
 * arbitrating between two versions of the truth, which is precisely the bug class these tests exist to
 * catch rather than to create.
 *
 * The existing `paneId` is read back and re-written because the store's contract for this call is
 * "state / source / pane / updated_at": passing `null` would CLEAR a pane rather than leave it alone.
 * The source is [EventSource.hook] because that is what really drives this transition in production —
 * it is not on the wire, so nothing observable depends on it, but an honest value costs nothing.
 *
 * An unknown session is refused rather than silently ignored: `updateSessionState` is a no-op for a
 * missing row, so without this check a mistyped id would look exactly like a working command.
 */
private fun handleEmit(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size != 3) {
        return reject("usage: emit <session-id> <state>; states: ${SessionState.entries.joinToString(" ")}")
    }
    val id = SessionId(words[1])
    val state = SessionState.entries.firstOrNull { it.name == words[2] }
        ?: return reject(
            "emit: '${words[2]}' is not a session state; expected one of " +
                SessionState.entries.joinToString(" "),
        )
    return runBlocking {
        val meta = ctx.fakes.events.getSession(id)
        if (meta == null) {
            reject("emit: no session '${id.value}' in this scenario")
        } else {
            ctx.fakes.events.updateSessionState(
                sessionId = id,
                state = state,
                stateSource = EventSource.hook,
                paneId = meta.paneId,
                // The harness's ONE clock spelling, shared with the fakes it writes through
                // (`newHarnessFakes`): a second inline `Clock.System.now()` here is the same "two clocks
                // for one fixture" hazard `TaskCommands.taskService` records, one layer down.
                updatedAt = daemonEpochMillis(),
            )
            true
        }
    }
}
