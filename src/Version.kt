package io.kotgent

/** The CLI's stable release-version line. Build metadata is deliberately a UI-only detail. */
fun versionLine(): String = "kotgent $VERSION"

/**
 * The version shown by the Web UI for the running binary.
 *
 * Ordinary source builds include the commit embedded by the build-info plugin; release artifacts omit
 * it, so the binary downloaded by Homebrew shows only the tagged version.
 */
fun currentUiVersion(): String = formatUiVersion(VERSION, BUILD_GIT_HASH, BUILD_IS_RELEASE)

/**
 * Pure display policy, kept separate from generated build metadata so every fallback is testable.
 *
 * A malformed hash is treated like a missing one. The generator emits either a validated hash or a blank
 * fallback; this guard keeps the same policy if metadata is ever supplied by another build path.
 */
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
