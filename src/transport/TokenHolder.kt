package io.kotgent.transport

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The master token as a **live provider** rather than a captured string.
 *
 * Every gate in the daemon (the `Bearer` check in [authenticated], both hook ingresses, and — from Task 7
 * — the session-cookie verification) resolves the secret through [current] on each request. That is what
 * makes `kotgent token rotate` mean anything: a token captured once at wiring time would keep authorising
 * the old value until the daemon restarted, and the cookie's whole revocation story ("rotate the master
 * token and every cookie ever issued stops verifying") depends on the new value taking effect immediately.
 *
 * ## Ordering: persist first, publish second
 * [rotate] writes the new value to disk ([persist]) BEFORE publishing it in memory. The two consumers that
 * read the secret from disk rather than from this object — the CLI (`~/.kotgent/token`) and the provider
 * hooks (their `0600` header files) — must never be handed a value the running daemon has not accepted yet
 * for longer than necessary, and a [persist] that FAILS must leave the daemon authenticating exactly what
 * is still on disk. Publishing first and persisting after would invert that: a failed write would leave a
 * live token nobody else could learn, and a restart would silently roll it back.
 *
 * The remaining window — new value on disk, old value still live — is the few instructions between the two
 * statements, and it is closed for every subsequent request. There is deliberately no grace period where
 * both the old and the new token are accepted: "the old key stops working" is the entire point of rotation.
 *
 * ## Concurrency
 * Reads go through an [AtomicReference], never a `Mutex`: [current] is on every request path (including
 * WebSocket handshakes) and must not suspend. Ktor CIO runs the request pipeline across a thread pool, so
 * the publication has to be a real atomic store — a plain `var` would be a data race with no guarantee a
 * request thread ever observes the new value.
 *
 * [rotate] additionally takes a short non-suspending lock so that two concurrent rotations cannot interleave
 * their persist+publish pairs — without it, `A.persist → B.persist → B.publish → A.publish` would leave one
 * token on disk and a different one live in memory, and the CLI/hooks (which read the secret from disk) would
 * 401 until a restart. The lock is on the WRITE side only; readers never touch it. Rotation is an operator
 * command, so real contention is vanishingly rare — the lock is there to make "at most once at a time" a
 * guarantee rather than an assumption.
 */
@OptIn(ExperimentalAtomicApi::class)
class TokenHolder(
    initial: String,
    /**
     * Write [rotate]'s new value everywhere it is read from disk — `~/.kotgent/token` plus both provider
     * hook-header files. Injected so the transport layer stays free of file layout knowledge (the daemon
     * wiring in `Commands.daemon` owns those paths); defaults to a no-op for tests and for callers that
     * only need the read side.
     *
     * This is THREE independent file writes, not one atomic act, and the ORDER is load-bearing: the caller
     * writes the two hook-header files FIRST and `~/.kotgent/token` LAST. A failure partway (disk full after
     * a hook header, before the token file) throws, which aborts the rotation with the OLD token still live
     * in memory — the safe end of the failure, since [ref.store] has not run yet. Writing the token file last
     * means such a failure ALSO leaves `~/.kotgent/token` holding the OLD value, so the CLI (which reads the
     * secret straight from that file) stays consistent with the daemon's in-memory OLD token and can still
     * reach it to re-run rotation. Were the token file written first, a mid-persist failure would strand the
     * NEW token on disk while memory kept the OLD one, and the CLI would 401 until a restart — a control-plane
     * lockout. A partially-updated hook header is recoverable rather than fatal because rotation is idempotent:
     * re-running `kotgent token rotate` mints another fresh token and rewrites all three, and a daemon restart
     * re-reads `~/.kotgent/token` regardless.
     */
    private val persist: (String) -> Unit = {},
) {
    private val ref = AtomicReference(initial)

    /** `0` = free, `1` = a rotation holds it. Serializes [rotate] against a concurrent [rotate] (write side
     *  only); [current] never touches it. */
    private val rotating = AtomicInt(0)

    /** The token in force right now. Called on every authenticated request — cheap, never suspends. */
    fun current(): String = ref.load()

    /**
     * Mint a fresh token ([generateToken]), persist it, publish it, and return it.
     *
     * After this returns, the previous token no longer authenticates anything: new requests and new
     * WebSocket handshakes with it are rejected. Sockets that are ALREADY open stay open — authorization is
     * evaluated once, in the `Plugins` phase of the handshake — so a live `kotgent attach` or an open
     * events stream survives until it reconnects. The CLI says so out loud rather than pretending otherwise.
     */
    fun rotate(): String {
        // A concurrent rotation is publishing its own value — let it finish so the persist+publish pair below
        // is atomic as a unit. Uncontended in practice (rotation is an operator command); the spin only ever
        // runs if two `POST /auth/rotate` land at once.
        while (!rotating.compareAndSet(0, 1)) { /* spin until the other rotation releases */ }
        try {
            val next = generateToken()
            persist(next)
            ref.store(next)
            return next
        } finally {
            rotating.store(0)
        }
    }
}
