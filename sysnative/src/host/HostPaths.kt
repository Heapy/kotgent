package io.kotgent.host

enum class HostOs { MACOS, LINUX, WINDOWS }

expect val hostOs: HostOs

enum class PathLengthUnit(val label: String) {
    UTF8_BYTES("UTF-8 bytes"),
    UTF16_CODE_UNITS("UTF-16 code units"),
}

data class PathLengthLimit(val max: Int, val unit: PathLengthUnit) {
    fun admits(path: String): Boolean = when (unit) {
        PathLengthUnit.UTF8_BYTES -> path.encodeToByteArray().size
        PathLengthUnit.UTF16_CODE_UNITS -> path.length
    } <= max
}

// POSIX PATH_MAX counts bytes including the terminating NUL. Windows uses the extended-length limit rather
// than MAX_PATH (260), so a host with long paths enabled is never refused a path it can hold.
fun pathLengthLimit(os: HostOs = hostOs): PathLengthLimit = when (os) {
    HostOs.MACOS -> PathLengthLimit(max = 1023, unit = PathLengthUnit.UTF8_BYTES)
    HostOs.LINUX -> PathLengthLimit(max = 4095, unit = PathLengthUnit.UTF8_BYTES)
    HostOs.WINDOWS -> PathLengthLimit(max = 32_767, unit = PathLengthUnit.UTF16_CODE_UNITS)
}
