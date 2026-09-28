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
    fun aBrowserChoiceNeedsANonEmptyBrowserVariable() {
        assertTrue(BrowserVariableDetector(env("BROWSER" to "/usr/local/bin/code-browser")).hasDisplay())
        assertFalse(BrowserVariableDetector(env("BROWSER" to "")).hasDisplay())
        assertFalse(BrowserVariableDetector(env("DISPLAY" to ":0")).hasDisplay())
    }

    @Test
    fun linuxLaunchesABrowserOnlyUnderAnXServerWaylandOrABrowserChoice() {
        assertFalse(browserDisplayDetector(env(), HostOs.LINUX).hasDisplay())
        assertFalse(
            browserDisplayDetector(env("DISPLAY" to "", "WAYLAND_DISPLAY" to "", "BROWSER" to ""), HostOs.LINUX)
                .hasDisplay(),
        )
        assertTrue(browserDisplayDetector(env("DISPLAY" to ":0"), HostOs.LINUX).hasDisplay())
        assertTrue(browserDisplayDetector(env("WAYLAND_DISPLAY" to "wayland-0"), HostOs.LINUX).hasDisplay())
        assertTrue(browserDisplayDetector(env("BROWSER" to "wslview"), HostOs.LINUX).hasDisplay())
    }

    @Test
    fun macosAlwaysHandsTheFormToOpen() {
        assertTrue(browserDisplayDetector(env(), HostOs.MACOS).hasDisplay())
    }
}
