package io.kotgent.transport

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class TokenHolder(
    initial: String,
    // Production writes hook headers first and the CLI token last to avoid a mid-persist CLI lockout.
    private val persist: (String) -> Unit = {},
) {
    private val ref = AtomicReference(initial)

    private val rotating = AtomicInt(0)

    fun current(): String = ref.load()

    fun rotate(expected: String): String? {
        // Serialize compare+persist+publish so concurrent rotations cannot split disk and memory.
        while (!rotating.compareAndSet(0, 1)) {   }
        try {
            if (!constantTimeEquals(ref.load(), expected)) return null
            val next = generateToken()
            // Persist first: a failure leaves the old on-disk token live in memory.
            persist(next)
            ref.store(next)
            return next
        } finally {
            rotating.store(0)
        }
    }
}
