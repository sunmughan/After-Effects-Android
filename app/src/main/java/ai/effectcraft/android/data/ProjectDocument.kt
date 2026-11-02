package ai.effectcraft.android.data

import java.util.UUID

data class ProjectDocument(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationFrames: Int,
    val layers: List<LayerDocument>,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    companion object {
        fun starter(): ProjectDocument = ProjectDocument(
            id = UUID.randomUUID().toString(),
            name = "Untitled Composition",
            width = 1080,
            height = 1920,
            frameRate = 30,
            durationFrames = 150,
            layers = listOf(
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Background",
                    kind = "Footage",
                    opacity = 1f,
                    tint = 0xFF56647A
                ),
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Title / Main",
                    kind = "Text",
                    opacity = 1f,
                    tint = 0xFF9278F6
                ),
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Accent Shapes",
                    kind = "Shape",
                    opacity = 0.82f,
                    tint = 0xFF45C8B5
                )
            )
        )
    }
}

data class LayerDocument(
    val id: String,
    val name: String,
    val kind: String,
    val opacity: Float,
    val tint: Long,
    val visible: Boolean = true
) {
    companion object {
        fun newShape(index: Int): LayerDocument = LayerDocument(
            id = UUID.randomUUID().toString(),
            name = "Shape $index",
            kind = "Shape",
            opacity = 1f,
            tint = 0xFF9278F6
        )
    }
}
