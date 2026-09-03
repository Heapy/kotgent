package io.kotgent.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The identifying arguments only: enough to tell two calls apart, not the whole signature. */
class FakeStoreCall(val store: String, val method: String, val args: List<String>)

/**
 * Wraps each shared fake store call — lock and publish included — so a test can journal it, refuse it,
 * fail it, or assert lock discipline without owning a private copy of the store. Race hooks are not
 * routed through here: they must run outside the store's lock, which is what lets them expose a race.
 */
interface FakeStoreInterceptor {
    suspend fun <T> around(call: FakeStoreCall, body: suspend () -> T): T
}

object PassThroughInterceptor : FakeStoreInterceptor {
    override suspend fun <T> around(call: FakeStoreCall, body: suspend () -> T): T = body()
}

/** Records every call in arrival order. */
class RecordingInterceptor(
    private val delegate: FakeStoreInterceptor = PassThroughInterceptor,
    private val describe: (FakeStoreCall) -> String = { it.method },
    // Shared when one ordered trace must interleave this store's calls with another recorder's.
    private val entries: MutableList<String> = mutableListOf(),
) : FakeStoreInterceptor {
    private val mutex = Mutex()

    suspend fun journal(): List<String> = mutex.withLock { entries.toList() }

    suspend fun countOf(method: String): Int = mutex.withLock { entries.count { it == method } }

    suspend fun clear(): Unit = mutex.withLock { entries.clear() }

    override suspend fun <T> around(call: FakeStoreCall, body: suspend () -> T): T {
        mutex.withLock { entries += describe(call) }
        return delegate.around(call, body)
    }
}

/** Asserts that the subject never reaches the named methods, bare or qualified as `Store.method`. */
class ForbiddingInterceptor(
    private val forbidden: Set<String>,
    private val delegate: FakeStoreInterceptor = PassThroughInterceptor,
    private val why: (String, String) -> String = { store, method -> "$store.$method must not be called" },
) : FakeStoreInterceptor {
    override suspend fun <T> around(call: FakeStoreCall, body: suspend () -> T): T {
        if (call.method in forbidden || "${call.store}.${call.method}" in forbidden) {
            // An Error, not an exception: a subject that swallows failures per row must not hide this one.
            throw ForbiddenFakeStoreCall(why(call.store, call.method))
        }
        return delegate.around(call, body)
    }
}

class ForbiddenFakeStoreCall(message: String) : Error(message)

/** Every guarded method name, for a double that must refuse its whole surface. */
val TASK_STORE_METHODS: Set<String> = setOf(
    "list", "get", "create", "update", "delete", "entry", "listBacklog", "nextCandidate", "startIfTodo",
    "startIfTodoInLiveProject", "transition", "move", "dependenciesOf", "dependentsOf", "dependencyEdges",
    "addDependency", "removeDependency", "comment", "appendActivity", "activity", "upsertProject",
    "setProjectArchived", "listProjects", "listAllProjects", "project",
)

/** `subscribe` is absent because it is not a suspending call and so cannot be guarded. */
val EVENT_STORE_METHODS: Set<String> = setOf(
    "upsertSession", "updateSessionState", "setArchived", "setModel", "setModelForProvider", "markRead",
    "setTaskRef", "clearTaskRefIf", "setProjectId", "setName", "sessionsHoldingTask", "getSession",
    "listSessions", "append", "read", "projectionOf",
)

/** Raises the configured failure instead of running the call. */
class FailingInterceptor(
    val failures: MutableMap<String, Throwable> = mutableMapOf(),
    private val delegate: FakeStoreInterceptor = PassThroughInterceptor,
) : FakeStoreInterceptor {
    override suspend fun <T> around(call: FakeStoreCall, body: suspend () -> T): T {
        failures[call.method]?.let { throw it }
        return delegate.around(call, body)
    }
}
