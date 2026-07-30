package io.kotgent.adapter.junie

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner

/**
 * A parsed Junie CLI version (`major.minor.patch`). `junie --version` prints
 * `Junie version: 26.8.3 (2548.3) eap` — the label, the build number in parentheses and the channel
 * suffix are all ignored; only the leading semantic triple is modeled.
 */
data class JunieVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<JunieVersion> {
    override fun compareTo(other: JunieVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Locates the `junie` binary and detects its version — the Junie counterpart of
 * [io.kotgent.adapter.claude.ClaudeCli] / [io.kotgent.adapter.codex.CodexCli].
 *
 * ## Why there is no feature gate
 * Junie has no `--session-id`-on-a-fresh-launch equivalent to gate (the id is captured after launch,
 * like Codex), and its hooks — which kotgent's state tracking rides on — are an EAP feature that is not
 * discoverable from `--version` output. A junie that ignores the hook config still launches and attaches
 * fine; the session's state simply stays coarse. Gating the launch on a version would therefore refuse a
 * usable session for a capability that is only nice-to-have, so the version is detected purely for
 * `sessions.cli_version` — the handle a support question starts from.
 *
 * As with its peers, the pure parsing ([parseVersion]) is separated from binary invocation
 * ([locate] / [detectVersion]) so it is unit-testable with no live CLI, and [runner] is injectable
 * (default [ProcessRunner], stock `platform.posix` `popen` — links into the test binary fine).
 */
class JunieCli(
    /** The program name (resolved on PATH) or an explicit path to the junie binary. */
    val binaryName: String = "junie",
    /** How a subprocess is run — injectable so tests avoid the real binary. */
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {

    /**
     * The path `junie` resolves to on PATH (`command -v junie`), or `null` if it is not found.
     * Best-effort — [detectVersion]/[isInstalled] are the authoritative "is it usable?" checks.
     */
    fun locate(): String? {
        val result = runner(listOf("command", "-v", binaryName))
        if (!result.isSuccess) return null
        return result.stdout.trim().ifEmpty { null }
    }

    /** Run `junie --version` and parse it, or `null` if junie is absent / the call failed. */
    fun detectVersion(): JunieVersion? {
        val result = runner(listOf(binaryName, "--version"))
        if (!result.isSuccess) return null
        return parseVersion(result.stdout)
    }

    /** Whether a usable `junie` is installed (its `--version` runs and parses). */
    fun isInstalled(): Boolean = detectVersion() != null

    companion object {
        private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** Extract the first `major.minor.patch` triple from arbitrary version output, or `null`. */
        fun parseVersion(output: String): JunieVersion? {
            val match = SEMVER.find(output) ?: return null
            val (major, minor, patch) = match.destructured
            return JunieVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
