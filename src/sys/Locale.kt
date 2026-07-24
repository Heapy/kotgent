package io.kotgent.sys

/**
 * The locale kotgent falls back to whenever the inherited one is missing or is not UTF-8. `en_US.UTF-8`
 * is always present on macOS (unlike glibc's `C.UTF-8`), so it is a safe floor.
 */
const val DEFAULT_UTF8_LOCALE: String = "en_US.UTF-8"

/**
 * [captured] if it names a **UTF-8** locale, else [DEFAULT_UTF8_LOCALE].
 *
 * Pure, no I/O. This is load-bearing for anything kotgent spawns towards a terminal, because a
 * **tmux client decides whether it may emit UTF-8 from its own locale** (`LC_ALL`/`LC_CTYPE`/`LANG`).
 * A client that reads as non-UTF-8 makes tmux rewrite **every** non-ASCII cell as `_` on the way out
 * (`tty_check_codeset`), so an agent TUI drawn out of box-drawing characters arrives as a wall of
 * underscores. Under launchd there is no `LANG` at all — on macOS it is the terminal *emulator*, not
 * the shell, that sets one — so "inherit it if present" is exactly the path that breaks there.
 *
 * `C` / `POSIX` are rejected for the same reason: they are inherited values that are *present* yet
 * still not UTF-8. The check is on the codeset suffix (`…​.UTF-8` / `…​.utf8`), which is how a locale
 * name carries it.
 */
fun utf8LocaleOrDefault(captured: String?): String {
    val value = captured?.trim().orEmpty()
    val codeset = value.substringAfter('.', "").replace("-", "")
    return if (codeset.equals("utf8", ignoreCase = true)) value else DEFAULT_UTF8_LOCALE
}
