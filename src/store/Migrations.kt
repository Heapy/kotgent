package io.kotgent.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * The codegen plugin drops `.sqm` files, so an additive column is migrated by a hand-rolled `ALTER` in
 * the owning store's `init`, run only when this answers false.
 *
 * The guard is load-bearing rather than tidy: SQLiter LOGS a failing statement with a full stack trace
 * before the exception is ever thrown, so `runCatching { ALTER … }` prints a wall of red on every open
 * for a pure no-op. With the guard a failing `ALTER` propagates, which is right — the column really was
 * missing and every write against it would fail anyway.
 *
 * Shared by both stores because its behaviour must be identical in each, and public because Kotlin
 * Toolchain 0.11 has no friend-module relationship to a test fragment.
 */
fun SqlDriver.hasColumn(table: String, column: String): Boolean =
    executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            var found = false
            while (!found && cursor.next().value) found = cursor.getString(1) == column
            QueryResult.Value(found)
        },
        parameters = 0,
    ).value
