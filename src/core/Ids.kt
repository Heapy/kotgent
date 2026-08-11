package io.kotgent.core

import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

/** `0` denotes no events; persisted event sequences start at `1`. */
@Serializable
@JvmInline
value class Seq(val value: Long) : Comparable<Seq> {
    init {
        require(value >= 0) { "Seq must be non-negative, was $value" }
    }

    override fun compareTo(other: Seq): Int = value.compareTo(other.value)

    fun next(): Seq = Seq(value + 1)
}

/**
 * Provider ids use a bounded path/argv-safe charset rather than a UUID invariant because Junie ids are
 * not UUIDs. The leading alphanumeric excludes `..` path traversal and flag-like values. Boundaries
 * that specifically require Claude/Codex UUIDs must additionally use [isCanonicalUuid].
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
        const val MAX_LENGTH: Int = 128

        private val SAFE_FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

fun isCanonicalUuid(value: String): Boolean = UUID_FORMAT.matches(value)

private val UUID_FORMAT =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private const val HEX_DIGITS = "0123456789abcdef"

/** Generates a lowercase RFC 4122 version-4 UUID. */
fun newUuidV4(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = buildString(32) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX_DIGITS[v ushr 4])
            append(HEX_DIGITS[v and 0x0f])
        }
    }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

/**
 * A stable `<tracker>:<key>` handle. The mandatory colon prevents literal task routes from being
 * shadowed, while the restricted halves are safe in URLs and argv. It is intentionally not
 * `@Serializable`: malformed constructor input would escape route deserialization as an
 * `IllegalArgumentException`; wire DTOs carry strings and use [parseOrNull].
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

    val tracker: String get() = value.substringBefore(':')

    val key: String get() = value.substringAfter(':')

    companion object {
        const val MAX_LENGTH: Int = 128

        const val LOCAL_TRACKER: String = "local"

        private val FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*:[A-Za-z0-9][A-Za-z0-9_-]*$")

        fun parseOrNull(value: String): TaskRef? = runCatching { TaskRef(value) }.getOrNull()
    }
}

/**
 * Canonical UUID committed in `.kotgent.json`. Identity is path-independent so worktrees share one
 * project. Construction normalizes case because SQLite compares the related TEXT keys byte-for-byte.
 * Like [TaskRef], wire DTOs must parse from strings rather than deserialize this value class directly.
 */
@JvmInline
value class ProjectId private constructor(val value: String) {

    companion object {
        fun of(value: String): ProjectId {
            require(isCanonicalUuid(value)) { "ProjectId must be a canonical uuid: '$value'" }
            return ProjectId(value.lowercase())
        }

        fun parseOrNull(value: String): ProjectId? =
            if (isCanonicalUuid(value)) ProjectId(value.lowercase()) else null

        fun mint(random: Random = Random.Default): ProjectId = of(newUuidV4(random))
    }
}

/** Tmux runtime correlation handle, `%` followed by digits. */
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
