package io.kotgent.sys

import io.kotgent.host.HostOs
import io.kotgent.host.hostOs

fun interface DisplayDetector {
    fun hasDisplay(): Boolean
}

class XServerDisplayDetector(private val env: (String) -> String?) : DisplayDetector {
    override fun hasDisplay(): Boolean = !env("DISPLAY").isNullOrEmpty()
}

class WaylandDisplayDetector(private val env: (String) -> String?) : DisplayDetector {
    override fun hasDisplay(): Boolean = !env("WAYLAND_DISPLAY").isNullOrEmpty()
}

/**
 * Without a display, xdg-open falls back to a terminal browser such as lynx, which waits on the terminal
 * forever while the process runner captures its screen.
 */
fun browserDisplayDetector(env: (String) -> String?, os: HostOs = hostOs): DisplayDetector = when (os) {
    HostOs.LINUX -> {
        val detectors = listOf(XServerDisplayDetector(env), WaylandDisplayDetector(env))
        DisplayDetector { detectors.any { it.hasDisplay() } }
    }
    HostOs.MACOS, HostOs.WINDOWS -> DisplayDetector { true }
}
