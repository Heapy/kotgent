package io.kotgent.push

import io.kotgent.store.FakeStoreCall
import io.kotgent.store.FakeStoreInterceptor
import io.kotgent.store.PassThroughInterceptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakePushStore : PushStore {
    var interceptor: FakeStoreInterceptor = PassThroughInterceptor

    private val mutex = Mutex()
    private val rows = LinkedHashMap<String, PushSubscription>()

    /** Endpoint-scoped failure; the method-scoped interceptor cannot tell one row's removal from another. */
    val failRemoveFor: MutableSet<String> = mutableSetOf()

    fun seed(vararg subscriptions: PushSubscription) {
        for (subscription in subscriptions) rows[subscription.endpoint] = subscription
    }

    suspend fun endpoints(): List<String> = mutex.withLock { rows.keys.toList() }

    override suspend fun list(): List<PushSubscription> = guarded("list") { rows.values.toList() }

    override suspend fun save(subscription: PushSubscription): Unit = guarded("save", subscription.endpoint) {
        rows[subscription.endpoint] = subscription
    }

    override suspend fun remove(endpoint: String): Unit = guarded("remove", endpoint) {
        if (endpoint in failRemoveFor) error("the subscription table is locked")
        val _ = rows.remove(endpoint)
    }

    private suspend fun <T> guarded(method: String, vararg args: String, block: () -> T): T =
        interceptor.around(FakeStoreCall(STORE, method, args.toList())) { mutex.withLock { block() } }

    private companion object {
        const val STORE = "PushStore"
    }
}
