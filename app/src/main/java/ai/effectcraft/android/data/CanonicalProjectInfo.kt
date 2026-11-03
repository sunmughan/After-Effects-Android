package ai.effectcraft.android.data

import org.json.JSONObject

data class CanonicalLayerInfo(
    val id: Long,
    val name: String,
    val source: String,
    val visible: Boolean,
    val opacityPercent: Double,
    val positionX: Double,
    val positionY: Double,
    val scaleX: Double,
    val scaleY: Double,
    val rotationDegrees: Double,
    /** Canonical opacity keys, sorted by frame; empty when the layer is not keyframed. */
    val opacityKeys: List<PropertyKeyframe> = emptyList()
)

data class CanonicalCompositionInfo(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val frameRateNum: Int,
    val frameRateDen: Int,
    val durationSeconds: Double,
    val layers: List<CanonicalLayerInfo>
) {
    val durationFrames: Int get() = (durationSeconds * frameRateNum / frameRateDen.coerceAtLeast(1)).toInt().coerceAtLeast(1)
}

data class CanonicalProjectItemInfo(
    val id: Long,
    val name: String,
    val type: String,
    val parentId: Long?,
    val path: String?,
    val width: Int,
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val missing: Boolean,
    val sequence: List<String>
)

data class CanonicalProjectInfo(
    val compositions: List<CanonicalCompositionInfo>,
    val items: List<CanonicalProjectItemInfo> = emptyList()
) {
    companion object {
        fun parse(metadataJson: String): CanonicalProjectInfo {
            val root = JSONObject(metadataJson)
            require(root.optBoolean("valid")) { root.optString("error", "Invalid canonical project") }
            val comps = root.optJSONArray("compositions")
            val compositions = (0 until (comps?.length() ?: 0)).map { index ->
                val comp = comps!!.getJSONObject(index)
                val layerArray = comp.optJSONArray("layers")
                val layers = (0 until (layerArray?.length() ?: 0)).map { layerIndex ->
                    val layer = layerArray!!.getJSONObject(layerIndex)
                    val position = layer.optJSONArray("position")
                    val scale = layer.optJSONArray("scale")
                    // Opacity keys ride the same sorted-upsert semantics as the
                    // draft timeline editor, so a reordered or duplicated
                    // metadata entry can never surface as an unsorted track.
                    val opacityKeysJson = layer.optJSONArray("opacityKeys")
                    var opacityTrack = PropertyTrack("opacity", emptyList())
                    for (keyIndex in 0 until (opacityKeysJson?.length() ?: 0)) {
                        val key = opacityKeysJson!!.getJSONObject(keyIndex)
                        opacityTrack = PropertyTrackEditor.upsert(
                            opacityTrack,
                            key.getInt("frame"),
                            key.getDouble("value").toFloat()
                        )
                    }
                    CanonicalLayerInfo(
                        id = layer.getLong("id"),
                        name = layer.optString("name", "Layer"),
                        source = layer.optString("source", "Unknown"),
                        visible = layer.optBoolean("visible", true),
                        opacityPercent = layer.optDouble("opacityPercent", 100.0),
                        positionX = position?.optDouble(0) ?: 0.0,
                        positionY = position?.optDouble(1) ?: 0.0,
                        scaleX = scale?.optDouble(0) ?: 100.0,
                        scaleY = scale?.optDouble(1) ?: 100.0,
                        rotationDegrees = layer.optDouble("rotationDegrees", 0.0),
                        opacityKeys = opacityTrack.keyframes
                    )
                }
                CanonicalCompositionInfo(
                    id = comp.getLong("id"),
                    name = comp.optString("name", "Composition"),
                    width = comp.optInt("width"),
                    height = comp.optInt("height"),
                    frameRateNum = comp.optInt("frameRateNum", 30),
                    frameRateDen = comp.optInt("frameRateDen", 1),
                    durationSeconds = comp.optDouble("durationSeconds", 5.0),
                    layers = layers
                )
            }
            val itemArray = root.optJSONArray("items")
            val items = (0 until (itemArray?.length() ?: 0)).map { index ->
                val item = itemArray!!.getJSONObject(index)
                val media = item.optJSONObject("media")
                val sequenceArray = media?.optJSONArray("sequence")
                val sequence = (0 until (sequenceArray?.length() ?: 0)).map { sequenceArray!!.getString(it) }
                CanonicalProjectItemInfo(
                    id = item.getLong("id"),
                    name = item.optString("name", "Item"),
                    type = item.optString("type", "Unknown"),
                    parentId = if (item.isNull("parentId")) null else item.optLong("parentId"),
                    path = media?.optString("path")?.takeIf { it.isNotBlank() },
                    width = media?.optInt("width") ?: 0,
                    height = media?.optInt("height") ?: 0,
                    hasVideo = media?.optBoolean("hasVideo") ?: false,
                    hasAudio = media?.optBoolean("hasAudio") ?: false,
                    missing = media?.optBoolean("missing") ?: false,
                    sequence = sequence
                )
            }
            return CanonicalProjectInfo(compositions, items)
        }
    }
}
