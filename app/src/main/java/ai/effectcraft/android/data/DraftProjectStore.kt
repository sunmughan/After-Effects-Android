package ai.effectcraft.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores bootstrap editor drafts in app-private storage.
 *
 * This deliberately uses the explicit .efx-draft extension. It is not the
 * upstream EffectCraft .ecproj format; conversion is gated on engine integration.
 */
class DraftProjectStore(context: Context) {
    private val projectDirectory = File(context.filesDir, "projects")

    @Throws(IOException::class)
    fun save(project: ProjectDocument): File {
        val validationErrors = project.validationErrors()
        require(validationErrors.isEmpty()) { validationErrors.joinToString("; ") }
        if (!projectDirectory.exists() && !projectDirectory.mkdirs()) {
            throw IOException("Unable to create the local projects directory")
        }

        // File timestamps are too coarse to order saves reliably (two saves in
        // quick succession can share a file timestamp), so keep the stored
        // timestamp strictly increasing: the newest save always wins.
        val latestUpdated = listSavedProjects().maxOfOrNull { it.updatedAtEpochMillis } ?: 0L
        val updatedTime = maxOf(System.currentTimeMillis(), latestUpdated + 1L)
        val updated = project.copy(updatedAtEpochMillis = updatedTime)
        val existing = draftFiles().firstOrNull { file ->
            runCatching { JSONObject(file.readText(Charsets.UTF_8)).optString("id") == updated.id }.getOrDefault(false)
        }
        val target = existing ?: File(projectDirectory, "${safeName(updated.name)}-${updated.id.take(8)}.efx-draft")
        val fileName = target.name
        val temporary = File(projectDirectory, "$fileName.tmp")
        temporary.writeText(encode(updated).toString(2), Charsets.UTF_8)

        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        return target
    }

    /** Returns all valid offline drafts, newest first, de-duplicated by stable project ID. */
    fun listSavedProjects(): List<ProjectDocument> = draftFiles()
        .mapNotNull { file -> runCatching { decode(JSONObject(file.readText(Charsets.UTF_8))) to file }.getOrNull() }
        .sortedWith(
            compareByDescending<Pair<ProjectDocument, File>> { it.first.updatedAtEpochMillis }
                .thenByDescending { it.second.lastModified() }
                .thenByDescending { it.second.name }
        )
        .map { it.first }
        .distinctBy { it.id }

    /** Opens a previously saved draft by its stable project ID. */
    fun load(projectId: String): ProjectDocument? = listSavedProjects().firstOrNull { it.id == projectId }

    fun loadLatest(): ProjectDocument? = listSavedProjects().firstOrNull()

    private fun draftFiles(): List<File> = projectDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".efx-draft") }
        .orEmpty()

    private fun encode(project: ProjectDocument): JSONObject = JSONObject().apply {
        put("format", "effectcraft-android-draft")
        put("formatVersion", 1)
        put("id", project.id)
        put("name", project.name)
        put("width", project.width)
        put("height", project.height)
        put("frameRate", project.frameRate)
        put("durationFrames", project.durationFrames)
        put("updatedAtEpochMillis", project.updatedAtEpochMillis)
        put("layers", JSONArray().apply {
            project.layers.forEach { layer ->
                put(JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("kind", layer.kind)
                    put("opacity", layer.opacity.toDouble())
                    put("tint", layer.tint)
                    put("visible", layer.visible)
                })
            }
        })
    }

    private fun decode(json: JSONObject): ProjectDocument {
        require(json.optString("format") == "effectcraft-android-draft") {
            "Unsupported draft format"
        }
        require(json.optInt("formatVersion") == 1) { "Unsupported draft version" }

        val layersJson = json.optJSONArray("layers") ?: JSONArray()
        val layers = buildList {
            for (index in 0 until layersJson.length()) {
                val layer = layersJson.getJSONObject(index)
                add(
                    LayerDocument(
                        id = layer.getString("id"),
                        name = layer.getString("name"),
                        kind = layer.getString("kind"),
                        opacity = layer.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                        tint = layer.optLong("tint", 0xFF9278F6),
                        visible = layer.optBoolean("visible", true)
                    )
                )
            }
        }

        val project = ProjectDocument(
            id = json.getString("id"),
            name = json.optString("name", "Untitled Composition"),
            width = json.optInt("width", 1080),
            height = json.optInt("height", 1920),
            frameRate = json.optInt("frameRate", 30),
            durationFrames = json.optInt("durationFrames", 150),
            layers = layers,
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis", 0L)
        )
        val validationErrors = project.validationErrors()
        require(validationErrors.isEmpty()) { validationErrors.joinToString("; ") }
        return project
    }

    private fun safeName(value: String): String {
        val normalized = value.trim()
            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
            .trim('-')
            .take(48)
        return normalized.ifBlank { "untitled" }
    }
}
