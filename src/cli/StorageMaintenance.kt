package io.kotgent.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun startStorageMaintenance(
    scope: CoroutineScope,
    prune: suspend () -> Unit,
    awaitNext: suspend () -> Unit = { delay(24 * 60 * 60_000L) },
    onError: (String) -> Unit = ::eprintln,
): Job {
    prune()
    return scope.launch {
        while (true) {
            awaitNext()
            try {
                prune()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("kotgent daemon: history pruning failed: ${e.message}")
            }
        }
    }
}
