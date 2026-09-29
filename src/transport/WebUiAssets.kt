package io.kotgent.transport

const val HASHED_ASSETS_DIR: String = "assets/"

const val IMMUTABLE_CACHE_CONTROL: String = "max-age=31536000, immutable"

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
