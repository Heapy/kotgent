package io.kotgent.transport

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.umask
import platform.posix.unlink
import kotlin.random.Random

/**
 * Token auth for the local control plane (plan Task 14 / Technical Details: `127.0.0.1` + one token
 * on everything, `~/.kotgent/token`, `0600`).
 *
 * ## One token for everything
 * A single shared secret gates the whole surface — the bearer clients present on control REST + the WS
 * handshakes, AND the value the Claude hooks send in their own header (the Task-12 [claudeHookRoutes]
 * checks the same string). The plan is explicit: "один токен на всё" (one token on all); a distinct
 * per-purpose token is backlog. [readOrCreateToken] is the one source of that value.
 *
 * ## Header OR query param (why both)
 * [presentedToken] accepts the token from either an `Authorization: Bearer <token>` header (REST clients)
 * or a `?token=<token>` query parameter. The query form exists because **browsers cannot set custom
 * headers on a WebSocket handshake** — the Web UI reads the token from its URL fragment (`#token=`, which
 * is never sent to the server) and appends it as `?token=` when opening the events / terminal sockets.
 * One extractor serves both so [authenticated] gates REST and WS uniformly.
 */

/** Query-parameter name carrying the bearer token (WS handshakes; browsers can't set headers). */
const val TOKEN_QUERY_PARAM: String = "token"

/**
 * The bearer token the client presented on [this] call, from `Authorization: Bearer <token>` or the
 * [TOKEN_QUERY_PARAM] query parameter, or `null` if neither is present. Trimmed; the scheme match is
 * case-insensitive.
 */
fun ApplicationCall.presentedToken(): String? {
    val auth = request.headers[HttpHeaders.Authorization]
    if (auth != null && auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
        return auth.substring(7).trim().ifEmpty { null }
    }
    return request.queryParameters[TOKEN_QUERY_PARAM]?.trim()?.ifEmpty { null }
}

/**
 * Gate every route built inside [build] behind [token]: a request that presents a missing or wrong
 * token (see [presentedToken]) is answered `401` and the pipeline is stopped **before** the wrapped
 * handler runs. Crucially this rejects a bad WebSocket handshake too — the `401` is written in the
 * `Plugins` phase, before the WS route's upgrade handler executes, so no socket is upgraded.
 *
 * Implemented as a transparent child route (adds no path segment) with a pipeline interceptor, the same
 * shape Ktor's own `authenticate { }` uses — so nested `/sessions`, `/events`, `/sessions/{id}/terminal`
 * keep their literal paths and their higher routing priority over any catch-all static route.
 */
fun Route.authenticated(token: String, build: Route.() -> Unit): Route {
    val authed = createChild(AuthRouteSelector) as RoutingNode
    authed.intercept(ApplicationCallPipeline.Plugins) {
        val presented = call.presentedToken()
        if (presented == null || !constantTimeEquals(presented, token)) {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            finish()
        }
    }
    authed.build()
    return authed
}

/**
 * Constant-time string equality for secret comparison — always inspects every byte (and folds in any
 * length difference) so it does not leak how many leading characters of the token matched via early-exit
 * (`!=`) timing. Used for the bearer token and the hook token ([claudeHookRoutes]).
 */
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

/** A path-neutral route selector (like Ktor's auth selector): matches transparently, adds no segment. */
private object AuthRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(authenticated)"
}

// --- token file (~/.kotgent/token, 0600) ---------------------------------------------------------

private const val MODE_0600: Int = S_IRUSR or S_IWUSR
private const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR

/** umask masking off all group + other permission bits (`0o077`), so a new file is created `0600`. */
private const val UMASK_GROUP_OTHER: Int = 0b111_111

/** Default token path: `~/.kotgent/token`. Falls back to a cwd-relative path if `$HOME` is unset. */
@OptIn(ExperimentalForeignApi::class)
fun defaultTokenPath(): String {
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    val dir = if (home.isNullOrEmpty()) ".kotgent" else "$home/.kotgent"
    return "$dir/token"
}

/**
 * Read the shared token from [path], or generate + persist one if it does not yet exist. A freshly
 * created token file is written `0600` inside a `0700` `~/.kotgent` directory (both enforced), so the
 * secret is not world-readable. The token is 32 bytes of entropy (from `/dev/urandom` when available,
 * else [Random]) hex-encoded. Idempotent: an existing non-blank token file is read back verbatim, so
 * every process (daemon, CLI, hooks) resolves the same value.
 */
@OptIn(ExperimentalForeignApi::class)
fun readOrCreateToken(path: String = defaultTokenPath()): String {
    readFileTextOrNull(path)?.trim()?.let { if (it.isNotEmpty()) return it }
    val token = generateToken()
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (dir.isNotEmpty()) mkdir(dir, MODE_0700.convert()) // ignore EEXIST — a pre-existing dir is fine
    writePrivateFile(path, token.encodeToByteArray())
    return token
}

/**
 * Write [bytes] to [path] as a secret file (mode `0600`) WITHOUT the brief world-readable window that
 * `fopen` (creating `0666 & ~umask`, typically `0644`) followed by a later `chmod 0600` would leave: a
 * restrictive [umask] is installed for the duration of the create so the file is `0600` from the moment
 * it exists. Any stale file is unlinked first so a rewrite (e.g. the hook-settings file on daemon
 * restart) also lands `0600`; a trailing `chmod` covers the rewrite-into-an-existing-file case. Shared
 * by the token and the hook-settings writers — both carry secrets.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writePrivateFile(path: String, bytes: ByteArray) {
    unlink(path) // ignore ENOENT — a fresh file gets the umask-restricted 0600 mode on create
    // Mask off ALL group + other bits (0o077) so a newly created 0666 file becomes 0600 immediately.
    val previousMask = umask(UMASK_GROUP_OTHER.convert())
    try {
        writeFileText(path, bytes.decodeToString())
    } finally {
        umask(previousMask)
    }
    chmod(path, MODE_0600.convert())
}

/**
 * Read the shared token from [path] WITHOUT creating one — `null` if the file is missing or blank.
 * This is the CLI client's path (plan Task 15): the daemon owns token creation ([readOrCreateToken]),
 * so a CLI that finds no token can surface a clear "is the daemon running?" error instead of silently
 * minting a secret the daemon does not know about.
 */
@OptIn(ExperimentalForeignApi::class)
fun readTokenOrNull(path: String = defaultTokenPath()): String? =
    readFileTextOrNull(path)?.trim()?.ifEmpty { null }

/** 32 bytes of entropy, hex-encoded. Prefers `/dev/urandom`; falls back to [Random] if unreadable. */
@OptIn(ExperimentalForeignApi::class)
private fun generateToken(): String {
    val bytes = readFileBytesOrNull("/dev/urandom", limit = 32)
        ?.takeIf { it.size == 32 }
        ?: Random.nextBytes(32)
    return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileTextOrNull(path: String): String? =
    readFileBytesOrNull(path, limit = Int.MAX_VALUE)?.decodeToString()

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytesOrNull(path: String, limit: Int): ByteArray? {
    val fp = fopen(path, "rb") ?: return null
    try {
        // /dev/urandom is not seekable to a size; read a fixed [limit] there. For a regular file, size
        // via seek. We branch on whether ftell after SEEK_END gives a positive size.
        fseek(fp, 0, SEEK_END)
        val size = ftell(fp)
        fseek(fp, 0, SEEK_SET)
        val want = if (size > 0L) minOf(size.toInt(), limit) else limit
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
private fun writeFileText(path: String, text: String) {
    val bytes = text.encodeToByteArray()
    val fp = fopen(path, "wb") ?: error("cannot open token file $path for write")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        }
    } finally {
        fclose(fp)
    }
}
