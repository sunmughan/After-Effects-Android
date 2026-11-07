package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {
    private val source = "content://media/audio/1"

    @Test
    fun emptyTimelineHasZeroDuration() {
        assertTrue(AudioTimeline.EMPTY.isEmpty)
        assertEquals(0L, AudioTimeline.EMPTY.durationUs)
    }

    @Test
    fun addClipSortsByStartAndReportsDuration() {
        val timeline = AudioTimeline.EMPTY
            .addClip(source, 10_000_000L, timelineStartUs = 5_000_000L, durationUs = 2_000_000L)
            .addClip(source, 10_000_000L, timelineStartUs = 1_000_000L, durationUs = 1_000_000L)
        assertEquals(1_000_000L, timeline.clips[0].timelineStartUs)
        assertEquals(5_000_000L, timeline.clips[1].timelineStartUs)
        assertEquals(7_000_000L, timeline.durationUs)
    }

    @Test
    fun moveTrimVolumeAndRemoveRoundTrip() {
        val added = AudioTimeline.EMPTY.addClip(source, 10_000_000L, 1_000_000L, durationUs = 4_000_000L)
        val id = added.clips[0].id
        val moved = added.moveClip(id, 2_000_000L)
        assertEquals(2_000_000L, moved.clip(id)!!.timelineStartUs)
        val trimmed = moved.trimClip(id, 1_000_000L, 2_000_000L)
        assertEquals(1_000_000L, trimmed.clip(id)!!.sourceStartUs)
        assertEquals(2_000_000L, trimmed.clip(id)!!.durationUs)
        val quiet = trimmed.setVolume(id, 0.25)
        assertEquals(0.25, quiet.clip(id)!!.volume, 1e-9)
        val faded = quiet.setFades(id, 500_000L, 500_000L)
        assertEquals(500_000L, faded.clip(id)!!.fadeInUs)
        assertTrue(faded.removeClip(id).isEmpty)
        assertNull(faded.removeClip(id).clip(id))
    }

    @Test
    fun clipsAtFindsOnlyCoveringClips() {
        val timeline = AudioTimeline.EMPTY
            .addClip(source, 10_000_000L, 0L, durationUs = 2_000_000L)
            .addClip(source, 10_000_000L, 5_000_000L, durationUs = 2_000_000L)
        assertEquals(1, timeline.clipsAt(1_000_000L).size)
        assertEquals(0, timeline.clipsAt(3_000_000L).size)
        assertEquals(1, timeline.clipsAt(6_000_000L).size)
    }

    @Test
    fun overlappingClipsAreAllowedForLayeredMixing() {
        val timeline = AudioTimeline.EMPTY
            .addClip(source, 10_000_000L, 0L, durationUs = 4_000_000L)
            .addClip(source, 10_000_000L, 2_000_000L, durationUs = 4_000_000L)
        assertEquals(2, timeline.clipsAt(3_000_000L).size)
        assertEquals(6_000_000L, timeline.durationUs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun removeUnknownClipFails() {
        AudioTimeline.EMPTY.removeClip("missing")
    }

    @Test(expected = IllegalArgumentException::class)
    fun addClipPastSourceEndFails() {
        AudioTimeline.EMPTY.addClip(source, 2_000_000L, 0L, durationUs = 3_000_000L)
    }
}
