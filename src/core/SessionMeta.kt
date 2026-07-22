package io.kotgent.core

/**
 * Session metadata — the non-event columns of the `sessions` row (Technical Details), host-free.
 * The event-derived fields ([state], [stateSource], [lastSeq], [readCursor]) are the reducer's
 * projection cached here; everything the projection does not own (identity, launch context, tmux
 * correlation, discovered repo/CLI info) lives here too.
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
    /** Read cursor for unread tracking; `Seq(0)` means nothing read yet. */
    val readCursor: Seq = Seq(0),
    /** Creation timestamp (epoch millis). */
    val createdAt: Long,
    /** Last-update timestamp (epoch millis). */
    val updatedAt: Long,
)
