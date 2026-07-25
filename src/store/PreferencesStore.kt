package io.kotgent.store

import kotlinx.coroutines.flow.StateFlow

/**
 * The daemon-wide Web UI preferences persisted alongside the event log.
 *
 * [revision] is assigned by the store and increases for every accepted save, including a save whose
 * values happen to equal the previous ones. Clients use it to discard older HTTP/WebSocket deliveries
 * that arrive after a newer committed value.
 */
data class UiPreferences(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
)

/**
 * Persistence seam for daemon-wide UI preferences.
 *
 * Kept separate from [EventStore]: preferences are ordinary mutable configuration, not events or part
 * of a session projection. The SQLite implementation happens to implement both contracts so they can
 * share one connection and one writer mutex.
 */
interface PreferencesStore {

    /** Current persisted value plus every later accepted save. */
    val preferences: StateFlow<UiPreferences>

    /**
     * Persist [basePath] and [groupingLevel], increment the stored revision, publish the committed value,
     * and return it. Input validation and path canonicalization belong to the transport boundary.
     */
    suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences
}
