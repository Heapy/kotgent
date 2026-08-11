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

const val MAX_UPLOAD_FILE_BYTES: Long = 100L * 1024L * 1024L

const val UPLOAD_BODY_TIMEOUT_MILLIS: Long = 10L * 60L * 1_000L

private const val MAX_UPLOAD_FILE_NAME_BYTES: Int = 255

sealed interface FileUploadResult {
    data class Stored(val bytes: Long) : FileUploadResult
    data object AlreadyExists : FileUploadResult
    data object TooLarge : FileUploadResult
    data object LengthMismatch : FileUploadResult
    data object TimedOut : FileUploadResult
    data class Failed(val reason: String) : FileUploadResult
}

fun interface FileUploader {
    suspend fun upload(
        directory: String,
        fileName: String,
        body: ByteReadChannel,
        expectedBytes: Long?,
    ): FileUploadResult
}

internal val posixFileUploader: FileUploader = FileUploader { directory, fileName, body, expectedBytes ->
    saveUploadedFile(directory, fileName, body, expectedBytes)
}

@Serializable
data class FileUploadResponse(
    val name: String,
    val bytes: Long,
    val directory: String,
)

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

internal fun Route.fileUploadRoutes(
    store: EventStore,
    uploader: FileUploader,
    json: Json = TRANSPORT_JSON,
) {
    post("/sessions/{id}/files") {
        // Destination directory always comes from the stored session; the client supplies only a leaf name.
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
    // mkstemp creates a private sibling; every exit unlinks it, so partial bodies are never published.
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

        val closing = fd
        // On macOS close(EINTR) has already released the descriptor; mark it unavailable and never retry.
        fd = -1
        if (close(closing) != 0) return FileUploadResult.Failed("close failed: ${errnoText(errno)}")

        val target = childPath(directory, fileName)
        // link(2) publishes atomically without following or overwriting an existing target/symlink.
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

private suspend fun ApplicationCall.rejectUnconsumedUploadAndClose(
    body: ByteReadChannel,
    text: String,
    status: HttpStatusCode,
) {
    // CIO can otherwise retain the raw parser/socket after an early response with unread body bytes.
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
