package com.codeair.effectcraft.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Astryx design tokens, adapted to Compose.
 *
 * Source: Meta's Astryx design system (github.com/meta/astryx,
 * docs astryx.atmeta.com, MIT). Astryx ships React 19 + StyleX for
 * the web; this file is the token layer only — no React component
 * is ported. Every value below is either a token read from the
 * published Astryx reference (astryx.atmeta.com/docs/color,
 * /docs/tokens, /docs/theme) or a documented EffectCraft theme
 * override, marked as such.
 *
 * Principles applied (from Astryx):
 * - Semantic tokens only: components reference a token by purpose
 *   (background-body, text-secondary, accent), never a raw hex or
 *   pixel value. The raw values live here and nowhere else.
 * - Layered surface hierarchy: body -> surface -> card -> popover.
 *   Each level sits visually above the previous one.
 * - Light/dark tuples: every color token carries a [light, dark]
 *   pair. EffectCraft ships dark only (Instagram Edits is dark),
 *   so the dark value of each pair is used.
 * - Themes control what things look like, core controls what
 *   things are: the EffectCraft theme is a set of token overrides
 *   on the Astryx neutral theme, not a fork.
 * - Accessibility is built in: 44 dp minimum pointer target,
 *   text tokens chosen for contrast on the body surface.
 * - Motion is a token too: durations and easings are named, and
 *   reduced motion is respected from the system setting.
 */
object AstryxTheme {

    /** Spacing: 4 px base unit, steps 0-9 as published (0/2/4/6/8/12/16/24/32/48). */
    object Spacing {
        val space0: Dp = 0.dp
        val space1: Dp = 2.dp
        val space2: Dp = 4.dp
        val space3: Dp = 6.dp
        val space4: Dp = 8.dp
        val space5: Dp = 12.dp
        val space6: Dp = 16.dp
        val space7: Dp = 24.dp
        val space8: Dp = 32.dp
        val space9: Dp = 48.dp
    }

    /** Radii: none / inner / element / container / chat / page / full. */
    object Radius {
        val none: Dp = 0.dp
        val inner: Dp = 8.dp
        val element: Dp = 12.dp
        val container: Dp = 16.dp
        val chat: Dp = 28.dp
        val page: Dp = 32.dp
        val full: Dp = 9999.dp
    }

    /** Motion durations (ms) and easings, from the Astryx motion tokens. */
    object Motion {
        const val durationInstant: Int = 0
        const val durationFast: Int = 100
        const val durationNormal: Int = 200
        const val durationSlow: Int = 300

        val easingDefault: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        val easingEaseIn: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
        val easingEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        val easingSpring: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

        /**
         * A tween from the motion tokens. When the user asked the
         * system to remove animations, the duration collapses to
         * instant — Astryx's reducedMotion: 'respect'.
         */
        fun <T> tweenFor(durationMs: Int, easing: Easing, reducedMotion: Boolean): AnimationSpec<T> =
            tween(durationMillis = if (reducedMotion) durationInstant else durationMs, easing = easing)
    }

    /**
     * Typography: geometric scale, base 14 px, ratio 1.2 — so sizes
     * are round(14 x 1.2^step): 12 / 14 / 17 / 20 / 24 / 29 / 35.
     * Line heights are tiered (1.5 body, 1.4 subhead, 1.25 heading)
     * and snapped to the 4 px grid.
     */
    object Type {
        val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
        val body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        val bodyLarge: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
        val label: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        val titleSmall: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        val title: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        val headline: TextStyle = TextStyle(fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
        val display: TextStyle = TextStyle(fontSize = 35.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    }

    /** Control heights (sm / md / lg) and the 44 dp minimum pointer target. */
    object Control {
        val heightSm: Dp = 28.dp
        val heightMd: Dp = 32.dp
        val heightLg: Dp = 36.dp
        val minTouchTarget: Dp = 44.dp
    }

    /** Elevation: none / low / med / high (shadow dp; color is [Colors.shadow]). */
    object Elevation {
        val none: Dp = 0.dp
        val low: Dp = 2.dp
        val med: Dp = 6.dp
        val high: Dp = 12.dp
    }

    /**
     * Dark-mode color tokens. Each value is the dark half of the
     * Astryx [light, dark] tuple from the published color reference,
     * unless marked as an EffectCraft theme override.
     */
    object Colors {
        // Surfaces: body -> surface -> card -> popover.
        // EFFECTCRAFT THEME OVERRIDE: body is pure black (#000000)
        // instead of Astryx neutral dark #1B1B1B, for parity with
        // the Instagram Edits product surface. Astryx themes are
        // token overrides by design, so this is the system working
        // as intended, not a deviation from it.
        val backgroundBody: Color = Color(0xFF000000)
        val backgroundSurface: Color = Color(0xFF262626)
        val backgroundCard: Color = Color(0xFF1B1B1B)
        // EFFECTCRAFT THEME OVERRIDE: popover sits one step above
        // surface, following the hierarchy rule (each level above
        // the previous); Astryx publishes no separate popover hex.
        val backgroundPopover: Color = Color(0xFF2E2E2E)
        val backgroundInverted: Color = Color(0xFFFFFFFF)

        // Text.
        val textPrimary: Color = Color(0xFFFFFFFF)
        val textSecondary: Color = Color(0xFF9E9E9E)
        val textDisabled: Color = Color(0xFF525252)
        val onDark: Color = Color(0xFFFFFFFF)
        val onLight: Color = Color(0xFF111111)

        // Accent: in dark mode the neutral accent is near-white,
        // which is exactly the Instagram Edits action color.
        val accent: Color = Color(0xFFF1F1F1)
        val onAccent: Color = Color(0xFF111111)

        // Borders, overlays, track, shadow.
        val border: Color = Color(0x1AFFFFFF)
        val borderEmphasized: Color = Color(0xFF525252)
        val overlay: Color = Color(0xCC000000)
        val overlayHover: Color = Color(0x0DFFFFFF)
        val overlayPressed: Color = Color(0x1AFFFFFF)
        val track: Color = Color(0xFF5A5E66)
        val shadow: Color = Color(0x4D000000)

        // Status roles, from the published tokens. Only the info
        // and warning roles ship a background token in the
        // reference; success and error are carried by text + border
        // on the surface, which the reference also supports.
        val infoText: Color = Color(0xFFA1CAFF)
        val infoBorder: Color = Color(0xFF5AA0F8)
        val infoBackground: Color = Color(0xFF253C5A)
        val successText: Color = Color(0xFF90D7C8)
        val successBorder: Color = Color(0xFF4FB1A0)
        val warningText: Color = Color(0xFFC29900)
        val warningBorder: Color = Color(0xFFC29900)
        val warningBackground: Color = Color(0xFF453A1C)
        val errorText: Color = Color(0xFFFA6762)
        val errorBorder: Color = Color(0xFFFA6762)
    }
}

/**
 * Reads the system's "remove animations" setting
 * (ANIMATOR_DURATION_SCALE == 0). Astryx motion tokens respect
 * reduced motion; this is the Compose read of that preference.
 */
@Composable
fun rememberAstryxReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}
