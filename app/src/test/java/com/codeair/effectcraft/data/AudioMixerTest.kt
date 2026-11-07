package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixerTest {
    private val source = "content://media/audio/1"

    /** Constant-amplitude source: every requested sample is [level]. */
    private fun constantSource(level: Float) = AudioPcmSource { _, _, sampleCount, _, channelCount ->
        FloatArray(sampleCount * channelCount) { level }
    }

    private fun timelineWithClip(
        volume: Double = 1.0,
        fadeInUs: Long = 0L,
        fadeOutUs: Long = 0L,
        timelineStartUs: Long = 0L,
        durationUs: Long = 1_000_000L
    ): AudioTimeline = AudioTimeline.EMPTY.addClip(
        source, 10_000_000L, timelineStartUs, durationUs = durationUs,
        volume = volume, fadeInUs = fadeInUs, fadeOutUs = fadeOutUs
    )

    @Test
    fun mixAppliesClipVolume() {
        val mixed = AudioMixer.mix(
            timelineWithClip(volume = 0.5), 0L, 1_000_000L, 48_000, 2, constantSource(0.5f)
        )
        assertEquals(48_000 * 2, mixed.size)
        assertEquals(0.25f, mixed[0], 1e-6f)
        assertEquals(0.25f, mixed[mixed.size - 1], 1e-6f)
    }

    @Test
    fun mixOfEmptyTimelineIsSilence() {
        val mixed = AudioMixer.mix(AudioTimeline.EMPTY, 0L, 500_000L, 48_000, 2, constantSource(0.9f))
        assertEquals(24_000 * 2, mixed.size)
        assertTrue(AudioMixer.isSilent(mixed))
    }

    @Test
    fun mixOfMovedClipIsSilentOutsideItsWindow() {
        val mixed = AudioMixer.mix(
            timelineWithClip(timelineStartUs = 2_000_000L), 0L, 1_000_000L, 48_000, 2, constantSource(0.5f)
        )
        assertTrue(AudioMixer.isSilent(mixed))
    }

    @Test
    fun overlappingClipsSumAndClampAtFullScale() {
        val timeline = AudioTimeline.EMPTY
            .addClip(source, 10_000_000L, 0L, durationUs = 1_000_000L)
            .addClip(source, 10_000_000L, 0L, durationUs = 1_000_000L)
        val mixed = AudioMixer.mix(timeline, 0L, 1_000_000L, 48_000, 2, constantSource(0.8f))
        assertEquals(1.0f, mixed[0], 1e-6f)
        assertEquals(1.0f, mixed[100], 1e-6f)
    }

    @Test
    fun fadeInRampsFromSilence() {
        val mixed = AudioMixer.mix(
            timelineWithClip(fadeInUs = 1_000_000L), 0L, 1_000_000L, 48_000, 2, constantSource(1.0f)
        )
        assertEquals(0.0f, mixed[0], 1e-6f)
        // Halfway through a 1 s fade-in the gain is ~0.5.
        assertEquals(0.5f, mixed[24_000 * 2], 0.01f)
        assertTrue(mixed[mixed.size - 2] > 0.99f)
    }

    @Test
    fun frameCountRoundsToNearestFrame() {
        assertEquals(48_000, AudioMixer.frameCount(1_000_000L, 48_000))
        assertEquals(24_000, AudioMixer.frameCount(500_000L, 48_000))
        assertEquals(0, AudioMixer.frameCount(0L, 48_000))
    }

    @Test
    fun resampleDoublesFramesAndKeepsEndpoints() {
        val input = floatArrayOf(0f, 0f, 1f, 1f)
        val output = PcmResampler.resampleLinear(input, 2, 2, 24_000, 48_000)
        assertEquals(8, output.size)
        assertEquals(0f, output[0], 1e-6f)
        assertEquals(1f, output[6], 1e-6f)
    }

    @Test
    fun toStereoDuplicatesMonoAndPassesStereoThrough() {
        assertEquals(4, PcmResampler.toStereo(floatArrayOf(0.5f, -0.5f), 1).size)
        val stereo = PcmResampler.toStereo(floatArrayOf(0.5f, -0.5f), 1)
        assertEquals(0.5f, stereo[0], 1e-6f)
        assertEquals(0.5f, stereo[1], 1e-6f)
        assertEquals(-0.5f, stereo[2], 1e-6f)
        val passthrough = PcmResampler.toStereo(floatArrayOf(0.25f, 0.75f), 2)
        assertEquals(0.25f, passthrough[0], 1e-6f)
        assertEquals(0.75f, passthrough[1], 1e-6f)
    }
}
