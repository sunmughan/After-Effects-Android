package ai.effectcraft.android.data

/**
 * MP4-specific plan rules on top of [RenderQueuePlan] validation. H.264
 * ByteBuffer encoding needs even dimensions (YUV420 chroma subsampling), so
 * odd dimensions are rejected with a message telling the user to pick even
 * values instead of silently padding or distorting the frame.
 */
object Mp4ExportRequirements {
    const val MAX_BIT_RATE = 20_000_000
    const val MIN_BIT_RATE = 1_000_000

    fun checkPlan(plan: RenderQueuePlan) {
        require(plan.settings.outputFormat == RenderOutputFormat.MP4_H264) {
            "MP4 export requires an MP4 render plan"
        }
        require(plan.settings.width % 2 == 0 && plan.settings.height % 2 == 0) {
            "MP4 export requires even width and height (YUV420); pick even dimensions"
        }
    }

    /** Whole microseconds per exported frame; integer division, same rule as the timeline clock. */
    fun frameDurationUs(frameRate: Int): Long {
        require(frameRate in 1..120) { "Frame rate must be between 1 and 120" }
        return 1_000_000L / frameRate
    }

    /** Presentation timestamp of the frame at [frameOffset] from the range start. */
    fun presentationTimeUs(frameOffset: Int, frameRate: Int): Long {
        require(frameOffset >= 0) { "Frame offset cannot be negative" }
        return frameOffset.toLong() * frameDurationUs(frameRate)
    }

    fun expectedDurationUs(frameCount: Int, frameRate: Int): Long {
        require(frameCount > 0) { "Frame count must be positive" }
        return frameCount.toLong() * frameDurationUs(frameRate)
    }

    /** Bit rate scales with pixels and frame rate, floored for small frames and capped for large ones. */
    fun bitRate(width: Int, height: Int, frameRate: Int): Int {
        val scaled = width.toLong() * height.toLong() * frameRate / 10L
        return scaled.coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }
}
