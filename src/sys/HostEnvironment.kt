package io.kotgent.sys

import io.kotgent.host.HostOs
import io.kotgent.host.hostOs

val DEFAULT_EXECUTABLE_PATH: String = if (hostOs == HostOs.LINUX) {
    "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin"
} else {
    "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
}

fun mergedExecutablePath(captured: String?, fallback: String = DEFAULT_EXECUTABLE_PATH): String =
    ((captured ?: "").split(':').filter { it.startsWith('/') } + fallback.split(':'))
        .distinct().joinToString(":")

fun browserOpenCommand(url: String): List<String> =
    listOf(if (hostOs == HostOs.LINUX) "xdg-open" else "open", url)
