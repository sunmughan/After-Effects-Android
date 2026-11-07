package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure audio clip/track model tests. Written before the model existed:
 * every test here must fail until AudioClipDocument, AudioTrackDocument
 * and AudioTimeline land.
 *
 * Locked semantics under test:
 * - Gain is 0..2 (0 silent, 1 unity, 2 is +6 dB). Non-finite or
 *   out-of-range gain is invalid, never clamped silently.
 * - Fades are in composition frames; fadeIn + fadeOut never exceeds the
 *   clip length. A trim clamps the fades to the new length (fade-in
 *   first, fade-out to the remainder).
 * - A split keeps the fade-in on the first half and the fade-out on the
 *   second half (each clamped to its half); the ramp never survives on
 *   the wrong side of the cut.
 * - No two audio clips overlap on the same track; adjacency is allowed.
 */
class AudioTimelineTest {
    private fun clip(
        start: Int = 0,
        end: Int = 100,
        sourceStart: Int = 0,
        sourceEnd: Int = 100,
        sourceLength: Int = 100,
        id: String = "audio-1",
        gain: Float = 1f,
        fadeIn: Int = 0,
        fadeOut: Int = 0,
        muted: Boolean = false
    ): AudioClipDocument = AudioClipDocument(
        clip = ClipDocument(
            id = id,
            startFrame = start,
            endFrame = end,
            sourceStartFrame = sourceStart,
            sourceEndFrame = sourceEnd,
            sourceLengthFrames = sourceLength
        ),
        sourcePath = "/audio/take1.wav",
        sourceMime = "audio/x-wav",
        sourceDurationUs = 3_333_333L,
        gain = gain,
        fadeInFrames = fadeIn,
        fadeOutFrames = fadeOut,
        muted = muted
    )

    private fun changed(outcome: AudioClipEditOutcome): List<AudioClipDocument> {
        assertTrue("Expected a change, got $outcome", outcome is AudioClipEditOutcome.Changed)
        return (outcome as AudioClipEditOutcome.Changed).clips
    }

    private fun unchangedReason(outcome: AudioClipEditOutcome): String {
        assertTrue("Expected no change, got $outcome", outcome is AudioClipEditOutcome.Unchanged)
        return (outcome as AudioClipEditOutcome.Unchanged).reason
    }

    @Test
    fun validClipPassesValidation() {
        assertTrue(AudioTimeline.validateAudioClips(listOf(clip()), 100).isEmpty())
        assertTrue(clip().validationErrors(100).isEmpty())
    }

    @Test
    fun gainRangeIsEnforced() {
        assertTrue(clip(gain = -0.1f).validationErrors(100).isNotEmpty())
        assertTrue(clip(gain = 2.01f).validationErrors(100).isNotEmpty())
        assertTrue(clip(gain = Float.NaN).validationErrors(100).isNotEmpty())
        assertTrue(clip(gain = 0f).validationErrors(100).isEmpty())
        assertTrue(clip(gain = 2f).validationErrors(100).isEmpty())
    }

    @Test
    fun fadesMustFitInsideTheClip() {
        assertTrue(clip(fadeIn = -1).validationErrors(100).isNotEmpty())
        assertTrue(clip(fadeOut = -1).validationErrors(100).isNotEmpty())
        assertTrue(clip(fadeIn = 60, fadeOut = 41).validationErrors(100).isNotEmpty())
        assertTrue(clip(fadeIn = 60, fadeOut = 40).validationErrors(100).isEmpty())
        assertTrue(clip(fadeIn = 100).validationErrors(100).isEmpty())
    }

    @Test
    fun blankSourceAndZeroSourceDurationAreInvalid() {
        assertTrue(clip().copy(sourcePath = "  ").validationErrors(100).isNotEmpty())
        assertTrue(clip().copy(sourceMime = "").validationErrors(100).isNotEmpty())
        assertTrue(clip().copy(sourceDurationUs = 0L).validationErrors(100).isNotEmpty())
    }

    @Test
    fun overlappingAudioClipsOnOneTrackAreInvalid() {
        val overlapping = listOf(clip(0, 60, sourceEnd = 60, id = "a"), clip(50, 100, sourceStart = 50, id = "b"))
        assertTrue(AudioTimeline.validateAudioClips(overlapping, 100).isNotEmpty())
        val adjacent = listOf(clip(0, 50, sourceEnd = 50, id = "a"), clip(50, 100, sourceStart = 50, id = "b"))
        assertTrue(AudioTimeline.validateAudioClips(adjacent, 100).isEmpty())
    }

    @Test
    fun duplicateAudioClipIdsAreInvalid() {
        val dupes = listOf(clip(0, 50, sourceEnd = 50, id = "same"), clip(50, 100, sourceStart = 50, id = "same"))
        assertTrue(AudioTimeline.validateAudioClips(dupes, 100).isNotEmpty())
    }

    @Test
    fun trimShiftsSourceAndClampsFades() {
        val original = clip(0, 100, fadeIn = 20, fadeOut = 20)
        // Trim the end to frame 30: the clip is 30 long, so the 20 + 20
        // fades no longer fit — fade-in keeps 20, fade-out clamps to 10.
        val trimmed = changed(AudioTimeline.trimAudioClip(listOf(original), "audio-1", null, 30, 100)).single()
        assertEquals(0, trimmed.clip.startFrame)
        assertEquals(30, trimmed.clip.endFrame)
        assertEquals(30, trimmed.clip.sourceEndFrame)
        assertEquals(20, trimmed.fadeInFrames)
        assertEquals(10, trimmed.fadeOutFrames)
        // Trim the end to frame 10: fade-in (20) is clamped to 10, fade-out to 0.
        val hard = changed(AudioTimeline.trimAudioClip(listOf(original), "audio-1", null, 10, 100)).single()
        assertEquals(10, hard.fadeInFrames)
        assertEquals(0, hard.fadeOutFrames)
        // Trim the start to frame 90: source in-point shifts by the same delta.
        val startTrimmed = changed(AudioTimeline.trimAudioClip(listOf(original), "audio-1", 90, null, 100)).single()
        assertEquals(90, startTrimmed.clip.sourceStartFrame)
        assertEquals(10, startTrimmed.fadeInFrames)
        assertEquals(0, startTrimmed.fadeOutFrames)
    }

    @Test
    fun trimRefusalsMirrorClipTrimRefusals() {
        val original = clip(20, 80)
        assertTrue(unchangedReason(AudioTimeline.trimAudioClip(listOf(original), "audio-1", 90, null, 100)).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.trimAudioClip(listOf(original), "missing", 30, null, 100)).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.trimAudioClip(listOf(original), "audio-1", 20, 80, 100)).isNotBlank())
    }

    @Test
    fun splitKeepsFadeInOnFirstHalfAndFadeOutOnSecond() {
        val original = clip(0, 100, fadeIn = 20, fadeOut = 20)
        val split = changed(
            AudioTimeline.splitAudioClip(listOf(original), "audio-1", 50, 100) { "audio-2" }
        )
        assertEquals(2, split.size)
        val first = split[0]
        val second = split[1]
        assertEquals("audio-1", first.id)
        assertEquals(0, first.clip.startFrame)
        assertEquals(50, first.clip.endFrame)
        assertEquals(50, first.clip.sourceEndFrame)
        assertEquals(20, first.fadeInFrames)
        assertEquals(0, first.fadeOutFrames)
        assertEquals("audio-2", second.id)
        assertEquals(50, second.clip.startFrame)
        assertEquals(100, second.clip.endFrame)
        assertEquals(50, second.clip.sourceStartFrame)
        assertEquals(0, second.fadeInFrames)
        assertEquals(20, second.fadeOutFrames)
        assertEquals(1f, second.gain, 0f)
        assertEquals(original.sourcePath, second.sourcePath)
        assertTrue(AudioTimeline.validateAudioClips(split, 100).isEmpty())
    }

    @Test
    fun splitAtEdgeIsARefusal() {
        val original = clip(0, 100)
        assertTrue(unchangedReason(AudioTimeline.splitAudioClip(listOf(original), "audio-1", 0, 100) { "x" }).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.splitAudioClip(listOf(original), "audio-1", 100, 100) { "x" }).isNotBlank())
    }

    @Test
    fun gainFadeAndMuteEditsArePureAndValidated() {
        val original = clip()
        val louder = changed(AudioTimeline.setGain(listOf(original), "audio-1", 1.5f)).single()
        assertEquals(1.5f, louder.gain, 0f)
        assertEquals(1f, original.gain, 0f)
        assertTrue(unchangedReason(AudioTimeline.setGain(listOf(original), "audio-1", 3f)).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.setGain(listOf(original), "audio-1", Float.NaN)).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.setGain(listOf(original), "audio-1", 1f)).isNotBlank())

        val faded = changed(AudioTimeline.setFades(listOf(original), "audio-1", 10, 15)).single()
        assertEquals(10, faded.fadeInFrames)
        assertEquals(15, faded.fadeOutFrames)
        assertTrue(unchangedReason(AudioTimeline.setFades(listOf(original), "audio-1", 60, 41)).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.setFades(listOf(original), "audio-1", -1, 0)).isNotBlank())

        val muted = changed(AudioTimeline.setMuted(listOf(original), "audio-1", true)).single()
        assertTrue(muted.muted)
        assertFalse(original.muted)
        assertTrue(unchangedReason(AudioTimeline.setMuted(listOf(original), "audio-1", false)).isNotBlank())
        assertTrue(unchangedReason(AudioTimeline.setGain(listOf(original), "missing", 1.5f)).isNotBlank())
    }

    @Test
    fun clipAtFindsTheCoveringAudioClip() {
        val clips = listOf(clip(0, 50, id = "a"), clip(60, 100, id = "b"))
        assertEquals("a", AudioTimeline.clipAt(clips, 10)?.id)
        assertEquals(null, AudioTimeline.clipAt(clips, 55))
        assertEquals("b", AudioTimeline.clipAt(clips, 99)?.id)
    }

    @Test
    fun clampToDurationTrimsAndDropsLikeVideoClips() {
        val clips = listOf(clip(0, 100, fadeIn = 20, fadeOut = 20), clip(120, 150, sourceEnd = 30, id = "late"))
        val clamped = AudioTimeline.clampToDuration(clips, 60)
        assertEquals(1, clamped.size)
        assertEquals(60, clamped.single().clip.endFrame)
        assertEquals(20, clamped.single().fadeInFrames)
        assertEquals(20, clamped.single().fadeOutFrames)
        assertTrue(AudioTimeline.clampToDuration(clips, 0).isEmpty())
    }

    @Test
    fun audioTrackValidationCoversNameAndClips() {
        val track = AudioTrackDocument(id = "track-1", name = "Voiceover", clips = listOf(clip()))
        assertTrue(track.validationErrors(100).isEmpty())
        assertTrue(track.copy(name = "  ").validationErrors(100).isNotEmpty())
        assertTrue(
            track.copy(clips = listOf(clip(0, 60, id = "a"), clip(50, 100, id = "b")))
                .validationErrors(100).isNotEmpty()
        )
    }

    @Test
    fun projectValidationIncludesAudioTracks() {
        val project = ProjectDocument.starter()
        assertTrue(project.validationErrors().isEmpty())
        val bad = project.copy(
            audioTracks = listOf(
                AudioTrackDocument(
                    id = "t1", name = "Music",
                    clips = listOf(clip(0, 100, gain = 5f))
                )
            )
        )
        assertTrue(bad.validationErrors().isNotEmpty())
        val good = project.copy(
            audioTracks = listOf(
                AudioTrackDocument(id = "t1", name = "Music", clips = listOf(clip(0, 100)))
            )
        )
        assertTrue(good.validationErrors().isEmpty())
    }
}
