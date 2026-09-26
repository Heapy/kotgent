package io.kotgent.launcher

import io.kotgent.sys.mergedExecutablePath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import io.kotgent.sys.DEFAULT_UTF8_LOCALE
import io.kotgent.systemd.daemonUnit
import io.kotgent.transport.writePrivateFile

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: systemdcheck <bounded-helper> <unit-output>" }
    writePrivateFile(args[1], daemonUnit(args[0], mergedExecutablePath(getenv("PATH")?.toKString()), DEFAULT_UTF8_LOCALE).encodeToByteArray())
}
