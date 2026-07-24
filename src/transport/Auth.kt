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

/**
 * Token auth for the local control plane (plan Task 14 / Technical Details: `127.0.0.1` + one token
 * on everything, `~/.kotgent/token`, `0600`).
 *
 * ## One token for everything
 * A single shared secret gates the whole surface — the bearer clients present on control REST + the WS
 * handshakes, AND the value the Claude hooks send in their own header (the Task-12 [claudeHookRoutes]
 * checks the same string). The plan is explicit: "один токен на всё" (one token on all); a distinct
 * per-purpose token is backlog. [readOrCreateToken] is the one source of that value at startup, and
 * [TokenHolder] is what every gate reads it through afterwards, so a rotation is picked up live.
 *
 * ## Bearer header only
 * [presentedToken] reads the token from an `Authorization: Bearer <token>` header and nowhere else — the
 * CLI, the hooks and `kotgent attach` all set it (an `attach` WS handshake carries it too; a native client
 * has no trouble setting headers). The browser does NOT present a token at all: it authenticates with the
 * ambient session cookie ([verifySessionCookie]) the login flow set, so no secret ever rides in a URL, its
 * query string or its fragment. The old `?token=` / `#token=` forms are gone entirely.
 *
 * ## Where the DECISION lives
 * This file is the Ktor edge: it collects request facts ([requestFacts]) and turns a refusal into a
 * response. What counts as authorized — the `Host` allowlist, the `Origin` rule, `Bearer` vs the browser's
 * session cookie — is the pure [authorize] in `Authorization.kt`, so the rule stays table-testable and this
 * layer stays a handful of accessor calls.
 */

/**
 * The bearer token the client presented on [this] call via `Authorization: Bearer <token>`, or `null` if
 * the header is absent or malformed. Delegates to [bearerToken] so the `Bearer ` scheme is parsed in
 * exactly one place; the scheme match is case-insensitive and the value is trimmed. There is no
 * query-parameter fallback any more — the browser authenticates with the session cookie, not a URL token.
 */
fun ApplicationCall.presentedToken(): String? = bearerToken(request.headers[HttpHeaders.Authorization])

/**
 * Every fact [authorize] is allowed to look at, read off [this] call.
 *
 * `isWebSocket` is derived from `Sec-WebSocket-Key`, NOT from matching a path: the stricter `Origin` rule
 * exists because a handshake is the one browser request that reaches a server without a CORS preflight, and
 * that property belongs to the request, not to the URL it happens to use. A future socket route therefore
 * cannot silently opt out of the rule by being mounted somewhere new.
 *
 * The `Authorization` header is read through [presentedToken] and re-normalised to a `Bearer <token>`
 * string, which [authorize] parses via [bearerToken]. There is no query-parameter fallback: the browser
 * authenticates with the session cookie, never a token in the URL.
 */
fun ApplicationCall.requestFacts(): RequestFacts = RequestFacts(
    host = request.headers[HttpHeaders.Host],
    origin = request.headers[HttpHeaders.Origin],
    authHeader = presentedToken()?.let { "Bearer $it" },
    cookie = sessionCookie(),
    method = request.httpMethod,
    isWebSocket = request.headers[HttpHeaders.SecWebSocketKey] != null,
)

/**
 * Gate every route built inside [build] behind the daemon's ONE authorization rule ([authorize]): a request
 * that fails it is answered `401`/`403` and the pipeline is stopped **before** the wrapped handler runs.
 * Crucially this rejects a bad WebSocket handshake too — the refusal is written in the `Plugins` phase,
 * before the WS route's upgrade handler executes, so no socket is upgraded.
 *
 * Two credentials are accepted, and they are not interchangeable in what they require:
 *  - a **`Bearer`** master token — the CLI, `kotgent attach`, the hooks. Never needs an `Origin`; it is not
 *    a browser and no third-party page can make something else present it.
 *  - the browser's **session cookie** ([verifySessionCookie]), which the browser attaches AMBIENTLY. That is
 *    why the `Host` allowlist and the `Origin` rule exist at all — see [authorize].
 *
 * [token] is a PROVIDER, not a captured string ([TokenHolder.current]): the secret is resolved per request,
 * so `kotgent token rotate` takes effect on the very next request instead of at the next daemon restart —
 * and, because the cookie is an HMAC under that same secret, a rotation invalidates every cookie ever issued
 * at the same moment. Requests already past this phase — an established WebSocket, most visibly — are
 * unaffected, because the decision is made once per request, at its start.
 *
 * [publicUrl] is the configured public origin (`~/.kotgent/config.json`), or `null` when the daemon is
 * loopback-only; it is exactly the extra entry in the `Host`/`Origin` allowlists.
 *
 * Implemented as a transparent child route (adds no path segment) with a pipeline interceptor, the same
 * shape Ktor's own `authenticate { }` uses — so nested `/sessions`, `/events`, `/sessions/{id}/terminal`
 * keep their literal paths and their higher routing priority over any catch-all static route.
 */
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
            // The client is told WHICH gate refused (401 vs 403) and nothing else: naming the host, the
            // origin or the credential that failed would hand a prober a free oracle. The reason is for the
            // daemon's own logs and for tests.
            call.respondText(refusalBody(decision.status), status = decision.status)
            finish()
        }
    }
    authed.build()
    return authed
}

/**
 * Restrict every route built inside [build] to requests that arrived under a LOOPBACK `Host` — the routes
 * that are never published through the tunnel: both hook ingresses, and (Task 8) ticket issuance and token
 * rotation. Anything else is `403`, decided in the `Plugins` phase like [authenticated].
 *
 * This is the route-level form of [authorize]'s `loopbackOnly` row, and it is a `Host` check rather than a
 * peer-address check on purpose: the daemon binds `127.0.0.1` and cloudflared connects to it FROM localhost,
 * so the peer address cannot tell "the tunnel" from "a local client" — the `Host` the tunnel forwards can.
 * A missing `Host` is refused too ([isLoopbackHost] of `""` is false): HTTP/1.1 requires the header, and a
 * request that omits it has not said it was talking to this machine.
 *
 * It carries NO credential check of its own, so it composes: the hook ingresses check their own header token
 * inside the handler, and a route needing the master token nests this inside [authenticated].
 */
fun Route.loopbackOnly(build: Route.() -> Unit): Route {
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

/** The one-word body sent with a refusal — deliberately says only which gate answered. */
internal fun refusalBody(status: HttpStatusCode): String =
    if (status == HttpStatusCode.Forbidden) "forbidden" else "unauthorized"

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

/** The same path-neutral selector for [loopbackOnly] — a separate object only so routes print distinctly. */
private object LoopbackRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(loopback-only)"
}

// --- token file (~/.kotgent/token, 0600) ---------------------------------------------------------

private const val MODE_0600: Int = S_IRUSR or S_IWUSR
private const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR

/** All nine `rwxrwxrwx` permission bits — the mask [permissionBits] applies to a `st_mode`. */
private const val PERMISSION_MASK: Int = 0b111_111_111

/**
 * Raised when the shared token file cannot be made (or confirmed) `0600`. Fatal on purpose: the token
 * gates the whole local control plane, so proceeding with a secret other local users can read would
 * silently downgrade auth to "anyone on this machine".
 */
class TokenPermissionException(message: String) : IllegalStateException(message)

/** The file's nine permission bits, or `null` if it cannot be `stat`ed (it was just read, so unlikely). */
@OptIn(ExperimentalForeignApi::class)
private fun permissionBits(path: String): Int? = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) null else st.st_mode.toInt() and PERMISSION_MASK
}

/**
 * Assert that [mode] (the nine permission bits of the token file at [path], as [permissionBits] reports
 * them) is exactly `0600`, throwing [TokenPermissionException] otherwise.
 *
 * A `null` [mode] — the `stat` failed, so the mode is UNKNOWN — is a hard failure too, not a pass. The
 * whole point of the check is to refuse a secret other local users might be able to read; "we could not
 * find out" is not evidence that they cannot, and treating it as one would hand out an unverified token
 * exactly when the filesystem is behaving strangely. Public so the decision is unit-testable directly
 * (the `stat`-failure branch cannot be provoked through the file API).
 */
fun requireTokenMode0600(path: String, mode: Int?) {
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
 *
 * On reading an EXISTING file its permissions are re-hardened to `0600` (`chmod`) and the result is
 * VERIFIED: a token written by an older build — or otherwise left group/other-readable — must not
 * silently stay world-readable just because it already exists. If it cannot be hardened (or its mode
 * cannot be confirmed) this throws rather than returning the secret ([TokenPermissionException]):
 * handing out a shared secret that other local users can read is worse than refusing to start.
 *
 * Creation is EXCLUSIVE ([createPrivateFileExclusive]) and the returned value is always re-read from the
 * file, so the token this process hands out is the one actually persisted. Two processes (daemon + CLI +
 * hooks) can legitimately race here on a fresh install; a plain "generate → overwrite → return what I
 * generated" would let the loser use a token the file no longer holds, and every subsequent request from
 * it would 401.
 */
@OptIn(ExperimentalForeignApi::class)
fun readOrCreateToken(path: String = defaultTokenPath()): String {
    existingToken(path)?.let { return it }

    val token = generateToken()
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (dir.isNotEmpty()) mkdir(dir, MODE_0700.convert()) // ignore EEXIST — a pre-existing dir is fine
    if (!createPrivateFileExclusive(path, token.encodeToByteArray())) {
        // Lost the race (or found a leftover). Whoever won owns the value — adopt it …
        existingToken(path)?.let { return it }
        // … unless what is there is blank/unusable (a truncated file from a crashed older writer), in
        // which case it carries no secret to preserve and is replaced atomically.
        writePrivateFile(path, token.encodeToByteArray())
    }
    // Read back what is on disk rather than trusting [token]: this is the value every other process will
    // read, so it is the only value safe to authenticate with.
    return existingToken(path)
        ?: error("token file $path is missing or blank immediately after being written")
}

/**
 * The persisted token at [path] — hardened to `0600` and verified — or `null` if the file is missing or
 * blank. Repairs a mis-permissioned pre-existing token (e.g. a `0644` left by an older version): both the
 * `chmod` RESULT and the resulting mode are checked ([requireTokenMode0600]), because an ignored failure
 * would leave the secret group/world-readable while we happily used it.
 */
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

/**
 * Write [bytes] to [path] as a secret file (mode `0600`) ATOMICALLY and durably, REPLACING whatever is
 * there: stage into a private sibling temp ([stagePrivateTemp]) and `rename` it over [path]. Every IO
 * error is checked and surfaced (`error`), and the temp is removed on failure — so a disk error can
 * neither DESTROY an existing valid secret (the original is untouched until the atomic rename) nor
 * persist a partial/empty one while startup reports success. Shared by the token, the hook-settings, and
 * the hook-header writers — all carry secrets.
 */
@OptIn(ExperimentalForeignApi::class)
fun writePrivateFile(path: String, bytes: ByteArray) {
    stagePrivateTemp(path, bytes) { tmp ->
        if (rename(tmp, path) != 0) error("rename $tmp -> $path failed: ${errnoText(errno)}")
        true
    }
}

/**
 * Create [path] with [bytes] as a `0600` secret file ONLY IF IT DOES NOT EXIST — `true` if this call
 * created it, `false` if another writer got there first (the existing file is left completely untouched).
 *
 * The publish step is `link`, which is atomic and fails with `EEXIST` on a pre-existing target: exactly
 * the "first writer wins, everyone else adopts the winner's value" primitive [readOrCreateToken] needs.
 * A `rename` cannot express this — it would silently clobber the winner's secret, leaving the loser
 * authenticating with a token the file no longer holds. Public so the exclusivity is unit-testable.
 */
@OptIn(ExperimentalForeignApi::class)
fun createPrivateFileExclusive(path: String, bytes: ByteArray): Boolean =
    stagePrivateTemp(path, bytes) { tmp ->
        if (link(tmp, path) == 0) return@stagePrivateTemp true
        val e = errno
        if (e == EEXIST) false else error("link $tmp -> $path failed: ${errnoText(e)}")
    }

/**
 * Write [bytes] to a private sibling temp of [path] (`0600` from the moment it exists — never the brief
 * `0644` window a plain `fopen`+later-`chmod` would leave, thanks to the restrictive [umask]), `fsync` +
 * close-check it, then hand it to [publish] to move it into place; the temp is always removed afterwards.
 *
 * The temp name is UNIQUE PER WRITER (pid + random suffix). A shared `<path>.tmp` made concurrent writers
 * collide: each one `unlink`ed and overwrote the other's in-flight temp, so a writer could `rename` a
 * file another process had already replaced the contents of — and end up returning a secret different
 * from the one persisted. Unique names make the writers independent up to their (atomic) publish step.
 */
@OptIn(ExperimentalForeignApi::class)
private fun stagePrivateTemp(path: String, bytes: ByteArray, publish: (String) -> Boolean): Boolean {
    val tmp = "$path.tmp.${getpid()}.${Random.nextInt().toUInt().toString(16).padStart(8, '0')}"
    // Mask off ALL group + other bits (0o077) so the newly created temp is 0600 immediately.
    val previousMask = umask(UMASK_GROUP_OTHER.convert())
    try {
        writeAllOrThrow(tmp, bytes) // fwrite/fflush/fsync/fclose all checked; unlinks tmp + throws on error
        if (chmod(tmp, MODE_0600.convert()) != 0) error("chmod 0600 failed for $tmp: ${errnoText(errno)}")
        return publish(tmp)
    } finally {
        // ENOENT after a successful rename (the temp IS the target now); removes the staged copy after a
        // link, a failed publish, or a chmod failure. Never touches [path] itself.
        unlink(tmp)
        umask(previousMask)
    }
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

/**
 * How many bytes of entropy the master token carries: 256 bits. The one-shot login codes are deliberately
 * NOT this long — they have to be typed on a phone, so they carry [TICKET_CODE_BYTES] and pay for the
 * difference with single use, a five-minute TTL and a rate limit (see [TicketStore]).
 */
const val SECRET_BYTES: Int = 32

/**
 * [n] bytes of entropy, preferring `/dev/urandom` and falling back to [Random] if it cannot be read.
 *
 * THE one entropy source in the daemon: the master token ([generateToken]) and the one-shot login tickets
 * are both minted from it, so there is a single place where "where do our secrets come from" is answered
 * (and a single place to fix if the fallback ever needs to become fatal instead).
 */
@OptIn(ExperimentalForeignApi::class)
fun randomBytes(n: Int = SECRET_BYTES): ByteArray =
    readFileBytesOrNull("/dev/urandom", limit = n)?.takeIf { it.size == n } ?: Random.nextBytes(n)

/**
 * A fresh master token: 32 bytes of entropy, hex-encoded. Prefers `/dev/urandom`; falls back to [Random]
 * if unreadable.
 *
 * Public so that THE minting of the machine's key happens in exactly one place — [readOrCreateToken] on
 * first start and [TokenHolder.rotate] on `kotgent token rotate` both call it, rather than each deciding
 * for itself how long a token is and where its entropy comes from.
 */
fun generateToken(): String = hex(randomBytes(SECRET_BYTES))

/**
 * The whole contents of [path] as text, or `null` if it cannot be opened. THE file reader of the daemon —
 * the token, the hook-header files and `~/.kotgent/config.json` ([io.kotgent.cli.readConfig]) all come
 * through here, so "how kotgent reads a file" is answered in exactly one place.
 */
@OptIn(ExperimentalForeignApi::class)
fun readFileTextOrNull(path: String): String? = readFileBytesOrNull(path)?.decodeToString()

/**
 * The bytes of [path], or `null` if it cannot be opened (missing, unreadable). At most [limit] bytes are
 * returned; the default reads the whole file, which is what the static Web UI and the config/token readers
 * want.
 *
 * A file whose size cannot be determined by seeking — `/dev/urandom` is the one that matters here — is read
 * as exactly [limit] bytes, and as NOTHING when [limit] is unbounded: there is no size to allocate against,
 * and sizing that read by `Int.MAX_VALUE` would try to allocate 2 GiB for what is usually an EMPTY regular
 * file (a truncated token, an empty `config.json`), turning a routine "start from scratch" into an OOM.
 */
@OptIn(ExperimentalForeignApi::class)
fun readFileBytesOrNull(path: String, limit: Int = Int.MAX_VALUE): ByteArray? {
    val fp = fopen(path, "rb") ?: return null
    try {
        // /dev/urandom is not seekable to a size; read a fixed [limit] there. For a regular file, size
        // via seek. We branch on whether ftell after SEEK_END gives a positive size.
        fseek(fp, 0, SEEK_END)
        val size = ftell(fp)
        fseek(fp, 0, SEEK_SET)
        val want = when {
            size > 0L -> minOf(size, limit.toLong()).toInt()
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

/**
 * Write ALL of [bytes] to [path], checking every step (short write, `fflush`, `fsync`, `fclose`). On any
 * failure the partial file is unlinked and an error is thrown — the caller ([writePrivateFile]) only
 * `rename`s a fully-written, fsynced temp over the real target, so a valid secret is never replaced by a
 * partial one.
 */
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

/** `strerror` for [code], or a numeric fallback — for the private-file writer's error messages. */
@OptIn(ExperimentalForeignApi::class)
private fun errnoText(code: Int): String = strerror(code)?.toKString() ?: "errno=$code"
