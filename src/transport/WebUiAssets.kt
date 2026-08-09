package io.kotgent.transport

import io.kotgent.crypto.hex
import io.kotgent.crypto.sha256
import io.kotgent.daemon.isDirectory
import io.kotgent.daemon.listDir

/*
 * Automatic cache invalidation for the Web UI.
 *
 * The SPA used to carry a HAND-BUMPED `?v=<token>` on `style.css`, `app.js` and one component, which was
 * both a chore and INCOMPLETE: the other ~30 files (all of `lib/`, the remaining components, `vendor/`)
 * were imported with no version at all, so an edit to `lib/api.js` could keep being served from a
 * browser's cache — its URL had not changed.
 *
 * The replacement is a single content revision of the WHOLE tree, carried in a PATH PREFIX rather than a
 * per-file query:
 *
 *     /_v/<rev>/app.js        /_v/<rev>/style.css        /_v/<rev>/vendor/preact.module.js
 *
 * The prefix — not a query — is what makes this free. An ES module specifier resolves against the URL of
 * the IMPORTING MODULE, not the document, so substituting the prefix once in `index.html` propagates it
 * through the entire import graph without rewriting a single line of JavaScript:
 *
 *     /_v/<rev>/app.js
 *       import "./lib/api.js"                 -> /_v/<rev>/lib/api.js
 *       import "./components/TerminalPane.js" -> /_v/<rev>/components/TerminalPane.js
 *         import "../lib/sessions.js"         -> /_v/<rev>/lib/sessions.js
 *
 * That yields the guarantee at the level of the URL: a browser cannot serve old bytes from an address it
 * has never seen. It does not depend on how Safari chooses to honour `Cache-Control` on an asset — only
 * on `index.html` (already `no-cache`) being re-read, which the old hand-bumped `?v=` already proved
 * happens, since otherwise a bumped token would never have reached the device either.
 *
 * The one thing the prefix does NOT reach is the `importmap` in `index.html`: its targets resolve against
 * the DOCUMENT, so they carry the prefix explicitly. `sw.js`, the manifest and the icons deliberately stay
 * on stable unprefixed URLs — the worker's root scope depends on its path, and an installed PWA refers to
 * the other two by a fixed address.
 */

/** The literal `index.html` carries where the revision goes; [webUiRevision] replaces it at serve time. */
const val WEBUI_REV_PLACEHOLDER: String = "__REV__"

/** First path segment of a revisioned asset URL: `/_v/<rev>/<path>`. */
const val WEBUI_REV_PREFIX: String = "_v"

/** How much of the hex digest the revision keeps — 48 bits, far past collision risk for one directory. */
const val WEBUI_REV_LENGTH: Int = 12

/**
 * Symlink loops are the one way a directory walk never terminates, and this one runs on a page load. The
 * real tree is three levels deep (`vendor/`, `lib/`, `components/`, `icons/`), so the cap costs nothing.
 */
private const val MAX_WALK_DEPTH: Int = 8

/** `Cache-Control` for an asset whose URL carries a valid revision — its content can never change. */
const val IMMUTABLE_CACHE_CONTROL: String = "max-age=31536000, immutable"

/**
 * A content revision of every file under [dir] — a hash of the list of per-file hashes.
 *
 * 1. walk [dir] recursively and collect every file;
 * 2. take `sha256` of each file's bytes;
 * 3. sort by relative path and join into one text, a `"<relpath> <hex>\n"` line per file;
 * 4. take `sha256` of that text and keep the first [WEBUI_REV_LENGTH] hex characters.
 *
 * Hashing per file rather than over one concatenation of every byte holds a single file in memory instead
 * of the whole tree, and puts the PATH into the result — so a rename or a move changes the revision too,
 * which a plain content concatenation would miss. The sort removes any dependence on the order `readdir`
 * happens to return, so the same set of files always yields the same revision.
 *
 * Recomputed on every `index.html` request (the tree is ~910 KB / 36 files, so tens of milliseconds per
 * page load) rather than memoized: a file edited while the daemon runs is then visible on the next reload,
 * with no cache to invalidate. Falls back to hashing `index.html` alone if the walk finds nothing, so the
 * answer is always a valid token — [isRevToken] is what stops a broken one from pinning an asset forever.
 */
fun webUiRevision(dir: String): String {
    val lines = ArrayList<String>()
    collectFileDigests(dir, rel = "", depth = 0, out = lines)
    if (lines.isEmpty()) {
        return hex(sha256(readFileBytesOrNull("$dir/index.html") ?: ByteArray(0))).take(WEBUI_REV_LENGTH)
    }
    lines.sort()
    return hex(sha256(lines.joinToString(separator = "").encodeToByteArray())).take(WEBUI_REV_LENGTH)
}

private fun collectFileDigests(root: String, rel: String, depth: Int, out: MutableList<String>) {
    if (depth > MAX_WALK_DEPTH) return
    val dir = if (rel.isEmpty()) root else "$root/$rel"
    for (name in listDir(dir)) {
        val childRel = if (rel.isEmpty()) name else "$rel/$name"
        val childAbs = "$root/$childRel"
        if (isDirectory(childAbs)) {
            collectFileDigests(root, childRel, depth + 1, out)
        } else {
            val bytes = readFileBytesOrNull(childAbs) ?: continue
            out.add("$childRel ${hex(sha256(bytes))}\n")
        }
    }
}

/**
 * Split a request-relative path into its revision and the file path underneath it: `_v/7c41f9ab30d2/app.js`
 * becomes `"7c41f9ab30d2" to "app.js"`. Anything that is not a well-formed `_v/<rev>/<non-empty path>` is
 * returned unchanged with a `null` revision, so an ordinary `app.js` still resolves.
 *
 * The revision itself is deliberately NOT checked against the current one — the prefix is only stripped.
 * A client can hold an old revision's URL only from an old `index.html`, which it cannot have (the shell
 * is `no-cache`), and answering `404` would break the one real race: a shell fetched just before a daemon
 * update asking for its assets just after it. The worst outcome is one cache entry the client never asks
 * for again.
 */
fun stripRevPrefix(rel: String): Pair<String?, String> {
    val marker = "$WEBUI_REV_PREFIX/"
    if (!rel.startsWith(marker)) return null to rel
    val afterPrefix = rel.substring(marker.length)
    val slash = afterPrefix.indexOf('/')
    if (slash <= 0 || slash == afterPrefix.length - 1) return null to rel
    return afterPrefix.substring(0, slash) to afterPrefix.substring(slash + 1)
}

/**
 * Whether [value] is a revision this server minted, i.e. exactly what [webUiRevision] produces.
 *
 * This is the guard on the one genuinely dangerous failure mode of the whole scheme: if the placeholder
 * substitution ever failed, the browser would request `/_v/__REV__/app.js` — a URL that never changes —
 * and an unconditional `immutable` would pin that file in every cache forever. Requiring a real hex token
 * makes such a URL revalidate instead, so a broken substitution degrades to today's behaviour rather than
 * to a permanent stale app.
 */
fun isRevToken(value: String): Boolean =
    value.length == WEBUI_REV_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * Whether [rel] is a History-API route the SPA owns, and therefore a request that must be answered with
 * the shell instead of `404` when no such file exists.
 *
 * The grammar is `/`, `/tasks`, `/tasks/{ref}` and `/s/{id}` — and it is an **exact segment grammar, not
 * a prefix match**. A prefix match would serve a `200` shell for `/s/id/extra` and for a mistyped asset
 * path like `/tasks/id/missing.js`, which makes the promise "a wrong asset path 404s" false and turns
 * every such typo into a page that loads and then does nothing. There is deliberately no arm for the
 * empty path: `staticWebUi` already substitutes `index.html` for it before this is ever consulted.
 *
 * Matched against the ORIGINAL request-relative path, not the rev-stripped one, because `stripRevPrefix`
 * runs first in `serveStaticFile` — a UI route never carries a `/_v/<rev>/` prefix, so a stripped path
 * that suddenly looks like one is not a route request.
 *
 * Returns `false` here on purpose: Task 17 of the task-backlog plan implements the grammar. Until then
 * static serving behaves exactly as before, which is what keeps the suite green with stubs.
 */
fun isSpaRoute(rel: String): Boolean {
    // Task 17.
    return false
}

/**
 * The two files that must revalidate no matter how they were requested. Both are entry points whose URL is
 * fixed: `index.html` is the shell that carries the revision itself (caching it would pin every asset URL
 * with it), and `sw.js` must stay at the root for its scope to cover the app. Neither is ever referenced
 * through the prefix, so this only defends against a hand-typed `/_v/<rev>/index.html`.
 */
fun neverImmutable(path: String): Boolean = path == "index.html" || path == "sw.js"
