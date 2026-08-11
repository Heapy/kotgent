package io.kotgent.adapter.codex

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner

data class CodexVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<CodexVersion> {
    override fun compareTo(other: CodexVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

class CodexCli(
    val binaryName: String = "codex",
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {
    fun locate(): String? {
        val result = runner(listOf("command", "-v", binaryName))
        if (!result.isSuccess) return null
        return result.stdout.trim().ifEmpty { null }
    }

    fun detectVersion(): CodexVersion? {
        val result = runner(listOf(binaryName, "--version"))
        if (!result.isSuccess) return null
        return parseVersion(result.stdout)
    }

    fun isInstalled(): Boolean = detectVersion() != null

    companion object {
        private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")

        fun parseVersion(output: String): CodexVersion? {
            val match = SEMVER.find(output) ?: return null
            val (major, minor, patch) = match.destructured
            return CodexVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
