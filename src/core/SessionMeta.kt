package io.kotgent.core

/** Session-row metadata; projection-derived values are cached alongside launch and discovery data. */
data class SessionMeta(
    val id: SessionId,
    val name: String,
    val tags: List<String> = emptyList(),
    val agent: String,
    val providerSessionId: ProviderSessionId? = null,
    val model: String? = null,
    val cliVersion: String? = null,
    val cliPath: String? = null,
    val cwd: String,
    val repository: String? = null,
    val worktree: String? = null,
    val branch: String? = null,
    val tmuxSession: String,
    val paneId: PaneId? = null,
    val state: SessionState,
    val stateSource: EventSource? = null,
    val lastSeq: Seq = Seq(0),
    val readCursor: Seq = Seq(0),
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
    /** Store-stamped global revision; caller-supplied values are ignored. */
    val rev: Long = 0,
    /**
     * A reference, not a foreign key or exclusive claim: many sessions may link one task. Upsert must
     * not clear a newer targeted link written after this snapshot.
     */
    val taskRef: TaskRef? = null,
    /** Upsert must not clear a newer targeted project link written after this snapshot. */
    val projectId: ProjectId? = null,
)

/**
 * Counted in UTF-16 units so the server and the browser's `maxlength` measure the same thing; a
 * code-point bound would be silently overridden by the stricter browser cap.
 */
const val MAX_SESSION_NAME_LENGTH: Int = 200

/** Applied at every write boundary so a name reads back the same whichever client set it. */
fun normalizeSessionName(name: String): String = name.trim()

fun sessionNameProblem(name: String): String? = when {
    name.length > MAX_SESSION_NAME_LENGTH ->
        "session name must be at most $MAX_SESSION_NAME_LENGTH characters, was ${name.length}"
    name.any { it.isISOControl() } ->
        "session name must not contain control characters"
    else -> null
}
