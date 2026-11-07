package com.codeair.effectcraft.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an audio file to 16-bit PCM through MediaExtractor/MediaCodec.
 *
 * Fail-closed: a file with no audio track, an undecodable track, or a
 * range that yields no samples throws [AudioDecodeException] with the
 * reason — audio is never faked, synthesized, or silently dropped.
 * The caller decides the export consequence (the mixdown fails the
 * export with that reason); nothing here writes a silent stand-in.
 *
 * Decoding is range-scoped: the extractor seeks to [startUs] and output
 * frames are trimmed by presentation time, so a clip decodes only its
 * source in/out window instead of a whole (possibly hour-long) file.
 * MP3-style decoder priming samples at the start of a stream are kept
 * as decoded (a ~12 ms offset at most); they are real decoded samples,
 * not an invented delay compensation.
 */
class AudioDecodeException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class AudioProbe(
    val mimeType: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationUs: Long
)

data class DecodedAudio(
    val sampleRateHz: Int,
    val channelCount: Int,
    val samples: ShortArray,
    val durationUs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedAudio) return false
        return sampleRateHz == other.sampleRateHz && channelCount == other.channelCount &&
            samples.contentEquals(other.samples) && durationUs == other.durationUs
    }

    override fun hashCode(): Int {
        var result = sampleRateHz
        result = 31 * result + channelCount
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + durationUs.hashCode()
        return result
    }
}

object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /**
     * The first audio track of [file], or null when the file has none or
     * cannot be opened. Never throws: callers that need audio treat null
     * as "no audio here" and callers that require it fail with their own
     * reason.
     */
    fun probe(file: File): AudioProbe? {
        if (!file.isFile) return null
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(file.absolutePath) }.getOrNull() ?: return null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                return AudioProbe(
                    mimeType = mime,
                    sampleRateHz = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0),
                    channelCount = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0),
                    durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, -1L)
                )
            }
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Decodes the whole file to PCM. */
    fun decode(file: File): DecodedAudio = decodeRange(file, 0L, Long.MAX_VALUE)

    /**
     * Decodes [startUs, endUs) of the file's first audio track to PCM.
     * Throws [AudioDecodeException] when the file is missing, holds no
     * audio track, cannot be decoded, or yields no samples in range.
     */
    fun decodeRange(file: File, startUs: Long, endUs: Long): DecodedAudio {
        require(endUs > startUs) { "Decode range must not be empty" }
        if (!file.isFile) throw AudioDecodeException("Audio file is missing: ${file.absolutePath}")
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (failure: Exception) {
                throw AudioDecodeException("Cannot open audio file ${file.name}: ${failure.message}", failure)
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if ((candidate.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw AudioDecodeException("Audio file ${file.name} holds no audio track")
            }
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (failure: Exception) {
                throw AudioDecodeException("No decoder for audio format $mime in ${file.name}", failure)
            }
            return decodeWith(extractor, trackIndex, decoder, startUs, endUs, file.name)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeWith(
        extractor: MediaExtractor,
        trackIndex: Int,
        decoder: MediaCodec,
        startUs: Long,
        endUs: Long,
        fileName: String
    ): DecodedAudio {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        var sampleRateHz = 0
        var channelCount = 0
        val samples = ArrayList<Short>()
        var inputEos = false
        var outputEos = false
        try {
            decoder.configure(
                extractor.getTrackFormat(trackIndex), null, null, 0
            )
            decoder.start()
            val info = MediaCodec.BufferInfo()
            while (!outputEos) {
                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: throw AudioDecodeException("Audio decoder exposed no input buffer for $fileName")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0 || extractor.sampleTime >= endUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEos) throw AudioDecodeException("Audio decoder stalled on $fileName")
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRateHz = outFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRateHz)
                        channelCount = outFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    }
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        try {
                            if (output != null && info.size > 0) {
                                if (sampleRateHz <= 0 || channelCount <= 0) {
                                    throw AudioDecodeException("Audio decoder gave no sample rate or channel count for $fileName")
                                }
                                appendInRange(output, info, sampleRateHz, channelCount, startUs, endUs, samples)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (failure: AudioDecodeException) {
            throw failure
        } catch (failure: Exception) {
            throw AudioDecodeException("Cannot decode audio file $fileName: ${failure.message}", failure)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
        if (samples.isEmpty()) {
            throw AudioDecodeException("Audio file $fileName decoded to no samples in the requested range")
        }
        if (sampleRateHz <= 0 || channelCount <= 0) {
            throw AudioDecodeException("Audio file $fileName decoded without a sample rate or channel count")
        }
        val decodedUs = samples.size.toLong() * 1_000_000L / (sampleRateHz * channelCount)
        return DecodedAudio(sampleRateHz, channelCount, samples.toShortArray(), decodedUs)
    }

    /**
     * Copies the samples of one decoder output frame whose presentation
     * time falls inside [startUs, endUs) into [out]. A frame straddling
     * either edge contributes only its in-range samples.
     */
    private fun appendInRange(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        sampleRateHz: Int,
        channelCount: Int,
        startUs: Long,
        endUs: Long,
        out: ArrayList<Short>
    ) {
        require(info.size % 2 == 0) { "Decoder PCM output is not a whole number of 16-bit samples" }
        val frameSamples = info.size / 2 / channelCount
        val frameStartUs = info.presentationTimeUs
        // Samples before the range start are dropped; samples at or past
        // the range end are dropped. All arithmetic stays in whole samples.
        val dropBefore = if (frameStartUs < startUs) {
            ((startUs - frameStartUs) * sampleRateHz / 1_000_000L).coerceIn(0L, frameSamples.toLong())
        } else {
            0L
        }
        val keepThrough = if (frameStartUs < endUs) {
            ((endUs - frameStartUs) * sampleRateHz / 1_000_000L).coerceIn(0L, frameSamples.toLong())
        } else {
            0L
        }
        val keep = (keepThrough - dropBefore).coerceAtLeast(0L).toInt()
        if (keep <= 0) return
        val view = buffer.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
        }.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        view.position(dropBefore.toInt() * channelCount)
        repeat(keep * channelCount) { out.add(view.get()) }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default
}
