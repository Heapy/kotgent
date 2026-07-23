package io.kotgent.adapter.codex

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner

/**
 * A parsed Codex CLI semantic version (`major.minor.patch`). `codex --version` prints
 * `codex-cli 0.145.0` — the `codex-cli ` prefix is ignored.
 */
data class CodexVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<CodexVersion> {
    override fun compareTo(other: CodexVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Locates the `codex` binary and detects its version — the Codex counterpart of
 * [io.kotgent.adapter.claude.ClaudeCli], minus the feature gate.
 *
 * ## Why there is no `supportsSessionId`-style gate
 * Codex has no `--session-id` equivalent, so there is nothing to version-gate: [CodexAdapter] always
 * takes the "id captured after launch" path (the `SessionStart` hook / the rollout scan). The version
 * is still worth detecting — it fills `sessions.cli_version` and is what a support question starts from.
 *
 * As with `ClaudeCli`, the pure parsing ([parseVersion]) is separated from binary invocation
 * ([locate] / [detectVersion]) so it is unit-testable with no live CLI, and [runner] is injectable
 * (default [ProcessRunner], stock `platform.posix` `popen` — links into the test binary fine).
 */
class CodexCli(
    /** The program name (resolved on PATH) or an explicit path to the codex binary. */
    val binaryName: String = "codex",
    /** How a subprocess is run — injectable so tests avoid the real binary. */
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {

    /**
     * The path `codex` resolves to on PATH (`command -v codex`), or `null` if it is not found.
     * Best-effort — [detectVersion]/[isInstalled] are the authoritative "is it usable?" checks.
     */
    fun locate(): String? {
        val result = runner(listOf("command", "-v", binaryName))
        if (!result.isSuccess) return null
        return result.stdout.trim().ifEmpty { null }
    }

    /** Run `codex --version` and parse it, or `null` if codex is absent / the call failed. */
    fun detectVersion(): CodexVersion? {
        val result = runner(listOf(binaryName, "--version"))
        if (!result.isSuccess) return null
        return parseVersion(result.stdout)
    }

    /** Whether a usable `codex` is installed (its `--version` runs and parses). */
    fun isInstalled(): Boolean = detectVersion() != null

    companion object {
        private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** Extract the first `major.minor.patch` triple from arbitrary version output, or `null`. */
        fun parseVersion(output: String): CodexVersion? {
            val match = SEMVER.find(output) ?: return null
            val (major, minor, patch) = match.destructured
            return CodexVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
