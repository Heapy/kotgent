package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

// Claude replaces every non-ASCII-alphanumeric character 1:1; consecutive dashes are significant.
fun encodeClaudeProjectDir(cwd: String): String = buildString(cwd.length) {
    for (c in cwd) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
}

fun claudeTranscriptPath(claudeDir: String, cwd: String, providerSessionId: ProviderSessionId): String =
    "${claudeDir.trimEnd('/')}/projects/${encodeClaudeProjectDir(cwd)}/${providerSessionId.value}.jsonl"

@OptIn(ExperimentalForeignApi::class)
fun defaultClaudeDir(): String {
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".claude" else "$home/.claude"
}

@OptIn(ExperimentalForeignApi::class)
fun claudeVendorStoreProbe(claudeDir: String = defaultClaudeDir()): VendorStoreProbe =
    // Claude can resume a dead session exactly while this transcript remains in its vendor store.
    VendorStoreProbe { _, cwd, providerSessionId ->
        access(claudeTranscriptPath(claudeDir, cwd, providerSessionId), F_OK) == 0
    }
