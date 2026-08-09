package io.kotgent.core

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/*
 * Typed identifiers for the host-free domain (Task 5). Each is a zero-overhead
 * @JvmInline value class: the compiler erases it to its underlying primitive at runtime
 * while the type checker keeps the different ids from being mixed up at a call site.
 *
 * The ids that appear inside an AgentEvent payload (currently only ProviderSessionId) are
 * @Serializable, so they serialize as their bare underlying primitive. The generated
 * serializer builds the value by calling the primary constructor, which means the init
 * invariants below are enforced on decode too, not just on hand construction.
 */

/**
 * Logical session key — the `sessions.id` row id and the stable handle every layer uses to
 * address a session. Distinct from the tmux session name (`kt-<shortid>`, carried separately
 * in [SessionMeta.tmuxSession]) and from the provider's own id ([ProviderSessionId]). No
 * provider-defined format, so the only invariant is non-blankness.
 */
@Serializable
@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

/**
 * Per-session event sequence number — monotonic and append-only (`events.seq`,
 * `sessions.last_seq`, `sessions.read_cursor`). `0` denotes "no events yet"; real events
 * start at `1`. Never negative. Ordered and incrementable because ordering/advancing a
 * cursor is intrinsic to what a sequence number is.
 */
@Serializable
@JvmInline
value class Seq(val value: Long) : Comparable<Seq> {
    init {
        require(value >= 0) { "Seq must be non-negative, was $value" }
    }

    override fun compareTo(other: Seq): Int = value.compareTo(other.value)

    /** The next sequence number after this one. */
    fun next(): Seq = Seq(value + 1)
}

/**
 * The agent provider's own session id — for Claude a preallocated UUID passed as
 * `claude --session-id <uuid>` (or captured from the `SessionStart` hook), surfaced by the
 * [AgentEvent.SessionBound] event and stored in `sessions.provider_session_id`.
 *
 * ## Why the invariant is a SAFE CHARSET, not a UUID
 * Claude and Codex both mint UUIDs, but Junie does not (`session-260730-015553-1j1h`), so a UUID
 * invariant here would reject a perfectly valid provider id at construction. What every provider's id
 * must actually satisfy is that kotgent can put it in a filesystem path (`<junieDir>/sessions/<id>/`),
 * an argv element (`junie --session-id <id>`) and a URL without quoting or escaping it: hence non-blank,
 * bounded, `[A-Za-z0-9._-]`, and starting with an alphanumeric. The leading-character rule is what keeps
 * `..` (a path component that escapes its parent) and `-…` (a value a CLI would read as a flag) out —
 * both are unrepresentable as a real provider id anyway.
 *
 * Where UUID-ness is genuinely load-bearing, the boundary checks it explicitly with [isCanonicalUuid]:
 * the Claude/Codex hook normalizers (an untrusted callback body must not bind a malformed id) and
 * `CodexRolloutScan`'s file-name parse (the last 36 characters of a stem are an id only if they are a
 * UUID). Keep new such checks at the boundary — this type is the union of all providers, not one of them.
 */
@Serializable
@JvmInline
value class ProviderSessionId(val value: String) {
    init {
        require(value.isNotEmpty()) { "ProviderSessionId must not be blank" }
        require(value.length <= MAX_LENGTH) {
            "ProviderSessionId must be at most $MAX_LENGTH characters, was ${value.length}"
        }
        require(SAFE_FORMAT.matches(value)) {
            "ProviderSessionId must start with a letter or digit and contain only letters, digits, " +
                "'.', '_' or '-': '$value'"
        }
    }

    companion object {
        /** Upper bound on an id's length — generous for every known provider, still path/argv-safe. */
        const val MAX_LENGTH: Int = 128

        private val SAFE_FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

/**
 * Whether [value] is a canonical UUID string (`8-4-4-4-12` hex groups) — the shape Claude and Codex
 * session ids take. Used at the boundaries that still require UUID-ness after [ProviderSessionId] was
 * relaxed to a safe charset (see its KDoc). Pure and host-free.
 */
fun isCanonicalUuid(value: String): Boolean = UUID_FORMAT.matches(value)

private val UUID_FORMAT =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * A task's stable handle, `"<tracker>:<key>"` — e.g. `"local:42"`.
 *
 * ## Why it lives in `core` and not in `src/task/`
 * [SessionMeta.taskRef] carries it, and `core` must not depend on `task`: the layering runs
 * `task → core`. So the two task-layer ids sit here beside [SessionId], next to the [isCanonicalUuid]
 * helper [ProjectId] uses.
 *
 * ## The invariant, and what each half of it buys
 * Exactly one `:`; both halves non-blank; the whole value at most [MAX_LENGTH] characters; each half
 * `[A-Za-z0-9][A-Za-z0-9_-]*`.
 *
 *  - **`.` is deliberately excluded**, unlike [ProviderSessionId]'s charset. That rule only rejects a
 *    value that *equals* `..`, not one that CONTAINS it, and a task ref ends up in URLs
 *    (`/api/v1/tasks/{ref}`, `/tasks/{ref}`) and in argv (`kotgent task show <ref>`), where a `..`
 *    component escapes its parent.
 *  - **The leading alphanumeric** on each half keeps a `-…` key out of a CLI's flag parser.
 *  - **The mandatory `:` is load-bearing for routing**: it is what keeps `POST /api/v1/tasks/claim`
 *    (and any other literal sibling of `/tasks/{ref}/…`) from ever being shadowed, because a bare word
 *    can never parse as a ref. Pin that with a test rather than assuming it.
 *
 * Deliberately NOT `@Serializable`: a value class's generated serializer decodes by calling this
 * constructor, so a malformed ref inside a request body would surface as [IllegalArgumentException]
 * rather than `SerializationException` and sail past a route's decode catch as a 500 — the same trap
 * `ImportSessionRequest` records. Wire DTOs therefore carry a plain `String` and the handler parses it
 * with [parseOrNull].
 */
@JvmInline
value class TaskRef(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskRef must not be blank" }
        require(value.length <= MAX_LENGTH) {
            "TaskRef must be at most $MAX_LENGTH characters, was ${value.length}"
        }
        require(FORMAT.matches(value)) {
            "TaskRef must be '<tracker>:<key>', each half starting with a letter or digit and " +
                "containing only letters, digits, '_' or '-': '$value'"
        }
    }

    /** The tracker half (`"local"` for the built-in tracker). */
    val tracker: String get() = value.substringBefore(':')

    /** The tracker-local key half (`"42"`). */
    val key: String get() = value.substringAfter(':')

    companion object {
        /** Upper bound on a ref's length — it travels in URLs and in argv. */
        const val MAX_LENGTH: Int = 128

        /** The built-in tracker's id — the only [TaskTracker][io.kotgent.task.TaskTracker] today. */
        const val LOCAL_TRACKER: String = "local"

        private val FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*:[A-Za-z0-9][A-Za-z0-9_-]*$")

        /** [value] as a [TaskRef], or `null` when it does not satisfy the invariant above. */
        fun parseOrNull(value: String): TaskRef? = runCatching { TaskRef(value) }.getOrNull()
    }
}

/**
 * A project's identity — the canonical UUID committed in the project's `.kotgent.json`.
 *
 * A project is keyed by that uuid rather than by a path on purpose: `/repo` and `/repo-wt/feature` are
 * one body of work but two strings, so a path key would give a `git worktree` its own, empty backlog. A
 * committed uuid survives a move, a rename and a clone.
 *
 * The file arrives with somebody else's repository, so the value is untrusted input and the invariant is
 * checked here with [isCanonicalUuid] — the same boundary rule the Claude/Codex hook normalizers apply.
 * Not `@Serializable`, for the reason spelled out on [TaskRef].
 *
 * ## Case is NORMALIZED here, and that is a correctness rule
 * [isCanonicalUuid] is case-INSENSITIVE, but `projects.id`, `backlog_entries.project` and
 * `sessions.project_id` are `TEXT` columns SQLite compares **binary**. So two spellings of one uuid —
 * `.kotgent.json` hand-edited to upper case in one worktree, lower case in another — would key two
 * `projects` rows with two backlogs that can never see each other. The constructor is therefore private
 * and every value arrives through [of] / [parseOrNull], which lower-case it: exactly the rule
 * `SessionManager.importSession` already applies to a UUID-shaped provider id, moved to the type so
 * there is no boundary left to forget it at. Lower-casing is safe *because* the value is a uuid — case
 * is not significant in one — which is why it is not done to [TaskRef] or [ProviderSessionId].
 */
@JvmInline
value class ProjectId private constructor(val value: String) {

    companion object {
        /**
         * [value] as a [ProjectId], lower-cased. Throws [IllegalArgumentException] when it is not a
         * canonical uuid — use [parseOrNull] at a boundary that must answer `400` instead.
         */
        fun of(value: String): ProjectId {
            require(isCanonicalUuid(value)) { "ProjectId must be a canonical uuid: '$value'" }
            return ProjectId(value.lowercase())
        }

        /** [value] as a lower-cased [ProjectId], or `null` when it is not a canonical uuid. */
        fun parseOrNull(value: String): ProjectId? =
            if (isCanonicalUuid(value)) ProjectId(value.lowercase()) else null
    }
}

/**
 * tmux pane id — the runtime correlation handle (`#{pane_id}`, e.g. `%3`) captured from
 * `new-session -P -F '#{pane_id}'` and sent by hooks as `$TMUX_PANE`. tmux pane ids are
 * always `%` followed by digits; anything else is a bug in the caller, so it is rejected.
 */
@Serializable
@JvmInline
value class PaneId(val value: String) {
    init {
        require(FORMAT.matches(value)) { "PaneId must look like a tmux pane id (%<n>): '$value'" }
    }

    companion object {
        private val FORMAT = Regex("^%\\d+$")
    }
}
