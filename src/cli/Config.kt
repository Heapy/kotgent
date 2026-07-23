package io.kotgent.cli

import io.kotgent.transport.authorityHasForbiddenChars
import io.kotgent.transport.isLoopbackHost
import io.kotgent.transport.readFileTextOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.mkdir

/**
 * The daemon's small persistent configuration, `~/.kotgent/config.json` (mode `0600`, next to the token).
 *
 * Only one setting lives here so far: the PUBLIC origin the daemon is published at through the cloudflared
 * tunnel. It is deliberately typed in by hand (`kotgent config set public-url …`) rather than sniffed out of
 * `~/.cloudflared/config.yml` — that would mean a YAML parser on Kotlin/Native for a value that is entered
 * once in the lifetime of a machine.
 *
 * The value is load-bearing for authorization, not decorative: it is exactly the extra entry in the `Host`
 * and `Origin` allowlists ([io.kotgent.transport.allowedOrigins]). That is why it is VALIDATED on both ends
 * (write and read) — a "URL" carrying a path, a query or userinfo would silently widen or break a rule that
 * decides who may drive the terminals on this machine.
 *
 * Reading is the CLI's job, not the transport's: the daemon reads the file at startup and passes the value
 * into `KotgentServer` through its constructor (Task 7). The dependency runs cli → transport, and turning it
 * around so the server could read its own config file would invert it.
 */
@Serializable
data class KotgentConfig(
    /** The public origin (`https://kotgent.heapyhop.com`), or `null` when the daemon is loopback-only. */
    val publicUrl: String? = null,
) {
    /**
     * This config with [publicUrl] canonicalised (trimmed, lower-cased, no trailing `/`), or a
     * [ConfigException] if the URL is not one kotgent will accept ([publicUrlProblem]).
     */
    fun normalized(): KotgentConfig {
        val url = publicUrl ?: return this
        publicUrlProblem(url)?.let { throw ConfigException("invalid public URL '$url': $it") }
        return copy(publicUrl = canonicalPublicUrl(url))
    }
}

/**
 * Raised when the config on disk cannot be understood — unparseable JSON, or a `publicUrl` that is not a
 * bare `scheme://host[:port]`. Fatal on purpose in the daemon's startup path: a config that was meant to
 * publish the daemon somewhere and does not is worse than a daemon that refuses to start and says why.
 */
class ConfigException(message: String) : IllegalStateException(message)

/** File name of the config inside `~/.kotgent`. */
const val CONFIG_FILE_NAME: String = "config.json"

/** `~/.kotgent/config.json` — the same directory as the token (`.kotgent/…` if `$HOME` is unset). */
fun defaultConfigPath(): String = "${kotgentHome()}/$CONFIG_FILE_NAME"

/**
 * Read the config at [path]. A MISSING (or empty) file is not an error — it is the normal state of a fresh
 * install and yields an empty [KotgentConfig]; only a file that exists and cannot be understood raises
 * [ConfigException], with [path] named in the message so the operator knows which file to fix.
 *
 * Unknown keys are ignored, so a config written by a newer kotgent does not stop an older one from starting.
 */
fun readConfig(path: String = defaultConfigPath()): KotgentConfig {
    val text = readFileTextOrNull(path)?.trim()?.ifEmpty { null } ?: return KotgentConfig()
    val parsed = try {
        CONFIG_JSON.decodeFromString(KotgentConfig.serializer(), text)
    } catch (e: SerializationException) {
        throw ConfigException("cannot parse the kotgent config at $path: ${e.message}")
    }
    val problem = parsed.publicUrl?.let(::publicUrlProblem)
    if (problem != null) throw ConfigException("invalid \"publicUrl\" in $path: $problem")
    return parsed.normalized()
}

/**
 * Write [config] to [path] as a `0600` file, atomically ([writePrivateFile] stages a private temp and
 * renames over the target, so a crash mid-write cannot leave a half-parsed config behind). The parent
 * directory is created `0700` if it is not there yet — `kotgent config set` may well run before the daemon
 * has ever created `~/.kotgent`.
 *
 * The config is [KotgentConfig.normalized] first, so what lands on disk is the canonical form and an invalid
 * URL is refused BEFORE anything is written.
 */
fun writeConfig(path: String, config: KotgentConfig) {
    val normalized = config.normalized()
    ensureParentDir(path)
    val json = CONFIG_JSON.encodeToString(KotgentConfig.serializer(), normalized)
    writePrivateFile(path, "$json\n".encodeToByteArray())
}

/**
 * Why [value] is not usable as `publicUrl`, or `null` if it is fine. A human-readable reason, meant to be
 * printed straight back at whoever typed it.
 *
 * The rule: a scheme and a host, and nothing else.
 * - `https://` always; `http://` ONLY for loopback. The public URL names a host reachable from a phone over
 *   the open internet — plain `http` there would put the session cookie and every keystroke of a terminal on
 *   the wire in clear. Loopback is exempt because the daemon itself speaks plain HTTP on `127.0.0.1` (there
 *   is no TLS on Kotlin/Native — that is the whole reason a tunnel exists).
 * - No path, query, fragment or userinfo. This value becomes an ORIGIN in the allowlist, and an origin is a
 *   scheme, a host and an optional port. A trailing `/` is tolerated, because that is how a URL gets copied
 *   out of a browser's address bar.
 */
fun publicUrlProblem(value: String): String? {
    val url = value.trim()
    if (url.isEmpty()) return "it is empty"
    val separator = url.indexOf(SCHEME_SEPARATOR)
    if (separator <= 0) return "it is not a URL — expected https://host"
    val scheme = url.substring(0, separator).lowercase()
    if (scheme != "https" && scheme != "http") return "'$scheme' is not a supported scheme — use https://"
    val authority = url.substring(separator + SCHEME_SEPARATOR.length).trimEnd('/')
    if (authority.isEmpty()) return "it has no host"
    if (authorityHasForbiddenChars(authority)) {
        return "it must be a scheme and a host only — drop the path, query and userinfo"
    }
    if (scheme == "http" && !isLoopbackHost(authority)) {
        return "plain http is only allowed for loopback — use https:// for a public host"
    }
    return null
}

/** Shared JSON for the config file: pretty (it is hand-edited) and forward-compatible (unknown keys pass). */
private val CONFIG_JSON: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = false // an unset publicUrl writes `{}`, not `{"publicUrl":null}`
}

/** The `://` between an origin's scheme and its authority. */
private const val SCHEME_SEPARATOR: String = "://"

/** `0700` — owner-only, the mode `~/.kotgent` is created with (it holds the token). */
private const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR

/** [value] lower-cased with any trailing `/` dropped. Only called on a value [publicUrlProblem] accepted. */
private fun canonicalPublicUrl(value: String): String = value.trim().trimEnd('/').lowercase()

/**
 * Create the directory [path] `0700` (owner-only) if it does not exist; a pre-existing directory is left
 * alone (EEXIST ignored). The single `0700` mkdir the cli reuses — both `~/.kotgent` (the daemon) and a
 * config file's parent dir (`config set`) are created through here so the mode and error handling stay in
 * one place.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun mkdir0700(path: String) {
    mkdir(path, MODE_0700.convert()) // ignore EEXIST — a pre-existing dir is fine
}

/** Create [path]'s parent directory `0700` if it does not exist; an existing directory is left alone. */
private fun ensureParentDir(path: String) {
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (dir.isNotEmpty()) mkdir0700(dir)
}
