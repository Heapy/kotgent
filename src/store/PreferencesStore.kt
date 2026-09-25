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
