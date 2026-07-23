package io.kotgent.adapter.claude

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner

/**
 * A parsed Claude CLI semantic version (`major.minor.patch`) — the minimum needed to gate feature
 * flags. `claude --version` prints e.g. `2.1.218 (Claude Code)`; the trailing label is ignored.
 */
data class ClaudeVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ClaudeVersion> {
    override fun compareTo(other: ClaudeVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Locates the `claude` binary and detects its version, and gates the `--session-id` feature the
 * [ClaudeAdapter] relies on for provider-id preallocation (plan Task 11).
 *
 * ## Pure decision logic vs. binary invocation
 * The version-gate *decision* — given a version string, is `--session-id` supported? — lives in the
 * companion's pure [parseVersion] / [supportsSessionId] functions, unit-testable with no live binary.
 * The instance methods ([locate], [detectVersion], [supportsSessionId]) actually invoke `claude`
 * through the injected [runner] (default [ProcessRunner], stock `platform.posix` `popen` — links into
 * the test binary fine, unlike our own cinterop; see the Task 8 ProcessRunner note). Tests inject a
 * fake [runner] to drive both success and failure paths without touching the real CLI.
 *
 * ## Version gate and the conservative fallback
 * `--session-id <uuid>` is confirmed present in the installed 2.1.x line. The exact version that
 * introduced the flag is not pinned upstream, so [MIN_SESSION_ID_VERSION] is a deliberately
 * conservative floor: at/above it the adapter preallocates the id via `--session-id`; below it — or
 * when the version cannot be parsed at all — [supportsSessionId] returns `false` and the adapter
 * takes the fallback path (launch without `--session-id`; capture the id later from the `SessionStart`
 * hook → `SessionBound`). Being conservative here is safe: the fallback always works, it just gives up
 * up-front preallocation.
 */
class ClaudeCli(
    /** The program name (resolved on PATH) or an explicit path to the claude binary. */
    val binaryName: String = "claude",
    /** How a subprocess is run — injectable so tests avoid the real binary. */
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {

    /**
     * The path `claude` resolves to on PATH (`command -v claude`), or `null` if it is not found.
     * Best-effort — [detectVersion]/[isInstalled] are the authoritative "is it usable?" checks.
     */
    fun locate(): String? {
        val result = runner(listOf("command", "-v", binaryName))
        if (!result.isSuccess) return null
        return result.stdout.trim().ifEmpty { null }
    }

    /** Run `claude --version` and parse it, or `null` if claude is absent / the call failed. */
    fun detectVersion(): ClaudeVersion? {
        val result = runner(listOf(binaryName, "--version"))
        if (!result.isSuccess) return null
        return parseVersion(result.stdout)
    }

    /** Whether a usable `claude` is installed (its `--version` runs and parses). */
    fun isInstalled(): Boolean = detectVersion() != null

    /**
     * Whether THIS installation supports `claude --session-id <uuid>` (invokes the binary once via
     * [detectVersion]). Drives [ClaudeAdapter]'s preallocate-vs-fallback decision.
     */
    fun supportsSessionId(): Boolean = supportsSessionId(detectVersion())

    companion object {
        /**
         * Conservative floor at/above which `claude --session-id <uuid>` is assumed supported.
         * Confirmed present in 2.1.x; the exact introducing version is not pinned upstream, so the
         * floor sits at 1.0.0 — pre-1.0 betas take the `SessionStart`-hook fallback.
         */
        val MIN_SESSION_ID_VERSION: ClaudeVersion = ClaudeVersion(1, 0, 0)

        private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** Extract the first `major.minor.patch` triple from arbitrary version output, or `null`. */
        fun parseVersion(output: String): ClaudeVersion? {
            val match = SEMVER.find(output) ?: return null
            val (major, minor, patch) = match.destructured
            return ClaudeVersion(major.toInt(), minor.toInt(), patch.toInt())
        }

        /**
         * PURE gate: is `--session-id` supported for [version]? A `null` (absent/unparseable)
         * version is treated conservatively as *unsupported* → the caller takes the fallback path.
         */
        fun supportsSessionId(version: ClaudeVersion?): Boolean =
            version != null && version >= MIN_SESSION_ID_VERSION
    }
}
