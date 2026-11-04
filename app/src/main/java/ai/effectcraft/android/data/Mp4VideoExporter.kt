package ai.effectcraft.android.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import ai.effectcraft.android.engine.RenderedFramePng
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Encodes rendered frames to MP4 (H.264 video via MediaCodec + MediaMuxer).
 *
 * The caller renders one frame at a time through the existing native session
 * path: [renderEnvelope] returns the native RGBA envelope for a plan frame,
 * exactly as the PNG exporter receives it. Frames are converted to YUV420
 * and fed to the encoder as ByteBuffers; no decoded bitmap sequence and no
 * PNG round-trip is retained. Output is staged in [outputFile] and must be
 * validated with [Mp4ExportValidator] before any SAF copy — the same
 * staged-integrity discipline as the ZIP exporter. Cancellation is
 * cooperative: checks run before every frame render and inside the drain
 * loop, and the caller deletes the staged file on cancellation or failure.
 *
 * Audio is passthrough only (see [AudioTrackSelection]): when [audioFile]
 * holds an AAC track, its samples are copied with presentation timestamps
 * shifted into the exported range ([audioRangeStartUs, audioRangeEndUs),
 * computed from the composition frame rate so audio stays in sync when the
 * export frame rate differs. When there is no AAC track, the export is
 * video-only and [Result.audioTrackIncluded] is false — no audio track is
 * ever synthesized. The caller maps that flag to the visible [AudioStatus].
 *
 * Device caveats, to be proven on hardware: the encoder must expose a
 * YUV420 ByteBuffer color format (devices exposing Surface-only input
 * fail loudly here instead of producing a corrupt file), and the stride
 * and slice-height are requested equal to width/height — encoders that
 * silently use a different stride would distort pixels, which only a
 * physical-device playback check can catch.
 */
object Mp4VideoExporter {
    data class Result(
        val framesWritten: Int,
        val audioTrackIncluded: Boolean,
        val audioSamplesWritten: Long
    )

    fun export(
        plan: RenderQueuePlan,
        outputFile: File,
        renderEnvelope: (frame: Int) -> ByteArray?,
        audioFile: File?,
        audioRangeStartUs: Long,
        audioRangeEndUs: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (completedFrames: Int, percent: Int) -> Unit = { _, _ -> }
    ): Result {
        Mp4ExportRequirements.checkPlan(plan)
        val width = plan.settings.width
        val height = plan.settings.height
        val frameRate = plan.settings.frameRate
        val audioProbe = probeAudio(audioFile)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            encoder = MediaCodec.createEncoderByType("video/avc")
            val (colorFormat, yuvLayout) = chooseColorFormat(encoder)
            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, Mp4ExportRequirements.bitRate(width, height, frameRate))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Literal keys: KEY_STRIDE/KEY_SLICE_HEIGHT constants post-date
            // minSdk 26, but the underlying keys are honored by encoders.
            format.setInteger("stride", width)
            format.setInteger("slice-height", height)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val activeMuxer = muxer!!

            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false
            var framesQueued = 0
            var encodedSamples = 0
            var encoderEos = false
            var outputEos = false
            while (!outputEos) {
                checkNotCancelled(isCancelled)
                if (!encoderEos) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                            ?: error("H.264 encoder returned no input buffer")
                        inputBuffer.clear()
                        if (framesQueued < plan.frameCount) {
                            val frame = plan.frameAt(framesQueued)
                            val envelope = renderEnvelope(frame)
                                ?: error("Native renderer returned no frame at $frame")
                            val rgba = RenderedFramePng.toRgbaPixels(envelope, width, height)
                            val yuv = RgbaToYuv420.convert(rgba, width, height, yuvLayout)
                            require(yuv.size <= inputBuffer.remaining()) {
                                "Encoded frame exceeds the H.264 encoder input buffer"
                            }
                            inputBuffer.put(yuv)
                            val isLast = framesQueued == plan.frameCount - 1
                            encoder.queueInputBuffer(
                                inputIndex, 0, yuv.size,
                                Mp4ExportRequirements.presentationTimeUs(framesQueued, frameRate),
                                if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            if (isLast) encoderEos = true
                            framesQueued++
                        } else {
                            encoder.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            encoderEos = true
                        }
                    }
                }
                var draining = true
                while (draining) {
                    val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            require(!muxerStarted) { "H.264 encoder output format changed twice" }
                            videoTrack = activeMuxer.addTrack(encoder.outputFormat)
                            if (audioProbe != null) {
                                audioTrack = activeMuxer.addTrack(audioProbe.format)
                            }
                            activeMuxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                ?: error("H.264 encoder returned no output buffer")
                            if (bufferInfo.size > 0 && muxerStarted &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                activeMuxer.writeSampleData(videoTrack, outputBuffer, bufferInfo)
                                encodedSamples++
                                onProgress(encodedSamples, plan.progressPercent(encodedSamples))
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        }
                        else -> draining = false
                    }
                }
            }
            require(muxerStarted) { "H.264 encoder produced no output format" }
            var audioSamples = 0L
            if (audioProbe != null && audioTrack >= 0 && audioFile != null) {
                audioSamples = copyAudioSamples(
                    audioFile, audioProbe.trackIndex, activeMuxer, audioTrack,
                    audioRangeStartUs, audioRangeEndUs, isCancelled
                )
            }
            activeMuxer.stop()
            activeMuxer.release()
            muxer = null
            return Result(framesQueued, audioProbe != null, audioSamples)
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            // A muxer that never reached stop() (failure/cancel) leaves a
            // partial staged file; the caller deletes it, so release only.
            if (muxer != null) runCatching { muxer.release() }
        }
    }

    private class AudioProbe(val trackIndex: Int, val format: MediaFormat)

    /**
     * Returns the AAC track probe, or null when there is no file, the file
     * cannot be parsed, or it holds no AAC track. A null probe means
     * video-only; it never means "try to transcode".
     */
    private fun probeAudio(audioFile: File?): AudioProbe? {
        if (audioFile == null || !audioFile.isFile) return null
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(audioFile.absolutePath) }.getOrNull() ?: return null
            val candidates = (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                AudioCandidate(
                    mimeType = format.getString(MediaFormat.KEY_MIME) ?: "",
                    sampleRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                    channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                )
            }
            val selected = AudioTrackSelection.selectPassthroughIndex(candidates) ?: return null
            return AudioProbe(selected, extractor.getTrackFormat(selected))
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun copyAudioSamples(
        audioFile: File,
        trackIndex: Int,
        muxer: MediaMuxer,
        muxerTrack: Int,
        rangeStartUs: Long,
        rangeEndUs: Long,
        isCancelled: () -> Boolean
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFile.absolutePath)
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var written = 0L
            while (true) {
                checkNotCancelled(isCancelled)
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                // Flags belong to the current sample: capture before advance().
                val flags = extractor.sampleFlags
                val shifted = AudioTrackSelection.shiftedPts(pts, rangeStartUs, rangeEndUs)
                if (shifted == null && pts >= rangeEndUs) break
                extractor.advance()
                if (shifted == null) continue
                info.set(0, size, shifted, flags)
                muxer.writeSampleData(muxerTrack, buffer, info)
                written++
            }
            return written
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun chooseColorFormat(encoder: MediaCodec): Pair<Int, RgbaToYuv420.Layout> {
        val capabilities = encoder.codecInfo.getCapabilitiesForType("video/avc")
        val formats = capabilities.colorFormats.toSet()
        val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val semiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        return when {
            flexible in formats -> flexible to RgbaToYuv420.Layout.PLANAR_I420
            planar in formats -> planar to RgbaToYuv420.Layout.PLANAR_I420
            semiPlanar in formats -> semiPlanar to RgbaToYuv420.Layout.SEMIPLANAR_NV12
            else -> error("H.264 encoder on this device exposes no YUV420 ByteBuffer input format")
        }
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("MP4 export cancelled")
    }
}
