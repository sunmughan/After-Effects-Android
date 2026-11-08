package com.codeair.effectcraft.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Batch 2 (P15): one-shot reader for the retired per-project
 * [AudioTimeline] store files (`audio-timelines/<sha256(projectId)>.json`,
 * format `effectcraft-audio-timeline` v1). The editor's one audio model is
 * now the frame-based clips in [ProjectDocument.audioTracks]; this exists
 * only so clips a user added under the old model are migrated into it
 * instead of being silently dropped. It never writes: after a migration
 * attempt the file is cleared, and an empty/missing file migrates
 * nothing. This is not a second model — it is a file-format reader with
 * a delete, and it goes away once the migration has run everywhere.
 */
object LegacyAudioTimelineMigration {

    /**
     * Reads the legacy timeline for [projectId], or [AudioTimeline.EMPTY]
     * when no file exists. Throws [IOException] on an unreadable or
     * malformed file so the caller can report it instead of guessing.
     */
    @Throws(IOException::class)
    fun load(root: File, projectId: String): AudioTimeline {
        val file = fileFor(root, projectId)
        if (!file.isFile) return AudioTimeline.EMPTY
        try {
            val document = JSONObject(file.readText(Charsets.UTF_8))
            require(document.optString("format") == "effectcraft-audio-timeline") {
                "Unsupported audio timeline format"
            }
            require(document.optInt("version", -1) == 1) { "Unsupported audio timeline version" }
            require(document.getString("projectId") == projectId) {
                "Audio timeline belongs to another project"
            }
            val clipsJson = document.getJSONArray("clips")
            val clips = buildList {
                for (index in 0 until clipsJson.length()) {
                    val entry = clipsJson.getJSONObject(index)
                    add(
                        AudioClip(
                            id = entry.getString("id"),
                            sourceUri = entry.getString("sourceUri"),
                            sourceDurationUs = entry.getLong("sourceDurationUs"),
                            timelineStartUs = entry.getLong("timelineStartUs"),
                            sourceStartUs = entry.getLong("sourceStartUs"),
                            durationUs = entry.getLong("durationUs"),
                            volume = entry.optDouble("volume", 1.0),
                            fadeInUs = entry.optLong("fadeInUs", 0L),
                            fadeOutUs = entry.optLong("fadeOutUs", 0L)
                        )
                    )
                }
            }
            return AudioTimeline(clips.sortedWith(compareBy({ it.timelineStartUs }, { it.id })))
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException(
                "Unable to read legacy audio timeline: ${failure.message ?: "invalid document"}",
                failure
            )
        }
    }

    /** Deletes the legacy file for [projectId]; false when nothing was there. */
    fun clear(root: File, projectId: String): Boolean {
        val file = fileFor(root, projectId)
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("Unable to remove legacy audio timeline")
        return true
    }

    private fun fileFor(root: File, projectId: String): File {
        require(projectId.isNotBlank() && projectId.length <= 256) {
            "Project ID must contain 1–256 characters"
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(root, "$hash.json")
    }
}
