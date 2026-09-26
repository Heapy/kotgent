package io.kotgent.service

import io.kotgent.launchd.DAEMON_LABEL
import io.kotgent.launchd.LaunchdInstaller

actual fun daemonService(): DaemonService = LaunchdInstaller()

actual val restartDaemonCommand: String = "launchctl kickstart -k gui/\$(id -u)/$DAEMON_LABEL"
