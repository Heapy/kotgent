package io.kotgent.service

/** Installing a service never owns the lifetime of the tmux sessions it supervises. */
interface DaemonService {
    val description: String
    val definitionPath: String
    fun install(binaryPath: String): String
    fun uninstall()
}

expect fun daemonService(): DaemonService
expect val restartDaemonCommand: String
