package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario


internal const val SESSIONS_BANNER: String = "KOTGENT-SESSIONS-READY"

internal const val ATTENTION_BANNER: String = "KOTGENT-ATTENTION-READY"

internal const val TERMINAL_BANNER: String = "KOTGENT-TERMINAL-READY"

internal const val RESTART_BANNER: String = "KOTGENT-RESTART-READY"

internal const val TERMINAL_X10_BANNER: String = "KOTGENT-X10-READY"

// SGR tracking exercises xterm's text input path used by production tmux.
private const val MOUSE_TRACKING_ENABLE: String = "\\033[?1006h\\033[?1000h"

// Legacy X10 coordinates above 127 leave xterm through onBinary, a separate input path.
private const val MOUSE_TRACKING_ENABLE_LEGACY: String = "\\033[?1000h"

private const val SCREEN_LINES: String =
    "LINE 01\\nLINE 02\\nLINE 03\\nLINE 04\\nLINE 05\\nLINE 06\\nLINE 07\\nLINE 08\\n"

// `exec cat` keeps one direct child for Pty.close; banner-last is the full-payload readiness marker.
internal fun deterministicUpstream(banner: String): List<String> = listOf(
    "/bin/sh",
    "-c",
    "printf '" + MOUSE_TRACKING_ENABLE + SCREEN_LINES + banner + "\\n'; exec cat",
)

internal fun legacyMouseUpstream(banner: String): List<String> = listOf(
    "/bin/sh",
    "-c",
    "printf '" + MOUSE_TRACKING_ENABLE_LEGACY + SCREEN_LINES + banner + "\\n'; exec cat",
)

fun terminalScenario(): Scenario = Scenario(
    name = "terminal",
    seed = { fakes ->
        fakes.projectFs.addDirectory("/w/terminal")
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-term",
                name = "terminal",
                agent = "claude",
                cwd = "/w/terminal",
                state = SessionState.running,
                createdAt = SEED_EPOCH_MS + 1,
                providerSessionId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                model = "claude-sonnet-4-5",
            ),
        )
    },
    terminalUpstream = deterministicUpstream(TERMINAL_BANNER),
)

fun terminalX10Scenario(): Scenario = Scenario(
    name = "terminal-x10",
    seed = { fakes ->
        fakes.projectFs.addDirectory("/w/x10")
        seedSessionRow(
            fakes,
            harnessSession(
                id = "s-x10",
                name = "legacy mouse",
                agent = "claude",
                cwd = "/w/x10",
                state = SessionState.running,
                createdAt = SEED_EPOCH_MS + 1,
                providerSessionId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                model = "claude-sonnet-4-5",
            ),
        )
    },
    terminalUpstream = legacyMouseUpstream(TERMINAL_X10_BANNER),
)
