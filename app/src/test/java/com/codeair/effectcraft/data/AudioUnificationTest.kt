package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 2 (P15): the editor has ONE audio model — the frame-based
 * [AudioClipDocument]s in [ProjectDocument.audioTracks]. These tests pin
 * the pure helpers that unification is built on: placing a new clip on a
 * non-overlapping track, moving a clip in frames, finding the clip under
 * the playhead, converting project clips to the microsecond
 * [AudioTimeline] the export mixer still consumes, and migrating a
 * legacy stored [AudioClip] into the unified model.
 */
class AudioUnificationTest {

    private fun clip(
        startFrame: Int,
        endFrame: Int,
        gain: Float = 1f,
        muted: Boolean = false,
        fadeInFrames: Int = 0,
        fadeOutFrames: Int = 0
    ): AudioClipDocument = AudioClipDocument.covering(
        startFrame = startFrame,
        endFrame = endFrame,
        sourceStartFrame = 0,
        sourceEndFrame = endFrame - startFrame,
        sourceLengthFrames = endFrame - startFrame,
        sourcePath = "/audio/test.wav",
        sourceMime = "audio/wav",
        sourceDurationUs = ((endFrame - startFrame).toLong() * 1_000_000L) / 30L,
        gain = gain,
        muted = muted
    ).let {
        // covering() builds gain/fades as defaults; apply the requested ones.
        it.copy(gain = gain, fadeInFrames = fadeInFrames, fadeOutFrames = fadeOutFrames, muted = muted)
    }

    @Test
    fun placeClipCreatesAudio1WhenNoTracksExist() {
        val placed = AudioClipTimeline.placeClip(emptyList(), clip(0, 30))
        assertEquals(1, placed.size)
        assertEquals("Audio 1", placed[0].name)
        assertEquals(1, placed[0].clips.size)
    }

    @Test
    fun placeClipReusesFirstTrackWithoutOverlap() {
        val first = AudioTrackDocument.named("Audio 1").copy(clips = listOf(clip(0, 30)))
        val placed = AudioClipTimeline.placeClip(listOf(first), clip(30, 60))
        assertEquals(1, placed.size)
        assertEquals(2, placed[0].clips.size)
    }

    @Test
    fun placeClipOpensNewTrackWhenFirstTrackOverlaps() {
        val first = AudioTrackDocument.named("Audio 1").copy(clips = listOf(clip(0, 60)))
        val placed = AudioClipTimeline.placeClip(listOf(first), clip(30, 90))
        assertEquals(2, placed.size)
        assertEquals("Audio 2", placed[1].name)
        assertEquals(1, placed[1].clips.size)
    }

    @Test
    fun placeClipNamesNewTrackAfterHighestExistingNumber() {
        val first = AudioTrackDocument.named("Audio 1").copy(clips = listOf(clip(0, 60)))
        val third = AudioTrackDocument.named("Audio 3").copy(clips = listOf(clip(0, 60)))
        val placed = AudioClipTimeline.placeClip(listOf(first, third), clip(30, 90))
        assertEquals(3, placed.size)
        assertEquals("Audio 4", placed[2].name)
    }

    @Test
    fun moveAudioClipShiftsTimelineWindowKeepingSource() {
        val original = clip(10, 40)
        val outcome = AudioClipTimeline.moveAudioClip(listOf(original), original.id, 50, 150)
        assertTrue(outcome is AudioClipEditOutcome.Changed)
        val moved = (outcome as AudioClipEditOutcome.Changed).clips.single()
        assertEquals(50, moved.clip.startFrame)
        assertEquals(80, moved.clip.endFrame)
        assertEquals(0, moved.clip.sourceStartFrame)
        assertEquals(30, moved.clip.sourceEndFrame)
    }

    @Test
    fun moveAudioClipRefusesOutOfBoundsMove() {
        val original = clip(10, 40)
        val outcome = AudioClipTimeline.moveAudioClip(listOf(original), original.id, 130, 150)
        assertTrue(outcome is AudioClipEditOutcome.Unchanged)
    }

    @Test
    fun moveAudioClipRefusesOverlappingMove() {
        val first = clip(0, 30)
        val second = clip(40, 70)
        val outcome = AudioClipTimeline.moveAudioClip(listOf(first, second), second.id, 20, 150)
        assertTrue(outcome is AudioClipEditOutcome.Unchanged)
    }

    @Test
    fun audioClipAtPlayheadFindsCoveringClipAcrossTracks() {
        val tracks = listOf(
            AudioTrackDocument.named("Audio 1").copy(clips = listOf(clip(0, 30))),
            AudioTrackDocument.named("Audio 2").copy(clips = listOf(clip(30, 60)))
        )
        assertEquals(30, AudioClipTimeline.audioClipAtPlayhead(tracks, 45)?.clip?.startFrame)
        assertNull(AudioClipTimeline.audioClipAtPlayhead(tracks, 60))
        assertNull(AudioClipTimeline.audioClipAtPlayhead(tracks, -1))
    }

    @Test
    fun fromAudioTracksConvertsFramesToMicroseconds() {
        val tracks = listOf(
            AudioTrackDocument.named("Audio 1").copy(
                clips = listOf(clip(30, 90, gain = 0.5f, fadeInFrames = 15, fadeOutFrames = 15))
            )
        )
        val timeline = AudioTimeline.fromAudioTracks(tracks, frameRateNum = 30, frameRateDen = 1)
        assertEquals(1, timeline.clips.size)
        val converted = timeline.clips.single()
        assertEquals(1_000_000L, converted.timelineStartUs)
        assertEquals(2_000_000L, converted.durationUs)
        assertEquals(0L, converted.sourceStartUs)
        assertEquals(0.5, converted.volume, 0.0)
        assertEquals(500_000L, converted.fadeInUs)
        assertEquals(500_000L, converted.fadeOutUs)
    }

    @Test
    fun fromAudioTracksSkipsMutedClipsAndClampsGain() {
        val tracks = listOf(
            AudioTrackDocument.named("Audio 1").copy(
                clips = listOf(
                    clip(0, 30, gain = 2f),
                    clip(30, 60, muted = true)
                )
            )
        )
        val timeline = AudioTimeline.fromAudioTracks(tracks, frameRateNum = 30, frameRateDen = 1)
        assertEquals(1, timeline.clips.size)
        assertEquals(1.0, timeline.clips.single().volume, 0.0)
    }

    @Test
    fun fromAudioTracksIsEmptyWithoutClips() {
        assertTrue(AudioTimeline.fromAudioTracks(emptyList(), 30, 1).isEmpty)
        assertTrue(
            AudioTimeline.fromAudioTracks(
                listOf(AudioTrackDocument.named("Audio 1")), 30, 1
            ).isEmpty
        )
    }

    @Test
    fun migratedClipMapsMicrosecondsToFrames() {
        val legacy = AudioClip(
            id = "legacy-1",
            sourceUri = "content://media/audio/7",
            sourceDurationUs = 2_000_000L,
            timelineStartUs = 1_000_000L,
            sourceStartUs = 0L,
            durationUs = 2_000_000L,
            volume = 0.5
        )
        val migrated = AudioClipTimeline.migratedClip(
            legacy, frameRate = 30, durationFrames = 150,
            sourcePath = "/audio/migrated.wav", sourceMime = "audio/wav"
        )
        assertNotNull(migrated)
        migrated!!
        assertEquals("legacy-1", migrated.id)
        assertEquals(30, migrated.clip.startFrame)
        assertEquals(90, migrated.clip.endFrame)
        assertEquals(0, migrated.clip.sourceStartFrame)
        assertEquals(60, migrated.clip.sourceEndFrame)
        assertEquals(60, migrated.clip.sourceLengthFrames)
        assertEquals(0.5f, migrated.gain)
    }

    @Test
    fun migratedClipIsNullWhenItEndsBeforeFrameZero() {
        val legacy = AudioClip(
            id = "legacy-2",
            sourceUri = "content://media/audio/8",
            sourceDurationUs = 2_000_000L,
            timelineStartUs = 5_000_000L,
            sourceStartUs = 0L,
            durationUs = 1_000_000L
        )
        assertNull(
            AudioClipTimeline.migratedClip(
                legacy, frameRate = 30, durationFrames = 150,
                sourcePath = "/audio/migrated.wav", sourceMime = "audio/wav"
            )
        )
    }

    private fun assertNotNull(value: Any?) {
        assertTrue(value != null)
    }
}
