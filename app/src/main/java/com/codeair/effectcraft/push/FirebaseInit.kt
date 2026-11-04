package com.codeair.effectcraft.push

import android.content.Context
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
 * switched on (the manifest keeps it off by default).
 */
object FirebaseInit {

    fun ensureInitialized(context: Context): Boolean = runCatching {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context) ?: return false
        }
        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = true }
        true
    }.getOrDefault(false)
}
