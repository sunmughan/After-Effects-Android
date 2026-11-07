package com.codeair.effectcraft.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Guards every Firebase touch so the app runs fully offline without
 * google-services.json (which does not exist yet: the app is registered
 * in Firebase later and a final rebuild accepts the file).
 *
 * Without the config file, [FirebaseApp.initializeApp] returns null and
 * this returns false; no Firebase API is then called, so nothing can
 * throw an "uninitialized FirebaseApp" crash. Returns true only when a
 * default FirebaseApp actually exists, in which case FCM auto-init is
 * switched on (the manifest keeps it off by default) and the current
 * token is fetched once and stored (see [fetchAndStoreToken]).
 * Both paths log at info level so logcat shows plainly whether push is
 * on or off; the unconfigured path is expected, not an error.
 */
object FirebaseInit {

    private const val TAG = "EffectCraftFirebase"

    fun ensureInitialized(context: Context): Boolean = runCatching {
        if (FirebaseApp.getApps(context).isEmpty()) {
            if (FirebaseApp.initializeApp(context) == null) {
                Log.i(
                    TAG,
                    "Firebase not configured (no google-services.json): " +
                        "push stays off, editor runs fully offline.",
                )
                return false
            }
        }
        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = true }
        Log.i(TAG, "Firebase configured: FCM auto-init enabled.")
        fetchAndStoreToken(context)
        true
    }.getOrDefault(false)

    /**
     * Explicit first token retrieval. FCM also delivers a token through
     * `onNewToken` on refresh, but without this fetch a freshly
     * registered install may hold no stored token until the first
     * refresh fires. Only called once Firebase is known to be
     * configured. Silent on failure: the token is re-fetched on the
     * next launch and on every refresh, so a failed fetch loses nothing.
     */
    private fun fetchAndStoreToken(context: Context) {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> FcmTokenStore.save(context, token) }
                .addOnFailureListener { error ->
                    Log.d(
                        TAG,
                        "FCM token fetch failed (retried on next launch): ${error.message}",
                    )
                }
        }
    }
}
