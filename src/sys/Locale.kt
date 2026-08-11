package io.kotgent.sys

/** `en_US.UTF-8` is present on macOS, unlike glibc's `C.UTF-8`. */
const val DEFAULT_UTF8_LOCALE: String = "en_US.UTF-8"

/**
 * Tmux decides whether to emit Unicode from its client locale and replaces non-ASCII cells with `_`
 * otherwise. Launchd supplies no locale, so missing, `C`, and `POSIX` values must all use the UTF-8
 * fallback.
 */
fun utf8LocaleOrDefault(captured: String?): String {
    val value = captured?.trim().orEmpty()
    val codeset = value.substringAfter('.', "").replace("-", "")
    return if (codeset.equals("utf8", ignoreCase = true)) value else DEFAULT_UTF8_LOCALE
}
