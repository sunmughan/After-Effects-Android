package com.codeair.effectcraft.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.codeair.effectcraft.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point. Token refreshes are kept in app-private storage so a
 * later send path can use them; every received message posts a visible
 * notification through [PushMessageMapper] — there is no code path that
 * receives a message and shows nothing.
 *
 * The service is only ever invoked by Firebase when the app is actually
 * registered (google-services.json present); without that config the app
 * never receives messages and this service is never started by FCM.
 */
class EffectCraftMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Refresh path: the stored token is overwritten in place, so the
        // latest token is always the one a later send path reads.
        FcmTokenStore.save(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = PushMessageMapper.map(
            title = message.notification?.title,
            body = message.notification?.body,
            data = message.data,
            collapseKey = message.collapseKey,
        )
        postNotification(notification)
    }

    private fun postNotification(notification: PushNotification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PushMessageMapper.CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built = NotificationCompat.Builder(this, PushMessageMapper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(notification.notificationId, built)
    }

    companion object {
        private const val CHANNEL_NAME = "EffectCraft updates"
    }
}
