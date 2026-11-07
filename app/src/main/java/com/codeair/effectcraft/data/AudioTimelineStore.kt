package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable, project-scoped audio timelines for the Android editor.
 *
 * Timelines are kept separate from upstream .ecproj until the native
 * EffectCraft model exposes an audio schema (same split as
 * [ProjectKeyframeStore]). Writes are fsynced before an atomic rename,
 * and an interrupted write's validated temp is promoted on the next
 * load, so a clip edit is never lost to a crash mid-save.
 */
class AudioTimelineStore(private val root: File) {

    companion object {
        const val FORMAT = "effectcraft-audio-timeline"
        const val VERSION = 1
    }

    @Synchronized
    fun load(projectId: String): AudioTimeline {
        val id = validateProjectId(projectId)
        recoverInterruptedWrite(id)
        val file = fileFor(id)
        if (!file.isFile) return AudioTimeline.EMPTY
        return readDocument(file, id)
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(projectId: String, timeline: AudioTimeline): AudioTimeline {
        val id = validateProjectId(projectId)
        write(id, timeline)
        return timeline
    }

    @Synchronized
    fun clear(projectId: String): Boolean {
        val file = fileFor(validateProjectId(projectId))
        val temporary = File(root, "${file.name}.tmp")
        if (temporary.exists()) temporary.delete()
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("Unable to remove project audio timeline")
        return true
    }

    private fun recoverInterruptedWrite(projectId: String) {
        val target = fileFor(projectId)
        val temporary = File(root, "${target.name}.tmp")
        if (!temporary.isFile) return
        val validTemp = runCatching { readDocument(temporary, projectId) }.isSuccess
        if (!validTemp) {
            temporary.delete()
            return
        }
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Preserve the validated temp so a later load can retry.
            }
        }
    }

    private fun readDocument(file: File, projectId: String): AudioTimeline {
        try {
            val document = JSONObject(file.readText(Charsets.UTF_8))
            require(document.optString("format") == FORMAT) { "Unsupported audio timeline format" }
            require(document.optInt("version", -1) == VERSION) { "Unsupported audio timeline version" }
            require(document.getString("projectId") == projectId) { "Audio timeline belongs to another project" }
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
        } catch (failure: Exception) {
            if (failure is IOException) throw failure
            throw IOException("Unable to read project audio timeline: ${failure.message ?: "invalid document"}", failure)
        }
    }

    private fun write(projectId: String, timeline: AudioTimeline) {
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create audio timeline directory")
        if (!root.isDirectory) throw IOException("Audio timeline path is not a directory")
        val document = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("projectId", projectId)
            put("clips", JSONArray().apply {
                timeline.clips.forEach { clip ->
                    put(JSONObject().apply {
                        put("id", clip.id)
                        put("sourceUri", clip.sourceUri)
                        put("sourceDurationUs", clip.sourceDurationUs)
                        put("timelineStartUs", clip.timelineStartUs)
                        put("sourceStartUs", clip.sourceStartUs)
                        put("durationUs", clip.durationUs)
                        put("volume", clip.volume)
                        put("fadeInUs", clip.fadeInUs)
                        put("fadeOutUs", clip.fadeOutUs)
                    })
                }
            })
        }
        val target = fileFor(projectId)
        val temporary = File(root, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(document.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileFor(projectId: String): File = File(root, "${projectId.sha256()}.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun validateProjectId(value: String): String {
        require(value.isNotBlank() && value.length <= 256) { "Project ID must contain 1–256 characters" }
        return value
    }
}
