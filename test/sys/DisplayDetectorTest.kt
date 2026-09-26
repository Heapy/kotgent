package io.kotgent.sys

import io.kotgent.host.HostOs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayDetectorTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun anXServerDisplayNeedsANonEmptyDisplayVariable() {
        assertTrue(XServerDisplayDetector(env("DISPLAY" to ":0")).hasDisplay())
        assertFalse(XServerDisplayDetector(env("DISPLAY" to "")).hasDisplay())
        assertFalse(XServerDisplayDetector(env("WAYLAND_DISPLAY" to "wayland-0")).hasDisplay())
    }

    @Test
    fun aWaylandDisplayNeedsANonEmptyWaylandDisplayVariable() {
        assertTrue(WaylandDisplayDetector(env("WAYLAND_DISPLAY" to "wayland-0")).hasDisplay())
        assertFalse(WaylandDisplayDetector(env("WAYLAND_DISPLAY" to "")).hasDisplay())
        assertFalse(WaylandDisplayDetector(env("DISPLAY" to ":0")).hasDisplay())
    }

    @Test
    fun linuxLaunchesABrowserOnlyUnderAnXServerOrWayland() {
        assertFalse(browserDisplayDetector(env(), HostOs.LINUX).hasDisplay())
        assertFalse(browserDisplayDetector(env("DISPLAY" to "", "WAYLAND_DISPLAY" to ""), HostOs.LINUX).hasDisplay())
        assertTrue(browserDisplayDetector(env("DISPLAY" to ":0"), HostOs.LINUX).hasDisplay())
        assertTrue(browserDisplayDetector(env("WAYLAND_DISPLAY" to "wayland-0"), HostOs.LINUX).hasDisplay())
    }

    @Test
    fun macosAlwaysHandsTheFormToOpen() {
        assertTrue(browserDisplayDetector(env(), HostOs.MACOS).hasDisplay())
    }
}
