package com.codeair.effectcraft.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.draftFrameLayers

/**
 * Batch 2 (P06): the real draft renderer. It executes the pure
 * [draftFrameLayers] spec onto a bitmap with Android Canvas — solid
 * tint cards in z-order with opacity, Text layers with their name
 * centred — and feeds the existing PreviewStage/TexturePreview path.
 * No native composition is involved: the draft model has no native
 * representation, and the renderer never pretends otherwise.
 *
 * The bitmap is capped at [MAX_PREVIEW_SIDE] on its longest side: the
 * preview is a viewport, not the export. A 1080×1920 composition
 * previews at 405×720, so re-rendering per frame while playing costs
 * ~1 MB per frame instead of ~8 MB. Export still renders full-size
 * through the native path.
 */
object DraftFrameRenderer {

    const val MAX_PREVIEW_SIDE = 720

    /** A bitmap of the draft at [frame], or null when the composition has no usable size. */
    fun render(
        project: ProjectDocument,
        frame: Int,
        opacityOverrides: Map<String, Float> = emptyMap()
    ): Bitmap? {
        if (project.width <= 0 || project.height <= 0) return null
        val scale = minOf(1f, MAX_PREVIEW_SIDE / maxOf(project.width, project.height).toFloat())
        val width = maxOf(1, (project.width * scale).toInt())
        val height = maxOf(1, (project.height * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF000000.toInt())
        val fill = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        val textPaint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textSize = height / 12f
        }
        draftFrameLayers(project, frame, opacityOverrides).forEach { draw ->
            fill.color = draw.colorArgb
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            draw.text?.let { text ->
                val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(text, width / 2f, y, textPaint)
            }
        }
        return bitmap
    }
}
