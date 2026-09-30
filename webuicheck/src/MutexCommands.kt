package io.kotgent.webuicheck

import io.kotgent.core.SessionId
import io.kotgent.mutex.MutexKey
import io.kotgent.store.MutexAcquireResult
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun handleMutexCommand(words: List<String>, ctx: HarnessContext): Boolean? = when (words.firstOrNull()) {
    "mutex-acquire" -> runBlocking { acquireMutex(ctx, words) }
    "mutex-wait" -> runBlocking { waitForMutex(ctx, words) }
    "mutex-release" -> runBlocking { releaseMutex(ctx, words) }
    "mutex-end" -> runBlocking { endSession(ctx, words) }
    else -> null
}

/** Only a free key: a held one would silently queue a waiter, which is `mutex-wait`'s job. */
private suspend fun acquireMutex(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 3) return reject("usage: mutex-acquire <session-id> <key>")
    val session = sessionOrNull(ctx, words[0], words[1]) ?: return false
    val key = keyOrNull(words[0], words[2]) ?: return false
    if (ctx.mutexes.listing.value.entries.any { it.key == key }) {
        return reject("mutex-acquire: '${key.value}' is held or awaited; use mutex-wait to queue behind it")
    }
    return when (ctx.mutexes.acquire(key, session, null, Duration.ZERO)) {
        is MutexAcquireResult.Acquired -> true
        else -> reject("mutex-acquire: '${key.value}' was not granted at once")
    }
}

/**
 * Queues in the calling order, then keeps a long-poll open in the background as `kotgent mutex run` does, so
 * the lease never lapses under the fixture's frozen clock and a release hands the key straight over.
 */
private suspend fun waitForMutex(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 3) return reject("usage: mutex-wait <session-id> <key>")
    val session = sessionOrNull(ctx, words[0], words[1]) ?: return false
    val key = keyOrNull(words[0], words[2]) ?: return false
    val first = ctx.mutexes.acquire(key, session, null, Duration.ZERO)
    if (first !is MutexAcquireResult.Pending) {
        return reject("mutex-wait: '${key.value}' was free, so ${session.value} took it; use mutex-acquire")
    }
    val _ = ctx.background.launch {
        var ticket = first.ticket
        while (isActive) {
            when (val next = ctx.mutexes.acquire(key, session, ticket, WAIT_POLL)) {
                is MutexAcquireResult.Pending -> ticket = next.ticket
                else -> return@launch
            }
        }
    }
    return true
}

private suspend fun releaseMutex(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2) return reject("usage: mutex-release <key>")
    val key = keyOrNull(words[0], words[1]) ?: return false
    return ctx.mutexes.forceRelease(key) != null || reject("mutex-release: '${key.value}' is not held")
}

private suspend fun endSession(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2) return reject("usage: mutex-end <session-id>")
    val session = sessionOrNull(ctx, words[0], words[1]) ?: return false
    ctx.mutexes.sessionEnded(session)
    return true
}

private suspend fun sessionOrNull(ctx: HarnessContext, verb: String, raw: String): SessionId? {
    val id = SessionId(raw)
    if (ctx.fakes.eventStore.getSession(id) == null) {
        val _ = reject("$verb: no session '$raw' in this scenario")
        return null
    }
    return id
}

private fun keyOrNull(verb: String, raw: String): MutexKey? = MutexKey.parseOrNull(raw) ?: run {
    val _ = reject("$verb: '$raw' is not a mutex key")
    null
}

private val WAIT_POLL: Duration = 540.seconds
