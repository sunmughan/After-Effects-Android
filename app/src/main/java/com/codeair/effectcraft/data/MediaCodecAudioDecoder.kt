package com.codeair.effectcraft.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio source the device can open (AAC, MP3, WAV, Opus,
 * Vorbis, FLAC — whatever MediaExtractor/MediaCodec supports) to PCM,
 * so export mixing is never limited to AAC passthrough. The decoded
 * window is channel-converted and linear-resampled to the rate the
 * mixer requests. A source that cannot be opened, has no audio track,
 * or decodes to anything but 16-bit PCM fails loudly; the export
 * worker treats that as "no mixed audio" and falls back to the
 * pre-existing AAC passthrough path instead of failing the video.
 */
class MediaCodecAudioDecoder(private val context: Context) : AudioPcmSource {

    data class SourceInfo(
        val mimeType: String,
        val durationUs: Long,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    /** Duration/rate/channels of the first audio track, or null when the source holds none. */
    fun probe(sourceUri: String): SourceInfo? {
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(context, Uri.parse(sourceUri), null) }.getOrNull()
                ?: return null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("audio/")) continue
                return SourceInfo(
                    mimeType = mime,
                    durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L,
                    sampleRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                    channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                )
            }
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    override fun readPcm(
        clip: AudioClip,
        sourceStartUs: Long,
        sampleCount: Int,
        sampleRateHz: Int,
        channelCount: Int
    ): FloatArray {
        require(sampleCount >= 0) { "Sample count cannot be negative" }
        val silence = FloatArray(sampleCount * channelCount)
        if (sampleCount == 0) return silence
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(context, Uri.parse(clip.sourceUri), null) }.getOrNull()
                ?: throw IllegalStateException("Audio source cannot be opened: ${clip.sourceUri}")
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: "")
                    .startsWith("audio/")
            } ?: throw IllegalStateException("Audio source has no audio track: ${clip.sourceUri}")
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Audio track has no MIME type")
            val nativeRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            val nativeChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            require(nativeRateHz > 0 && nativeChannels > 0) { "Audio track reports no sample rate or channels" }
            extractor.selectTrack(trackIndex)
            extractor.seekTo(sourceStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val decoded = decodeWindow(
                    decoder, extractor, sourceStartUs, sampleCount, nativeRateHz, nativeChannels
                )
                val stereo = PcmResampler.toStereo(decoded, nativeChannels)
                val resampled = PcmResampler.resampleLinear(
                    stereo, stereo.size / 2, 2, nativeRateHz, sampleRateHz
                )
                val output = FloatArray(sampleCount * channelCount)
                val frames = minOf(resampled.size / 2, sampleCount)
                if (channelCount == 2) {
                    resampled.copyInto(output, 0, 0, frames * 2)
                } else {
                    // The mixer only ever asks for stereo export; any other
                    // request is served from the left channel, never faked.
                    for (frame in 0 until frames) {
                        for (channel in 0 until channelCount) {
                            output[frame * channelCount + channel] = resampled[frame * 2]
                        }
                    }
                }
                return output
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes from the extractor's current position, dropping samples
     * before [sourceStartUs] and collecting [sampleCount] frames at the
     * native rate/channels as interleaved floats. Stops at end of
     * stream; the caller zero-pads the shortfall.
     */
    private fun decodeWindow(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        sourceStartUs: Long,
        sampleCount: Int,
        nativeRateHz: Int,
        nativeChannels: Int
    ): FloatArray {
        val pcm = FloatArray(sampleCount * nativeChannels)
        var framesCollected = 0
        var inputEos = false
        var outputEos = false
        val info = MediaCodec.BufferInfo()
        while (!outputEos && framesCollected < sampleCount) {
            if (!inputEos) {
                val inputIndex = decoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Audio decoder returned no input buffer")
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        val pts = extractor.sampleTime
                        extractor.advance()
                        decoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
                    }
                }
            }
            val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
            when {
                outputIndex >= 0 -> {
                    val outBuffer = decoder.getOutputBuffer(outputIndex)
                    try {
                        if (info.size > 0 && outBuffer != null) {
                            val pcmFormat = decoder.outputFormat
                            val encoding = if (pcmFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                android.media.AudioFormat.ENCODING_PCM_16BIT
                            }
                            require(encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                                "Audio decoder produced non-16-bit PCM"
                            }
                            val framePtsUs = info.presentationTimeUs
                            val bytes = ByteArray(info.size)
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            outBuffer.get(bytes)
                            val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val totalFrames = info.size / 2 / nativeChannels
                            for (frame in 0 until totalFrames) {
                                val sampleTimeUs = framePtsUs + frame.toLong() * 1_000_000L / nativeRateHz
                                if (sampleTimeUs < sourceStartUs) continue
                                if (framesCollected >= sampleCount) break
                                for (channel in 0 until nativeChannels) {
                                    val sample = shortBuffer.get(frame * nativeChannels + channel)
                                    pcm[framesCollected * nativeChannels + channel] = sample / 32768f
                                }
                                framesCollected++
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputEos) outputEos = true
                }
            }
        }
        return pcm
    }
}
