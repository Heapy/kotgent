package io.kotgent.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Avoids noisy failed-ALTER probes; public because Toolchain 0.11 has no test friend modules. */
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

/** SQLite has no boolean: any non-zero integer is set, and a missing row reads as absent, not archived. */
fun Long?.isArchived(): Boolean = this != null && this != 0L

fun Boolean.toSqliteFlag(): Long = if (this) 1L else 0L
