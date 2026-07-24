package io.kotgent.transport

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

/** Completes only the final path segment; implementations never recursively scan the filesystem. */
fun interface DirectoryCompleter {
    fun complete(basePath: String?, input: String): List<String>
}

@Serializable
data class CompleteDirectoryRequest(
    val basePath: String? = null,
    val input: String,
)

@Serializable
data class CompleteDirectoryResponse(val paths: List<String>)

internal val posixDirectoryCompleter: DirectoryCompleter = DirectoryCompleter { basePath, input ->
    completeDirectoryPaths(basePath, input, ::posixChildDirectoryNames)
}

/**
 * Authenticated completion endpoint. It is deliberately part of the published control plane: a phone
 * should complete paths on the daemon's Mac, not on the phone's filesystem.
 */
internal fun Route.directoryCompletionRoutes(
    completer: DirectoryCompleter,
    json: Json = TRANSPORT_JSON,
) {
    post("/directories/complete") {
        val request = try {
            json.decodeFromString(CompleteDirectoryRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }

        val input = request.input.trim()
        val basePath = request.basePath?.trim()?.ifEmpty { null }
        if (input.isNotEmpty() && !input.startsWith("/") && basePath?.startsWith("/") != true) {
            call.respondText(
                "basePath must be absolute when input is relative",
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }

        val response = CompleteDirectoryResponse(
            if (input.isEmpty()) emptyList() else completer.complete(basePath, input),
        )
        call.respondText(
            json.encodeToString(CompleteDirectoryResponse.serializer(), response),
            ContentType.Application.Json,
        )
    }
}

/** Pure completion policy over an injected one-level directory listing. */
fun completeDirectoryPaths(
    basePath: String?,
    input: String,
    listChildDirectories: (String) -> List<String>,
): List<String> {
    val target = completionTarget(basePath, input) ?: return emptyList()
    val showHidden = target.prefix.startsWith(".")
    return listChildDirectories(target.parent)
        .asSequence()
        .filter { name -> showHidden || !name.startsWith(".") }
        .filter { name -> name.startsWith(target.prefix, ignoreCase = true) }
        .sortedWith(compareBy<String> { it.lowercase() }.thenBy { it })
        .take(DIRECTORY_COMPLETION_LIMIT)
        .map { name -> joinPath(target.parent, name) }
        .toList()
}

private data class CompletionTarget(val parent: String, val prefix: String)

private fun completionTarget(basePath: String?, input: String): CompletionTarget? {
    val typed = input.trim()
    if (typed.isEmpty()) return null

    val absolute = if (typed.startsWith("/")) {
        typed
    } else {
        val base = basePath?.trim()?.takeIf { it.startsWith("/") } ?: return null
        joinPath(base.trimEnd('/').ifEmpty { "/" }, typed)
    }.replace(REPEATED_SLASHES, "/")

    if (absolute.endsWith("/")) {
        return CompletionTarget(absolute.trimEnd('/').ifEmpty { "/" }, "")
    }
    val slash = absolute.lastIndexOf('/')
    val parent = if (slash <= 0) "/" else absolute.substring(0, slash)
    return CompletionTarget(parent, absolute.substring(slash + 1))
}

private fun joinPath(parent: String, child: String): String =
    if (parent == "/") "/$child" else "${parent.trimEnd('/')}/$child"

/** Immediate child directory names, following symlinks through `stat`; unreadable paths yield no matches. */
@OptIn(ExperimentalForeignApi::class)
fun posixChildDirectoryNames(path: String): List<String> {
    val directory = opendir(path) ?: return emptyList()
    try {
        val names = ArrayList<String>()
        while (true) {
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            if (isDirectory(joinPath(path, name))) names += name
        }
        return names
    } finally {
        closedir(directory)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun isDirectory(path: String): Boolean = memScoped {
    val info = alloc<stat>()
    stat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFDIR
}

private val REPEATED_SLASHES = Regex("/{2,}")
private const val DIRECTORY_COMPLETION_LIMIT: Int = 20
