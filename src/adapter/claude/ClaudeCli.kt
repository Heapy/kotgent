package io.kotgent.adapter.claude

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner

data class ClaudeVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ClaudeVersion> {
    override fun compareTo(other: ClaudeVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

class ClaudeCli(
    val binaryName: String = "claude",
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {
    fun locate(): String? {
        val result = runner(listOf("command", "-v", binaryName))
        if (!result.isSuccess) return null
        return result.stdout.trim().ifEmpty { null }
    }

    fun detectVersion(): ClaudeVersion? {
        val result = runner(listOf(binaryName, "--version"))
        if (!result.isSuccess) return null
        return parseVersion(result.stdout)
    }

    fun isInstalled(): Boolean = detectVersion() != null

    fun supportsSessionId(): Boolean = supportsSessionId(detectVersion())

    companion object {
        /**
         * The exact introduction of `--session-id` is undocumented. Versions below this conservative
         * floor use the `SessionStart` fallback.
         */
        val MIN_SESSION_ID_VERSION: ClaudeVersion = ClaudeVersion(1, 0, 0)

        private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")

        fun parseVersion(output: String): ClaudeVersion? {
            val match = SEMVER.find(output) ?: return null
            val (major, minor, patch) = match.destructured
            return ClaudeVersion(major.toInt(), minor.toInt(), patch.toInt())
        }

        fun supportsSessionId(version: ClaudeVersion?): Boolean =
            version != null && version >= MIN_SESSION_ID_VERSION
    }
}
