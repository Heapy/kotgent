package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionState
import io.kotgent.webuicheck.Scenario

/*
 * The harness's terminal upstream, and the one place its bytes are spelled out.
 *
 * Every scenario that owns a session declares the same shape of upstream, differing only in a banner,
 * so a browser test can always tell which fixture painted the screen it is reading. The argv is
 * DECLARED here and built into a `TerminalBridge` by the harness core (with `realPtyFactory`) — a
 * scenario deliberately does not construct the bridge itself, because `terminalBridgeFactory` has
 * exactly one owner and two would race over the pty lifecycle.
 */

/** Banner printed by the `sessions` scenario's upstream. */
internal const val SESSIONS_BANNER: String = "KOTGENT-SESSIONS-READY"

/** Banner printed by the `attention` scenario's upstream. */
internal const val ATTENTION_BANNER: String = "KOTGENT-ATTENTION-READY"

/** Banner printed by the `terminal` scenario's upstream. */
internal const val TERMINAL_BANNER: String = "KOTGENT-TERMINAL-READY"

/** Banner printed by the `restart` scenario's upstream. */
internal const val RESTART_BANNER: String = "KOTGENT-RESTART-READY"

/**
 * `\033[?1006h\033[?1000h` — SGR encoding first, then vt200 mouse tracking.
 *
 * This is the one thing only the upstream can supply, and without it a whole browser test is not
 * merely hard but impossible: `installSwipeScroll` (`components/TerminalPane.js`) hands the gesture
 * BACK when `term.modes.mouseTrackingMode === "none"`, and xterm 6.0 removed the terminal element's
 * own touch handlers, so on a phone-sized viewport a swipe over a tracking-free terminal does nothing
 * at all. In production tmux turns tracking on (kotgent forces `mouse on`); here the pty does it, so
 * the bridge engages and the wheel reports it synthesises travel the real path — out through xterm's
 * mouse protocol, into the pty, and back as `cat`'s echo, which is what makes them OBSERVABLE in the
 * DOM instead of only inside a JavaScript spy.
 *
 * The SGR enable comes first deliberately: tracking that arrives without `?1006h` degrades to the
 * legacy X10 encoding, whose coordinate bytes above 127 travel on `term.onBinary` rather than
 * `term.onData` — a second, easily-forgotten input path (`CLAUDE.md`, "xterm reports input on TWO
 * events"). Ordering it this way keeps the fixture on the encoding the product actually uses.
 */
private const val MOUSE_TRACKING_ENABLE: String = "\\033[?1006h\\033[?1000h"

/**
 * Eight short, numbered lines — deliberately FEWER than any plausible viewport is tall.
 *
 * A payload taller than the screen would make "what is visible" depend on the browser's row count,
 * i.e. on the window size the test happened to run at; with eight lines every one of them is on
 * screen at every geometry, so the painted screen is a constant. They are numbered so an assertion can
 * name a row (`LINE 03`) rather than count anonymous ones.
 */
private const val SCREEN_LINES: String =
    "LINE 01\\nLINE 02\\nLINE 03\\nLINE 04\\nLINE 05\\nLINE 06\\nLINE 07\\nLINE 08\\n"

/**
 * The upstream argv for a scenario whose sessions can be attached: a `/bin/sh` that prints a fixed
 * screen and then blocks in `cat`.
 *
 * **Byte-for-byte reproducible.** No timestamp, no hostname, no `$USER`, no `date`, nothing read from
 * the environment — two runs on two machines paint identical bytes. `cat` is what holds the pty open
 * after the print (a child that exits closes the master and the socket with it) and doubles as the
 * echo that makes input observable.
 *
 * **`exec cat`, not `cat`.** `Pty.close` sends SIGTERM to the DIRECT child only, so a forked `cat`
 * outlives the shell that spawned it and keeps the slave open until the master fd is released —
 * teardown then depends on the fd close rather than on the signal, and a `stop`-then-reattach test is
 * measuring a race instead of a lifecycle. `exec` makes the shell REPLACE itself, so the one process on
 * the pty is the one the bridge's close terminates and reaps. `SelfCheck.kt`'s own upstream has always
 * said so; this file used to disagree with it silently.
 *
 * [banner] goes LAST, so waiting for it is proof the WHOLE payload arrived rather than a prefix of it.
 *
 * `command[0]` must be an absolute path: the pty spawns with `posix_spawn`, which performs no `PATH`
 * search (`RealPtyHandle.kt`). `/bin/sh` is on every macOS host by definition.
 *
 * Line endings are `\n`, not `\r\n`: `openpty` is called with a NULL termios, i.e. the kernel default,
 * which has `OPOST|ONLCR` set and turns each into CRLF on the way out. Writing `\r\n` here would emit
 * `\r\r\n`.
 */
internal fun deterministicUpstream(banner: String): List<String> = listOf(
    "/bin/sh",
    "-c",
    "printf '" + MOUSE_TRACKING_ENABLE + SCREEN_LINES + banner + "\\n'; exec cat",
)

/**
 * `terminal` — one running session over a real pty. Consumers: the terminal itself, the swipe bridge,
 * the FitAddon geometry rules, and reattach.
 *
 * One session is enough for all four: the swipe and the geometry both read a single attached pane, and
 * the reattach branches that need a SECOND session to switch to live in the `restart` scenario, which
 * seeds two.
 */
fun terminalScenario(): Scenario = Scenario(
    name = "terminal",
    seed = { fakes ->
        fakes.projectFs.addDirectory("/w/terminal")
        fakes.events.upsertSession(
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
