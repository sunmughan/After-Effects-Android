package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcode decision tests. Written before the decision logic existed:
 * every test here must fail until AudioTranscodeDecision lands.
 *
 * Locked semantics under test:
 * - AAC in MP4 (audio/mp4a-latm) is the only passthrough mime.
 * - WAV, MP3, FLAC and Opus decode through MediaCodec and re-encode to
 *   AAC — they are never silently dropped to video-only.
 * - Anything else is unsupported and fails closed with a reason.
 * - Mime matching is case-insensitive and ignores surrounding space.
 */
class AudioTranscodeDecisionTest {
    @Test
    fun aacPassesThrough() {
        assertEquals(AudioSourceDecision.PASSTHROUGH, AudioTranscodeDecision.decide("audio/mp4a-latm"))
        assertEquals(AudioSourceDecision.PASSTHROUGH, AudioTranscodeDecision.decide("Audio/MP4A-LATM"))
        assertEquals(AudioSourceDecision.PASSTHROUGH, AudioTranscodeDecision.decide("  audio/mp4a-latm  "))
    }

    @Test
    fun wavMp3FlacAndOpusTranscode() {
        assertEquals(AudioSourceDecision.TRANSCODE, AudioTranscodeDecision.decide("audio/x-wav"))
        assertEquals(AudioSourceDecision.TRANSCODE, AudioTranscodeDecision.decide("audio/wav"))
        assertEquals(AudioSourceDecision.TRANSCODE, AudioTranscodeDecision.decide("audio/mpeg"))
        assertEquals(AudioSourceDecision.TRANSCODE, AudioTranscodeDecision.decide("audio/flac"))
        assertEquals(AudioSourceDecision.TRANSCODE, AudioTranscodeDecision.decide("audio/opus"))
    }

    @Test
    fun adtsAacTranscodesInsteadOfPassingThrough() {
        // ADTS AAC is decodable but is not MP4 AAC: it must go through the
        // decode/re-encode path, never the sample-for-sample mux path.
        assertEquals(AudioSourceDecision.TRANSCODE, AudioTranscodeDecision.decide("audio/aac"))
    }

    @Test
    fun unknownMimesAreUnsupported() {
        assertEquals(AudioSourceDecision.UNSUPPORTED, AudioTranscodeDecision.decide("audio/x-ms-wma"))
        assertEquals(AudioSourceDecision.UNSUPPORTED, AudioTranscodeDecision.decide("video/mp4"))
        assertEquals(AudioSourceDecision.UNSUPPORTED, AudioTranscodeDecision.decide(""))
        assertEquals(AudioSourceDecision.UNSUPPORTED, AudioTranscodeDecision.decide("application/octet-stream"))
    }

    @Test
    fun transcodeNeedsTranscodingAndPassthroughDoesNot() {
        assertTrue(AudioTranscodeDecision.requiresTranscode("audio/mpeg"))
        assertTrue(!AudioTranscodeDecision.requiresTranscode("audio/mp4a-latm"))
        assertTrue(!AudioTranscodeDecision.requiresTranscode("audio/x-ms-wma"))
    }

    @Test
    fun describeNamesTheReason() {
        assertTrue(AudioTranscodeDecision.describe("audio/mpeg").contains("transcode", ignoreCase = true))
        assertTrue(AudioTranscodeDecision.describe("audio/mp4a-latm").contains("passthrough", ignoreCase = true))
        assertTrue(AudioTranscodeDecision.describe("audio/x-ms-wma").contains("unsupported", ignoreCase = true))
    }
}
