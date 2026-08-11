package io.kotgent.daemon

// Shells have no vendor transcript; their cwd is the only durable resume context.
fun shellVendorStoreProbe(): VendorStoreProbe =
    VendorStoreProbe { _, cwd, _ -> isDirectory(cwd) }
