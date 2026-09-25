package io.kotgent.transport

import io.kotgent.host.pathLengthLimit
import io.kotgent.store.PreferencesStore
import io.kotgent.store.UiPreferences
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class SavePreferencesRequest(
    val basePath: String,
    val groupingLevel: Int,
)

@Serializable
data class SetFolderAdhdRequest(
    val path: String,
    val adhd: Boolean,
)

@Serializable
data class PreferencesDto(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
    val adhdPaths: List<String> = emptyList(),
)

@Serializable
@SerialName("preferences_update")
data class PreferencesUpdateDto(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
    val adhdPaths: List<String> = emptyList(),
) : EventsFrame()

fun Route.preferencesRoutes(
    store: PreferencesStore,
    json: Json = TRANSPORT_JSON,
) {
    get("/preferences") {
        call.respondText(
            json.encodeToString(PreferencesDto.serializer(), store.preferences.value.toDto()),
            ContentType.Application.Json,
        )
    }

    put("/preferences") {
        val request = decodeSavePreferencesRequest(call.receiveText(), json)
        if (request == null) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@put
        }

        val basePath = normalizePreferencePath(request.basePath)
        if (basePath.isNotEmpty() && !basePath.startsWith("/")) {
            call.respondText("basePath must be empty or absolute", status = HttpStatusCode.BadRequest)
            return@put
        }
        if (!isPlausibleFolderPath(basePath)) {
            call.respondText("basePath must be $FOLDER_PATH_RULE", status = HttpStatusCode.BadRequest)
            return@put
        }
        if (request.groupingLevel !in MIN_GROUPING_LEVEL..MAX_GROUPING_LEVEL) {
            call.respondText(
                "groupingLevel must be between $MIN_GROUPING_LEVEL and $MAX_GROUPING_LEVEL",
                status = HttpStatusCode.BadRequest,
            )
            return@put
        }

        val saved = store.savePreferences(basePath, request.groupingLevel)
        call.respondText(
            json.encodeToString(PreferencesDto.serializer(), saved.toDto()),
            ContentType.Application.Json,
        )
    }

    // One path per call: a whole-list PUT would let two devices overwrite each other's edits.
    post("/preferences/adhd-paths") {
        val request = decodeSetFolderAdhdRequest(call.receiveText(), json)
        if (request == null) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }

        val path = normalizePreferencePath(request.path)
        if (path.isEmpty() || !path.startsWith("/")) {
            call.respondText("path must be absolute", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (!isPlausibleFolderPath(path)) {
            call.respondText("path must be $FOLDER_PATH_RULE", status = HttpStatusCode.BadRequest)
            return@post
        }

        val saved = store.setFolderAdhd(path, request.adhd)
        call.respondText(
            json.encodeToString(PreferencesDto.serializer(), saved.toDto()),
            ContentType.Application.Json,
        )
    }
}

private fun decodeSavePreferencesRequest(text: String, json: Json): SavePreferencesRequest? =
    // kotlinx.serialization accepts quoted numbers for Int; this wire contract requires exact JSON types.
    runCatching {
        val body = json.parseToJsonElement(text).jsonObject
        val basePath = body["basePath"] as? JsonPrimitive
        val groupingLevel = body["groupingLevel"] as? JsonPrimitive
        if (basePath?.isString != true || groupingLevel == null || groupingLevel.isString) return null
        SavePreferencesRequest(
            basePath = basePath.content,
            groupingLevel = groupingLevel.intOrNull ?: return null,
        )
    }.getOrNull()

private fun decodeSetFolderAdhdRequest(text: String, json: Json): SetFolderAdhdRequest? =
    // kotlinx.serialization reads a quoted "true" as a Boolean; this wire contract requires exact JSON types.
    runCatching {
        val body = json.parseToJsonElement(text).jsonObject
        val path = body["path"] as? JsonPrimitive
        val adhd = body["adhd"] as? JsonPrimitive
        if (path?.isString != true || adhd == null || adhd.isString) return null
        SetFolderAdhdRequest(path = path.content, adhd = adhd.booleanOrNull ?: return null)
    }.getOrNull()

fun UiPreferences.toDto(): PreferencesDto = PreferencesDto(basePath, groupingLevel, revision, adhdPaths)

fun UiPreferences.toUpdateDto(): PreferencesUpdateDto =
    PreferencesUpdateDto(
        basePath = basePath,
        groupingLevel = groupingLevel,
        revision = revision,
        adhdPaths = adhdPaths,
    )

// Keep this normalization compatible with the Web UI's path grouping implementation.
fun normalizePreferencePath(path: String): String {
    val normalized = path.trim().replace(REPEATED_PATH_SLASHES, "/")
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}

private val REPEATED_PATH_SLASHES = Regex("/{2,}")

// Control characters are legal in POSIX names but break every place that shows the path.
private fun isPlausibleFolderPath(path: String): Boolean =
    HOST_PATH_LIMIT.admits(path) && path.none { it.isISOControl() }

private val HOST_PATH_LIMIT = pathLengthLimit()
private val FOLDER_PATH_RULE = "at most ${HOST_PATH_LIMIT.max} ${HOST_PATH_LIMIT.unit.label} without control characters"

private const val MIN_GROUPING_LEVEL: Int = 0
const val MAX_GROUPING_LEVEL: Int = 4
