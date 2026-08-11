package io.kotgent.transport

import io.kotgent.crypto.hex
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.chmod
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fsync
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.link
import platform.posix.mkdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.strerror
import platform.posix.umask
import platform.posix.unlink
import kotlin.random.Random


fun ApplicationCall.presentedToken(): String? = bearerToken(request.headers[HttpHeaders.Authorization])

fun ApplicationCall.requestFacts(): RequestFacts = RequestFacts(
    host = request.headers[HttpHeaders.Host],
    origin = request.headers[HttpHeaders.Origin],
    authHeader = presentedToken()?.let { "Bearer $it" },
    cookie = sessionCookie(),
    method = request.httpMethod,
    // Apply the browser-origin rule to every handshake, independent of its route path.
    isWebSocket = request.headers[HttpHeaders.SecWebSocketKey] != null,
)

fun Route.authenticated(token: () -> String, publicUrl: String? = null, build: Route.() -> Unit): Route {
    val authed = createChild(AuthRouteSelector) as RoutingNode
    authed.intercept(ApplicationCallPipeline.Plugins) {
        val decision = authorize(
            facts = call.requestFacts(),
            publicUrl = publicUrl,
            verifyToken = { presented -> constantTimeEquals(presented, token()) },
            verifyCookie = { cookie -> verifySessionCookie(token(), cookie) },
        )
        if (decision is AuthDecision.Deny) {
            // Do not turn host/origin/credential failures into a probing oracle.
            call.respondText(refusalBody(decision.status), status = decision.status)
            finish()
        }
    }
    authed.build()
    return authed
}

fun Route.loopbackOnly(build: Route.() -> Unit): Route {
    // Peer address is insufficient: the tunnel itself connects from localhost, but forwards a public Host.
    val local = createChild(LoopbackRouteSelector) as RoutingNode
    local.intercept(ApplicationCallPipeline.Plugins) {
        if (!isLoopbackHost(call.request.headers[HttpHeaders.Host].orEmpty())) {
            call.respondText(refusalBody(HttpStatusCode.Forbidden), status = HttpStatusCode.Forbidden)
            finish()
        }
    }
    local.build()
    return local
}

internal fun refusalBody(status: HttpStatusCode): String =
    if (status == HttpStatusCode.Forbidden) "forbidden" else "unauthorized"

fun constantTimeEquals(a: String, b: String): Boolean {
    val ab = a.encodeToByteArray()
    val bb = b.encodeToByteArray()
    var diff = ab.size xor bb.size
    val n = maxOf(ab.size, bb.size)
    for (i in 0 until n) {
        val x = if (i < ab.size) ab[i].toInt() and 0xff else 0
        val y = if (i < bb.size) bb[i].toInt() and 0xff else 0
        diff = diff or (x xor y)
    }
    return diff == 0
}

private object AuthRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(authenticated)"
}

private object LoopbackRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(loopback-only)"
}


private const val MODE_0600: Int = S_IRUSR or S_IWUSR
private const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR

private const val PERMISSION_MASK: Int = 0b111_111_111

class TokenPermissionException(message: String) : IllegalStateException(message)

@OptIn(ExperimentalForeignApi::class)
private fun permissionBits(path: String): Int? = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) null else st.st_mode.toInt() and PERMISSION_MASK
}

fun requireTokenMode0600(path: String, mode: Int?) {
    // Unknown permissions are not evidence that a machine-wide control secret is private.
    if (mode == null) {
        throw TokenPermissionException(
            "cannot verify the permissions of token file $path (stat failed) — " +
                "refusing to use a token whose mode could not be confirmed 0600",
        )
    }
    if (mode != MODE_0600) {
        throw TokenPermissionException(
            "token file $path is still mode ${mode.toString(8).padStart(3, '0')} after chmod 0600 — " +
                "refusing to use a token that may be readable by other users",
        )
    }
}

private const val UMASK_GROUP_OTHER: Int = 0b111_111

@OptIn(ExperimentalForeignApi::class)
fun defaultTokenPath(): String {
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    val dir = if (home.isNullOrEmpty()) ".kotgent" else "$home/.kotgent"
    return "$dir/token"
}

@OptIn(ExperimentalForeignApi::class)
fun readOrCreateToken(path: String = defaultTokenPath()): String {
    existingToken(path)?.let { return it }

    val token = generateToken()
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (dir.isNotEmpty()) mkdir(dir, MODE_0700.convert())
    if (!createPrivateFileExclusive(path, token.encodeToByteArray())) {
        // Concurrent creators adopt the exclusive winner instead of authenticating with a lost value.
        existingToken(path)?.let { return it }
        writePrivateFile(path, token.encodeToByteArray())
    }
    return existingToken(path)
        ?: error("token file $path is missing or blank immediately after being written")
}

@OptIn(ExperimentalForeignApi::class)
private fun existingToken(path: String): String? {
    val token = readFileTextOrNull(path)?.trim()?.ifEmpty { null } ?: return null
    if (chmod(path, MODE_0600.convert()) != 0) {
        throw TokenPermissionException(
            "cannot secure token file $path (chmod 0600 failed: ${errnoText(errno)}) — " +
                "refusing to use a token that may be readable by other users",
        )
    }
    requireTokenMode0600(path, permissionBits(path))
    return token
}

@OptIn(ExperimentalForeignApi::class)
fun writePrivateFile(path: String, bytes: ByteArray) {
    // A fully-written private sibling replaces the old secret atomically.
    stagePrivateTemp(path, bytes) { tmp ->
        if (rename(tmp, path) != 0) error("rename $tmp -> $path failed: ${errnoText(errno)}")
        true
    }
}

@OptIn(ExperimentalForeignApi::class)
fun createPrivateFileExclusive(path: String, bytes: ByteArray): Boolean =
    stagePrivateTemp(path, bytes) { tmp ->
        // link(2) is the atomic no-clobber publish primitive; rename would overwrite a race winner.
        if (link(tmp, path) == 0) return@stagePrivateTemp true
        val e = errno
        if (e == EEXIST) false else error("link $tmp -> $path failed: ${errnoText(e)}")
    }

@OptIn(ExperimentalForeignApi::class)
private fun stagePrivateTemp(path: String, bytes: ByteArray, publish: (String) -> Boolean): Boolean {
    // Per-writer temp names prevent concurrent writers from replacing or unlinking each other's staging file.
    val tmp = "$path.tmp.${getpid()}.${Random.nextInt().toUInt().toString(16).padStart(8, '0')}"
    // Apply privacy at creation; chmod alone would leave a brief group/world-readable window.
    val previousMask = umask(UMASK_GROUP_OTHER.convert())
    try {
        writeAllOrThrow(tmp, bytes)
        if (chmod(tmp, MODE_0600.convert()) != 0) error("chmod 0600 failed for $tmp: ${errnoText(errno)}")
        return publish(tmp)
    } finally {
        unlink(tmp)
        umask(previousMask)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun readTokenOrNull(path: String = defaultTokenPath()): String? =
    readFileTextOrNull(path)?.trim()?.ifEmpty { null }

const val SECRET_BYTES: Int = 32

@OptIn(ExperimentalForeignApi::class)
fun randomBytes(n: Int = SECRET_BYTES): ByteArray =
    readFileBytesOrNull("/dev/urandom", limit = n)?.takeIf { it.size == n } ?: Random.nextBytes(n)

fun generateToken(): String = hex(randomBytes(SECRET_BYTES))

@OptIn(ExperimentalForeignApi::class)
fun readFileTextOrNull(path: String): String? = readFileBytesOrNull(path)?.decodeToString()

@OptIn(ExperimentalForeignApi::class)
fun readFileBytesOrNull(path: String, limit: Int = Int.MAX_VALUE): ByteArray? {
    val fp = fopen(path, "rb") ?: return null
    try {
        fseek(fp, 0, SEEK_END)
        val size = ftell(fp)
        fseek(fp, 0, SEEK_SET)
        val want = when {
            size > 0L -> minOf(size, limit.toLong()).toInt()
            // Non-seekable sources such as /dev/urandom need an explicit allocation bound.
            limit < Int.MAX_VALUE -> limit
            else -> 0
        }
        if (want <= 0) return ByteArray(0)
        val buffer = ByteArray(want)
        val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), want.convert(), fp) }
        val n = read.toInt()
        return if (n == want) buffer else buffer.copyOf(n)
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAllOrThrow(path: String, bytes: ByteArray) {
    val fp = fopen(path, "wb") ?: error("cannot open $path for write: ${errnoText(errno)}")
    val writeError: String? = run {
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
            if (written.toInt() != bytes.size) return@run "short write (${written.toInt()} of ${bytes.size} bytes)"
        }
        if (fflush(fp) != 0) return@run "fflush failed: ${errnoText(errno)}"
        val fd = fileno(fp)
        if (fd >= 0 && fsync(fd) != 0) return@run "fsync failed: ${errnoText(errno)}"
        null
    }
    val closeFailed = fclose(fp) != 0
    if (writeError != null || closeFailed) {
        unlink(path)
        error("write to $path failed: ${writeError ?: "fclose failed"}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun errnoText(code: Int): String = strerror(code)?.toKString() ?: "errno=$code"
