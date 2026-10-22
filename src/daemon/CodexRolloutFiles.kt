package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.isCanonicalUuid
import io.kotgent.host.RegularFileStatus
import io.kotgent.host.regularFileStatus

internal data class RolloutLocation(val canonicalPath: String) {
    companion object {
        fun fromPath(path: String): RolloutLocation? =
            if (rolloutFileSessionId(path.substringAfterLast('/')) == null) null
            else RolloutLocation(path.removeSuffix(".zst"))
    }
}

internal data class RolloutRepresentation(val path: String, val compressed: Boolean)

/** A present but unreadable plain file is authoritative; only absence permits its sibling. */
internal fun resolveRollout(
    location: RolloutLocation,
    status: (String) -> RegularFileStatus = ::regularFileStatus,
): RolloutRepresentation? {
    if (status(location.canonicalPath) != RegularFileStatus.MISSING)
        return RolloutRepresentation(location.canonicalPath, compressed = false)
    val compressed = "${location.canonicalPath}.zst"
    return if (status(compressed) == RegularFileStatus.MISSING) null
    else RolloutRepresentation(compressed, compressed = true)
}

// The timestamp also contains dashes, so the UUID is parsed from the fixed-width suffix.
fun rolloutFileSessionId(fileName: String): ProviderSessionId? {
    val name = fileName.removeSuffix(".zst")
    if (!name.startsWith("rollout-") || !name.endsWith(".jsonl")) return null
    val stem = name.removePrefix("rollout-").removeSuffix(".jsonl")
    if (stem.length < 37 || stem[stem.length - 37] != '-') return null
    val candidate = stem.takeLast(36)
    return if (isCanonicalUuid(candidate)) ProviderSessionId(candidate) else null
}
