package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioClipTest {
    private fun clip(
        timelineStartUs: Long = 1_000_000L,
        sourceStartUs: Long = 0L,
        durationUs: Long = 2_000_000L,
        volume: Double = 1.0,
        fadeInUs: Long = 0L,
        fadeOutUs: Long = 0L
    ) = AudioClip(
        id = "clip-1",
        sourceUri = "content://media/audio/1",
        sourceDurationUs = 5_000_000L,
        timelineStartUs = timelineStartUs,
        sourceStartUs = sourceStartUs,
        durationUs = durationUs,
        volume = volume,
        fadeInUs = fadeInUs,
        fadeOutUs = fadeOutUs
    )

    @Test
    fun coversOnlyItsTimelineWindow() {
        val c = clip()
        assertFalse(c.covers(999_999L))
        assertTrue(c.covers(1_000_000L))
        assertTrue(c.covers(2_999_999L))
        assertFalse(c.covers(3_000_000L))
        assertEquals(3_000_000L, c.timelineEndUs)
    }

    @Test
    fun gainIsVolumeInsideAndZeroOutside() {
        val c = clip(volume = 0.5)
        assertEquals(0.0, c.gainAt(0L), 0.0)
        assertEquals(0.5, c.gainAt(1_500_000L), 1e-9)
        assertEquals(0.0, c.gainAt(3_000_000L), 0.0)
    }

    @Test
    fun fadesRampLinearlyFromAndToSilence() {
        val c = clip(fadeInUs = 1_000_000L, fadeOutUs = 1_000_000L)
        assertEquals(0.0, c.gainAt(1_000_000L), 1e-9)
        assertEquals(0.5, c.gainAt(1_500_000L), 1e-9)
        assertEquals(1.0, c.gainAt(2_000_000L), 1e-9)
        assertEquals(0.5, c.gainAt(2_500_000L), 1e-9)
        assertEquals(0.0, c.gainAt(2_999_999L), 1e-3)
    }

    @Test
    fun moveTrimAndVolumeReturnNewClips() {
        val moved = clip().movedTo(4_000_000L)
        assertEquals(4_000_000L, moved.timelineStartUs)
        assertEquals(1_000_000L, clip().timelineStartUs)
        val trimmed = clip().trimmed(500_000L, 1_000_000L)
        assertEquals(500_000L, trimmed.sourceStartUs)
        assertEquals(1_000_000L, trimmed.durationUs)
        assertEquals(0.25, clip().withVolume(0.25).volume, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWindowPastSourceEnd() {
        clip(sourceStartUs = 4_000_000L, durationUs = 2_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsVolumeAboveOne() {
        clip(volume = 1.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFadeLongerThanClip() {
        clip(fadeInUs = 3_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeTimelineStart() {
        clip(timelineStartUs = -1L)
    }
}
