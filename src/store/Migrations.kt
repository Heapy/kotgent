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
