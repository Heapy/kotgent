package io.kotgent.daemon

import io.kotgent.adapter.extractModel
import io.kotgent.core.ProviderSessionId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.stat

/*
 * Codex's on-disk session record — the second half of the Codex integration's identity story.
 *
 * Codex writes every session to a "rollout" JSONL:
 *
 *     ~/.codex/sessions/<YYYY>/<MM>/<DD>/rollout-<local-timestamp>-<session-id>.jsonl
 *
 * whose FIRST line is a `session_meta` record carrying `session_id` and `cwd`. Archived sessions move
 * to a FLAT `~/.codex/archived_sessions/` with the same file naming.
 *
 * Two things are read out of that layout, both of which Claude gets for free and Codex does not:
 *
 *  1. **Provider-id discovery.** There is no `claude --session-id` equivalent, so a freshly launched
 *     codex session's id is unknown until something reports it. The `SessionStart` hook is one source;
 *     this scan is the other (and the one that does not depend on hook delivery): find the newest
 *     rollout whose `cwd` matches the session's, and take its id straight out of the FILE NAME.
 *
 *  2. **Resumability.** A dead session is resumable iff its rollout still exists under `sessions/`
 *     (`codex resume <id>` can re-address it). Archived rollouts deliberately do NOT count: archiving
 *     removes a session from `codex resume`'s reach, so treating it as resumable would offer a revival
 *     that fails.
 *
 * The pure parts (file-name → id, first-line → cwd) are separated from the directory walking so they
 * are unit-testable with no filesystem, and the walking roots at an injectable [codexDir] so tests point
 * at a throwaway fixture tree instead of the user's real `~/.codex`.
 */

/** Length of a canonical UUID string (`8-4-4-4-12`), the id suffix of a rollout file name. */
private const val UUID_LENGTH = 36

private const val ROLLOUT_PREFIX = "rollout-"
private const val ROLLOUT_SUFFIX = ".jsonl"

/**
 * Extract the provider session id from a rollout FILE NAME (`rollout-<ts>-<uuid>.jsonl`), or `null` if
 * [fileName] is not a rollout file or its id is not a UUID. Pure and host-free.
 *
 * The id is taken from the END of the stem rather than by splitting on `-`: the timestamp portion
 * contains dashes too, so only "the last 36 characters" is unambiguous.
 */
fun rolloutFileSessionId(fileName: String): ProviderSessionId? {
    if (!fileName.startsWith(ROLLOUT_PREFIX) || !fileName.endsWith(ROLLOUT_SUFFIX)) return null
    val stem = fileName.substring(ROLLOUT_PREFIX.length, fileName.length - ROLLOUT_SUFFIX.length)
    if (stem.length < UUID_LENGTH + 1) return null
    val candidate = stem.substring(stem.length - UUID_LENGTH)
    // The character before the id must be the separating '-', else this is some other naming scheme.
    if (stem[stem.length - UUID_LENGTH - 1] != '-') return null
    return runCatching { ProviderSessionId(candidate) }.getOrNull()
}

/**
 * Read the `cwd` out of a rollout's first (`session_meta`) line, or `null` if absent. Pure and host-free.
 * Also the per-line field scan behind [claudeTranscriptCwd] — the `"cwd":"…"` shape is the same there.
 *
 * Deliberately a scan for the `"cwd":"…"` field rather than a JSON parse: the caller only reads the
 * HEAD of the file (a `session_meta` line embeds the full base instructions and can be tens of KB), so
 * what arrives here is usually a TRUNCATED line that no JSON parser would accept. `cwd` sits in the
 * first ~200 bytes of the payload, well inside the head. JSON string escapes are unescaped, so a path
 * containing a quote or a backslash round-trips.
 */
fun rolloutCwd(head: String): String? {
    val marker = "\"cwd\":\""
    val start = head.indexOf(marker)
    if (start < 0) return null
    val from = start + marker.length
    val sb = StringBuilder()
    var i = from
    while (i < head.length) {
        when (val c = head[i]) {
            '"' -> return sb.toString()
            '\\' -> {
                if (i + 1 >= head.length) return null // truncated mid-escape: no usable value
                when (val esc = head[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 >= head.length) return null
                        val code = head.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(esc) // covers \" \\ \/ and anything else, literally
                }
                i++
            }
            else -> sb.append(c)
        }
        i++
    }
    return null // the closing quote never arrived (truncated head)
}

/** `$CODEX_HOME`, else `~/.codex` (falls back to a cwd-relative `.codex` if `$HOME` is unset). */
@OptIn(ExperimentalForeignApi::class)
fun defaultCodexDir(): String {
    val explicit = getenv("CODEX_HOME")?.toKString()?.trimEnd('/')
    if (!explicit.isNullOrEmpty()) return explicit
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".codex" else "$home/.codex"
}

/**
 * Reads Codex's rollout tree under [codexDir] (see the file header). All methods degrade to
 * `null`/`false`/empty on any filesystem trouble — a missing or unreadable `~/.codex` means "nothing
 * known", never an exception into the daemon.
 */
@OptIn(ExperimentalForeignApi::class)
class CodexRolloutScan(private val codexDir: String = defaultCodexDir()) {

    private val sessionsRoot: String get() = "${codexDir.trimEnd('/')}/sessions"

    /**
     * Whether a live (non-archived) rollout exists for [providerSessionId] — the resumability probe.
     * Matches on the file name alone (the id is in it), so no file is opened.
     */
    fun hasRollout(providerSessionId: ProviderSessionId): Boolean =
        rolloutFiles().any { rolloutFileSessionId(it.name) == providerSessionId }

    /**
     * The id of the newest rollout that was written for [cwd] at or after [notBeforeMillis] (epoch
     * millis, compared against the file's mtime), or `null` if there is none.
     *
     * [notBeforeMillis] is what keeps this from binding the WRONG session: without it, a codex the user
     * started by hand in the same directory an hour ago would be the newest match. The caller passes the
     * moment its own launch began, so only rollouts created by that launch qualify. A small
     * [MTIME_SLACK_MILLIS] tolerance absorbs sub-second clock/rounding differences between kotgent's
     * clock and the filesystem's.
     */
    fun discoverSessionId(cwd: String, notBeforeMillis: Long): ProviderSessionId? {
        val threshold = notBeforeMillis - MTIME_SLACK_MILLIS
        return rolloutFiles()
            .filter { it.mtimeMillis >= threshold }
            .sortedByDescending { it.mtimeMillis }
            .firstNotNullOfOrNull { file ->
                val id = rolloutFileSessionId(file.name) ?: return@firstNotNullOfOrNull null
                val head = readHead(file.path, HEAD_BYTES) ?: return@firstNotNullOfOrNull null
                if (rolloutCwd(head) == cwd) id else null
            }
    }

    /**
     * The model of the newest rollout written for [cwd] at or after [notBeforeMillis], or `null`. Codex
     * records the model in a `turn_context` record (a few lines in, PAST the multi-KB `session_meta` line),
     * so this reads a larger [MODEL_SCAN_BYTES] head than [discoverSessionId] and pulls the first
     * `"model":"…"` out of it with [extractModel] (which skips `session_meta`'s neighbouring
     * `model_provider`). Best-effort — a rollout with no model line yields `null`.
     */
    fun discoverModel(cwd: String, notBeforeMillis: Long): String? {
        val threshold = notBeforeMillis - MTIME_SLACK_MILLIS
        return rolloutFiles()
            .filter { it.mtimeMillis >= threshold }
            .sortedByDescending { it.mtimeMillis }
            .firstNotNullOfOrNull { file ->
                val head = readHead(file.path, MODEL_SCAN_BYTES) ?: return@firstNotNullOfOrNull null
                if (rolloutCwd(head) == cwd) extractModel(head) else null
            }
    }

    /**
     * The recorded `cwd` of the live rollout for [providerSessionId], or `null` when none exists — the
     * Codex half of import discovery ([codexSessionLocator]). The rollout is matched by id in the file
     * NAME (like [hasRollout]), then its `cwd` is read out of the first (`session_meta`) line exactly as
     * [discoverSessionId] does. Only `sessions/` is walked, so an ARCHIVED rollout deliberately does not
     * answer: archiving puts a session out of `codex resume`'s reach, and an import discovered from it
     * would offer a revival that fails.
     */
    fun cwdOf(providerSessionId: ProviderSessionId): String? =
        rolloutHeadOf(providerSessionId, HEAD_BYTES)?.let(::rolloutCwd)

    /**
     * The recorded model of the live rollout for [providerSessionId], or `null`. Precise where
     * [discoverModel]'s cwd+mtime heuristic is not: the rollout is matched by id in the file NAME
     * (like [cwdOf]), so a busier neighbour session in the same cwd can never answer for this one.
     * Used on the resume path, where the provider id is always known ([SessionManager.resume]
     * requires it) — a FRESH codex launch has no captured id yet and stays on [discoverModel].
     */
    fun modelOf(providerSessionId: ProviderSessionId): String? =
        rolloutHeadOf(providerSessionId, MODEL_SCAN_BYTES)?.let(::extractModel)

    /**
     * The one model-capture lookup rule (the daemon's `captureModelInBackground` wiring): with a KNOWN
     * [providerSessionId] (the resume path — [SessionManager.resume] requires one) the answer comes
     * ONLY from that session's id-keyed rollout ([modelOf]). A temporary miss there — `turn_context`
     * is not written until the session takes its first turn — must stay `null` so the caller's retry
     * loop polls again; it must NEVER fall back to [discoverModel], because on resume [notBeforeMillis]
     * is the ORIGINAL launch time, so the cwd+mtime heuristic would span every rollout written since
     * and could stamp a busier neighbour session's model over this one's. Only an id-less FRESH launch
     * (codex before its id capture lands) uses the heuristic, where the window is tight
     * (`createdAt` ≈ now).
     */
    fun modelForCapture(providerSessionId: ProviderSessionId?, cwd: String, notBeforeMillis: Long): String? =
        if (providerSessionId != null) modelOf(providerSessionId)
        else discoverModel(cwd, notBeforeMillis)

    /**
     * The first [bytes] of the live rollout named by [providerSessionId] — the ONE id-keyed lookup
     * behind [cwdOf] and [modelOf], which differ only in window size and extractor. Matched by id in
     * the file NAME (like [hasRollout]); `null` when no live rollout exists or it cannot be read.
     */
    private fun rolloutHeadOf(providerSessionId: ProviderSessionId, bytes: Int): String? =
        rolloutFiles().firstNotNullOfOrNull { file ->
            if (rolloutFileSessionId(file.name) != providerSessionId) return@firstNotNullOfOrNull null
            readHead(file.path, bytes)
        }

    /** One rollout file found on disk: its base [name], full [path], and mtime in epoch millis. */
    private data class RolloutFile(val name: String, val path: String, val mtimeMillis: Long)

    /**
     * Every rollout under `sessions/<YYYY>/<MM>/<DD>/`. The tree is walked explicitly three levels deep
     * rather than recursively: the layout is fixed, and a bounded walk cannot be led astray by a
     * surprise symlink into a large tree.
     */
    private fun rolloutFiles(): List<RolloutFile> {
        val out = ArrayList<RolloutFile>()
        for (year in listDir(sessionsRoot)) {
            val yearPath = "$sessionsRoot/$year"
            for (month in listDir(yearPath)) {
                val monthPath = "$yearPath/$month"
                for (day in listDir(monthPath)) {
                    val dayPath = "$monthPath/$day"
                    for (file in listDir(dayPath)) {
                        if (!file.endsWith(ROLLOUT_SUFFIX)) continue
                        val path = "$dayPath/$file"
                        out.add(RolloutFile(file, path, mtimeMillis(path) ?: continue))
                    }
                }
            }
        }
        return out
    }

    /** Modification time of [path] in epoch millis, or `null` if it cannot be stat'ed. */
    private fun mtimeMillis(path: String): Long? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped null
        st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
    }

    companion object {
        /**
         * How much of a rollout to read when looking for `cwd`. The `session_meta` line embeds the full
         * base instructions (tens of KB), but `cwd` sits within its first few hundred bytes — 8 KB is
         * generous headroom while keeping the read O(1) per candidate file.
         */
        const val HEAD_BYTES: Int = 8 * 1024

        /**
         * How much of a rollout to read when looking for the `model`. It lives in a `turn_context` record a
         * few lines PAST the multi-KB `session_meta` line, so this window must clear that line — 256 KB is
         * ample for a freshly-started session (which is when model capture runs) while staying bounded.
         */
        const val MODEL_SCAN_BYTES: Int = 256 * 1024

        /** Tolerance on the mtime cutoff, absorbing clock/rounding skew (see [discoverSessionId]). */
        const val MTIME_SLACK_MILLIS: Long = 2_000
    }
}

/**
 * The production [VendorStoreProbe] for Codex: a dead session is resumable iff its rollout JSONL still
 * exists under `<codexDir>/sessions/` (see the file header). `cwd` is not part of the key — Codex names
 * rollouts by id alone — so it is accepted and ignored, keeping the probe interface uniform across
 * providers.
 */
fun codexVendorStoreProbe(codexDir: String = defaultCodexDir()): VendorStoreProbe {
    val scan = CodexRolloutScan(codexDir)
    return VendorStoreProbe { _, _, providerSessionId -> scan.hasRollout(providerSessionId) }
}
