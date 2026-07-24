package io.kotgent.push

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.store.SessionUpdate

/**
 * Edge-detection for "this session just started waiting on the human" — the one trigger the push
 * feature fires on.
 *
 * [io.kotgent.store.EventStore.sessionUpdates] is a *level* signal: it re-emits a session's current
 * state on every cache change, and the `/events` resync path re-sends the same state on a timer. Sending
 * a push per update would ring the phone repeatedly for one approval. So this holds
 * `SessionId → wasNeedingAttention` and answers a single question: is this update a `false → true`
 * transition?
 *
 * Host-free by design (no store, no clock, no I/O — [PushNotifier] is the edge that owns those), so the
 * whole rule is unit-testable and the transition logic never depends on a live daemon.
 *
 * ## Concurrency
 * Deliberately NOT synchronized: the tracker is confined to the single collector coroutine that drains
 * `sessionUpdates` ([PushNotifier]), which calls [seed] once and then [isNewAttention] sequentially.
 * Sharing an instance across coroutines would be a data race — construct one per collector.
 */
class AttentionTracker {

    /**
     * Last known "is waiting on the human" per session. An absent id means *not* waiting: a session
     * first seen already in attention is therefore a transition and fires exactly once, which is what
     * a hook-created session that goes straight to `needs_approval` should do.
     */
    private val waiting = mutableMapOf<SessionId, Boolean>()

    /**
     * Establish the baseline from the store's current rows, discarding anything learned before.
     *
     * This is what stops a daemon restart from re-notifying: without it, the first update about a
     * session that was *already* waiting before the restart would look like a fresh transition. Call it
     * inside `.onSubscription { }` — after subscribing to the non-replaying `sessionUpdates`, so no
     * update can slip between the snapshot and the first collected item — and before any
     * [isNewAttention] call.
     */
    fun seed(sessions: List<SessionMeta>) {
        waiting.clear()
        for (meta in sessions) waiting[meta.id] = meta.isWaiting()
    }

    /**
     * True when [update] moves its session from "not waiting" to "waiting" — and records the new level
     * either way, so the next identical update returns false. Leaving attention and re-entering it later
     * fires again.
     */
    fun isNewAttention(update: SessionUpdate): Boolean {
        val now = update.isWaiting()
        val before = waiting.put(update.sessionId, now) ?: false
        return now && !before
    }
}

/**
 * "Waiting on the human" for push purposes: blocked on an approval/answer AND still visible.
 *
 * The `archived` half matters because the service worker wakes up and filters `/sessions` by
 * `needsAttention && !archived`; a push for an archived row would find nothing to show and degrade into
 * the generic filler banner. (Archiving normally follows a kill, so in practice this only guards the
 * archive-without-kill order.)
 */
private fun SessionMeta.isWaiting(): Boolean = state.needsAttention && !archived

/** @see isWaiting */
private fun SessionUpdate.isWaiting(): Boolean = state.needsAttention && !archived
