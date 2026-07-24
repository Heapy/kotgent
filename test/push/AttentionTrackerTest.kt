package io.kotgent.push

import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.SessionUpdate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AttentionTracker] is pure edge-detection over the level signal `sessionUpdates` carries, so these
 * tests need no store, no clock and no coroutines: they feed updates in and assert exactly when a
 * `false → true` transition is reported.
 */
class AttentionTrackerTest {

    private fun meta(
        id: String,
        state: SessionState,
        archived: Boolean = false,
    ) = SessionMeta(
        id = SessionId(id), name = id, agent = "claude", cwd = "/w",
        tmuxSession = "kt-$id", state = state, createdAt = 1L, updatedAt = 1L, archived = archived,
    )

    private fun update(
        id: String,
        state: SessionState,
        archived: Boolean = false,
    ) = SessionUpdate(
        sessionId = SessionId(id), state = state, lastSeq = Seq(1), unread = 0L, archived = archived,
    )

    @Test
    fun aSingleTransitionIntoAttentionFiresExactlyOnce() {
        val tracker = AttentionTracker()
        tracker.seed(listOf(meta("s1", SessionState.running)))

        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "running → needs_approval fires")
    }

    @Test
    fun stayingInAttentionDoesNotReFire() {
        val tracker = AttentionTracker()
        tracker.seed(listOf(meta("s1", SessionState.running)))

        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "the transition fires")
        // The resync tick and any further cache write re-emit the same level; only the edge counts.
        assertFalse(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "the same level is silent")
        assertFalse(
            tracker.isNewAttention(update("s1", SessionState.needs_answer)),
            "another attention state is still not an edge",
        )
    }

    @Test
    fun leavingAndReEnteringAttentionFiresAgain() {
        val tracker = AttentionTracker()
        tracker.seed(emptyList())

        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "first approval fires")
        assertFalse(tracker.isNewAttention(update("s1", SessionState.running)), "the operator answered — no push")
        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "the next approval fires again")
    }

    @Test
    fun seedingFromAnAlreadyWaitingSessionSuppressesTheNextIdenticalUpdate() {
        // The daemon-restart case: the reconciler re-writes a session that was ALREADY waiting before
        // the restart. Without the seed that first update would look like a fresh transition.
        val tracker = AttentionTracker()
        tracker.seed(listOf(meta("s1", SessionState.needs_approval)))

        assertFalse(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "no re-notify after restart")
        assertFalse(tracker.isNewAttention(update("s1", SessionState.ready)), "leaving attention is silent")
        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "a genuinely new approval fires")
    }

    @Test
    fun anUnknownSessionAlreadyInAttentionFiresExactlyOnce() {
        // A session created by a hook and seen for the first time already blocked: absent means "not
        // waiting", so this is a transition — but only the first time.
        val tracker = AttentionTracker()
        tracker.seed(listOf(meta("other", SessionState.running)))

        assertTrue(tracker.isNewAttention(update("fresh", SessionState.needs_approval)), "first sighting fires")
        assertFalse(tracker.isNewAttention(update("fresh", SessionState.needs_approval)), "and not again")
    }

    @Test
    fun sessionsAreTrackedIndependently() {
        val tracker = AttentionTracker()
        tracker.seed(listOf(meta("a", SessionState.needs_approval), meta("b", SessionState.running)))

        assertFalse(tracker.isNewAttention(update("a", SessionState.needs_approval)), "a was already waiting")
        assertTrue(tracker.isNewAttention(update("b", SessionState.needs_approval)), "b's own edge still fires")
    }

    @Test
    fun anArchivedSessionNeverCountsAsWaiting() {
        // The service worker filters /sessions by `needsAttention && !archived`, so a push for an
        // archived row would wake the phone only to show the generic filler.
        val tracker = AttentionTracker()
        tracker.seed(emptyList())

        assertFalse(
            tracker.isNewAttention(update("s1", SessionState.needs_approval, archived = true)),
            "archived + needs_approval is not an attention edge",
        )
        assertTrue(
            tracker.isNewAttention(update("s1", SessionState.needs_approval)),
            "restoring the row makes the same state an edge",
        )
    }

    @Test
    fun seedReplacesEverythingLearnedBefore() {
        val tracker = AttentionTracker()
        tracker.seed(emptyList())
        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "learned: s1 is waiting")

        // A fresh baseline that says "s1 is not waiting" must make the next identical update an edge again.
        tracker.seed(listOf(meta("s1", SessionState.running)))
        assertTrue(tracker.isNewAttention(update("s1", SessionState.needs_approval)), "the new baseline wins")
    }
}
