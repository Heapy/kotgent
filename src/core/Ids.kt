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
 * [AgentEvent.SessionBound] event and stored in `sessions.provider_session_id`. Validated as
 * a UUID so a malformed id fails fast instead of silently blocking `resume`.
 */
@Serializable
@JvmInline
value class ProviderSessionId(val value: String) {
    init {
        require(UUID_FORMAT.matches(value)) { "ProviderSessionId must be a UUID: '$value'" }
    }

    companion object {
        private val UUID_FORMAT =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
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
