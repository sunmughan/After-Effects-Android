package com.codeair.effectcraft.data

import kotlin.math.roundToInt

/**
 * Pure audio math for the export mixdown: frame/time conversion, linear
 * fade curves, sample mixing, resampling, channel conversion, and the
 * multi-clip mix itself. No Android types, no I/O — the mix runs on
 * already-decoded PCM, so every function here is JVM-testable.
 *
 * Mix contract:
 * - Output is stereo 16-bit PCM at [OUTPUT_SAMPLE_RATE_HZ].
 * - Each [MixClipInput.pcm] is stereo interleaved PCM at the output rate
 *   covering exactly the clip's timeline span; pcm[0] is the sample at
 *   [MixClipInput.startUs]. The decoder slices each source to its
 *   clip's source in/out before this point, so the mix never sees
 *   source time — only timeline time.
 * - Samples are summed across clips and clamped to [-1, 1]: mixing
 *   never wraps, and silence (zero) is written wherever no clip covers.
 */
object AudioMixMath {
    /** The mixdown output sample rate: 44.1 kHz stereo, the AAC encoder's input. */
    const val OUTPUT_SAMPLE_RATE_HZ = 44100

    /** The mixdown output channel count: stereo. */
    const val OUTPUT_CHANNELS = 2

    /** AAC frames hold 1024 samples per channel. */
    const val AAC_FRAME_SAMPLES = 1024

    /** One input to the mix: decoded, resampled, stereo PCM for one clip. */
    data class MixClipInput(
        val pcm: ShortArray,
        val startUs: Long,
        val gain: Float,
        val fadeInSamples: Long,
        val fadeOutSamples: Long,
        val muted: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MixClipInput) return false
            return pcm.contentEquals(other.pcm) && startUs == other.startUs && gain == other.gain &&
                fadeInSamples == other.fadeInSamples && fadeOutSamples == other.fadeOutSamples &&
                muted == other.muted
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + startUs.hashCode()
            result = 31 * result + gain.hashCode()
            result = 31 * result + fadeInSamples.hashCode()
            result = 31 * result + fadeOutSamples.hashCode()
            result = 31 * result + muted.hashCode()
            return result
        }
    }

    /** Microseconds of [frame] at a composition rate of num/den frames per second. */
    fun frameToUs(frame: Int, frameRateNum: Int, frameRateDen: Int): Long {
        val den = frameRateDen.coerceAtLeast(1).toLong()
        val num = frameRateNum.coerceAtLeast(1).toLong()
        return frame.toLong() * 1_000_000L * den / num
    }

    /** Whole samples in [durationUs] at [sampleRateHz]; the remainder is dropped, never rounded up. */
    fun samplesForDurationUs(durationUs: Long, sampleRateHz: Int): Long =
        durationUs.coerceAtLeast(0L) * sampleRateHz / 1_000_000L

    /**
     * Linear fade gain for [sampleInClip] (0-based) inside a clip of
     * [clipSamples] samples. The fade-in ramps 0 to [gain] over
     * [fadeInSamples] samples (sample 0 is silent, the first sample after
     * the fade is full gain); the fade-out ramps [gain] to 0 over the
     * last [fadeOutSamples] samples. A zero fade means full gain from
     * that end. Muted is always 0.
     */
    fun fadeGain(
        sampleInClip: Long,
        clipSamples: Long,
        fadeInSamples: Long,
        fadeOutSamples: Long,
        gain: Float,
        muted: Boolean
    ): Float {
        if (muted || gain == 0f) return 0f
        var factor = 1f
        if (fadeInSamples > 0 && sampleInClip < fadeInSamples) {
            factor = minOf(factor, sampleInClip.toFloat() / fadeInSamples)
        }
        if (fadeOutSamples > 0) {
            val fadeOutStart = clipSamples - fadeOutSamples
            if (sampleInClip >= fadeOutStart) {
                factor = minOf(factor, (clipSamples - sampleInClip).toFloat() / fadeOutSamples)
            }
        }
        return gain * factor
    }

    /** Sums normalized [-1, 1] sample values and clamps to 16-bit PCM; never wraps. */
    fun mixSamples(values: List<Float>): Short {
        val summed = values.sum().coerceIn(-1f, 1f)
        return (summed * 32767f).roundToInt().coerceIn(-32767, 32767).toShort()
    }

    /**
     * Resamples interleaved PCM from [fromRate] to [toRate] by linear
     * interpolation, per channel. Same-rate input is returned unchanged.
     * The output frame count is the input duration scaled by the rate
     * ratio, rounded to the nearest whole frame.
     */
    fun resample(samples: ShortArray, channels: Int, fromRate: Int, toRate: Int): ShortArray {
        require(channels >= 1) { "Channel count must be at least 1" }
        require(fromRate > 0 && toRate > 0) { "Sample rates must be positive" }
        require(samples.size % channels == 0) { "PCM length must be a whole number of frames" }
        if (samples.isEmpty() || fromRate == toRate) return samples.copyOf()
        val inFrames = samples.size / channels
        val outFrames = ((inFrames - 1).toLong() * toRate / fromRate + 1).toInt().coerceAtLeast(1)
        val output = ShortArray(outFrames * channels)
        for (outFrame in 0 until outFrames) {
            val position = outFrame.toDouble() * fromRate / toRate
            val lower = position.toInt().coerceIn(0, inFrames - 1)
            val upper = minOf(lower + 1, inFrames - 1)
            val fraction = (position - lower).toFloat()
            for (channel in 0 until channels) {
                val a = samples[lower * channels + channel].toFloat()
                val b = samples[upper * channels + channel].toFloat()
                output[outFrame * channels + channel] =
                    (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return output
    }

    /**
     * Converts interleaved PCM to stereo: mono is duplicated to both
     * channels, stereo passes through, more channels keep the first two.
     */
    fun toStereo(samples: ShortArray, channels: Int): ShortArray {
        require(channels >= 1) { "Channel count must be at least 1" }
        require(samples.size % channels == 0) { "PCM length must be a whole number of frames" }
        if (channels == 2) return samples.copyOf()
        val frames = samples.size / channels
        return ShortArray(frames * 2) { index ->
            val frame = index / 2
            if (channels == 1) samples[frame] else samples[frame * channels + index % 2]
        }
    }

    /**
     * Mixes [inputs] over [rangeStartUs, rangeEndUs) into one stereo
     * 16-bit PCM buffer at [OUTPUT_SAMPLE_RATE_HZ]. Each clip contributes
     * its samples at its timeline position, scaled by its gain and fade
     * curves; muted clips contribute nothing. Output frames no clip
     * covers are digital silence.
     */
    fun mix(inputs: List<MixClipInput>, rangeStartUs: Long, rangeEndUs: Long): ShortArray {
        require(rangeEndUs >= rangeStartUs) { "Mix range end must be at or after its start" }
        val frames = samplesForDurationUs(rangeEndUs - rangeStartUs, OUTPUT_SAMPLE_RATE_HZ).toInt()
        val output = ShortArray(frames * OUTPUT_CHANNELS)
        if (inputs.isEmpty()) return output
        val active = inputs.filter { !it.muted && it.pcm.isNotEmpty() }
        for (input in active) {
            val clipFrames = input.pcm.size / OUTPUT_CHANNELS
            // Output frame index of the clip's first sample (may be negative).
            val clipStartFrame = ((input.startUs - rangeStartUs) * OUTPUT_SAMPLE_RATE_HZ / 1_000_000L).toInt()
            val firstFrame = maxOf(0, -clipStartFrame)
            val lastFrame = minOf(clipFrames, frames - clipStartFrame)
            for (clipFrame in firstFrame until lastFrame) {
                val outFrame = clipStartFrame + clipFrame
                val gain = fadeGain(
                    sampleInClip = clipFrame.toLong(),
                    clipSamples = clipFrames.toLong(),
                    fadeInSamples = input.fadeInSamples,
                    fadeOutSamples = input.fadeOutSamples,
                    gain = input.gain,
                    muted = false
                )
                if (gain == 0f) continue
                for (channel in 0 until OUTPUT_CHANNELS) {
                    val sample = input.pcm[clipFrame * OUTPUT_CHANNELS + channel] / 32767f * gain
                    val outIndex = outFrame * OUTPUT_CHANNELS + channel
                    val mixed = output[outIndex] / 32767f + sample
                    output[outIndex] = (mixed.coerceIn(-1f, 1f) * 32767f).roundToInt()
                        .coerceIn(-32767, 32767).toShort()
                }
            }
        }
        return output
    }
}
