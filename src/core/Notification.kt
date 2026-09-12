package io.kotgent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val NOTIFICATION_WINDOW_MILLIS: Long = 3_600_000
const val NOTIFICATION_RETENTION_MILLIS: Long = 86_400_000

@Serializable
sealed interface Notification {
    val id: String
    val createdAt: Long
}

@Serializable
@SerialName("usage.reset")
data class UsageResetNotification(
    override val id: String,
    override val createdAt: Long,
    val provider: String,
    val windowKey: String,
    val expectedAt: Long?,
    val usedBefore: Double,
    val usedBeforeSeenAt: Long,
    val windowSeconds: Long? = null,
) : Notification

@Serializable
@SerialName("session.attention")
data class SessionAttentionNotification(
    override val id: String,
    override val createdAt: Long,
    val sessionId: String,
    val sessionName: String,
) : Notification
