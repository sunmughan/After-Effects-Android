package com.codeair.effectcraft.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure math for dragging a timeline key marker to a new frame. Kept out of
 * the composable so the mapping is unit-testable without an emulator: the
 * timeline track is a proportional view of the composition, so an x offset
 * scales to a frame by the track-width-to-duration ratio, and a drag that
 * starts away from every marker grabs nothing.
 */
object CanonicalKeyframeDrag {
    /** Frame under [xPx] on a track [widthPx] wide spanning [durationFrames] frames, clamped to the range. */
    fun frameFromX(xPx: Float, widthPx: Float, durationFrames: Int): Int {
        if (!xPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f || durationFrames <= 1) return 0
        val range = (durationFrames - 1).toFloat()
        return ((xPx / widthPx) * range).roundToInt().coerceIn(0, durationFrames - 1)
    }

    /** The key frame nearest to [frame], or null when the track has no keys. */
    fun nearestKey(frames: List<Int>, frame: Int): Int? =
        frames.minByOrNull { abs(it - frame) }

    /** True when a drag starting at [xPx] is close enough to a marker to grab it (within [grabRadiusPx]). */
    fun grabsKey(frames: List<Int>, xPx: Float, widthPx: Float, durationFrames: Int, grabRadiusPx: Float = 24f): Int? {
        val frame = frameFromX(xPx, widthPx, durationFrames)
        val nearest = nearestKey(frames, frame) ?: return null
        val markerX = if (durationFrames <= 1) 0f else nearest.toFloat() / (durationFrames - 1) * widthPx
        return if (abs(xPx - markerX) <= grabRadiusPx) nearest else null
    }

    /** The single (layerId, property) owning a key at [frame], or null when zero or several own one. */
    fun resolveMove(candidates: List<Pair<Long, String>>): Pair<Long, String>? =
        candidates.singleOrNull()
}
