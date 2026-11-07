package com.codeair.effectcraft.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Pure audio math tests. Written before the math existed: every test
 * here must fail until AudioMixMath lands.
 *
 * Locked semantics under test:
 * - Fades are linear ramps: sample 0 of a fade-in is silent, the first
 *   sample after the fade-in is full gain; the last sample of a
 *   fade-out approaches (but never reaches) silence only at the clip end.
 * - Mixing sums per-sample and clamps to [-1, 1] — it never wraps.
 * - Resampling is linear interpolation, per channel; same-rate is a copy.
 * - mix() places each clip at its timeline position, honours gain,
 *   fades and mute, and writes digital silence where no clip covers.
 */
class AudioMixMathTest {
    private fun mixInput(
        pcm: ShortArray,
        startUs: Long,
        gain: Float = 1f,
        fadeInSamples: Long = 0,
        fadeOutSamples: Long = 0,
        muted: Boolean = false
    ) = AudioMixMath.MixClipInput(
        pcm = pcm, startUs = startUs, gain = gain,
        fadeInSamples = fadeInSamples, fadeOutSamples = fadeOutSamples, muted = muted
    )

    /** A stereo frame pair holding the same float level on both channels. */
    private fun levelFrames(level: Float, frames: Int): ShortArray {
        val sample = (level * 32767f).roundToInt().toShort()
        return ShortArray(frames * 2) { sample }
    }

    @Test
    fun frameToUsUsesCompositionFrameRate() {
        assertEquals(0L, AudioMixMath.frameToUs(0, 30, 1))
        assertEquals(1_000_000L, AudioMixMath.frameToUs(30, 30, 1))
        assertEquals(500_000L, AudioMixMath.frameToUs(15, 30, 1))
        assertEquals(1_000_000L, AudioMixMath.frameToUs(60, 60, 1))
        assertEquals(1_001_000_000L, AudioMixMath.frameToUs(30000, 30000, 1001))
    }

    @Test
    fun fadeGainRampsLinearly() {
        // 10-sample clip, 4-sample fade-in, 4-sample fade-out, unity gain.
        assertEquals(0f, AudioMixMath.fadeGain(0, 10, 4, 4, 1f, false), 0.001f)
        assertEquals(0.25f, AudioMixMath.fadeGain(1, 10, 4, 4, 1f, false), 0.001f)
        assertEquals(1f, AudioMixMath.fadeGain(4, 10, 4, 4, 1f, false), 0.001f)
        assertEquals(1f, AudioMixMath.fadeGain(5, 10, 4, 4, 1f, false), 0.001f)
        assertEquals(0.75f, AudioMixMath.fadeGain(7, 10, 4, 4, 1f, false), 0.001f)
        assertEquals(0.25f, AudioMixMath.fadeGain(9, 10, 4, 4, 1f, false), 0.001f)
        // Gain scales the ramp; mute silences everything.
        assertEquals(2f, AudioMixMath.fadeGain(4, 10, 4, 4, 2f, false), 0.001f)
        assertEquals(0.5f, AudioMixMath.fadeGain(1, 10, 4, 4, 2f, false), 0.001f)
        assertEquals(0f, AudioMixMath.fadeGain(5, 10, 4, 4, 1f, true), 0.001f)
        // No fades means full gain from the first sample to the last.
        assertEquals(1f, AudioMixMath.fadeGain(0, 10, 0, 0, 1f, false), 0.001f)
        assertEquals(1f, AudioMixMath.fadeGain(9, 10, 0, 0, 1f, false), 0.001f)
    }

    @Test
    fun mixSamplesClampsInsteadOfWrapping() {
        assertEquals(32767, AudioMixMath.mixSamples(listOf(0.9f, 0.9f)).toInt())
        assertEquals(-32767, AudioMixMath.mixSamples(listOf(-0.9f, -0.9f)).toInt())
        assertEquals(0, AudioMixMath.mixSamples(listOf(0.5f, -0.5f)).toInt())
        assertEquals(0, AudioMixMath.mixSamples(emptyList()).toInt())
        assertEquals(16384, AudioMixMath.mixSamples(listOf(0.5f)).toInt())
    }

    @Test
    fun resampleSameRateIsACopy() {
        val input = shortArrayOf(100, -100, 200, -200)
        assertArrayEquals(input, AudioMixMath.resample(input, 2, 44100, 44100))
    }

    @Test
    fun resampleHalvesRateByDroppingEveryOtherFrame() {
        // Mono ramp 0,1,2,3 at 4 Hz -> 2 Hz keeps frames 0 and 2.
        val input = shortArrayOf(0, 1000, 2000, 3000)
        assertArrayEquals(shortArrayOf(0, 2000), AudioMixMath.resample(input, 1, 4, 2))
    }

    @Test
    fun resampleInterpolatesBetweenFrames() {
        // Mono 0 -> 3000 over 2 frames at 2 Hz, upsampled to 4 Hz:
        // frames at 0, 0.5, 1.0 of the way through: 0, 1500, 3000.
        val input = shortArrayOf(0, 3000)
        assertArrayEquals(shortArrayOf(0, 1500, 3000), AudioMixMath.resample(input, 1, 2, 4))
    }

    @Test
    fun resampleKeepsChannelsSeparate() {
        // Stereo: left ramps 0->2000, right stays 4000; 2 Hz -> 4 Hz.
        val input = shortArrayOf(0, 4000, 2000, 4000)
        assertArrayEquals(
            shortArrayOf(0, 4000, 1000, 4000, 2000, 4000),
            AudioMixMath.resample(input, 2, 2, 4)
        )
    }

    @Test
    fun toStereoDuplicatesMonoAndKeepsStereo() {
        assertArrayEquals(shortArrayOf(7, 7, -3, -3), AudioMixMath.toStereo(shortArrayOf(7, -3), 1))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), AudioMixMath.toStereo(shortArrayOf(1, 2, 3, 4), 2))
        assertArrayEquals(shortArrayOf(1, 2, 5, 6), AudioMixMath.toStereo(shortArrayOf(1, 2, 9, 5, 6, 9), 3))
    }

    @Test
    fun mixPlacesClipAtItsTimelinePositionWithSilenceElsewhere() {
        // 44,100 Hz: 1 second = 44,100 frames. Clip covers [0.5s, 0.6s).
        val clipFrames = 4410
        val output = AudioMixMath.mix(
            inputs = listOf(mixInput(levelFrames(0.5f, clipFrames), startUs = 500_000L)),
            rangeStartUs = 0L,
            rangeEndUs = 1_000_000L
        )
        assertEquals(44100 * 2, output.size)
        // Before the clip: silence. Inside: half level. After: silence.
        assertEquals(0, output[0].toInt())
        assertEquals(0, output[(22049 * 2)].toInt())
        assertEquals(16384, output[22050 * 2].toInt())
        assertEquals(16384, output[(22050 + 4409) * 2].toInt())
        assertEquals(0, output[(22050 + 4410) * 2].toInt())
        assertEquals(0, output.last().toInt())
    }

    @Test
    fun mixHonoursGainFadesAndMute() {
        val clipFrames = 100
        val full = levelFrames(1f, clipFrames)
        val gained = AudioMixMath.mix(
            listOf(mixInput(full, 0L, gain = 0.5f)), 0L, clipFrames * 1_000_000L / 44100L
        )
        assertEquals(16384, gained[0].toInt())

        val faded = AudioMixMath.mix(
            listOf(mixInput(full, 0L, fadeInSamples = 100, fadeOutSamples = 100)), 0L,
            clipFrames * 1_000_000L / 44100L
        )
        assertEquals(0, faded[0].toInt())
        assertTrue("Mid-clip fade must reach near full level, was ${faded[50 * 2]}", faded[50 * 2] > 16000)

        val muted = AudioMixMath.mix(
            listOf(mixInput(full, 0L, muted = true)), 0L, clipFrames * 1_000_000L / 44100L
        )
        assertTrue(muted.all { it == 0.toShort() })
    }

    @Test
    fun mixSumsOverlappingClipsFromDifferentTracks() {
        val frames = 10
        val output = AudioMixMath.mix(
            inputs = listOf(
                mixInput(levelFrames(0.5f, frames), 0L),
                mixInput(levelFrames(0.5f, frames), 0L)
            ),
            rangeStartUs = 0L,
            rangeEndUs = frames * 1_000_000L / 44100L
        )
        assertEquals(32767, output[0].toInt())
    }

    @Test
    fun mixWithNoClipsIsAllSilence() {
        val output = AudioMixMath.mix(emptyList(), 0L, 1_000_000L)
        assertEquals(44100 * 2, output.size)
        assertTrue(output.all { it == 0.toShort() })
    }

    @Test
    fun mixClipsPartiallyOutsideTheRange() {
        // Clip starts 0.5 s before the range and runs 1 s into it.
        val clipFrames = 44100
        val output = AudioMixMath.mix(
            inputs = listOf(mixInput(levelFrames(0.5f, clipFrames), startUs = -500_000L)),
            rangeStartUs = 0L,
            rangeEndUs = 500_000L
        )
        assertEquals(22050 * 2, output.size)
        assertEquals(16384, output[0].toInt())
        assertEquals(16384, output.last().toInt())
    }
}
