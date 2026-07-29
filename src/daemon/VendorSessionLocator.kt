package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId

/*
 * Locating the project directory (`cwd`) a provider session was launched in, by provider session id —
 * the discovery half of `kotgent import`. A Web UI / phone client has no pwd to send, and typing an
 * absolute path on a phone is the main error source, so discovery is in v1 rather than "explicit cwd
 * only".
 *
 * Providers disagree on where the answer lives:
 *  - **Claude** namespaces transcripts per project dir (`~/.claude/projects/<encoded-cwd>/<id>.jsonl`),
 *    but [encodeClaudeProjectDir] is irreversible — so the cwd is found by scanning the `projects`
 *    subdirectories for `<id>.jsonl` (one `opendir` + an `access()` per subdirectory, never a listing
 *    of all transcripts) and reading the recorded `"cwd"` field out of the transcript's head
 *    ([claudeTranscriptCwd]).
 *  - **Codex** names a rollout by id alone and records `cwd` in its first (`session_meta`) line —
 *    [CodexRolloutScan.cwdOf]. Archived rollouts do not answer (out of `codex resume`'s reach).
 *
 * A located cwd is a CANDIDATE, not a verdict: import re-probes `(agent, cwd, id)` with the same
 * [VendorStoreProbe] the Reconciler will use, so a recorded cwd that re-encodes into a different
 * project dir (e.g. `/tmp` vs `/private/tmp`) fails the import loudly instead of silently degrading
 * `resumable → crashed` on the next daemon restart.
 */

/**
 * Finds the `cwd` a provider session was launched in, or `null` when the provider's on-disk store has
 * no live record for [providerSessionId]. Injected into the import path so it stays host-free and
 * unit-testable with a fake; the real per-provider lookups are [claudeSessionLocator] and
 * [codexSessionLocator], dispatched by [byAgentVendorSessionLocator]. `suspend` to match its twin
 * [SessionManager] seam [VendorStoreProbe.hasTranscript] — both are blocking filesystem scans behind
 * required constructor seams, and a test fake must be able to park inside either.
 */
fun interface VendorSessionLocator {
    suspend fun cwdOf(agent: String, providerSessionId: ProviderSessionId): String?
}

/**
 * Dispatch to the per-provider locator registered for [agent] (mirrors [byAgentVendorStoreProbe]).
 * An agent kind with no registered locator answers `null` — "nothing known", never an exception.
 */
fun byAgentVendorSessionLocator(locators: Map<String, VendorSessionLocator>): VendorSessionLocator =
    VendorSessionLocator { agent, providerSessionId ->
        locators[agent]?.cwdOf(agent, providerSessionId)
    }

/**
 * The PRODUCTION locator dispatch (mirrors [productionVendorStoreProbe]): the real per-provider cwd
 * lookups the daemon wires into [SessionManager.importSession], with the vendor homes injectable so
 * the import wiring test drives this same function over throwaway dirs.
 */
fun productionSessionLocator(
    claudeDir: String = defaultClaudeDir(),
    codexDir: String = defaultCodexDir(),
): VendorSessionLocator = byAgentVendorSessionLocator(
    mapOf(
        CLAUDE_AGENT_KIND to claudeSessionLocator(claudeDir),
        CODEX_AGENT_KIND to codexSessionLocator(codexDir),
    ),
)

/**
 * How much of a Claude transcript [claudeSessionLocator] reads before scanning ([claudeTranscriptCwd]).
 * `cwd` sits near the START of each message record, so the window only needs to span the small summary
 * records plus the head of the first message — 64 KB is generous headroom while keeping the read O(1).
 * This byte window is the ONLY bound on the scan: a second line-count cap used to sit on top of it and
 * added nothing but a failure mode (a cwd first appearing past the line cap but inside the byte window
 * was rejected), so it was removed.
 */
const val CLAUDE_CWD_SCAN_BYTES: Int = 64 * 1024

/**
 * The recorded `cwd` in a Claude transcript head: the first `"cwd":"…"` field in the (byte-bounded)
 * [head], or `null`. Pure and host-free. Garbage / empty / truncated lines are skipped, not fatal
 * (the caller hands over a bounded head whose last line may be cut off — the per-line field scan is
 * [rolloutCwd], which already refuses a value missing its closing quote). The caller's byte window
 * ([CLAUDE_CWD_SCAN_BYTES]) is the only bound — see its KDoc.
 */
fun claudeTranscriptCwd(head: String): String? =
    head.lineSequence().firstNotNullOfOrNull(::rolloutCwd)

/**
 * The production [VendorSessionLocator] for Claude (see the file header): one `opendir` over
 * `<claudeDir>/projects/`, then [readHead] on `<dir>/<id>.jsonl` in each project subdirectory (the id
 * is a validated UUID, so the joined path is safe; `readHead` answers `null` for a missing file, so no
 * separate existence pre-check is needed), then [claudeTranscriptCwd] over the found transcript's
 * head. First match wins; a missing/unreadable home degrades to `null`.
 */
fun claudeSessionLocator(claudeDir: String = defaultClaudeDir()): VendorSessionLocator =
    VendorSessionLocator { _, providerSessionId ->
        val projects = "${claudeDir.trimEnd('/')}/projects"
        listDir(projects).firstNotNullOfOrNull { project ->
            readHead("$projects/$project/${providerSessionId.value}.jsonl", CLAUDE_CWD_SCAN_BYTES)
                ?.let(::claudeTranscriptCwd)
        }
    }

/**
 * The production [VendorSessionLocator] for Codex: [CodexRolloutScan.cwdOf] behind the uniform
 * `(agent, id)` shape (the agent kind is accepted and ignored — dispatch already chose this locator).
 */
fun codexSessionLocator(codexDir: String = defaultCodexDir()): VendorSessionLocator {
    val scan = CodexRolloutScan(codexDir)
    return VendorSessionLocator { _, providerSessionId -> scan.cwdOf(providerSessionId) }
}
