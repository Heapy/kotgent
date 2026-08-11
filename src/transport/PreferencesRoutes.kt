package io.kotgent.transport

import io.kotgent.store.PreferencesStore
import io.kotgent.store.UiPreferences
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class SavePreferencesRequest(
    val basePath: String,
    val groupingLevel: Int,
)

@Serializable
data class PreferencesDto(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
)

@Serializable
@SerialName("preferences_update")
data class PreferencesUpdateDto(
    val basePath: String,
    val groupingLevel: Int,
    val revision: Long,
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

fun UiPreferences.toDto(): PreferencesDto = PreferencesDto(basePath, groupingLevel, revision)

fun UiPreferences.toUpdateDto(): PreferencesUpdateDto =
    PreferencesUpdateDto(basePath = basePath, groupingLevel = groupingLevel, revision = revision)

// Keep this normalization compatible with the Web UI's path grouping implementation.
fun normalizePreferencePath(path: String): String {
    val normalized = path.trim().replace(REPEATED_PATH_SLASHES, "/")
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}

private val REPEATED_PATH_SLASHES = Regex("/{2,}")
private const val MIN_GROUPING_LEVEL: Int = 0
const val MAX_GROUPING_LEVEL: Int = 4
