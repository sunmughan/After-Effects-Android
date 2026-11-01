package ai.effectcraft.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EffectCraftColors = darkColorScheme(
    primary = Color(0xFFB6A3FF),
    onPrimary = Color(0xFF1B1534),
    secondary = Color(0xFF67D7C5),
    onSecondary = Color(0xFF06211D),
    background = Color(0xFF0A0C11),
    onBackground = Color(0xFFE9EAF2),
    surface = Color(0xFF131720),
    onSurface = Color(0xFFE9EAF2),
    surfaceVariant = Color(0xFF202633),
    onSurfaceVariant = Color(0xFFADB4C4),
    outline = Color(0xFF343C4B)
)

@Composable
fun EffectCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EffectCraftColors,
        content = content
    )
}
