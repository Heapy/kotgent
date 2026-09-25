package io.kotgent.store

import kotlinx.coroutines.flow.StateFlow

data class UiPreferences(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
    /** Absolute paths whose sessions stay visible in ADHD mode. Declared last so positional callers hold. */
    val adhdPaths: List<String> = emptyList(),
)

interface PreferencesStore {
    val preferences: StateFlow<UiPreferences>

    suspend fun savePreferences(
        basePath: String,
        groupingLevel: Int,
    ): UiPreferences

    /** Marks or unmarks one folder and advances the shared revision, so other clients see the change. */
    suspend fun setFolderAdhd(
        path: String,
        adhd: Boolean,
    ): UiPreferences
}

/**
 * Whether a folder mark still has a folder the sidebar can draw once grouping moves to [base] and
 * [level]; a mark without one would keep filtering with no button left to clear it. Mirrors
 * `segmentsUnder` and `groupSessions` in resources/webui/lib/paths.js: inside the base a folder is drawn
 * down to [level] segments, and outside it only as a session's exact cwd, which a change of level cannot
 * affect but a change of base can. Every path arrives normalized.
 */
fun adhdPathSurvivesGrouping(path: String, previousBase: String, base: String, level: Int): Boolean {
    if (base.isEmpty()) return false
    val depth = depthUnder(base, path) ?: return depthUnder(previousBase, path) == null
    return depth <= level
}

private fun depthUnder(base: String, path: String): Int? {
    if (base.isEmpty() || path.isEmpty()) return null
    if (path == base) return 0
    val prefix = if (base == "/") "/" else "$base/"
    if (!path.startsWith(prefix)) return null
    return path.substring(prefix.length).split('/').count { it.isNotEmpty() }
}
