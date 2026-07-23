package io.kotgent.transport

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
 * An [AtomicReference] rather than a `Mutex`: readers are on every request path (including WebSocket
 * handshakes) and must not suspend, while writes happen at most once per operator command. Ktor CIO runs
 * the request pipeline across a thread pool, so the publication has to be a real atomic store — a plain
 * `var` would be a data race with no guarantee a request thread ever observes the new value.
 */
@OptIn(ExperimentalAtomicApi::class)
class TokenHolder(
    initial: String,
    /**
     * Write [rotate]'s new value everywhere it is read from disk — `~/.kotgent/token` plus both provider
     * hook-header files. Injected so the transport layer stays free of file layout knowledge (the daemon
     * wiring in `Commands.daemon` owns those paths); defaults to a no-op for tests and for callers that
     * only need the read side. Throwing aborts the rotation with the old token still live.
     */
    private val persist: (String) -> Unit = {},
) {
    private val ref = AtomicReference(initial)

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
        val next = generateToken()
        persist(next)
        ref.store(next)
        return next
    }
}
