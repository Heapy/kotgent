package io.kotgent

/** Build metadata is deliberately a UI-only detail. */
fun versionLine(): String = "kotgent $VERSION"

/** Source builds include their commit; release artifacts show only the tagged version. */
fun currentUiVersion(): String = formatUiVersion(VERSION, BUILD_GIT_HASH, BUILD_IS_RELEASE)

/** A malformed hash is treated as missing. */
fun formatUiVersion(
    version: String,
    gitHash: String?,
    releaseBuild: Boolean,
): String {
    if (releaseBuild) return version
    val hash = gitHash
        ?.trim()
        ?.takeIf { it.matches(GIT_HASH_PATTERN) }
        ?: return version
    return "$version+$hash"
}

private val GIT_HASH_PATTERN = Regex("[0-9a-f]{7,64}")
