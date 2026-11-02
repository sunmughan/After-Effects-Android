package ai.effectcraft.android.data

import org.json.JSONObject

data class CanonicalLayerInfo(
    val id: Long,
    val name: String,
    val source: String,
    val visible: Boolean
)

data class CanonicalCompositionInfo(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val layers: List<CanonicalLayerInfo>
)

data class CanonicalProjectInfo(val compositions: List<CanonicalCompositionInfo>) {
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
                    CanonicalLayerInfo(
                        id = layer.getLong("id"),
                        name = layer.optString("name", "Layer"),
                        source = layer.optString("source", "Unknown"),
                        visible = layer.optBoolean("visible", true)
                    )
                }
                CanonicalCompositionInfo(
                    id = comp.getLong("id"),
                    name = comp.optString("name", "Composition"),
                    width = comp.optInt("width"),
                    height = comp.optInt("height"),
                    layers = layers
                )
            }
            return CanonicalProjectInfo(compositions)
        }
    }
}
