package io.kotgent.push

/**
 * One browser Web Push subscription, as handed to the daemon by `PushSubscription.toJSON()` in the
 * page.
 *
 * [endpoint] is the push service URL the daemon POSTs to (Apple's `web.push.apple.com`, Google's
 * `fcm.googleapis.com`, …) and is the identity of the subscription — a device that re-subscribes on
 * the same endpoint replaces its row rather than adding one.
 *
 * [p256dh] and [auth] are the RFC 8291 client keys (base64url). Nothing in the payload-less push path
 * reads them; they are persisted so that adding encrypted payloads later needs no device to
 * re-subscribe. [createdAt] is epoch millis, supplied by the caller (the store has no clock).
 */
data class PushSubscription(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAt: Long,
)

/**
 * The push-subscription storage seam. Kept separate from `EventStore` on purpose: subscriptions are
 * device registrations, not part of the event-sourced session log, and nothing about them is derived
 * by the reducer. The concrete backend ([SqlitePushStore]) shares the daemon's SQLite driver, but
 * callers — the `/push` routes and the sender — depend only on this interface, so tests can use a
 * trivial in-memory fake.
 */
interface PushStore {

    /** All known subscriptions, oldest first. Empty when push has never been enabled anywhere. */
    suspend fun list(): List<PushSubscription>

    /** Insert [subscription], or replace the existing row with the same `endpoint`. */
    suspend fun save(subscription: PushSubscription)

    /**
     * Remove the subscription with this [endpoint]. Idempotent — removing an endpoint that is not
     * stored is a silent no-op, because both callers (an explicit unsubscribe and a `404`/`410`
     * from the push service) can legitimately race a removal that already happened.
     */
    suspend fun remove(endpoint: String)
}
