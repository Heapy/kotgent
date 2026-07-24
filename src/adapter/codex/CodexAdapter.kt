package io.kotgent.adapter.codex

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * The Codex [AgentAdapter] — the OUTGOING side of the Codex integration, and the second provider
 * behind the [AgentAdapter] seam:
 *
 *  - [buildLaunchSpec] renders the `codex` argv. A [LaunchMode.New] starts a fresh conversation;
 *    a [LaunchMode.Resume] re-addresses the saved one with `codex resume <id>`. Both install the hook
 *    config for THIS LAUNCH ONLY via `-c hooks={…}` (see [CodexHookConfig]) plus
 *    `-c bypass_hook_trust=true`.
 *
 *  - [events] is an INJECTED seam, exactly as in
 *    [io.kotgent.adapter.claude.ClaudeAdapter]: real events arrive through the `/hooks/codex` ingress,
 *    which appends straight to the `EventStore` (the single ordering authority). This adapter produces
 *    no events itself.
 *
 * ## No preallocated session id
 * Codex has no `claude --session-id` equivalent, so [LaunchSpec.preallocatedSessionId] is ALWAYS `null`
 * and the id is captured after launch — from the `SessionStart` hook, or by
 * [io.kotgent.daemon.CodexRolloutScan] reading the rollout file Codex writes for the session. Until one
 * of them lands, the session is "id pending" and `resume` is refused, which is the pre-existing fallback
 * path [io.kotgent.daemon.ProviderIdCapture] already implements.
 *
 * ## Why hook trust is bypassed
 * Codex marks a hook it has not seen before as `trustStatus: untrusted` and would otherwise prompt for
 * confirmation. The hook here is one kotgent generated, wrote `0600` under its own home, and passed by
 * absolute path in the same argv — there is no third-party code to vet, and an interactive trust prompt
 * on every launch would make an automated start impossible. The bypass is scoped to this launch (it is
 * a `-c` session-layer override), so it never relaxes trust for the user's own codex sessions.
 */
class CodexAdapter(
    /** Working directory the agent runs in (the session's `cwd`). */
    private val cwd: String,
    /** Absolute path of the generated hook script (`codex-hook.sh`), written by the daemon. */
    private val hookScriptPath: String,
    /**
     * The normalized event stream, fed by the hook ingress. Injected, not produced here — the adapter
     * only exposes it so downstream (reducer/store/transport) can fold it.
     */
    override val events: Flow<AgentEvent>,
    /** The codex program name / path used as argv[0]. */
    private val binaryName: String = "codex",
    /** Extra environment for the launch (kept minimal; `KOTGENT_SESSION_ID` is a tmux-side debug label). */
    private val env: Map<String, String> = emptyMap(),
    /** Detected `codex` version (e.g. `"0.145.0"`), echoed onto every [LaunchSpec.cliVersion]. */
    private val cliVersion: String? = null,
    /** Resolved `codex` path, echoed onto every [LaunchSpec.cliPath]. */
    private val cliPath: String? = null,
) : AgentAdapter {

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec {
        val command = buildList {
            add(binaryName)
            // `resume <id>` must come before the -c overrides: it is a subcommand plus its positional
            // argument, and codex parses the subcommand first.
            if (mode is LaunchMode.Resume) {
                add(RESUME_SUBCOMMAND)
                add(mode.providerSessionId.value)
            }
            add(CONFIG_FLAG)
            add(CodexHookConfig.hooksToml(hookScriptPath))
            add(CONFIG_FLAG)
            add(BYPASS_HOOK_TRUST)
        }
        return LaunchSpec(
            command = command,
            env = env,
            cwd = cwd,
            // Never preallocated — see the class KDoc.
            preallocatedSessionId = null,
            cliVersion = cliVersion,
            cliPath = cliPath,
        )
    }

    companion object {
        /** `codex resume <SESSION_ID>` — continue a recorded conversation. */
        const val RESUME_SUBCOMMAND: String = "resume"

        /** Codex's config-override flag: `-c key=value`, value parsed as TOML. */
        const val CONFIG_FLAG: String = "-c"

        /** Runs kotgent's own generated hook without the interactive trust prompt (see the class KDoc). */
        const val BYPASS_HOOK_TRUST: String = "bypass_hook_trust=true"
    }
}
