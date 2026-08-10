package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

/**
 * `attention` — a session POISED to enter `needs_approval`, beside one that already carries an unread
 * count. Consumers: the badges and the notify edge.
 *
 * ## Why the first row must start OUT of attention
 * The notification edge is a `false → true` transition, in the daemon's `AttentionTracker` and again in
 * the browser's snapshot diff. A fixture that seeded `needs_approval` directly would give the browser
 * a session that is ALREADY waiting on its very first frame — no transition anywhere — and a test
 * written over it would prove only that a badge renders, which the `sessions` scenario's `s-gamma`
 * already proves. So `s-quiet` starts `ready` and the edge is produced on demand by the stdin command
 * `emit s-quiet needs_approval`, which is the only way to make the transition happen at a moment the
 * driver chose.
 *
 * ## Why there are two rows
 * `emit` changes STATE; it does not append an event, so it cannot move an unread count (the store's
 * `updateSessionState` writes state/pane/`updated_at` and deliberately never `last_seq` — see
 * `Sessions.sq`). The unread badge therefore needs a row that is born with a gap between `lastSeq` and
 * `readCursor`, and it must be a row the test does NOT select: `app.js` posts `/read` for the session
 * it displays, so selecting `s-unread` is how you make the badge disappear — which is itself the other
 * half of the assertion.
 *
 * `s-unread` is left `running` rather than dead so both rows stay attachable and neither is filtered
 * out of the working set.
 */
fun attentionScenario(): Scenario = Scenario(
    name = "attention",
    seed = { fakes ->
        listOf("/w/quiet", "/w/unread").forEach(fakes.projectFs::addDirectory)
        fakes.events.upsertSession(
            harnessSession(
                id = "s-quiet",
                name = "quiet",
                agent = "claude",
                cwd = "/w/quiet",
                state = SessionState.ready,
                createdAt = SEED_EPOCH_MS + 1,
                providerSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                model = "claude-sonnet-4-5",
            ),
        )
        fakes.events.upsertSession(
            harnessSession(
                id = "s-unread",
                name = "unread",
                agent = "codex",
                cwd = "/w/unread",
                state = SessionState.running,
                createdAt = SEED_EPOCH_MS + 2,
                providerSessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                model = "gpt-5-codex",
                // unread = lastSeq - readCursor = 3. Both are seeded rather than produced by appends
                // because an append would also drive the reducer and move the row's STATE, which is the
                // one thing this scenario needs to hold still.
                lastSeq = 5,
                readCursor = 2,
            ),
        )
    },
    terminalUpstream = deterministicUpstream(ATTENTION_BANNER),
)
