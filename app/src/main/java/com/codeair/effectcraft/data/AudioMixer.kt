package com.codeair.effectcraft.data

import kotlin.math.roundToInt

/**
 * Source of decoded PCM for one [AudioClip]. Implementations decode
 * [clip]'s trimmed window starting at [sourceStartUs] and return exactly
 * [sampleCount] frames of interleaved floats in [-1, 1] at [sampleRateHz]
 * and [channelCount], zero-padding past the end of the source. The mixer
 * never sees file formats or codecs; the production implementation is
 * [MediaCodecAudioDecoder], tests supply a synthetic source.
 */
fun interface AudioPcmSource {
    fun readPcm(
        clip: AudioClip,
        sourceStartUs: Long,
        sampleCount: Int,
        sampleRateHz: Int,
        channelCount: Int
    ): FloatArray
}

/**
 * Pure PCM helpers shared by the mixer and the decoder. Linear
 * interpolation is the honest resampler here: it never invents spectral
 * content, and the export sample rate (48 kHz) matches the common
 * Android AAC encoder rate, so most sources resample by a small ratio
 * or not at all.
 */
object PcmResampler {
    /** Linear-interpolation resample of interleaved PCM. */
    fun resampleLinear(
        input: FloatArray,
        inputFrames: Int,
        channelCount: Int,
        inputRateHz: Int,
        outputRateHz: Int
    ): FloatArray {
        require(inputFrames >= 0) { "Input frame count cannot be negative" }
        require(channelCount in 1..8) { "Channel count must be between 1 and 8" }
        require(inputRateHz > 0 && outputRateHz > 0) { "Sample rates must be positive" }
        require(input.size == inputFrames * channelCount) { "PCM buffer does not match its frame count" }
        if (inputRateHz == outputRateHz || inputFrames == 0) return input.copyOf()
        val outputFrames = ((inputFrames.toLong() * outputRateHz + inputRateHz / 2L) / inputRateHz).toInt()
        if (outputFrames == 0) return FloatArray(0)
        val output = FloatArray(outputFrames * channelCount)
        for (outFrame in 0 until outputFrames) {
            val position = outFrame.toDouble() * inputFrames / outputFrames
            val base = position.toInt().coerceAtMost(inputFrames - 1)
            val frac = (position - base).coerceIn(0.0, 1.0).toFloat()
            val next = (base + 1).coerceAtMost(inputFrames - 1)
            for (channel in 0 until channelCount) {
                val a = input[base * channelCount + channel]
                val b = input[next * channelCount + channel]
                output[outFrame * channelCount + channel] = a + (b - a) * frac
            }
        }
        return output
    }

    /** Converts interleaved PCM to stereo: mono duplicates, stereo passes through, more channels downmix by mean. */
    fun toStereo(input: FloatArray, inputChannels: Int): FloatArray {
        require(inputChannels in 1..8) { "Channel count must be between 1 and 8" }
        require(input.size % inputChannels == 0) { "PCM buffer is not a whole number of frames" }
        if (inputChannels == 2) return input.copyOf()
        val frames = input.size / inputChannels
        val output = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            if (inputChannels == 1) {
                output[frame * 2] = input[frame]
                output[frame * 2 + 1] = input[frame]
            } else {
                var sum = 0f
                for (channel in 0 until inputChannels) sum += input[frame * inputChannels + channel]
                val mean = sum / inputChannels
                output[frame * 2] = mean
                output[frame * 2 + 1] = mean
            }
        }
        return output
    }
}

/**
 * Mixes an [AudioTimeline] to interleaved float PCM for one output
 * window. Every clip overlapping the window contributes its decoded
 * samples scaled by its per-sample gain envelope ([AudioClip.gainAt],
 * volume times linear fades); overlapping clips sum, and the sum is
 * hard-clamped to [-1, 1] so no sample ever wraps. Mixing is chunked
 * in one-second windows so a long export never holds the whole mix
 * plus every decoded clip in memory at once.
 */
object AudioMixer {
    const val EXPORT_SAMPLE_RATE_HZ = 48000
    const val EXPORT_CHANNEL_COUNT = 2
    private const val WINDOW_US = 1_000_000L

    /** Whole frames covering [durationUs] at [sampleRateHz], rounded to nearest. */
    fun frameCount(durationUs: Long, sampleRateHz: Int): Int {
        require(durationUs >= 0L) { "Duration cannot be negative" }
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        return ((durationUs * sampleRateHz + 500_000L) / 1_000_000L).toInt()
    }

    fun mix(
        timeline: AudioTimeline,
        startUs: Long,
        endUs: Long,
        sampleRateHz: Int,
        channelCount: Int,
        source: AudioPcmSource
    ): FloatArray {
        require(startUs >= 0L) { "Mix start cannot be negative" }
        require(endUs >= startUs) { "Mix end must be at or after its start" }
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(channelCount in 1..8) { "Channel count must be between 1 and 8" }
        val totalFrames = frameCount(endUs - startUs, sampleRateHz)
        val mixed = FloatArray(totalFrames * channelCount)
        var windowStartUs = startUs
        while (windowStartUs < endUs) {
            val windowEndUs = minOf(windowStartUs + WINDOW_US, endUs)
            val windowOffsetFrames = frameCount(windowStartUs - startUs, sampleRateHz)
            for (clip in timeline.clips) {
                val overlapStart = maxOf(clip.timelineStartUs, windowStartUs)
                val overlapEnd = minOf(clip.timelineEndUs, windowEndUs)
                if (overlapEnd <= overlapStart) continue
                val overlapFrames = frameCount(overlapEnd - overlapStart, sampleRateHz)
                val clipOffsetFrames = frameCount(overlapStart - windowStartUs, sampleRateHz)
                val pcm = source.readPcm(
                    clip,
                    clip.sourceStartUs + (overlapStart - clip.timelineStartUs),
                    overlapFrames,
                    sampleRateHz,
                    channelCount
                )
                require(pcm.size == overlapFrames * channelCount) {
                    "Audio source returned ${pcm.size} samples for $overlapFrames frames"
                }
                for (frame in 0 until overlapFrames) {
                    val timelineUs = overlapStart + frame.toLong() * 1_000_000L / sampleRateHz
                    val gain = clip.gainAt(timelineUs).toFloat()
                    if (gain == 0f) continue
                    val outBase = (windowOffsetFrames + clipOffsetFrames + frame) * channelCount
                    val inBase = frame * channelCount
                    for (channel in 0 until channelCount) {
                        mixed[outBase + channel] =
                            (mixed[outBase + channel] + pcm[inBase + channel] * gain)
                                .coerceIn(-1f, 1f)
                    }
                }
            }
            windowStartUs = windowEndUs
        }
        return mixed
    }

    /** True when every sample is exactly zero: silence is never written as an audio track. */
    fun isSilent(pcm: FloatArray): Boolean = pcm.all { it == 0f }
}
