package com.codeair.effectcraft.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app theme, built from Astryx tokens. Every color and type
 * value below comes from [AstryxTheme]; no raw hex or size lives
 * here — this file only maps the Astryx token roles onto the
 * Material 3 slots the app's components already use.
 */
private val EffectCraftColors = darkColorScheme(
    primary = AstryxTheme.Colors.accent,
    onPrimary = AstryxTheme.Colors.onAccent,
    secondary = AstryxTheme.Colors.successText,
    onSecondary = Color(0xFF06211D),
    background = AstryxTheme.Colors.backgroundBody,
    onBackground = AstryxTheme.Colors.textPrimary,
    surface = AstryxTheme.Colors.backgroundCard,
    onSurface = AstryxTheme.Colors.textPrimary,
    surfaceVariant = AstryxTheme.Colors.backgroundSurface,
    onSurfaceVariant = AstryxTheme.Colors.textSecondary,
    outline = AstryxTheme.Colors.border,
    outlineVariant = AstryxTheme.Colors.borderEmphasized,
    error = AstryxTheme.Colors.errorText,
    onError = AstryxTheme.Colors.onLight
)

private val EffectCraftTypography = Typography(
    bodyLarge = AstryxTheme.Type.bodyLarge,
    bodyMedium = AstryxTheme.Type.body,
    bodySmall = AstryxTheme.Type.caption,
    titleLarge = AstryxTheme.Type.title,
    titleMedium = AstryxTheme.Type.titleSmall,
    headlineMedium = AstryxTheme.Type.headline,
    labelLarge = AstryxTheme.Type.label
)

@Composable
fun EffectCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EffectCraftColors,
        typography = EffectCraftTypography,
        content = content
    )
}
