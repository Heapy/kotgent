package io.kotgent.transport

import io.kotgent.crypto.hex
import io.kotgent.crypto.sha256
import io.kotgent.daemon.isDirectory
import io.kotgent.daemon.listDir


const val WEBUI_REV_PLACEHOLDER: String = "__REV__"

const val WEBUI_REV_PREFIX: String = "_v"

const val WEBUI_REV_LENGTH: Int = 12

private const val MAX_WALK_DEPTH: Int = 8

const val IMMUTABLE_CACHE_CONTROL: String = "max-age=31536000, immutable"

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
    // Bound traversal against symlink cycles; the shipped asset tree is much shallower.
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

fun stripRevPrefix(rel: String): Pair<String?, String> {
    // Old revisions remain servable across a shell/assets update race; only caching validates token shape.
    val marker = "$WEBUI_REV_PREFIX/"
    if (!rel.startsWith(marker)) return null to rel
    val afterPrefix = rel.substring(marker.length)
    val slash = afterPrefix.indexOf('/')
    if (slash <= 0 || slash == afterPrefix.length - 1) return null to rel
    return afterPrefix.substring(0, slash) to afterPrefix.substring(slash + 1)
}

// Prevent a failed `__REV__` substitution from making one permanently immutable stale URL.
fun isRevToken(value: String): Boolean =
    value.length == WEBUI_REV_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

fun isSpaRoute(rel: String): Boolean {
    // Exact segment grammar keeps mistyped/deep asset paths as 404s instead of silent SPA shells.
    val segments = rel.split('/')
    if (segments.any { it.isEmpty() }) return false
    return when (segments.size) {
        1 -> segments[0] == SPA_TASKS_SEGMENT
        2 -> segments[0] == SPA_TASKS_SEGMENT || segments[0] == SPA_SESSION_SEGMENT
        else -> false
    }
}

private const val SPA_TASKS_SEGMENT: String = "tasks"

private const val SPA_SESSION_SEGMENT: String = "s"

// Both fixed entry-point URLs must revalidate; the worker also needs its root path for scope.
fun neverImmutable(path: String): Boolean = path == "index.html" || path == "sw.js"
