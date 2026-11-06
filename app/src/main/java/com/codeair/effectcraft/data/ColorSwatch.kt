package com.codeair.effectcraft.data

import kotlin.math.roundToInt

/**
 * Color keyframes for the timeline COLOR lane (owner pick 2026-11-06):
 * keyframe dots on the clip/timeline with a color swatch at each keyframe.
 * The graph/curve view is a later advanced option, not the primary UI.
 *
 * A swatch is drawn from the key's OWN RGBA value, never the layer's
 * current color: the native metadata carries the full RGBA array on each
 * color curve key, and [CanonicalCurveKey.components] holds it.
 */

/** Formats straight RGBA components (0..1, clamped) as a #RRGGBB hex swatch; alpha is not shown. */
fun rgbaToHex(components: List<Double>): String {
    fun channel(index: Int): Int =
        (components.getOrNull(index)?.coerceIn(0.0, 1.0)?.times(255.0)?.roundToInt() ?: 0).coerceIn(0, 255)
    return "#%02X%02X%02X".format(channel(0), channel(1), channel(2))
}

/**
 * All color keyframes of one canonical layer, across every shape item and
 * effect color parameter, deduplicated by (frame, RGBA) and sorted by frame.
 * Two color parameters keyed at the same frame with the same color collapse
 * to one dot; different colors at one frame stay separate dots.
 */
fun colorKeysOf(layer: CanonicalLayerInfo): List<CanonicalCurveKey> =
    (layer.shapeItems.flatMap { it.colors } + layer.effects.flatMap { it.colors })
        .flatMap { it.keys }
        .distinctBy { it.frame to it.components }
        .sortedBy { it.frame }
