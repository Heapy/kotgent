package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

/*
 * The real (production) [VendorStoreProbe] for Claude — closes the Task-15 gap where the daemon's
 * Reconciler used a stub `{ false }`, so genuine `resumable` classification never fired in production.
 *
 * Claude Code stores each session's transcript as a JSONL file under a per-project directory:
 *
 *     ~/.claude/projects/<encoded-cwd>/<provider-session-id>.jsonl
 *
 * A dead session is *resumable* iff that file survives on disk (its conversation can be revived with
 * `claude --resume <id>`); otherwise the exit is a dead-end `crashed`. The probe therefore stats the
 * exact path for a session's cwd + provider-session-id.
 *
 * The path convention was verified against a real `~/.claude/projects` (2026-07): every character that
 * is not ASCII alphanumeric is replaced 1:1 with '-' (so '/', '.', '_', spaces all map to '-', and an
 * existing '-' is preserved), with NO collapsing of consecutive dashes. Confirming examples seen on disk:
 *   /Users/yoda/dev/pet                                     -> -Users-yoda-dev-pet
 *   /Users/yoda/dev/os/kotlinx.serialization                -> -Users-yoda-dev-os-kotlinx-serialization
 *   /…/bond-customer-app-backend/.claude-worktrees/perf-…   -> …-bond-customer-app-backend--claude-worktrees-perf-…
 * (the `/.claude-worktrees` segment yields a double dash — '/' and '.' each contribute one '-').
 */

/**
 * Encodes [cwd] into Claude Code's per-project transcript directory name (see the file header):
 * every non-`[A-Za-z0-9]` character becomes '-', 1:1, no collapsing. Pure and host-free.
 */
fun encodeClaudeProjectDir(cwd: String): String = buildString(cwd.length) {
    for (c in cwd) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
}

/**
 * The absolute path of the transcript JSONL Claude Code would write for a session launched in [cwd]
 * with provider id [providerSessionId], rooted at [claudeDir] (the `~/.claude` base):
 * `<claudeDir>/projects/<encoded-cwd>/<provider-session-id>.jsonl`. Pure and host-free (no I/O).
 */
fun claudeTranscriptPath(claudeDir: String, cwd: String, providerSessionId: ProviderSessionId): String =
    "${claudeDir.trimEnd('/')}/projects/${encodeClaudeProjectDir(cwd)}/${providerSessionId.value}.jsonl"

/** `~/.claude` from `$HOME` (falls back to a cwd-relative `.claude` if `$HOME` is unset — degrades, never crashes). */
@OptIn(ExperimentalForeignApi::class)
fun defaultClaudeDir(): String {
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".claude" else "$home/.claude"
}

/**
 * The production [VendorStoreProbe]: a dead session is resumable iff Claude's transcript JSONL for its
 * (cwd, provider-session-id) exists on disk under [claudeDir] — an O(1) `access(F_OK)` on the exact path.
 *
 * Host-free by injection: the default roots at the real `~/.claude` ([defaultClaudeDir]), but a test
 * points [claudeDir] at a throwaway fake home so it never reads the real one. Wired into the daemon's
 * Reconciler in place of the Task-15 `{ false }` stub.
 */
@OptIn(ExperimentalForeignApi::class)
fun claudeVendorStoreProbe(claudeDir: String = defaultClaudeDir()): VendorStoreProbe =
    VendorStoreProbe { _, cwd, providerSessionId ->
        access(claudeTranscriptPath(claudeDir, cwd, providerSessionId), F_OK) == 0
    }
