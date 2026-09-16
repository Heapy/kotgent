package io.kotgent.webuicheck

import io.kotgent.cli.eprintln
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
import io.kotgent.core.UsageObservation
import io.kotgent.daemon.daemonEpochMillis
import kotlinx.coroutines.runBlocking

fun reject(message: String): Boolean {
    eprintln("webuicheck: $message")
    return false
}

private val WORDS = Regex("\\s+")

fun handleCommand(line: String, ctx: HarnessContext): Boolean {
    val words = line.trim().split(WORDS).filter { it.isNotEmpty() }
    return when (words.firstOrNull()) {
        "restart" -> handleRestart(words, ctx)
        "emit" -> handleEmit(words, ctx)
        "done" -> handleDone(words, ctx)
        "model" -> handleModel(words, ctx)
        "rename" -> handleRename(words, ctx)
        "append" -> handleAppend(words, ctx)
        "usage" -> handleUsage(words, ctx)
        "usage-clock" -> handleUsageClock(words, ctx)
        else -> handleTaskCommand(words, ctx)
    }
}

private fun handleUsageClock(words: List<String>, ctx: HarnessContext): Boolean {
    val timestamp = words.getOrNull(1)?.toLongOrNull()
    if (words.size != 2 || timestamp == null || timestamp < 0) {
        return reject("usage-clock: usage-clock <non-negative epoch millis>")
    }
    runBlocking { ctx.fakes.usage.setTime(timestamp) }
    writeStdoutLine("${COMMAND_ACK_PREFIX}usage-clock")
    return true
}

private fun handleUsage(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size !in 4..7) {
        return reject("usage: usage <provider> <windowKey> <percent> [resetsAt|-] [observedAt|-] [windowSeconds|-]")
    }
    val percent = words[3].toDoubleOrNull()
    if (percent == null || !percent.isFinite() || percent !in 0.0..100.0) {
        return reject("usage: percent must be finite and between 0 and 100")
    }
    val optional = words.drop(4).map { it.takeUnless { value -> value == "-" } }
    for ((index, value) in optional.withIndex()) {
        if (value == null) continue
        val number = value.toLongOrNull()
        if (number == null || number < 0 || (index == 2 && number == 0L)) {
            return reject("usage: timestamps must be non-negative epoch millis and windowSeconds must be positive")
        }
    }
    val resetsAt = optional.getOrNull(0)?.toLong()
    val observedAt = optional.getOrNull(1)?.toLong()
    val windowSeconds = if (optional.size >= 3) optional[2]?.toLong() else when (words[2]) {
        "five_hour" -> 5 * 60 * 60L
        "seven_day" -> 7 * 24 * 60 * 60L
        else -> null
    }
    runBlocking {
        ctx.fakes.usage.observe(
            UsageObservation(words[1], words[2], percent, resetsAt, windowSeconds),
            observedAt = observedAt,
        )
    }
    return true
}

private fun handleRestart(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size != 1) {
        return reject("restart takes no arguments (got '${words.drop(1).joinToString(" ")}')")
    }
    runBlocking { ctx.restart() }
    return true
}

private fun handleAppend(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size !in 2..3) return reject("usage: append <session-id> [tool-name]")
    val id = SessionId(words[1])
    val tool = words.getOrElse(2) { "Read" }
    return runBlocking {
        if (ctx.fakes.eventStore.getSession(id) == null) {
            reject("append: no session '${id.value}' in this scenario")
        } else {
            val _ = ctx.fakes.eventStore.append(id, AgentEvent.ToolCall(tool), EventSource.hook)
            true
        }
    }
}

private fun handleModel(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size != 3) return reject("usage: model <session-id> <name|-> ('-' clears it)")
    val id = SessionId(words[1])
    val model = words[2].takeUnless { it == "-" }
    return runBlocking {
        if (ctx.fakes.eventStore.getSession(id) == null) {
            reject("model: no session '${id.value}' in this scenario")
        } else {
            ctx.fakes.eventStore.setModel(id, model)
            true
        }
    }
}

/** Renames in the store, so the browser learns it from the patch alone, exactly as a CLI rename would. */
private fun handleRename(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size != 3) return reject("usage: rename <session-id> <name|-> ('-' restores the automatic label)")
    val id = SessionId(words[1])
    val name = if (words[2] == "-") "" else words[2]
    return runBlocking {
        if (ctx.fakes.eventStore.getSession(id) == null) {
            reject("rename: no session '${id.value}' in this scenario")
        } else {
            ctx.fakes.eventStore.setName(id, name)
            true
        }
    }
}

/** Archives in the store, so the browser learns it from the patch alone, exactly as a second tab would. */
private fun handleDone(words: List<String>, ctx: HarnessContext): Boolean {
    if (words.size !in 2..3) return reject("usage: done <session-id> [<epoch-millis>]")
    val id = SessionId(words[1])
    val stamp = if (words.size == 3) {
        words[2].toLongOrNull() ?: return reject("done: '${words[2]}' is not an epoch-millis stamp")
    } else {
        daemonEpochMillis()
    }
    return runBlocking {
        if (ctx.fakes.eventStore.getSession(id) == null) {
            reject("done: no session '${id.value}' in this scenario")
        } else {
            ctx.fakes.eventStore.setArchived(id, true, stamp)
            true
        }
    }
}

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
        val meta = ctx.fakes.eventStore.getSession(id)
        if (meta == null) {
            reject("emit: no session '${id.value}' in this scenario")
        } else {
            ctx.fakes.eventStore.updateSessionState(
                sessionId = id,
                state = state,
                stateSource = EventSource.hook,
                paneId = meta.paneId,
                updatedAt = daemonEpochMillis(),
            )
            true
        }
    }
}
