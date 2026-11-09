package com.codeair.effectcraft

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.codeair.effectcraft.data.DraftProjectStore
import com.codeair.effectcraft.push.FirebaseInit
import com.codeair.effectcraft.ui.EffectCraftApp
import com.codeair.effectcraft.ui.theme.EffectCraftTheme

class MainActivity : ComponentActivity() {

    // Fire-and-forget: a denied notification permission never blocks the
    // editor; FCM data delivery is unaffected, only display is gated by OS.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safe without google-services.json: returns false and nothing
        // Firebase-related is touched when no config exists.
        FirebaseInit.ensureInitialized(applicationContext)
        requestNotificationPermissionIfNeeded()

        // Android 15+ enforces edge-to-edge: the old window colour calls
        // were ignored and the app drew under the status bar. The editor
        // reserves the system bars with WindowInsets padding instead, and
        // the dark theme keeps light status-bar icons.
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val projectStore = DraftProjectStore(applicationContext)
        setContent {
            EffectCraftTheme {
                EffectCraftApp(projectStore = projectStore)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
