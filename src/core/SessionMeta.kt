package io.kotgent.core

/**
 * Session metadata — the non-event columns of the `sessions` row (Technical Details), host-free.
 * The event-derived fields ([state], [stateSource], [lastSeq]) are the reducer's projection cached
 * here; everything the projection does not own (identity, launch context, tmux correlation, discovered
 * repo/CLI info, and the client-driven [readCursor]) lives here too.
 *
 * Fields are declared in `sessions`-column order and use the value-class ids where the schema
 * has one. Anything not known when the session is first created is nullable and filled in later:
 * [providerSessionId] once [AgentEvent.SessionBound] fires, [paneId] after tmux `new-session`, the
 * `model`/CLI/repo fields once discovered. Because several middle fields have defaults, construct
 * with named arguments.
 */
data class SessionMeta(
    /** Logical key (`sessions.id`). */
    val id: SessionId,
    /** Human-facing name. */
    val name: String,
    /** Free-form labels for filtering/grouping. */
    val tags: List<String> = emptyList(),
    /** Provider/agent kind, e.g. `"claude"` (Codex is backlog). */
    val agent: String,
    /** The provider's own session id — null until [AgentEvent.SessionBound]. */
    val providerSessionId: ProviderSessionId? = null,
    /** Model in use (e.g. `"claude-opus-…"`) — null until discovered. */
    val model: String? = null,
    /** Version of the agent CLI — null until discovered. */
    val cliVersion: String? = null,
    /** Absolute path to the agent CLI binary — null until discovered. */
    val cliPath: String? = null,
    /** Working directory the agent runs in. */
    val cwd: String,
    /** Repository root, if the cwd is inside one — null otherwise. */
    val repository: String? = null,
    /** Git worktree path, if any. */
    val worktree: String? = null,
    /** Git branch, if known. */
    val branch: String? = null,
    /** tmux session name — the logical `kt-<shortid>` handle in `tmux -L kotgent`. */
    val tmuxSession: String,
    /** Runtime tmux pane correlation — null until `new-session` returns it. */
    val paneId: PaneId? = null,
    /** Current state (reducer projection). */
    val state: SessionState,
    /** Which source last drove [state] — null before the first state-affecting event. */
    val stateSource: EventSource? = null,
    /** Highest applied event seq; `Seq(0)` means no events yet. */
    val lastSeq: Seq = Seq(0),
    /**
     * Read cursor for the unread badge (`unread = lastSeq - readCursor`); `Seq(0)` means nothing read
     * yet. Client-driven, never produced by the reducer: written only via
     * [io.kotgent.store.EventStore.markRead], which the Web UI drives for the session it is displaying.
     */
    val readCursor: Seq = Seq(0),
    /** Creation timestamp (epoch millis). */
    val createdAt: Long,
    /** Last-update timestamp (epoch millis). */
    val updatedAt: Long,
    /**
     * Whether this session is "done" — hidden from the working-set sidebar. Orthogonal to [state]
     * (an archived session is dead, but the reducer/control paths never touch this flag); set only via
     * [io.kotgent.store.EventStore.setArchived] (the "Done" op archives after a kill; "Restore" clears it).
     */
    val archived: Boolean = false,
    /**
     * The row's global monotonic revision — stamped by the store on every write that touches the row
     * (`Sessions.sq`), never by callers: the store overrides whatever a passed-in meta carries. Clients
     * compare revs to apply the newest observation of a row regardless of the channel it arrived on.
     */
    val rev: Long = 0,
    /**
     * The task this session is working on, or `null`. A session works ONE task at a time (pointing it at
     * another overwrites the link; there is no error case), while a task may be linked from ANY number of
     * sessions — kotgent deliberately enforces no exclusivity, because it cannot: the operator opens a
     * second terminal in the same repository and the daemon never hears about it.
     *
     * A **reference, not a foreign key**: a task deleted while a link write is in flight leaves a
     * dangling ref, which the UI renders as the bare ref and startup reconciliation clears. Written only
     * by [io.kotgent.store.EventStore.setTaskRef] — a `SessionMeta` snapshot read before a link must not
     * clear it, which is why `Sessions.sq`'s `upsert` COALESCEs this column.
     */
    val taskRef: TaskRef? = null,
    /**
     * The project the session's cwd resolves to (`.kotgent.json`'s uuid), or `null` outside one.
     * Resolved by the daemon at `start`/`import` and backfilled by startup reconciliation. COALESCEd in
     * `upsert` for the same reason as [taskRef].
     */
    val projectId: ProjectId? = null,
)
