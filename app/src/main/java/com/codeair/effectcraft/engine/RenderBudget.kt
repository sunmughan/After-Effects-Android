package com.codeair.effectcraft.engine

import java.util.Locale
/**
 * Enforced render budgets for the preview/render path.
 *
 * This is the single authority for the per-frame pixel budget: the value
 * mirrors the native-frame decode budget ([RenderedFramePng.MAX_PIXELS])
 * and the render-queue budget, and a JVM cross-check pins all three to the
 * same number so they cannot drift apart silently.
 *
 * Budget failures are [BudgetViolationException]: they fail visibly to the
 * caller (editor status text in the export flows, a blocked-preview status
 * in the viewer) instead of silently degrading a frame, dropping quality,
 * or allocating past the budget.
 *
 * Frame pacing: the interval itself stays owned by the editor's frame-rate
 * math; [FramePaceTracker] only counts lateness against a supplied
 * interval, so this unit needs no Android API and no cross-package import.
 * Sustained lateness is the observable symptom of device throttling, so a
 * tripped pacing budget is the thermal budget's enforcement point here: the
 * viewer must surface it, never quietly shed frames. Reading a live thermal
 * sensor value still needs physical-device work and is deliberately not
 * faked with a synthetic sensor.
 */
class BudgetViolationException(message: String) : IllegalStateException(message)

object RenderBudget {
    /** Maximum RGBA pixels in one rendered frame (8,388,608 = 2896 x 2896 rounded down). */
    const val MAX_PIXELS_PER_FRAME = 8_388_608L

    /** RGBA bytes per pixel. */
    const val BYTES_PER_PIXEL_RGBA = 4L

    /** Consecutive late frames that trip the pacing budget. */
    const val MAX_CONSECUTIVE_LATE_FRAMES = 3

    /** Largest square preview side that fits the pixel budget: 2896^2 <= budget < 2897^2. */
    fun maxPreviewSide(): Int = 2896

    /** Estimated RGBA bytes for a frame; Long math so large dimensions cannot wrap. */
    fun estimatedRgbaBytes(width: Int, height: Int): Long {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        return width.toLong() * height.toLong() * BYTES_PER_PIXEL_RGBA
    }

    /** Throws [BudgetViolationException] naming the budget when a frame exceeds it. */
    fun checkFrameDimensions(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_PIXELS_PER_FRAME) {
            throw BudgetViolationException(
                "Rendered frame is ${width}x$height ($pixels pixels), over the per-frame budget of " +
                    "%,d pixels; refusing to allocate instead of degrading silently".format(Locale.US, MAX_PIXELS_PER_FRAME)
            )
        }
    }

    /**
     * Throws [BudgetViolationException] when a preview side cannot fit the
     * pixel budget. `0` means full scale and is allowed: the rendered frame
     * itself is still bounded downstream by the decode pixel budget, so 0
     * is not an unbounded preview.
     */
    fun checkPreviewMaxSide(maxSide: Int) {
        if (maxSide == 0) return
        require(maxSide > 0) { "Preview side must be 0 (full scale) or positive" }
        if (maxSide > maxPreviewSide()) {
            throw BudgetViolationException(
                "Preview side $maxSide would allow ${maxSide.toLong() * maxSide} pixels, over the " +
                    "per-frame preview budget of %,d pixels (max side ${maxPreviewSide()})".format(Locale.US, MAX_PIXELS_PER_FRAME)
            )
        }
    }

    sealed interface PaceVerdict {
        data object OnTime : PaceVerdict
        data class Late(val consecutiveLate: Int) : PaceVerdict
        data class Violation(val consecutiveLate: Int) : PaceVerdict
    }

    /**
     * Counts consecutive frames slower than [frameIntervalMillis]. The
     * [MAX_CONSECUTIVE_LATE_FRAMES]th consecutive late frame trips to
     * [PaceVerdict.Violation]; one on-time frame clears the streak. The
     * caller must surface a violation (status text), never swallow it by
     * dropping frames or lowering quality without telling the user.
     */
    class FramePaceTracker(
        private val frameIntervalMillis: Long,
        private val maxConsecutiveLate: Int = MAX_CONSECUTIVE_LATE_FRAMES,
    ) {
        private var lateStreak = 0

        init {
            require(frameIntervalMillis > 0) { "Frame interval must be positive" }
            require(maxConsecutiveLate > 0) { "Late-frame threshold must be positive" }
        }

        fun consecutiveLate(): Int = lateStreak

        fun observeFrame(elapsedMillis: Long): PaceVerdict {
            require(elapsedMillis >= 0) { "Frame elapsed time cannot be negative" }
            if (elapsedMillis <= frameIntervalMillis) {
                lateStreak = 0
                return PaceVerdict.OnTime
            }
            lateStreak += 1
            return if (lateStreak >= maxConsecutiveLate) {
                PaceVerdict.Violation(lateStreak)
            } else {
                PaceVerdict.Late(lateStreak)
            }
        }

        fun reset() {
            lateStreak = 0
        }
    }
}
