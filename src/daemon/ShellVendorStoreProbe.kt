package io.kotgent.daemon

/**
 * The shell resumability probe. A shell has no transcript or vendor store; its only durable launch
 * context is its cwd, so "resumable" means there is still a directory to come back to. The synthetic
 * provider id exists only to pass through the shared lifecycle and is deliberately ignored here.
 */
fun shellVendorStoreProbe(): VendorStoreProbe =
    VendorStoreProbe { _, cwd, _ -> isDirectory(cwd) }
