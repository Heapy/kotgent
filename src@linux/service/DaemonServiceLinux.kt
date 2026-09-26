package io.kotgent.service

import io.kotgent.systemd.SystemdInstaller
import io.kotgent.systemd.DAEMON_UNIT

actual fun daemonService(): DaemonService = SystemdInstaller()

actual val restartDaemonCommand: String = "systemctl --user restart $DAEMON_UNIT"
