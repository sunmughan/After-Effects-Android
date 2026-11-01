package ai.effectcraft.android

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.ui.EffectCraftApp
import ai.effectcraft.android.ui.theme.EffectCraftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = AndroidColor.rgb(10, 12, 17)
        window.navigationBarColor = AndroidColor.rgb(10, 12, 17)
        window.decorView.systemUiVisibility = 0

        val projectStore = DraftProjectStore(applicationContext)
        setContent {
            EffectCraftTheme {
                EffectCraftApp(projectStore = projectStore)
            }
        }
    }
}
