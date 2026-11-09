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
 * Source: the installed Astryx repo at ~/workspace/astryx/
 * (commit 2e6162c9ac8516aa85003d8c2a6714ad8979fe1e, MIT) — every
 * value below was read from its sources, not from web pages:
 * - Color/spacing/radius/size/motion/type scales:
 *   packages/core/src/theme/tokens.stylex.ts
 * - The neutral theme's overrides + palettes:
 *   packages/cli/assets/templates/themes/neutral/neutralTheme.ts
 *   and neutralPalettes.generated.ts
 * - Type leading rule:
 *   packages/core/src/theme/expandTypeScale.ts
 *
 * Astryx ships React 19 + StyleX for the web; this file is the
 * token layer only — no React component is ported. The app uses
 * the neutral theme (the theme the Astryx demo ships), in dark
 * mode only.
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

    /**
     * Spacing: 4 px base unit — step N is 4 x N px, with half
     * steps at 0.5 (2 px) and 1.5 (6 px). Names match the token
     * names (--spacing-0, --spacing-0-5, --spacing-1, ...).
     */
    object Spacing {
        val space0: Dp = 0.dp
        val space05: Dp = 2.dp
        val space1: Dp = 4.dp
        val space15: Dp = 6.dp
        val space2: Dp = 8.dp
        val space3: Dp = 12.dp
        val space4: Dp = 16.dp
        val space5: Dp = 20.dp
        val space6: Dp = 24.dp
        val space7: Dp = 28.dp
        val space8: Dp = 32.dp
        val space9: Dp = 36.dp
        val space10: Dp = 40.dp
        val space11: Dp = 44.dp
        val space12: Dp = 48.dp
    }

    /** Radii: none / inner / element / container / chat / page / full. */
    object Radius {
        val none: Dp = 0.dp
        val inner: Dp = 4.dp
        val element: Dp = 8.dp
        val container: Dp = 12.dp
        val chat: Dp = 28.dp
        val page: Dp = 28.dp
        val full: Dp = 9999.dp
    }

    /**
     * Motion durations (ms) and easings, from the neutral theme's
     * motion config (fast 125, medium 300, slow 700, ratio 0.75)
     * and the core easing tokens. The core defaults are slower
     * (fast 175, medium 410, slow 975); the neutral theme is
     * snappier, and this app uses the neutral theme.
     */
    object Motion {
        /** Reduced-motion collapse; not a token, the duration used when animations are off. */
        const val durationInstant: Int = 0
        const val durationFastMin: Int = 95
        const val durationFast: Int = 125
        const val durationFastMax: Int = 165
        const val durationMediumMin: Int = 225
        const val durationMedium: Int = 300
        const val durationMediumMax: Int = 400
        const val durationSlow: Int = 700
        /** The standard dismissal duration: the fast band. */
        const val durationExit: Int = durationFast

        /** --ease-standard / --ease-entrance / --ease-move (decelerate). */
        val easingStandard: Easing = CubicBezierEasing(0.24f, 1f, 0.4f, 1f)
        val easingEntrance: Easing = easingStandard
        val easingMove: Easing = easingStandard
        /** --ease-exit (accelerate: things leaving). */
        val easingExit: Easing = CubicBezierEasing(0.3f, 0f, 0.6f, 0.6f)

        /**
         * A tween from the motion tokens. When the user asked the
         * system to remove animations, the duration collapses to
         * instant — Astryx's reducedMotion: 'respect'.
         */
        fun <T> tweenFor(durationMs: Int, easing: Easing, reducedMotion: Boolean): AnimationSpec<T> =
            tween(durationMillis = if (reducedMotion) durationInstant else durationMs, easing = easing)
    }

    /**
     * Typography: the neutral theme's touch + narrow adaptation.
     * Phones (coarse pointer, narrow width) floor the base to
     * 16 px and re-derive the ratio to 1.1736 so the largest
     * display size stays 42 px: sizes are round(16 x 1.1736^step).
     * Line heights follow the type-scale rule (< 20 px -> 1.5,
     * 20–31 px -> 1.4, >= 32 px -> 1.25), snapped to the 4 px
     * grid with a minimum of fontSize + 4.
     *
     * Family: the neutral theme uses Figtree. No font asset is
     * bundled in this app, so these styles carry no family and
     * render in the platform default (Roboto on Android) — the
     * documented fallback, not a silent substitution.
     */
    object Type {
        val caption: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        val body: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
        val bodyLarge: TextStyle = TextStyle(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
        val label: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        val titleSmall: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
        val title: TextStyle = TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
        val headline: TextStyle = TextStyle(fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
        val display: TextStyle = TextStyle(fontSize = 42.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold)
    }

    /** Control heights (sm / md / lg) and the 44 dp minimum pointer target. */
    object Control {
        val heightSm: Dp = 28.dp
        val heightMd: Dp = 32.dp
        val heightLg: Dp = 36.dp
        val minTouchTarget: Dp = 44.dp
    }

    /**
     * Elevation: the real shadow tokens are multi-layer CSS
     * shadows (low: 0 1px 1px + 0 2px 8px; med: 0 1px 2px +
     * 0 2px 12px; high: 0 2px 2px + 0 8px 24px, all at 20–30%
     * black in dark mode). Compose has no multi-layer shadow, so
     * these dp values are the adaptation: same ordering
     * (low < med < high), same shadow color ([Colors.shadow]).
     */
    object Elevation {
        val none: Dp = 0.dp
        val low: Dp = 2.dp
        val med: Dp = 6.dp
        val high: Dp = 12.dp
    }

    /**
     * Dark-mode color tokens from the neutral theme: each value is
     * the dark half of the theme's [light, dark] tuple, resolved
     * through the neutral palette ramp (a pure grayscale: step N
     * is the same hex in light and dark).
     */
    object Colors {
        // Surfaces: body -> surface -> card -> popover.
        // EFFECTCRAFT THEME OVERRIDE: body is pure black (#000000)
        // instead of the neutral theme's dark[10] #1B1B1B, for
        // parity with the Instagram Edits product surface. Astryx
        // themes are token overrides by design, so this is the
        // system working as intended, not a deviation from it.
        val backgroundBody: Color = Color(0xFF000000)
        val backgroundSurface: Color = Color(0xFF262626)
        val backgroundCard: Color = Color(0xFF1B1B1B)
        // The neutral theme sets popover to dark[10] #1B1B1B — the
        // same value as card. Separation comes from elevation and
        // shadow, not from a lighter fill.
        val backgroundPopover: Color = Color(0xFF1B1B1B)
        val backgroundInverted: Color = Color(0xFFFFFFFF)

        // Text.
        val textPrimary: Color = Color(0xFFFFFFFF)
        val textSecondary: Color = Color(0xFF9E9E9E)
        val textDisabled: Color = Color(0xFF525252)
        val onDark: Color = Color(0xFFFFFFFF)
        val onLight: Color = Color(0xFF111111)

        // Accent: neutral dark[95] — near-white in dark mode,
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

        // Status roles, from the neutral theme (text = the
        // --color-text-<status> token, border = the
        // --color-border-<status> token, background = the
        // --color-background-<status> token, fill = the strong
        // --color-<status> fill). All four roles resolve to real
        // palette steps — none of these values is invented.
        val infoText: Color = Color(0xFFA1CAFF)
        val infoBorder: Color = Color(0xFF5AA0F8)
        val infoBackground: Color = Color(0xFF253C5A)
        val infoFill: Color = Color(0xFF6D9CFE)
        val successText: Color = Color(0xFF90CA90)
        val successBorder: Color = Color(0xFF6AB26B)
        val successBackground: Color = Color(0xFF2B422B)
        val successFill: Color = Color(0xFF64AF4C)
        val warningText: Color = Color(0xFFEEC448)
        val warningBorder: Color = Color(0xFFC29900)
        val warningBackground: Color = Color(0xFF453A1C)
        val warningFill: Color = Color(0xFFFFCE2F)
        val errorText: Color = Color(0xFFFFAEA7)
        val errorBorder: Color = Color(0xFFFA6762)
        val errorBackground: Color = Color(0xFF5B2B28)
        val errorFill: Color = Color(0xFFFF705D)
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
