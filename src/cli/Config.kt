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
 * Persistent daemon configuration. [publicUrl] is part of the Host and Origin authorization allowlists,
 * so it is validated on both read and write rather than treated as a display URL.
 */
@Serializable
data class KotgentConfig(
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
 * Invalid on-disk configuration is fatal at startup so a requested public origin is never silently ignored.
 */
class ConfigException(message: String) : IllegalStateException(message)

const val CONFIG_FILE_NAME: String = "config.json"

fun defaultConfigPath(): String = "${kotgentHome()}/$CONFIG_FILE_NAME"

/**
 * Missing or empty files yield defaults; malformed files throw [ConfigException]. Unknown keys are ignored
 * for forward compatibility.
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
 * Writes canonical configuration atomically as `0600`, creating an owner-only parent directory. Validation
 * occurs before any file is replaced.
 */
fun writeConfig(path: String, config: KotgentConfig) {
    val normalized = config.normalized()
    ensureParentDir(path)
    val json = CONFIG_JSON.encodeToString(KotgentConfig.serializer(), normalized)
    writePrivateFile(path, "$json\n".encodeToByteArray())
}

/**
 * Accepts only an origin. Non-loopback HTTP would expose the session cookie and terminal traffic, while a
 * path, query, fragment, or userinfo would make the authorization allowlist ambiguous.
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

private val CONFIG_JSON: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private const val SCHEME_SEPARATOR: String = "://"

private const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR

private fun canonicalPublicUrl(value: String): String = value.trim().trimEnd('/').lowercase()

@OptIn(ExperimentalForeignApi::class)
internal fun mkdir0700(path: String) {
    mkdir(path, MODE_0700.convert())
}

private fun ensureParentDir(path: String) {
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (dir.isNotEmpty()) mkdir0700(dir)
}
