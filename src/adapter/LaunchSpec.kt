package io.kotgent.adapter

import io.kotgent.core.ProviderSessionId

/**
 * How to launch an agent: a fresh conversation ([New]) or a continuation of an existing one
 * ([Resume]). The input to [AgentAdapter.buildLaunchSpec], kept provider-neutral so the daemon
 * (Task 13) chooses the mode and the concrete adapter (e.g. `ClaudeAdapter`, Task 11) decides how
 * to render it into an argv.
 *
 * The asymmetry is intentional: a [New] launch has no provider id yet — the adapter preallocates
 * one and surfaces it via [LaunchSpec.preallocatedSessionId] — whereas a [Resume] must carry the
 * existing [providerSessionId] so the CLI can re-address the saved transcript (Claude:
 * `--resume <id>`). This is the only session-identity information [buildLaunchSpec] needs.
 */
sealed interface LaunchMode {
    /**
     * Start a brand-new conversation. The adapter is responsible for preallocating the provider
     * session id (so `SessionBound` can fire immediately rather than waiting on a `SessionStart`
     * hook) and echoing it back in [LaunchSpec.preallocatedSessionId].
     */
    data object New : LaunchMode

    /**
     * Continue the conversation identified by [providerSessionId] (captured earlier via
     * [io.kotgent.core.AgentEvent.SessionBound]). The id is required — a resume with no id is
     * meaningless — so it is a constructor field rather than a nullable on the spec.
     */
    data class Resume(val providerSessionId: ProviderSessionId) : LaunchMode
}

/**
 * A provider-neutral, fully-resolved description of how to spawn an agent process — the value the
 * daemon (Task 13) hands to tmux `new-session` to launch the agent. Produced by
 * [AgentAdapter.buildLaunchSpec]; it is a pure data value (no IO), so it is trivially testable and
 * the launch decision is separated from the act of launching.
 *
 * Field shape mirrors the plan's `sessions` launch context and the `ProcessRunner`/tmux argv model:
 * a [command] argv (never a shell string — arguments stay un-split), the [env] to export, and the
 * [cwd] to run in.
 */
data class LaunchSpec(
    /** The argv to exec — program plus arguments, already split (no shell interpretation). */
    val command: List<String>,
    /** Environment variables to export for the process (added to / overriding the inherited env). */
    val env: Map<String, String>,
    /** Working directory the agent runs in (the session's `cwd`). */
    val cwd: String,
    /**
     * For a [LaunchMode.New] launch, the provider session id the adapter preallocated and passed to
     * the CLI (Claude: `--session-id <uuid>`), so the daemon can emit
     * [io.kotgent.core.AgentEvent.SessionBound] up front without waiting on a `SessionStart` hook.
     * `null` for a [LaunchMode.Resume] (the id already exists and is carried inside [command]).
     */
    val preallocatedSessionId: ProviderSessionId? = null,
    /**
     * The agent CLI's version string (e.g. `"2.1.218"`), as detected once at daemon bootstrap. Pure
     * metadata echoed here — the IO already happened when the adapter was constructed, so
     * [io.kotgent.adapter.AgentAdapter.buildLaunchSpec] stays pure — so the daemon can persist it onto the
     * session (see [io.kotgent.core.SessionMeta.cliVersion]). `null` when the version could not be detected.
     */
    val cliVersion: String? = null,
    /**
     * Absolute path the agent CLI resolved to (argv[0]), as located at bootstrap. Same metadata role as
     * [cliVersion]; persisted onto [io.kotgent.core.SessionMeta.cliPath]. `null` when it could not be located.
     */
    val cliPath: String? = null,
)
