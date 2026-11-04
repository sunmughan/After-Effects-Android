package ai.effectcraft.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable, project-scoped scalar animation tracks for the Android editor.
 *
 * Tracks are kept separate from upstream .ecproj until the native EffectCraft
 * model exposes a compatible keyframe schema. This store is editor state; it
 * does not claim that these draft tracks are rendered by the native engine.
 */
class ProjectKeyframeStore(private val root: File) {
    data class StoredTrack(val layerId: String, val track: PropertyTrack)

    @Synchronized
    fun load(projectId: String): List<StoredTrack> {
        val id = validateProjectId(projectId)
        recoverInterruptedWrite(id)
        val file = fileFor(id)
        if (!file.isFile) return emptyList()
        return readDocument(file, id)
    }

    /**
     * A process can die after the complete JSON temp is fsynced/written but
     * before the atomic rename. Promote only a fully validated temp belonging
     * to this project. Invalid temps are discarded; the committed file stays
     * untouched. If promotion itself fails, keep the valid temp for retry.
     */
    private fun recoverInterruptedWrite(projectId: String) {
        val target = fileFor(projectId)
        val temporary = File(root, "${target.name}.tmp")
        if (!temporary.isFile) return
        val validTemp = runCatching { readDocument(temporary, projectId) }.isSuccess
        if (!validTemp) {
            // Best effort: never let a malformed temp block future loads.
            temporary.delete()
            return
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Preserve the validated temp so a later load can retry.
            }
        }
    }

    private fun readDocument(file: File, projectId: String): List<StoredTrack> {
        try {
            val document = JSONObject(file.readText(Charsets.UTF_8))
            require(document.optString("format") == FORMAT) { "Unsupported keyframe document format" }
            require(document.optInt("version", -1) == VERSION) { "Unsupported keyframe document version" }
            require(document.getString("projectId") == projectId) { "Keyframe document belongs to another project" }
            val tracksJson = document.getJSONArray("tracks")
            val tracks = buildList {
                for (index in 0 until tracksJson.length()) {
                    val entry = tracksJson.getJSONObject(index)
                    val layerId = validateLayerId(entry.getString("layerId"))
                    val property = validateProperty(entry.getString("property"))
                    val keysJson = entry.getJSONArray("keyframes")
                    val keys = buildList {
                        for (keyIndex in 0 until keysJson.length()) {
                            val key = keysJson.getJSONObject(keyIndex)
                            add(PropertyKeyframe(readFrame(key.get("frame")), readValue(key.get("value"))))
                        }
                    }
                    require(keys.isNotEmpty()) { "Empty keyframe tracks are not valid" }
                    require(keys.zipWithNext().all { (a, b) -> a.frame < b.frame }) {
                        "Keyframes must be strictly ordered with unique frames"
                    }
                    add(StoredTrack(layerId, PropertyTrack(property, keys)))
                }
            }
            require(tracks.map { it.layerId to it.track.property }.toSet().size == tracks.size) {
                "Duplicate layer/property tracks"
            }
            return tracks.sortedWith(compareBy<StoredTrack> { it.layerId }.thenBy { it.track.property })
        } catch (failure: Exception) {
            if (failure is IOException) throw failure
            throw IOException("Unable to read project keyframes: ${failure.message ?: "invalid document"}", failure)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun upsert(projectId: String, layerId: String, property: String, frame: Int, value: Float): StoredTrack {
        val id = validateProjectId(projectId)
        val layer = validateLayerId(layerId)
        val name = validateProperty(property)
        val tracks = load(id).toMutableList()
        val index = tracks.indexOfFirst { it.layerId == layer && it.track.property == name }
        val current = if (index >= 0) tracks[index].track else PropertyTrack(name, emptyList())
        val updated = StoredTrack(layer, PropertyTrackEditor.upsert(current, frame, value))
        if (index >= 0) tracks[index] = updated else tracks += updated
        write(id, tracks)
        return updated
    }

    @Synchronized
    @Throws(IOException::class)
    fun removeKeyframe(projectId: String, layerId: String, property: String, frame: Int): Boolean {
        val id = validateProjectId(projectId)
        val layer = validateLayerId(layerId)
        val name = validateProperty(property)
        require(frame >= 0) { "Keyframe frame must be non-negative" }
        val tracks = load(id).toMutableList()
        val index = tracks.indexOfFirst { it.layerId == layer && it.track.property == name }
        if (index < 0 || tracks[index].track.keyframes.none { it.frame == frame }) return false
        val updated = PropertyTrackEditor.remove(tracks[index].track, frame)
        if (updated.keyframes.isEmpty()) tracks.removeAt(index) else tracks[index] = StoredTrack(layer, updated)
        write(id, tracks)
        return true
    }

    @Synchronized
    @Throws(IOException::class)
    fun clear(projectId: String): Boolean {
        val file = fileFor(validateProjectId(projectId))
        val temporary = File(root, "${file.name}.tmp")
        // Delete the interrupted-write temp FIRST: deleting the committed
        // file first and then crashing would let the next load promote the
        // stale temp and resurrect the cleared tracks. Temp-first means a
        // crash mid-clear leaves the committed file intact, never a
        // resurrection.
        if (temporary.exists()) temporary.delete()
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("Unable to remove project keyframes")
        return true
    }

    private fun write(projectId: String, tracks: List<StoredTrack>) {
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create keyframe directory")
        if (!root.isDirectory) throw IOException("Keyframe path is not a directory")
        val document = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("projectId", projectId)
            put("tracks", JSONArray().apply {
                tracks.sortedWith(compareBy<StoredTrack> { it.layerId }.thenBy { it.track.property }).forEach { stored ->
                    put(JSONObject().apply {
                        put("layerId", stored.layerId)
                        put("property", stored.track.property)
                        put("keyframes", JSONArray().apply {
                            stored.track.keyframes.sortedBy { it.frame }.forEach { key ->
                                put(JSONObject().put("frame", key.frame).put("value", key.value.toDouble()))
                            }
                        })
                    })
                }
            })
        }
        val target = fileFor(projectId)
        val temporary = File(root, "${target.name}.tmp")
        // Flush the complete recovery candidate to stable storage before the
        // rename. Without fd.sync(), a power loss can leave a syntactically
        // complete temp whose contents were never durably written.
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

    private fun validateProjectId(value: String): String {
        require(value.isNotBlank() && value.length <= 256) { "Project ID must contain 1–256 characters" }
        return value
    }

    private fun validateLayerId(value: String): String {
        require(value.isNotBlank() && value.length <= 256) { "Layer ID must contain 1–256 characters" }
        return value
    }

    private fun validateProperty(value: String): String {
        require(value.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}"))) { "Invalid scalar property name" }
        return value
    }

    private fun readFrame(value: Any): Int {
        require(value is Number) { "Keyframe frame must be numeric" }
        val frame = value.toDouble()
        require(frame.isFinite() && frame >= 0.0 && frame <= Int.MAX_VALUE && frame % 1.0 == 0.0) {
            "Keyframe frame must be a non-negative integer"
        }
        return frame.toInt()
    }

    private fun readValue(value: Any): Float {
        require(value is Number) { "Keyframe value must be numeric" }
        val number = value.toDouble()
        require(number.isFinite() && number >= -Float.MAX_VALUE && number <= Float.MAX_VALUE) {
            "Keyframe value must be finite and representable"
        }
        return number.toFloat().also { require(it.isFinite()) }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FORMAT = "effectcraft-android-keyframes"
        private const val VERSION = 1
    }
}
