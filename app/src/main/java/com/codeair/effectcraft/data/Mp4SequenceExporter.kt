package com.codeair.effectcraft.data

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.codeair.effectcraft.engine.RenderedFrameBitmap
import com.codeair.effectcraft.engine.Yuv420FrameConverter
import java.io.FileDescriptor
import java.util.concurrent.CancellationException

/**
 * Streams canonical native RGBA frames into an Android H.264 or HEVC
 * encoder and MP4 muxer. H.264 is the only default (user decision
 * 2026-11-06); HEVC is an explicit opt-in whose encoder must be the
 * hardware encoder [HevcEncoderProbe] confirms, never a software encoder.
 *
 * The caller supplies a fresh SAF/app-private output FileDescriptor and a callback
 * that renders one canonical composition frame. Frames are converted/scaled one
 * at a time; this exporter never retains a sequence of Bitmaps. On cancellation
 * or failure the caller must delete the partial output document.
 */
object Mp4SequenceExporter {
    const val MIME = "video/avc"
    const val HEVC_MIME = "video/hevc"
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L

    /** The encoder MIME for a plan format; throws for non-MP4 formats. */
    fun mimeFor(format: RenderOutputFormat): String = when (format) {
        RenderOutputFormat.MP4_H264 -> MIME
        RenderOutputFormat.MP4_HEVC -> HEVC_MIME
        RenderOutputFormat.PNG_SEQUENCE_ZIP -> throw IllegalArgumentException("PNG sequence has no video encoder MIME")
    }

    /** Short codec label for messages: "H.264" or "HEVC". */
    fun codecLabel(format: RenderOutputFormat): String = when (format) {
        RenderOutputFormat.MP4_H264 -> "H.264"
        RenderOutputFormat.MP4_HEVC -> "HEVC"
        RenderOutputFormat.PNG_SEQUENCE_ZIP -> throw IllegalArgumentException("PNG sequence has no video codec")
    }

    data class Result(val framesEncoded: Int, val durationUs: Long, val codecName: String)

    private data class MuxerState(var track: Int = -1, var started: Boolean = false)

    fun export(
        plan: RenderQueuePlan,
        output: FileDescriptor,
        renderRgba: (frame: Int) -> ByteArray,
        isCancelled: () -> Boolean = { false },
        onProgress: (completedFrames: Int, percent: Int) -> Unit = { _, _ -> }
    ): Result {
        val format = plan.settings.outputFormat
        require(format == RenderOutputFormat.MP4_H264 || format == RenderOutputFormat.MP4_HEVC) {
            "Render queue is not configured for MP4"
        }
        val mime = mimeFor(format)
        val label = codecLabel(format)
        require(plan.settings.width % 2 == 0 && plan.settings.height % 2 == 0) {
            "$label YUV420 output dimensions must be even"
        }
        checkCancellation(isCancelled)
        val codecName = findFlexibleEncoder(mime, hardwareOnly = format == RenderOutputFormat.MP4_HEVC)
        val encoder = MediaCodec.createByCodecName(codecName)
        val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val state = MuxerState()
        val info = MediaCodec.BufferInfo()
        var encoderStarted = false
        var framesEncoded = 0
        try {
            val videoFormat = MediaFormat.createVideoFormat(mime, plan.settings.width, plan.settings.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.settings.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.settings.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true

            for (frameNumber in plan.frames) {
                checkCancellation(isCancelled)
                val nativeFrame = renderRgba(frameNumber)
                checkCancellation(isCancelled)
                val source = RenderedFrameBitmap.decode(nativeFrame)
                    ?: throw IllegalArgumentException("Native renderer returned an invalid RGBA frame")
                val yuv = try {
                    Yuv420FrameConverter.fromBitmap(source, plan.settings.width, plan.settings.height)
                } finally {
                    source.recycle()
                }
                queueFrame(
                    encoder, muxer, state, info, yuv.i420, yuv.width, yuv.height,
                    framesEncoded.toLong() * 1_000_000L / plan.settings.frameRate,
                    isCancelled
                )
                drain(encoder, muxer, state, info, 0L, isCancelled)
                framesEncoded++
                onProgress(framesEncoded, plan.progressPercent(framesEncoded))
            }

            queueEndOfStream(
                encoder, muxer, state, info,
                framesEncoded.toLong() * 1_000_000L / plan.settings.frameRate,
                isCancelled
            )
            while (!drain(encoder, muxer, state, info, OUTPUT_TIMEOUT_US, isCancelled)) {
                checkCancellation(isCancelled)
            }
            check(state.started) { "$label encoder produced no MP4 output track" }
            return Result(
                framesEncoded,
                framesEncoded.toLong() * 1_000_000L / plan.settings.frameRate,
                codecName
            )
        } finally {
            if (encoderStarted) runCatching { encoder.stop() }
            encoder.release()
            if (state.started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun findFlexibleEncoder(mime: String, hardwareOnly: Boolean): String {
        if (hardwareOnly) {
            return HevcEncoderProbe.probe().codecName
                ?: throw IllegalStateException("No hardware HEVC encoder accepts flexible YUV420 input on this device")
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.firstOrNull { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching {
                    codec.getCapabilitiesForType(mime).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }.getOrDefault(false)
        }?.name ?: throw IllegalStateException("No H.264 encoder accepts flexible YUV420 input on this device")
    }

    private fun queueFrame(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        state: MuxerState,
        info: MediaCodec.BufferInfo,
        i420: ByteArray,
        width: Int,
        height: Int,
        presentationTimeUs: Long,
        isCancelled: () -> Boolean
    ) {
        var inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        while (inputIndex < 0) {
            checkCancellation(isCancelled)
            drain(encoder, muxer, state, info, INPUT_TIMEOUT_US, isCancelled)
            inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        }
        val image = encoder.getInputImage(inputIndex)
            ?: run {
                encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                throw IllegalStateException("Flexible YUV420 encoder did not expose an input Image")
            }
        try {
            require(image.width == width && image.height == height) {
                "Encoder input Image dimensions do not match the render plan"
            }
            val planes = image.planes
            require(planes.size == 3) { "Encoder input Image is not planar YUV420" }
            val ySize = width * height
            val chromaSize = ySize / 4
            writePlane(planes[0], i420, 0, width, height, width)
            writePlane(planes[1], i420, ySize, width / 2, height / 2, width / 2)
            writePlane(planes[2], i420, ySize + chromaSize, width / 2, height / 2, width / 2)
        } finally {
            image.close()
        }
        encoder.queueInputBuffer(inputIndex, 0, i420.size, presentationTimeUs, 0)
    }

    private fun writePlane(
        plane: android.media.Image.Plane,
        source: ByteArray,
        sourceOffset: Int,
        columns: Int,
        rows: Int,
        sourceRowStride: Int
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(rowStride > 0 && pixelStride > 0) { "Encoder input plane has invalid strides" }
        for (row in 0 until rows) {
            val sourceRow = sourceOffset + row * sourceRowStride
            val targetRow = row * rowStride
            for (column in 0 until columns) {
                val target = targetRow + column * pixelStride
                require(target < buffer.limit()) { "Encoder input plane buffer is too small" }
                buffer.put(target, source[sourceRow + column])
            }
        }
    }

    private fun queueEndOfStream(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        state: MuxerState,
        info: MediaCodec.BufferInfo,
        presentationTimeUs: Long,
        isCancelled: () -> Boolean
    ) {
        var inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        while (inputIndex < 0) {
            checkCancellation(isCancelled)
            drain(encoder, muxer, state, info, INPUT_TIMEOUT_US, isCancelled)
            inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        }
        encoder.queueInputBuffer(
            inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
    }

    /** Returns true after output EOS; otherwise drains available output and returns on TRY_AGAIN. */
    private fun drain(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        state: MuxerState,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        while (true) {
            checkCancellation(isCancelled)
            when (val outputIndex = encoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!state.started) { "Encoder changed output format more than once" }
                    state.track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    state.started = true
                }
                else -> if (outputIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(outputIndex)
                    try {
                        if (info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            check(state.started) { "Encoder emitted samples before its output format" }
                            requireNotNull(encoded) { "Encoder returned a null output buffer" }
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(state.track, encoded, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos) return true
                    } finally {
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun checkCancellation(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("MP4 export cancelled")
    }
}
