package io.kotgent.adapter.shell

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.newUuidV4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Adapts a plain login shell to kotgent's ordinary provider-neutral launch seam.
 *
 * A shell has no vendor session to identify, but a fresh launch still receives a synthetic id for
 * two existing provider-neutral contracts: [io.kotgent.daemon.SessionManager.resume] refuses rows
 * whose provider id is pending, and [io.kotgent.daemon.Reconciler] skips its vendor-store probe when
 * that id is null. The id therefore identifies nothing outside kotgent; it only makes a shell row
 * resumable through the same paths as every other agent kind.
 *
 * The default generator deliberately reuses [newUuidV4], the existing public and tested source of
 * collision-resistant ids, instead of duplicating its entropy/encoding logic or moving it solely for
 * naming. It is injectable so tests remain deterministic. Only [LaunchMode.New] mints an id;
 * [LaunchMode.Resume] has no external conversation to address and launches the same shell argv.
 */
class ShellAdapter(
    /** Working directory in which tmux starts the login shell. */
    private val cwd: String,
    /** Absolute executable selected by `currentLoginShell()`. */
    private val shell: String,
    /** Synthetic binding-id generator, injectable for deterministic tests. */
    private val generateSessionId: () -> ProviderSessionId = { ProviderSessionId(newUuidV4()) },
) : AgentAdapter {

    /** A shell has no provider hooks and therefore emits no canonical agent events. */
    override val events: Flow<AgentEvent> = emptyFlow()

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = LaunchSpec(
        command = listOf(shell, "-l"),
        env = emptyMap(),
        cwd = cwd,
        preallocatedSessionId = when (mode) {
            LaunchMode.New -> generateSessionId()
            is LaunchMode.Resume -> null
        },
        cliVersion = null,
        cliPath = shell,
    )
}
