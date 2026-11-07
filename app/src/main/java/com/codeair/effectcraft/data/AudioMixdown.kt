package com.codeair.effectcraft.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * Mixes the editor's audio clips into ONE AAC track at export.
 *
 * Pipeline, per clip, in order:
 * 1. Decide by MIME ([AudioTranscodeDecision]): AAC may pass through
 *    only on the legacy single-track path — a mix always decodes to PCM
 *    first, because gain, fades and placement need samples. WAV, MP3,
 *    FLAC and Opus decode through MediaCodec and re-encode to AAC.
 *    Any other format fails the export with a clear reason.
 * 2. Decode only the clip's source in/out window ([AudioDecoder]).
 * 3. Resample to 44.1 kHz stereo ([AudioMixMath]) — the mix's single
 *    rate, so clips recorded at different rates still line up.
 * 4. Mix all clips at their timeline positions with gain, fades and
 *    mute ([AudioMixMath.mix]); uncovered time is digital silence.
 * 5. Encode the mixed PCM to AAC LC (44.1 kHz stereo, 128 kbps) with
 *    composition-timeline presentation timestamps, so the existing
 *    remux step can shift them into the export range exactly like a
 *    passthrough track.
 *
 * Fail-closed: a missing source file, a file with no audio track, an
 * undecodable source, or an unsupported format throws with the clip id
 * and the reason — the export fails instead of shipping video-only or
 * synthesized audio. Muted clips are never decoded: they contribute
 * silence by definition. When no audible clip overlaps the export
 * range, no AAC file is produced and the caller reports the mix as
 * silent instead of claiming audio.
 */
object AudioMixdown {
    private const val TIMEOUT_US = 10_000L
    private const val AAC_MIME = "audio/mp4a-latm"
    private const val AAC_BIT_RATE = 128_000

    data class Result(
        val file: File,
        val aacSamplesWritten: Long,
        val firstPtsUs: Long?,
        val lastPtsUs: Long?
    )

    /**
     * Mixes [clips] over [rangeStartUs, rangeEndUs) and encodes the
     * result to AAC in [outputFile]. Returns zero samples (and writes
     * no file) when no audible clip overlaps the range. Throws
     * [AudioDecodeException] or [IllegalStateException] for a missing,
     * undecodable or unsupported source — fail closed, never silent.
     */
    fun mixToAac(
        clips: List<AudioClipDocument>,
        frameRateNum: Int,
        frameRateDen: Int,
        rangeStartUs: Long,
        rangeEndUs: Long,
        outputFile: File,
        isCancelled: () -> Boolean = { false }
    ): Result {
        require(clips.isNotEmpty()) { "Mixdown needs at least one audio clip" }
        require(rangeEndUs > rangeStartUs) { "Mixdown range must not be empty" }
        val inputs = clips.sortedBy { it.clip.startFrame }.mapNotNull { clip ->
            checkCancellation(isCancelled)
            val clipStartUs = AudioMixMath.frameToUs(clip.clip.startFrame, frameRateNum, frameRateDen)
            val clipEndUs = AudioMixMath.frameToUs(clip.clip.endFrame, frameRateNum, frameRateDen)
            if (clip.muted || clipEndUs <= rangeStartUs || clipStartUs >= rangeEndUs) return@mapNotNull null
            val source = File(clip.sourcePath)
            if (!source.isFile) {
                throw AudioDecodeException("Audio clip ${clip.id} source file is missing: ${clip.sourcePath}")
            }
            val probe = AudioDecoder.probe(source)
                ?: throw AudioDecodeException("Audio clip ${clip.id} source ${source.name} holds no audio track")
            if (AudioTranscodeDecision.decide(probe.mimeType) == AudioSourceDecision.UNSUPPORTED) {
                throw IllegalStateException(
                    "Audio clip ${clip.id} source format ${probe.mimeType} is not supported: " +
                        "use AAC, WAV, MP3, FLAC or Opus"
                )
            }
            val sourceStartUs = AudioMixMath.frameToUs(clip.clip.sourceStartFrame, frameRateNum, frameRateDen)
            val sourceEndUs = AudioMixMath.frameToUs(clip.clip.sourceEndFrame, frameRateNum, frameRateDen)
            val decoded = AudioDecoder.decodeRange(source, sourceStartUs, sourceEndUs)
            val resampled = AudioMixMath.resample(decoded.samples, decoded.channelCount, decoded.sampleRateHz, AudioMixMath.OUTPUT_SAMPLE_RATE_HZ)
            val stereo = AudioMixMath.toStereo(resampled, decoded.channelCount)
            val fadeInSamples = AudioMixMath.samplesForDurationUs(
                AudioMixMath.frameToUs(clip.fadeInFrames, frameRateNum, frameRateDen),
                AudioMixMath.OUTPUT_SAMPLE_RATE_HZ
            )
            val fadeOutSamples = AudioMixMath.samplesForDurationUs(
                AudioMixMath.frameToUs(clip.fadeOutFrames, frameRateNum, frameRateDen),
                AudioMixMath.OUTPUT_SAMPLE_RATE_HZ
            )
            AudioMixMath.MixClipInput(
                pcm = stereo,
                startUs = clipStartUs,
                gain = clip.gain,
                fadeInSamples = fadeInSamples,
                fadeOutSamples = fadeOutSamples,
                muted = false
            )
        }
        if (inputs.isEmpty()) return Result(outputFile, 0L, null, null)
        val mixed = AudioMixMath.mix(inputs, rangeStartUs, rangeEndUs)
        if (mixed.isEmpty()) return Result(outputFile, 0L, null, null)
        return encodeAac(mixed, rangeStartUs, outputFile, isCancelled)
    }

    /** Encodes stereo 44.1 kHz PCM to AAC in [outputFile], one 1024-sample frame at a time. */
    private fun encodeAac(
        pcm: ShortArray,
        rangeStartUs: Long,
        outputFile: File,
        isCancelled: () -> Boolean
    ): Result {
        val totalFrames = pcm.size / AudioMixMath.OUTPUT_CHANNELS
        val encoder = MediaCodec.createEncoderByType(AAC_MIME)
        var track = -1
        var started = false
        var samplesWritten = 0L
        var firstPtsUs: Long? = null
        var lastPtsUs: Long? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(
                AAC_MIME, AudioMixMath.OUTPUT_SAMPLE_RATE_HZ, AudioMixMath.OUTPUT_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val activeMuxer = requireNotNull(muxer)
            val info = MediaCodec.BufferInfo()
            var frameIndex = 0
            var inputEos = false
            var outputEos = false
            while (!outputEos) {
                checkCancellation(isCancelled)
                if (!inputEos) {
                    val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = encoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("AAC encoder exposed no input buffer")
                        val frameStart = frameIndex * AudioMixMath.AAC_FRAME_SAMPLES
                        if (frameStart >= totalFrames) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val frameSamples = minOf(AudioMixMath.AAC_FRAME_SAMPLES, totalFrames - frameStart)
                            input.clear()
                            input.order(ByteOrder.LITTLE_ENDIAN)
                            val shorts = input.asShortBuffer()
                            shorts.put(pcm, frameStart * AudioMixMath.OUTPUT_CHANNELS, frameSamples * AudioMixMath.OUTPUT_CHANNELS)
                            val ptsUs = rangeStartUs + frameStart.toLong() * 1_000_000L / AudioMixMath.OUTPUT_SAMPLE_RATE_HZ
                            encoder.queueInputBuffer(inputIndex, 0, frameSamples * AudioMixMath.OUTPUT_CHANNELS * 2, ptsUs, 0)
                            frameIndex++
                        }
                    }
                }
                when (val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEos) throw IllegalStateException("AAC encoder stalled before end of stream")
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!started) { "AAC encoder changed output format more than once" }
                        track = activeMuxer.addTrack(encoder.outputFormat)
                        activeMuxer.start()
                        started = true
                    }
                    else -> if (outputIndex >= 0) {
                        val encoded = encoder.getOutputBuffer(outputIndex)
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started) { "AAC encoder emitted samples before its output format" }
                                requireNotNull(encoded) { "AAC encoder returned a null output buffer" }
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                lastPtsUs = AudioTrackSelection.validateSamplePts(lastPtsUs, info.presentationTimeUs)
                                if (firstPtsUs == null) firstPtsUs = info.presentationTimeUs
                                activeMuxer.writeSampleData(track, encoded, info)
                                samplesWritten++
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                        } finally {
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            check(started) { "AAC encoder produced no MP4 output track" }
            activeMuxer.stop()
            activeMuxer.release()
            muxer = null
            return Result(outputFile, samplesWritten, firstPtsUs, lastPtsUs)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            muxer?.let { runCatching { it.release() } }
            if (samplesWritten == 0L) runCatching { outputFile.delete() }
        }
    }

    private fun checkCancellation(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Audio mixdown cancelled")
    }
}
