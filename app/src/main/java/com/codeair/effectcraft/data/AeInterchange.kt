package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The After Effects interchange format (version 1): the open JSON a
 * project is exported to from After Effects with
 * docs/ae-export/ExportToEffectCraft.jsx (see docs/AE_IMPORT.md).
 *
 * A raw .aep file is a RIFX binary. Direct .aep import is implemented
 * by the ported official EffectCraft .aep importer (v0.21.1+, see
 * [ImportDocumentResolver]); the interchange stays as an interim route
 * for projects the .aep importer cannot fully read, not the AE import
 * solution.
 *
 * The parser is the trust boundary: it validates every number and
 * name, clamps times into the composition, and turns anything it
 * does not understand into a warning (unknown layer type) or a
 * precise error (missing comp, bad dimensions) instead of a crash.
 */
enum class AeLayerType {
    TEXT, SOLID, FOOTAGE, SHAPE, NULL;

    companion object {
        fun fromName(name: String): AeLayerType? = when (name.lowercase()) {
            "text" -> TEXT
            "solid" -> SOLID
            "footage" -> FOOTAGE
            "shape" -> SHAPE
            "null" -> NULL
            else -> null
        }
    }
}

sealed interface AeValue {
    data class Scalar(val value: Double) : AeValue
    data class Vec2(val x: Double, val y: Double) : AeValue
}

data class AeKeyframe(val timeSeconds: Double, val value: AeValue)

data class AeLayer(
    val name: String,
    val type: AeLayerType,
    val text: String = "",
    val font: String = "Inter",
    val fontSize: Double = 72.0,
    /** Straight RGBA, 0..1. Text and shape fill; solids use [r, g, b]. */
    val color: List<Double> = listOf(1.0, 1.0, 1.0, 1.0),
    /** Text only: the full canonical TextDoc value (runs, paragraphs, box);
     * null keeps the single-style document built from [text], [font],
     * [fontSize] and [color]. */
    val textDoc: JSONObject? = null,
    /** Footage only: path relative to the bundle root, e.g. "media/Clip.mp4". */
    val file: String? = null,
    /** Footage only: "video", "still" or "audio". */
    val kind: String = "video",
    /** Footage only: true when the source file carries audio. */
    val hasAudio: Boolean = false,
    /** Footage only: native frame rate, or null to use the comp rate. */
    val frameRate: Double? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Footage and still duration in seconds; defaults to the comp duration. */
    val durationSeconds: Double = 0.0,
    /** Shape only: [width, height] of the rectangle. */
    val size: List<Double> = listOf(200.0, 200.0),
    val position: Pair<Double, Double> = 0.0 to 0.0,
    val anchorPoint: Pair<Double, Double> = 0.0 to 0.0,
    val scale: Pair<Double, Double> = 100.0 to 100.0,
    val rotation: Double = 0.0,
    val opacity: Double = 100.0,
    val startTime: Double = 0.0,
    val inPoint: Double = 0.0,
    val outPoint: Double = 0.0,
    /** Name of the parent layer in the same comp, or null. */
    val parentName: String? = null,
    /** Keyframes by property: "position", "anchorPoint", "scale", "rotation", "opacity". */
    val keyframes: Map<String, List<AeKeyframe>> = emptyMap()
)

data class AeComp(
    val name: String,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val durationSeconds: Double,
    val background: List<Double>,
    val layers: List<AeLayer>
)

data class AeProject(
    val projectName: String,
    val comps: List<AeComp>,
    val warnings: List<String>
)

object AeInterchange {
    const val FORMAT = "effectcraft-ae-interchange"
    const val VERSION = 1

    /** True when the document carries the interchange marker. Never throws. */
    fun isInterchange(json: String): Boolean = runCatching {
        val root = JSONObject(json)
        root.optString("format") == FORMAT && root.optInt("version", -1) == VERSION
    }.getOrDefault(false)

    /**
     * Parses and validates an interchange document.
     * @throws IllegalArgumentException with a precise reason for anything unusable.
     */
    fun parse(json: String): AeProject {
        val root = try {
            JSONObject(json)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Not a readable JSON document: ${failure.message}")
        }
        require(root.optString("format") == FORMAT) {
            "Not an EffectCraft AE interchange document (missing format \"$FORMAT\")"
        }
        require(root.optInt("version", -1) == VERSION) {
            "Unsupported AE interchange version ${root.optInt("version", -1)}; this app reads version $VERSION"
        }
        val warnings = mutableListOf<String>()
        val projectName = root.optString("projectName", "Imported Project").ifBlank { "Imported Project" }
        val compsJson = root.optJSONArray("comps")
            ?: throw IllegalArgumentException("AE interchange document has no \"comps\" array")
        require(compsJson.length() > 0) { "AE interchange document contains no compositions" }
        val comps = (0 until compsJson.length()).map { index ->
            parseComp(compsJson.getJSONObject(index), warnings, index)
        }
        return AeProject(projectName, comps, warnings)
    }

    private fun parseComp(json: JSONObject, warnings: MutableList<String>, index: Int): AeComp {
        val where = "comp ${index + 1}"
        val name = json.optString("name", "Composition").ifBlank { "Composition" }
        val width = json.optDouble("width", 0.0).toInt()
        val height = json.optDouble("height", 0.0).toInt()
        require(width > 0 && height > 0) { "AE $where \"$name\" has invalid dimensions ${width}x$height" }
        val frameRate = json.optDouble("frameRate", 0.0)
        require(frameRate > 0.0 && frameRate.isFinite()) { "AE $where \"$name\" has an invalid frame rate $frameRate" }
        val duration = json.optDouble("duration", 0.0)
        require(duration > 0.0 && duration.isFinite()) { "AE $where \"$name\" has an invalid duration $duration" }
        val background = json.optJSONArray("background")?.toDoubleList(3)
            ?: listOf(0.0, 0.0, 0.0)
        val layersJson = json.optJSONArray("layers") ?: JSONArray()
        val layers = (0 until layersJson.length()).mapNotNull { layerIndex ->
            parseLayer(layersJson.getJSONObject(layerIndex), width, height, duration, warnings, "$where \"$name\"")
        }
        return AeComp(name, width, height, frameRate, duration, background, layers)
    }

    private fun parseLayer(
        json: JSONObject,
        compWidth: Int,
        compHeight: Int,
        compDuration: Double,
        warnings: MutableList<String>,
        where: String
    ): AeLayer? {
        val name = json.optString("name", "Layer").ifBlank { "Layer" }
        val typeName = json.optString("type", "")
        val type = AeLayerType.fromName(typeName)
        if (type == null) {
            warnings += "Skipped layer \"$name\" ($where): unknown type \"$typeName\""
            return null
        }
        val width = json.optDouble("width", compWidth.toDouble()).toInt().coerceAtLeast(1)
        val height = json.optDouble("height", compHeight.toDouble()).toInt().coerceAtLeast(1)
        val duration = json.optDouble("duration", compDuration).let {
            if (it > 0.0 && it.isFinite()) it else compDuration
        }
        val centre = compWidth / 2.0 to compHeight / 2.0
        val position = json.optJSONArray("position")?.toVec2() ?: centre
        val anchorDefault = when (type) {
            AeLayerType.FOOTAGE, AeLayerType.SOLID -> width / 2.0 to height / 2.0
            else -> 0.0 to 0.0
        }
        val anchorPoint = json.optJSONArray("anchorPoint")?.toVec2() ?: anchorDefault
        val scale = json.optJSONArray("scale")?.toVec2() ?: (100.0 to 100.0)
        val inPoint = json.optDouble("inPoint", 0.0).coerceIn(0.0, compDuration)
        val outPoint = json.optDouble("outPoint", compDuration).coerceIn(0.0, compDuration)
        require(outPoint > inPoint) { "AE layer \"$name\" ($where) has an empty time range ($inPoint..$outPoint)" }
        val kind = json.optString("kind", "").ifBlank {
            when (type) {
                AeLayerType.FOOTAGE -> kindFromExtension(json.optString("file", ""))
                else -> "video"
            }
        }
        val keyframes = parseKeyframes(json.optJSONObject("keyframes"), warnings, name, where)
        return AeLayer(
            name = name,
            type = type,
            text = json.optString("text", ""),
            font = json.optString("font", "Inter").ifBlank { "Inter" },
            fontSize = json.optDouble("fontSize", 72.0).let { if (it > 0.0) it else 72.0 },
            color = when (type) {
                // Text fill is RGBA (4); solid colour is RGB (3).
                AeLayerType.TEXT -> json.optJSONArray("color")?.toDoubleList(4)
                    ?: listOf(1.0, 1.0, 1.0, 1.0)
                AeLayerType.SOLID -> json.optJSONArray("color")?.toDoubleList(3)
                    ?: listOf(1.0, 1.0, 1.0)
                else -> listOf(1.0, 1.0, 1.0, 1.0)
            },
            file = json.optString("file", "").ifBlank { null },
            kind = kind,
            hasAudio = json.optBoolean("hasAudio", kind == "audio"),
            frameRate = if (json.has("frameRate")) json.optDouble("frameRate").takeIf { it > 0.0 } else null,
            width = width,
            height = height,
            durationSeconds = duration,
            size = json.optJSONArray("size")?.toDoubleList(2) ?: listOf(200.0, 200.0),
            position = position,
            anchorPoint = anchorPoint,
            scale = scale,
            rotation = json.optDouble("rotation", 0.0),
            opacity = json.optDouble("opacity", 100.0).coerceIn(0.0, 100.0),
            startTime = json.optDouble("startTime", 0.0),
            inPoint = inPoint,
            outPoint = outPoint,
            parentName = json.optString("parent", "").ifBlank { null },
            keyframes = keyframes
        )
    }

    private fun parseKeyframes(
        json: JSONObject?,
        warnings: MutableList<String>,
        layerName: String,
        where: String
    ): Map<String, List<AeKeyframe>> {
        if (json == null) return emptyMap()
        val known = setOf("position", "anchorPoint", "scale", "rotation", "opacity")
        val result = mutableMapOf<String, List<AeKeyframe>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val property = keys.next()
            if (property !in known) {
                warnings += "Skipped keyframes for \"$property\" on layer \"$layerName\" ($where): unsupported property"
                continue
            }
            val array = json.optJSONArray(property) ?: continue
            val frames = (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val time = entry.optDouble("time", Double.NaN)
                if (!time.isFinite()) return@mapNotNull null
                val value = when (val raw = entry.opt("value")) {
                    is Number -> AeValue.Scalar(raw.toDouble())
                    is JSONArray -> raw.toVec2()?.let { AeValue.Vec2(it.first, it.second) }
                    else -> null
                } ?: return@mapNotNull null
                AeKeyframe(time, value)
            }.sortedBy { it.timeSeconds }
            if (frames.isNotEmpty()) result[property] = frames
        }
        return result
    }

    private fun kindFromExtension(file: String): String = when (
        file.substringAfterLast('.', "").lowercase()
    ) {
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff" -> "still"
        "mp3", "wav", "aac", "flac", "ogg", "m4a", "opus" -> "audio"
        else -> "video"
    }

    private fun JSONArray.toVec2(): Pair<Double, Double>? {
        if (length() < 2) return null
        val x = optDouble(0, Double.NaN)
        val y = optDouble(1, Double.NaN)
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    private fun JSONArray.toDoubleList(size: Int): List<Double>? {
        if (length() < size) return null
        val values = (0 until size).map { optDouble(it, Double.NaN) }
        if (values.any { !it.isFinite() }) return null
        return values
    }
}
