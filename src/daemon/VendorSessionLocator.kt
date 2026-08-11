package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId

// Provider stores encode cwd differently; a located value is revalidated by the import probe.
fun interface VendorSessionLocator {
    suspend fun cwdOf(agent: String, providerSessionId: ProviderSessionId): String?
}

fun byAgentVendorSessionLocator(locators: Map<String, VendorSessionLocator>): VendorSessionLocator =
    VendorSessionLocator { agent, providerSessionId ->
        locators[agent]?.cwdOf(agent, providerSessionId)
    }

fun productionSessionLocator(
    claudeDir: String = defaultClaudeDir(),
    codexDir: String = defaultCodexDir(),
    junieDir: String = defaultJunieDir(),
): VendorSessionLocator = byAgentVendorSessionLocator(
    mapOf(
        CLAUDE_AGENT_KIND to claudeSessionLocator(claudeDir),
        CODEX_AGENT_KIND to codexSessionLocator(codexDir),
        JUNIE_AGENT_KIND to junieSessionLocator(junieDir),
    ),
)

const val CLAUDE_CWD_SCAN_BYTES: Int = 64 * 1024

// Transcript heads may end mid-record, so malformed/truncated lines are skipped.
fun claudeTranscriptCwd(head: String): String? =
    head.lineSequence().firstNotNullOfOrNull(::rolloutCwd)

fun claudeSessionLocator(claudeDir: String = defaultClaudeDir()): VendorSessionLocator =
    VendorSessionLocator { _, providerSessionId ->
        val projects = "${claudeDir.trimEnd('/')}/projects"
        listDir(projects).firstNotNullOfOrNull { project ->
            readHead("$projects/$project/${providerSessionId.value}.jsonl", CLAUDE_CWD_SCAN_BYTES)
                ?.let(::claudeTranscriptCwd)
        }
    }

fun codexSessionLocator(codexDir: String = defaultCodexDir()): VendorSessionLocator {
    val scan = CodexRolloutScan(codexDir)
    return VendorSessionLocator { _, providerSessionId -> scan.cwdOf(providerSessionId) }
}
