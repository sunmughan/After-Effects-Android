package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackSelectionTest {
    @Test
    fun selectsFirstAacTrackAndRejectsAllOtherCodecs() {
        val candidates = listOf(
            AudioCandidate("audio/mpeg", 44100, 2),
            AudioCandidate("audio/mp4a-latm", 48000, 2),
            AudioCandidate("audio/mp4a-latm", 44100, 1)
        )
        assertEquals(1, AudioTrackSelection.selectPassthroughIndex(candidates))
        assertNull(
            AudioTrackSelection.selectPassthroughIndex(
                listOf(AudioCandidate("audio/mpeg", 44100, 2), AudioCandidate("audio/opus", 48000, 2))
            )
        )
        assertNull(AudioTrackSelection.selectPassthroughIndex(emptyList()))
    }

    @Test
    fun shiftsSampleTimestampsIntoExportRangeAndClipsOutsideSamples() {
        val startUs = 1_000_000L
        val endUs = 2_000_000L
        assertNull(AudioTrackSelection.shiftedPts(999_999L, startUs, endUs))
        assertEquals(0L, AudioTrackSelection.shiftedPts(1_000_000L, startUs, endUs))
        assertEquals(500_000L, AudioTrackSelection.shiftedPts(1_500_000L, startUs, endUs))
        assertNull(AudioTrackSelection.shiftedPts(2_000_000L, startUs, endUs))
        assertNull(AudioTrackSelection.shiftedPts(3_000_000L, startUs, endUs))
    }

    @Test
    fun passthroughMimeIsAacOnly() {
        assertEquals("audio/mp4a-latm", AudioTrackSelection.PASSTHROUGH_MIME)
    }
}

    @Test
    fun everyAudioStatusExplainsTheActualExportOutcome() {
        assertEquals("with audio", AudioStatus.INCLUDED.describe())
        assertEquals(
            "video-only — composition has no audio",
            AudioStatus.NO_AUDIO_IN_COMPOSITION.describe()
        )
        assertEquals(
            "video-only — audio file unavailable",
            AudioStatus.AUDIO_FILE_UNAVAILABLE.describe()
        )
        assertEquals(
            "video-only — no compatible AAC audio track",
            AudioStatus.AUDIO_TRACK_UNSUPPORTED.describe()
        )
    }
}
