package com.codeair.effectcraft.ui

import android.content.Context

/**
 * The one place the first-run onboarding flag lives: app-private
 * SharedPreferences, same pattern as the push token store.
 *
 * The welcome dialog shows on first launch only; dismissing it sets
 * the flag, so it never shows again on this device. [reset] exists
 * for instrumented tests that need a fresh first launch.
 */
internal object OnboardingStore {

    private const val PREFS_NAME = "effectcraft_onboarding"
    private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

    fun isSeen(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_SEEN, false)
    }.getOrDefault(false)

    fun markSeen(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING_SEEN, true)
                .apply()
        }
    }

    fun reset(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ONBOARDING_SEEN)
                .apply()
        }
    }
}
