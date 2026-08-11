package io.kotgent.store

import kotlinx.coroutines.flow.StateFlow

data class UiPreferences(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
)

interface PreferencesStore {

    val preferences: StateFlow<UiPreferences>

    suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences
}
