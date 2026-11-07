package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audio track JSON codec tests. Written before the codec existed: every
 * test here must fail until AudioTrackJson lands.
 *
 * Locked semantics under test:
 * - Round-trip is exact: every field, including gain, fades, mute and
 *   the nested clip's source in/out, survives encode/decode.
 * - A document written before audio existed carries no "audioTracks"
 *   key and decodes to an empty list — the codec is additive only.
 * - The nested clip uses exactly the ClipJson field names, so the two
 *   codecs can never drift apart.
 */
class AudioTrackJsonTest {
    private fun track(): AudioTrackDocument = AudioTrackDocument(
        id = "track-1",
        name = "Voiceover",
        clips = listOf(
            AudioClipDocument(
                clip = ClipDocument(
                    id = "clip-1", startFrame = 10, endFrame = 70,
                    sourceStartFrame = 5, sourceEndFrame = 65, sourceLengthFrames = 200
                ),
                sourcePath = "/audio/voice.wav",
                sourceMime = "audio/x-wav",
                sourceDurationUs = 6_666_666L,
                gain = 1.25f,
                fadeInFrames = 6,
                fadeOutFrames = 12,
                muted = false
            ),
            AudioClipDocument(
                clip = ClipDocument(
                    id = "clip-2", startFrame = 80, endFrame = 100,
                    sourceStartFrame = 0, sourceEndFrame = 20, sourceLengthFrames = 90
                ),
                sourcePath = "/audio/music.mp3",
                sourceMime = "audio/mpeg",
                sourceDurationUs = 3_000_000L,
                gain = 0.5f,
                fadeInFrames = 0,
                fadeOutFrames = 0,
                muted = true
            )
        )
    )

    @Test
    fun roundTripPreservesEveryField() {
        val tracks = listOf(track())
        val decoded = AudioTrackJson.decode(AudioTrackJson.encode(tracks))
        assertEquals(tracks, decoded)
    }

    @Test
    fun emptyTracksRoundTripToEmpty() {
        assertEquals(emptyList<AudioTrackDocument>(), AudioTrackJson.decode(AudioTrackJson.encode(emptyList())))
        assertEquals(emptyList<AudioTrackDocument>(), AudioTrackJson.decode(JSONArray()))
    }

    @Test
    fun missingArrayMeansPreAudioDocument() {
        assertEquals(emptyList<AudioTrackDocument>(), AudioTrackJson.decode(null))
    }

    @Test
    fun nestedClipUsesClipJsonFieldNames() {
        val clipJson = AudioTrackJson.encode(listOf(track()))
            .getJSONObject(0).getJSONArray("clips").getJSONObject(0)
            .getJSONObject("clip")
        assertEquals(10, clipJson.getInt("startFrame"))
        assertEquals(70, clipJson.getInt("endFrame"))
        assertEquals(5, clipJson.getInt("sourceStartFrame"))
        assertEquals(65, clipJson.getInt("sourceEndFrame"))
        assertEquals(200, clipJson.getInt("sourceLengthFrames"))
        assertEquals("clip-1", clipJson.getString("id"))
    }

    @Test
    fun clipJsonCodecAndAudioCodecAgreeOnAClip() {
        val clip = track().clips.first().clip
        val viaClipJson = ClipJson.decode(JSONArray().put(ClipJson.encode(listOf(clip)).getJSONObject(0))).single()
        val viaAudioJson = AudioTrackJson.decode(AudioTrackJson.encode(listOf(track()))).single().clips.first().clip
        assertEquals(viaClipJson, viaAudioJson)
    }

    @Test
    fun audioFieldsAreStoredAtTheClipLevel() {
        val clipJson = AudioTrackJson.encode(listOf(track()))
            .getJSONObject(0).getJSONArray("clips").getJSONObject(0)
        assertEquals("/audio/voice.wav", clipJson.getString("sourcePath"))
        assertEquals("audio/x-wav", clipJson.getString("sourceMime"))
        assertEquals(6_666_666L, clipJson.getLong("sourceDurationUs"))
        assertEquals(1.25, clipJson.getDouble("gain"), 0.001)
        assertEquals(6, clipJson.getInt("fadeInFrames"))
        assertEquals(12, clipJson.getInt("fadeOutFrames"))
        assertEquals(false, clipJson.getBoolean("muted"))
        assertEquals(true, AudioTrackJson.encode(listOf(track()))
            .getJSONObject(0).getJSONArray("clips").getJSONObject(1).getBoolean("muted"))
    }

    @Test
    fun oldDraftWithoutAudioTracksStillDecodes() {
        // A pre-audio draft layer object has no audioTracks key at all.
        val layer = JSONObject().apply {
            put("id", "layer-1")
            put("name", "Background")
            put("kind", "Footage")
            put("opacity", 1.0)
            put("tint", 0xFF56647AL)
            put("visible", true)
            put("clips", ClipJson.encode(listOf(ClipDocument.fullRange(150))))
        }
        assertTrue(layer.optJSONArray("audioTracks") == null)
        assertEquals(emptyList<AudioTrackDocument>(), AudioTrackJson.decode(layer.optJSONArray("audioTracks")))
    }
}
