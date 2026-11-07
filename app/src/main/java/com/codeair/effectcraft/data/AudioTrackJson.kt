package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for a project's audio tracks — the single place that
 * decides the audio field names, shared by the draft store and the
 * recovery journal so the two can never drift apart. Additive only:
 * documents written before audio existed carry no "audioTracks" key
 * and decode to an empty list.
 *
 * The nested clip is encoded through [ClipJson], so the clip field
 * names are decided in exactly one place.
 */
object AudioTrackJson {
    fun encode(tracks: List<AudioTrackDocument>): JSONArray = JSONArray().apply {
        tracks.forEach { track ->
            put(JSONObject().apply {
                put("id", track.id)
                put("name", track.name)
                put("clips", JSONArray().apply {
                    track.clips.forEach { audioClip ->
                        put(JSONObject().apply {
                            put("clip", ClipJson.encode(listOf(audioClip.clip)).getJSONObject(0))
                            put("sourcePath", audioClip.sourcePath)
                            put("sourceMime", audioClip.sourceMime)
                            put("sourceDurationUs", audioClip.sourceDurationUs)
                            put("gain", audioClip.gain.toDouble())
                            put("fadeInFrames", audioClip.fadeInFrames)
                            put("fadeOutFrames", audioClip.fadeOutFrames)
                            put("muted", audioClip.muted)
                        })
                    }
                })
            })
        }
    }

    /** Missing or null array means a pre-audio document: no audio tracks. */
    fun decode(json: JSONArray?): List<AudioTrackDocument> {
        if (json == null) return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                val track = json.getJSONObject(index)
                val clipsJson = track.optJSONArray("clips") ?: JSONArray()
                add(
                    AudioTrackDocument(
                        id = track.getString("id"),
                        name = track.getString("name"),
                        clips = buildList {
                            for (clipIndex in 0 until clipsJson.length()) {
                                val audioClip = clipsJson.getJSONObject(clipIndex)
                                val clipJson = audioClip.getJSONObject("clip")
                                val clip = ClipJson.decode(JSONArray().put(clipJson)).single()
                                add(
                                    AudioClipDocument(
                                        clip = clip,
                                        sourcePath = audioClip.getString("sourcePath"),
                                        sourceMime = audioClip.getString("sourceMime"),
                                        sourceDurationUs = audioClip.getLong("sourceDurationUs"),
                                        gain = audioClip.optDouble("gain", 1.0).toFloat(),
                                        fadeInFrames = audioClip.optInt("fadeInFrames", 0),
                                        fadeOutFrames = audioClip.optInt("fadeOutFrames", 0),
                                        muted = audioClip.optBoolean("muted", false)
                                    )
                                )
                            }
                        }
                    )
                )
            }
        }
    }
}
