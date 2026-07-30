package io.kotgent.adapter.junie

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * The Junie [AgentAdapter] — the OUTGOING side of the Junie integration, and the third provider behind
 * the [AgentAdapter] seam:
 *
 *  - [buildLaunchSpec] renders the `junie` argv. A [LaunchMode.New] starts a fresh session;
 *    a [LaunchMode.Resume] re-addresses the saved one with `--resume --session-id <id>`. Both install the
 *    hook config for THIS LAUNCH ONLY via `--config-location <file>` (see [JunieHookConfig]).
 *
 *  - [events] is an INJECTED seam, exactly as in [io.kotgent.adapter.claude.ClaudeAdapter] /
 *    [io.kotgent.adapter.codex.CodexAdapter]: real events arrive through the `/hooks/junie` ingress,
 *    which appends straight to the `EventStore` (the single ordering authority). This adapter produces
 *    no events itself.
 *
 * ## No preallocated session id
 * Junie's `--session-id` only selects an EXISTING session to follow up (it is the argument of `--resume`),
 * so there is no `claude --session-id <fresh-uuid>` equivalent: [LaunchSpec.preallocatedSessionId] is
 * ALWAYS `null` and the id is captured after launch — from a `SessionStart` hook if one ever carries it,
 * or by [io.kotgent.daemon.JunieSessionScan] reading the session index Junie writes. Until one of them
 * lands, the session is "id pending" and `resume` is refused, which is the pre-existing fallback path
 * [io.kotgent.daemon.ProviderIdCapture] already implements (same as Codex).
 *
 * ## The cwd is set by tmux, not by `--project`
 * `junie` takes a `--project` flag, but kotgent launches every provider through
 * `tmux new-session -c <cwd>`, so the pane's own working directory already IS the session cwd — and it is
 * the value the provider records as its project dir, which is what the import/resumability probes match
 * on. Passing the directory twice could only introduce a way for the two to disagree.
 */
class JunieAdapter(
    /** Working directory the agent runs in (the session's `cwd`). */
    private val cwd: String,
    /** Absolute path of the generated hook config (`junie-hooks.json`), written by the daemon. */
    private val hookConfigPath: String,
    /**
     * The normalized event stream, fed by the hook ingress. Injected, not produced here — the adapter
     * only exposes it so downstream (reducer/store/transport) can fold it.
     */
    override val events: Flow<AgentEvent>,
    /** The junie program name / path used as argv[0]. */
    private val binaryName: String = "junie",
    /** Extra environment for the launch (kept minimal; `KOTGENT_SESSION_ID` is a tmux-side debug label). */
    private val env: Map<String, String> = emptyMap(),
    /** Detected `junie` version (e.g. `"26.8.3"`), echoed onto every [LaunchSpec.cliVersion]. */
    private val cliVersion: String? = null,
    /** Resolved `junie` path, echoed onto every [LaunchSpec.cliPath]. */
    private val cliPath: String? = null,
) : AgentAdapter {

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec {
        val command = buildList {
            add(binaryName)
            if (mode is LaunchMode.Resume) {
                add(RESUME_FLAG)
                add(SESSION_ID_FLAG)
                add(mode.providerSessionId.value)
            }
            add(CONFIG_LOCATION_FLAG)
            add(hookConfigPath)
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
        /** `junie --resume` — continue a recorded session (paired with [SESSION_ID_FLAG]). */
        const val RESUME_FLAG: String = "--resume"

        /** `--session-id <id>` — which recorded session [RESUME_FLAG] should continue. */
        const val SESSION_ID_FLAG: String = "--session-id"

        /** Junie's extra-config flag; the one hook layer scoped to a single launch (see [JunieHookConfig]). */
        const val CONFIG_LOCATION_FLAG: String = "--config-location"
    }
}
