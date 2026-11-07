package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for one layer's clips — the single place that decides the
 * clip field names, shared by the draft store and the recovery journal
 * so the two can never drift apart. Additive only: documents written
 * before clips existed carry no "clips" key and decode to an empty list.
 */
object ClipJson {
    fun encode(clips: List<ClipDocument>): JSONArray = JSONArray().apply {
        clips.forEach { clip ->
            put(JSONObject().apply {
                put("id", clip.id)
                put("startFrame", clip.startFrame)
                put("endFrame", clip.endFrame)
                put("sourceStartFrame", clip.sourceStartFrame)
                put("sourceEndFrame", clip.sourceEndFrame)
                put("sourceLengthFrames", clip.sourceLengthFrames)
            })
        }
    }

    /** Missing or null array means a pre-clip document: no clips. */
    fun decode(json: JSONArray?): List<ClipDocument> {
        if (json == null) return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                val clip = json.getJSONObject(index)
                add(
                    ClipDocument(
                        id = clip.getString("id"),
                        startFrame = clip.getInt("startFrame"),
                        endFrame = clip.getInt("endFrame"),
                        sourceStartFrame = clip.getInt("sourceStartFrame"),
                        sourceEndFrame = clip.getInt("sourceEndFrame"),
                        sourceLengthFrames = clip.getInt("sourceLengthFrames")
                    )
                )
            }
        }
    }
}
