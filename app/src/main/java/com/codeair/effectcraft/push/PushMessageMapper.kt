package com.codeair.effectcraft.push

/**
 * Pure mapping from an FCM message to the notification the user sees.
 *
 * Kept free of Android framework types so it is unit-testable on the JVM.
 * It never returns "no notification": a message with no title, no body and
 * no data still maps to a fixed fallback, so nothing is silently dropped.
 */
data class PushNotification(
    val title: String,
    val body: String,
    val tag: String?,
    val notificationId: Int,
)

object PushMessageMapper {

    const val CHANNEL_ID = "effectcraft_updates"
    const val FALLBACK_TITLE = "EffectCraft"
    const val FALLBACK_BODY = "You have a new update."

    fun map(
        title: String?,
        body: String?,
        data: Map<String, String>,
        collapseKey: String?,
    ): PushNotification {
        val resolvedTitle = title
            .takeIf { !it.isNullOrBlank() }
            ?: data["title"].takeIf { !it.isNullOrBlank() }
            ?: FALLBACK_TITLE
        val resolvedBody = body
            .takeIf { !it.isNullOrBlank() }
            ?: data["body"].takeIf { !it.isNullOrBlank() }
            ?: FALLBACK_BODY
        val tag = data["tag"].takeIf { !it.isNullOrBlank() }
            ?: collapseKey.takeIf { !it.isNullOrBlank() }
        val key = tag ?: "$resolvedTitle\n$resolvedBody"
        val id = key.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return PushNotification(resolvedTitle, resolvedBody, tag, id)
    }
}
