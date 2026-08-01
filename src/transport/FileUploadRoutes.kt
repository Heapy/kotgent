package io.kotgent.transport

import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.getpid
import platform.posix.link
import platform.posix.mkstemp
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.write

/** One uploaded file is bounded independently; the Web UI sends multiple selections one at a time. */
const val MAX_UPLOAD_FILE_BYTES: Long = 100L * 1024L * 1024L

/** Ten minutes permits a 100 MiB file over a slow mobile uplink without allowing a stalled body forever. */
const val UPLOAD_BODY_TIMEOUT_MILLIS: Long = 10L * 60L * 1_000L

/** The portable POSIX `NAME_MAX` used by the filename gate before any filesystem side effect. */
private const val MAX_UPLOAD_FILE_NAME_BYTES: Int = 255

/** Result of staging and atomically publishing one upload into a session's working directory. */
sealed interface FileUploadResult {
    data class Stored(val bytes: Long) : FileUploadResult
    data object AlreadyExists : FileUploadResult
    data object TooLarge : FileUploadResult
    data object LengthMismatch : FileUploadResult
    data object TimedOut : FileUploadResult
    data class Failed(val reason: String) : FileUploadResult
}

/**
 * Filesystem edge for the upload route. The route supplies the session row's cwd — never a client-provided
 * directory — plus the raw request channel. Injected so transport tests can prove routing and validation
 * without touching the developer's filesystem.
 */
fun interface FileUploader {
    suspend fun upload(
        directory: String,
        fileName: String,
        body: ByteReadChannel,
        expectedBytes: Long?,
    ): FileUploadResult
}

/** Production uploader: bounded streaming into a private sibling temp, then an atomic no-clobber publish. */
internal val posixFileUploader: FileUploader = FileUploader { directory, fileName, body, expectedBytes ->
    saveUploadedFile(directory, fileName, body, expectedBytes)
}

@Serializable
data class FileUploadResponse(
    val name: String,
    val bytes: Long,
    val directory: String,
)

/**
 * Validate one leaf filename. Uploads always land directly in the selected session's cwd: path separators,
 * dot entries, NUL/control characters and names beyond POSIX's portable component limit are refused.
 */
fun uploadFileNameProblem(fileName: String?): String? = when {
    fileName == null -> "missing file name"
    fileName.isBlank() -> "file name must not be blank"
    fileName == "." || fileName == ".." -> "file name must identify a file, not a directory entry"
    '/' in fileName -> "file name must not contain '/'"
    fileName.any { it == '\u0000' || it.code < 0x20 || it.code == 0x7f } ->
        "file name must not contain control characters"
    fileName.encodeToByteArray().size > MAX_UPLOAD_FILE_NAME_BYTES ->
        "file name is longer than $MAX_UPLOAD_FILE_NAME_BYTES UTF-8 bytes"
    else -> null
}

/**
 * Authenticated upload endpoint used by the mobile PWA.
 *
 * The URL identifies a session, not a directory. Its current stored cwd is looked up for every request,
 * which prevents a browser from turning this into an arbitrary-path write API. Only a leaf `name` travels
 * in the query; [uploadFileNameProblem] rejects traversal. Existing targets are never overwritten.
 */
internal fun Route.fileUploadRoutes(
    store: EventStore,
    uploader: FileUploader,
    json: Json = TRANSPORT_JSON,
) {
    post("/sessions/{id}/files") {
        val body = call.receiveChannel()
        val id = call.parameters["id"]?.let { raw -> runCatching { SessionId(raw) }.getOrNull() }
        if (id == null) {
            call.rejectUnconsumedUploadAndClose(body, "malformed session id", HttpStatusCode.BadRequest)
            return@post
        }
        val session = store.getSession(id)
        if (session == null) {
            call.rejectUnconsumedUploadAndClose(
                body,
                "no such session ${id.value}",
                HttpStatusCode.NotFound,
            )
            return@post
        }
        val fileName = call.request.queryParameters["name"]
        val nameProblem = uploadFileNameProblem(fileName)
        if (nameProblem != null) {
            call.rejectUnconsumedUploadAndClose(
                body,
                "cannot upload file: $nameProblem",
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        when (val result = uploader.upload(session.cwd, fileName!!, body, call.request.contentLength())) {
            is FileUploadResult.Stored -> call.respondText(
                json.encodeToString(
                    FileUploadResponse.serializer(),
                    FileUploadResponse(fileName, result.bytes, session.cwd),
                ),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )

            FileUploadResult.AlreadyExists -> call.respondText(
                "cannot upload '$fileName': a file with that name already exists in ${session.cwd}",
                status = HttpStatusCode.Conflict,
            )

            FileUploadResult.TooLarge -> call.rejectUnconsumedUploadAndClose(
                body,
                "cannot upload '$fileName': files are limited to ${MAX_UPLOAD_FILE_BYTES / (1024L * 1024L)} MiB",
                HttpStatusCode.PayloadTooLarge,
            )

            FileUploadResult.LengthMismatch -> call.respondText(
                "cannot upload '$fileName': request body ended before its declared length",
                status = HttpStatusCode.BadRequest,
            )

            FileUploadResult.TimedOut -> call.rejectUnconsumedUploadAndClose(
                body,
                "cannot upload '$fileName': request body timed out",
                HttpStatusCode.RequestTimeout,
            )

            is FileUploadResult.Failed -> call.rejectUnconsumedUploadAndClose(
                body,
                "cannot upload '$fileName' to ${session.cwd}: ${result.reason}",
                HttpStatusCode.Conflict,
            )
        }
    }
}

/**
 * Stream one request body to a unique temp in [directory], then publish it with `link(2)`.
 *
 * `mkstemp` creates the staging file atomically as `0600`; the agent runs as the same user, so it can read
 * it without making a phone-supplied file visible to other local users. The final hard-link is atomic and
 * returns `EEXIST` instead of replacing a target (including a symlink). Every non-success path unlinks the
 * temp, so a disconnect, timeout, oversized body or filesystem error never leaves a partial project file.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun saveUploadedFile(
    directory: String,
    fileName: String,
    body: ByteReadChannel,
    expectedBytes: Long?,
    maxBytes: Long = MAX_UPLOAD_FILE_BYTES,
    timeoutMillis: Long = UPLOAD_BODY_TIMEOUT_MILLIS,
): FileUploadResult {
    require(maxBytes >= 0L) { "upload size limit must be non-negative, got $maxBytes" }
    require(timeoutMillis > 0L) { "upload timeout must be positive, got $timeoutMillis ms" }
    require(uploadFileNameProblem(fileName) == null) { "invalid upload file name '$fileName'" }
    if (expectedBytes != null && expectedBytes > maxBytes) return FileUploadResult.TooLarge
    if (expectedBytes != null && expectedBytes < 0L) return FileUploadResult.LengthMismatch

    return withTimeoutOrNull(timeoutMillis) {
        saveUploadedFileBeforeDeadline(directory, fileName, body, expectedBytes, maxBytes)
    } ?: FileUploadResult.TimedOut
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun saveUploadedFileBeforeDeadline(
    directory: String,
    fileName: String,
    body: ByteReadChannel,
    expectedBytes: Long?,
    maxBytes: Long,
): FileUploadResult {
    val temp = createUploadTemp(directory)
        ?: return FileUploadResult.Failed("cannot create a temporary file (${errnoText(errno)})")
    var fd = temp.fd
    try {
        val buffer = ByteArray(UPLOAD_BUFFER_BYTES)
        var received = 0L
        while (true) {
            val count = try {
                body.readAvailable(buffer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return FileUploadResult.Failed("request body read failed: ${e.message ?: e::class.simpleName}")
            }
            if (count < 0) break
            if (count == 0) continue
            if (received > maxBytes - count.toLong()) return FileUploadResult.TooLarge
            val writeProblem = writeAll(fd, buffer, count)
            if (writeProblem != null) return FileUploadResult.Failed(writeProblem)
            received += count
        }
        if (expectedBytes != null && received != expectedBytes) return FileUploadResult.LengthMismatch
        if (fsync(fd) != 0) return FileUploadResult.Failed("fsync failed: ${errnoText(errno)}")

        // Do not retry close on EINTR: on macOS the descriptor has already been released and could be
        // reused by another coroutine. Mark it unavailable before checking the return for the same reason.
        val closing = fd
        fd = -1
        if (close(closing) != 0) return FileUploadResult.Failed("close failed: ${errnoText(errno)}")

        val target = childPath(directory, fileName)
        if (link(temp.path, target) == 0) return FileUploadResult.Stored(received)
        val linkError = errno
        return if (linkError == EEXIST) {
            FileUploadResult.AlreadyExists
        } else {
            FileUploadResult.Failed("cannot create the destination file: ${errnoText(linkError)}")
        }
    } finally {
        if (fd >= 0) close(fd)
        unlink(temp.path)
    }
}

private data class UploadTemp(val fd: Int, val path: String)

@OptIn(ExperimentalForeignApi::class)
private fun createUploadTemp(directory: String): UploadTemp? = memScoped {
    val template = childPath(directory, ".kotgent-upload-${getpid()}-XXXXXX")
    val encoded = template.encodeToByteArray()
    val chars = allocArray<ByteVar>(encoded.size + 1)
    encoded.forEachIndexed { index, byte -> chars[index] = byte }
    chars[encoded.size] = 0
    val fd = mkstemp(chars)
    if (fd < 0) null else UploadTemp(fd, chars.toKString())
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAll(fd: Int, buffer: ByteArray, size: Int): String? = buffer.usePinned { pinned ->
    var offset = 0
    while (offset < size) {
        val written = write(fd, pinned.addressOf(offset), (size - offset).convert()).toInt()
        if (written > 0) {
            offset += written
        } else if (written < 0 && errno == EINTR) {
            continue
        } else {
            return@usePinned "write failed after $offset of $size bytes: ${errnoText(errno)}"
        }
    }
    null
}

private fun childPath(directory: String, name: String): String =
    if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"

@OptIn(ExperimentalForeignApi::class)
private fun errnoText(code: Int): String = strerror(code)?.toKString() ?: "errno=$code"

/** Reject an unread tail and release CIO's raw socket parser, using the same pinned close as auth intake. */
private suspend fun ApplicationCall.rejectUnconsumedUploadAndClose(
    body: ByteReadChannel,
    text: String,
    status: HttpStatusCode,
) {
    response.headers.append(HttpHeaders.Connection, "close")
    try {
        respondText(text, status = status)
    } finally {
        body.cancel(null)
        withContext(NonCancellable) {
            closePinnedCioConnectionAfterFlush("closing unconsumed file upload request body")
        }
    }
}

private const val UPLOAD_BUFFER_BYTES: Int = 64 * 1024
