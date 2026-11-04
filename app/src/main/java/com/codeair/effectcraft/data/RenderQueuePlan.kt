package com.codeair.effectcraft.data

/**
 * Validated, bounded plan for a native-rendered PNG frame sequence.
 *
 * This is planning/validation only: the current app exporter writes one PNG.
 * A ZIP sequence executor can consume [frames] one frame at a time without
 * allocating a frame list or retaining decoded bitmaps.
 */
enum class RenderOutputFormat {
    PNG_SEQUENCE_ZIP
}

data class RenderQueueSettings(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val startFrame: Int,
    val endFrame: Int,
    val outputFormat: RenderOutputFormat = RenderOutputFormat.PNG_SEQUENCE_ZIP
)

data class RenderQueuePlan private constructor(
    val settings: RenderQueueSettings,
    val frameCount: Int,
    val estimatedUncompressedRgbaBytes: Long
) {
    /** Inclusive frame range; lazy iteration keeps memory independent of queue length. */
    val frames: IntRange get() = settings.startFrame..settings.endFrame

    fun frameAt(index: Int): Int {
        require(index in 0 until frameCount) { "Frame index is outside this render queue" }
        return settings.startFrame + index
    }

    fun zipEntryName(frame: Int): String {
        require(frame in frames) { "Frame is outside this render queue" }
        return "frame-${frame.toString().padStart(6, '0')}.png"
    }

    /** Completed-frame count is in [0, frameCount]; zero is 0%, all frames is 100%. */
    fun progressPercent(completedFrames: Int): Int {
        require(completedFrames in 0..frameCount) { "Completed frame count is outside this queue" }
        return (completedFrames.toLong() * 100L / frameCount).toInt()
    }

    companion object {
        const val MIN_DIMENSION = 1
        const val MAX_DIMENSION = 4096
        const val MAX_PIXELS_PER_FRAME = 8_388_608L
        const val MAX_FRAME_INDEX = 1_000_000
        const val MAX_FRAME_COUNT = 10_000
        const val MAX_UNCOMPRESSED_RGBA_BYTES = 8L * 1024L * 1024L * 1024L

        /**
         * Validates the requested inclusive range against a canonical composition
         * before the user is asked to create an output document.
         */
        fun create(settings: RenderQueueSettings, compositionFrameCount: Int): RenderQueuePlan {
            require(compositionFrameCount > 0) { "Composition must contain at least one frame" }
            require(settings.endFrame < compositionFrameCount) {
                "End frame must be below composition frame count ($compositionFrameCount)"
            }
            return create(settings)
        }

        fun create(settings: RenderQueueSettings): RenderQueuePlan {
            require(settings.width in MIN_DIMENSION..MAX_DIMENSION) {
                "Render width must be between $MIN_DIMENSION and $MAX_DIMENSION"
            }
            require(settings.height in MIN_DIMENSION..MAX_DIMENSION) {
                "Render height must be between $MIN_DIMENSION and $MAX_DIMENSION"
            }
            require(settings.frameRate in 1..120) { "Frame rate must be between 1 and 120" }
            require(settings.startFrame >= 0) { "Start frame cannot be negative" }
            require(settings.endFrame >= settings.startFrame) { "End frame must be at or after start frame" }
            require(settings.endFrame <= MAX_FRAME_INDEX) { "End frame exceeds the supported frame index" }

            val countLong = settings.endFrame.toLong() - settings.startFrame.toLong() + 1L
            require(countLong in 1L..MAX_FRAME_COUNT.toLong()) {
                "Render queue must contain between 1 and $MAX_FRAME_COUNT frames"
            }
            val pixels = settings.width.toLong() * settings.height.toLong()
            require(pixels <= MAX_PIXELS_PER_FRAME) {
                "Render dimensions exceed the per-frame pixel budget"
            }
            val bytes = Math.multiplyExact(Math.multiplyExact(pixels, 4L), countLong)
            require(bytes <= MAX_UNCOMPRESSED_RGBA_BYTES) {
                "Render queue exceeds the 8 GiB uncompressed RGBA budget"
            }
            return RenderQueuePlan(settings, countLong.toInt(), bytes)
        }
    }
}
