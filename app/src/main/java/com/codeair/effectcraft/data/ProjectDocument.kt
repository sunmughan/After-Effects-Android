package com.codeair.effectcraft.data

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
    /** Returns all editor-model invariant violations; an empty list means saveable. */
    fun validationErrors(): List<String> = buildList {
        if (name.isBlank()) add("Project name must not be blank")
        if (width !in 320..3840) add("Composition width must be between 320 and 3840")
        if (height !in 320..3840) add("Composition height must be between 320 and 3840")
        if (frameRate !in 1..120) add("Frame rate must be between 1 and 120")
        if (durationFrames !in 1..3600) add("Duration must be between 1 and 3600 frames")
        if (layers.isEmpty()) add("A composition must contain at least one layer")
        if (layers.map { it.id }.toSet().size != layers.size) add("Layer IDs must be unique")
        layers.forEachIndexed { index, layer ->
            if (layer.name.isBlank()) add("Layer ${index + 1} name must not be blank")
            if (!layer.opacity.isFinite() || layer.opacity !in 0f..1f) add("Layer ${index + 1} opacity must be finite and normalized")
            ClipTimeline.validateClips(layer.clips, durationFrames).forEach { error ->
                add("Layer ${index + 1}: $error")
            }
        }
    }

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
                    tint = 0xFF56647A,
                    clips = listOf(ClipDocument.fullRange(150))
                ),
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Title / Main",
                    kind = "Text",
                    opacity = 1f,
                    tint = 0xFF9278F6,
                    clips = listOf(ClipDocument.fullRange(150))
                ),
                LayerDocument(
                    id = UUID.randomUUID().toString(),
                    name = "Accent Shapes",
                    kind = "Shape",
                    opacity = 0.82f,
                    tint = 0xFF45C8B5,
                    clips = listOf(ClipDocument.fullRange(150))
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
    val visible: Boolean = true,
    /** Clips on this layer's timeline. Empty means the legacy behaviour: the layer covers the full composition (see [ClipTimeline.layerCoverage]). */
    val clips: List<ClipDocument> = emptyList()
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
