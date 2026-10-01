# Structured plans

A plan belongs to a backlog task. Its content and review history live in the daemon's SQLite database.
The author puts a JSON document; the operator reviews it in the task's Plan view or a session workspace.

```sh
kotgent plan put local:42 < plan.json
kotgent plan show local:42
kotgent plan review local:42 --wait 90
kotgent plan reply local:42 th_question -m 'The migration preserves existing rows.'
kotgent plan put local:42 --base-rev 12 < revised-plan.json
kotgent plan review local:42 --after-round 1
```

All commands accept `--session ID` outside a Kotgent pane. Put and review require a live session.
Viewed marks and submitting or approving a review are operator actions. Caller identity is attribution:
every client has the same master credential; this is not an isolation boundary between agents.

## Document and revisions

The initial document needs its task reference and title. Sections carry a kind and Markdown body;
tasks carry an ordinal, title and steps. For example:

```json
{
  "taskRef": "local:42",
  "title": "Add export",
  "sections": [{"kind": "overview", "body": "Export the selected rows as CSV."}],
  "tasks": [{"ordinal": 1, "title": "Implement export", "steps": [{"text": "Verify escaping and ordering"}]}]
}
```

The daemon issues stable block IDs. Preserve them on updates; omit an ID only for a new block. Omit a
block to delete it, which resolves its open threads. To set dependencies between new tasks, first put
them without dependencies, then use their returned IDs in `dependsOn` in a second put.

`show`, `put` and `reply` return `{plan, review}`. Feed the `plan` member back to `put`, using its current
`rev` as `--base-rev`. A stale write returns HTTP 409 and changed block IDs, including deleted IDs. Fetch
again and incorporate the operator's changes before retrying. Put preserves execution state and settings.
Viewed marks and discussion also advance the plan revision; only authored text advances a block revision.

The document limit is 512 KiB, a block body 32 KiB, and a thread message 8 KiB, measured as UTF-8 bytes.
Raw HTML is discarded when rendering Markdown. Links use HTTP(S); images render as links.

## Review loop

Review opens or continues a durable round. Its first output line is `verdict: pending`, `changes`, or
`approved`, followed by `plan-rev`, `round`, operator edit diffs, and unresolved threads. `--json` returns
the structured response. A pending result can be retried with the same arguments, including after a
daemon restart or a dropped connection.

After `changes`, answer threads and update the document as needed, then pass `--after-round N` with the
round you addressed. This also opens the next round when only answers changed. Retrying that argument
continues the same next round. Without it, a completed unchanged round keeps returning its verdict.

The server waits 90 seconds by default, accepts 0–540 seconds, and the client timeout is the wait plus
15 seconds. A disconnected wait prints `pending`. Exit codes are 0 for a printed result (including
pending), 1 for daemon/HTTP errors, 2 for usage errors, and 3 for a conflict (409/410).

An open round appears as `plan.review:<taskRef>` in the notification inbox. Opening emits one payload-less
push wake; continuing does not emit another. Submit/approve clears the inbox item without changing
session attention. Push is best effort; the round and inbox state survive restart independently.

Plan changes arrive as revision hints on the events socket. An open viewer fetches the full document
after a hint and after connection recovery; plan content is not duplicated into a socket snapshot.

## Browser review

Open **Plan** on a task to review at `/tasks/<ref>/plan`. In a session's Task column, the same button opens
or reuses a **Terminal · Plan** tab. The Plan column follows the session's linked task.

Use the contents list (a dropdown in narrow columns) to navigate. Viewed marks track the exact block
revision; a changed block is unviewed again. Ask opens a question on that block. Edit keeps your draft
when another client changes the block and asks you to accept the new base before saving it.

Submit review returns `changes` to the waiting author. Approve returns `approved`, with confirmation
if threads remain unresolved. Both actions name the displayed round, so an old browser cannot submit
a later one. With focus inside the panel, `v` toggles Viewed and `n` jumps to the next unviewed block.
These shortcuts do not run in text fields.
