package io.github.ktcplugins.sqldelight

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface SqlDelightSettings {
    val packageName: String
    val className: String get() = "Database"
    val sourceDirectory: String get() = "sqldelight"
}
