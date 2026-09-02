package io.kotgent.webuicheck

import io.kotgent.cli.eprintln
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
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
        else -> handleTaskCommand(words, ctx)
    }
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
