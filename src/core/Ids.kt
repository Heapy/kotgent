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
