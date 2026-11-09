package com.codeair.effectcraft.ui

import com.codeair.effectcraft.data.AudioClipDocument
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Batch 2 (P21): the audio preview seek math — source position for a
 * timeline frame — is pure and pinned here. The MediaPlayer itself
 * cannot run on the JVM; the clip selection it depends on is covered
 * by AudioUnificationTest.audioClipAtPlayhead.
 */
class DraftAudioPlayerTest {

    @Test
    fun sourceMsAddsClipOffsetToSourceStart() {
        val clip = AudioClipDocument.covering(
            startFrame = 30,
            endFrame = 90,
            sourceStartFrame = 60,
            sourceEndFrame = 120,
            sourceLengthFrames = 120,
            sourcePath = "/audio/test.wav",
            sourceMime = "audio/wav",
            sourceDurationUs = 4_000_000L
        )
        // Frame 45 is 15 frames into the clip; source starts at frame 60.
        assertEquals(2500, audioPreviewSourceMs(clip, 45, 30))
        assertEquals(2000, audioPreviewSourceMs(clip, 30, 30))
    }

    @Test
    fun sourceMsClampsFramesBeforeTheClipToItsStart() {
        val clip = AudioClipDocument.covering(
            startFrame = 30,
            endFrame = 90,
            sourceStartFrame = 60,
            sourceEndFrame = 120,
            sourceLengthFrames = 120,
            sourcePath = "/audio/test.wav",
            sourceMime = "audio/wav",
            sourceDurationUs = 4_000_000L
        )
        assertEquals(2000, audioPreviewSourceMs(clip, 0, 30))
    }
}
