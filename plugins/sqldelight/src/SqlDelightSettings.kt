package io.heapy.ktc.plugins.sqldelight

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface SqlDelightSettings {
    val packageName: String
    val className: String get() = "Database"
    val sourceDirectory: String get() = "sqldelight"
}
