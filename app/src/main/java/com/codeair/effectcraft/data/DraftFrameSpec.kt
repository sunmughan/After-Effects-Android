package com.codeair.effectcraft.data

/**
 * Batch 2 (P06): the pure draw specification for the draft preview. The
 * draft model carries no layer geometry (no position/size — see
 * [LayerDocument]), so every layer draws full-frame at the composition
 * size, in list order (first layer at the bottom, last on top), exactly
 * like a flattened stack of full-frame colour cards. Text layers draw
 * their name centred on their card: the model stores no separate text
 * content, so the name is the text. Image/footage layers have no pixel
 * payload in the draft model, so they draw as their tint card — an
 * honest render of what the model actually holds, never a fake image.
 *
 * This function is pure and JVM-testable: the Android Canvas adapter
 * ([com.codeair.effectcraft.ui.DraftFrameRenderer]) only executes the
 * spec it returns. Opacity comes from the layer, overridden per layer
 * by an opacity-keyframe evaluation at the frame when the caller has
 * one (a null override means "no keyframe track").
 */
data class DraftLayerDraw(
    val layerId: String,
    val layerName: String,
    val kind: String,
    /** Tint with its alpha replaced by the effective opacity (0–255). */
    val colorArgb: Int,
    /** Centred text for Text layers; null for every other kind. */
    val text: String?
)

fun draftFrameLayers(
    project: ProjectDocument,
    frame: Int,
    opacityOverrides: Map<String, Float> = emptyMap()
): List<DraftLayerDraw> = project.layers
    .filter { it.visible && it.isActiveAt(frame, project.durationFrames) }
    .map { layer ->
        val opacity = (opacityOverrides[layer.id] ?: layer.opacity).coerceIn(0f, 1f)
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        DraftLayerDraw(
            layerId = layer.id,
            layerName = layer.name,
            kind = layer.kind,
            colorArgb = (alpha shl 24) or (layer.tint and 0x00FFFFFFL).toInt(),
            text = if (layer.kind == "Text") layer.name else null
        )
    }
