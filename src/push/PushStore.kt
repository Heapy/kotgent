package io.kotgent.push

/**
 * Endpoint is subscription identity. RFC 8291 keys remain stored although payload-less push does not
 * yet consume them, avoiding a forced re-subscription if encrypted payloads are added.
 */
data class PushSubscription(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAt: Long,
)

/** Device registrations are not part of the event-sourced session log. */
interface PushStore {
    suspend fun list(): List<PushSubscription>

    suspend fun save(subscription: PushSubscription)

    /** Idempotent because explicit unsubscribe and push-service pruning may race. */
    suspend fun remove(endpoint: String)
}
