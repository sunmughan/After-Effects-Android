package com.codeair.effectcraft.push

import android.content.Context

/**
 * The one place the FCM token lives: app-private SharedPreferences.
 *
 * Both the token-refresh callback ([EffectCraftMessagingService.onNewToken])
 * and the explicit fetch in [FirebaseInit] write through here, so the
 * stored token is the same whichever path produced it. Nothing outside
 * the push package reads it yet; a later send path can use [load].
 */
internal object FcmTokenStore {

    private const val PREFS_NAME = "effectcraft_push"
    private const val KEY_FCM_TOKEN = "fcm_token"

    fun save(context: Context, token: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FCM_TOKEN, token)
                .apply()
        }
    }

    fun load(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)
    }.getOrNull()
}
