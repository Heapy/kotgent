package io.kotgent.transport

import io.kotgent.core.NOTIFICATION_WINDOW_MILLIS
import io.kotgent.core.Notification
import io.kotgent.core.SessionAttentionNotification
import io.kotgent.store.EventStore
import io.kotgent.store.NotificationStore
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

fun Route.notificationRoutes(
    events: EventStore,
    inbox: NotificationStore? = null,
    json: Json = TRANSPORT_JSON,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    get("/notifications") {
        val notifications = buildList<Notification> {
            addAll(inbox?.recent(now() - NOTIFICATION_WINDOW_MILLIS).orEmpty())
            for (session in events.listSessions()) {
                if (session.state.needsAttention && !session.archived) {
                    add(SessionAttentionNotification(
                        id = "session.attention:${session.id.value}",
                        createdAt = session.updatedAt,
                        sessionId = session.id.value,
                        sessionName = session.name.ifEmpty { session.tmuxSession.ifEmpty { session.id.value } },
                    ))
                }
            }
        }.sortedWith(compareByDescending<Notification> { it.createdAt }.thenBy { it.id })
        call.respondText(
            json.encodeToString(ListSerializer(Notification.serializer()), notifications),
            ContentType.Application.Json,
        )
    }
}
