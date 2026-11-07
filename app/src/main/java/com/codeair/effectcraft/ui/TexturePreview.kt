package com.codeair.effectcraft.ui

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.codeair.effectcraft.engine.RenderedFrameBitmap
import com.codeair.effectcraft.engine.SurfacePresentation

/**
 * The editor's Surface presentation path: rendered native frames drawn
 * onto a [TextureView]'s [Surface] with a Canvas, not only displayed as
 * a Bitmap image.
 *
 * Lifecycle, all driven through [SurfacePresentation]:
 * - Surface available → the state machine attaches and [onSurfaceReady]
 *   fires so the parent re-renders the current frame into [frameBytes].
 * - Surface destroyed → the Surface is released, the machine detaches,
 *   and no further draw is attempted.
 * - Surface resized → a size change owes one re-render via
 *   [onSurfaceReady]; an unchanged size redraws the latest frame as-is.
 * - Session re-attach after loss → the parent drives the remembered
 *   [presentation] with onSessionLost/onSessionRecovered; recovery
 *   re-attaches at the remembered size, owes a re-render, and the next
 *   [frameBytes] update is drawn again.
 *
 * The Surface and the latest frame live in one remembered holder because
 * the factory listener and the update block are separate lambdas: a plain
 * local would give each its own copy and the update block would never
 * see the Surface the listener created.
 *
 * Drawing is defensive: a null or malformed frame draws nothing and never
 * crashes the surface, and the Surface is released exactly once on destroy.
 */
@Composable
fun TexturePreview(
    frameBytes: ByteArray?,
    onSurfaceReady: () -> Unit,
    modifier: Modifier = Modifier,
    presentation: SurfacePresentation = remember { SurfacePresentation() },
) {
    val holder = remember { object { var surface: Surface? = null; var bytes: ByteArray? = null } }
    fun drawLatest() {
        val target = holder.surface ?: return
        if (!presentation.isAttached) return
        val bitmap = RenderedFrameBitmap.decode(holder.bytes) ?: return
        runCatching {
            val canvas = target.lockCanvas(null)
            try {
                // Fit the frame inside the surface like ContentScale.Fit:
                // scale uniformly, center it, and clear the letterbox
                // area black. Drawing unscaled at (0, 0) left a 512px
                // frame stranded in the top-left corner of the surface.
                canvas.drawColor(android.graphics.Color.BLACK)
                if (bitmap.width > 0 && bitmap.height > 0) {
                    val scale = minOf(
                        canvas.width.toFloat() / bitmap.width,
                        canvas.height.toFloat() / bitmap.height
                    )
                    val destWidth = bitmap.width * scale
                    val destHeight = bitmap.height * scale
                    val left = (canvas.width - destWidth) / 2f
                    val top = (canvas.height - destHeight) / 2f
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(left, top, left + destWidth, top + destHeight),
                        null
                    )
                }
            } finally {
                target.unlockCanvasAndPost(canvas)
            }
        }
    }
    AndroidView(
        modifier = modifier.semantics { contentDescription = "Surface preview" },
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        holder.surface = Surface(texture)
                        presentation.onSurfaceAvailable(width, height)
                        if (presentation.takeNeedsRender()) onSurfaceReady()
                        drawLatest()
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        presentation.onSurfaceResized(width, height)
                        if (presentation.takeNeedsRender()) onSurfaceReady()
                        drawLatest()
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        presentation.onSurfaceDestroyed()
                        holder.surface?.release()
                        holder.surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = {
            // The composable body refreshes the holder every recomposition,
            // so the listener's drawLatest always sees the newest frame even
            // though the listener itself was created on the first composition.
            holder.bytes = frameBytes
            // A fresh frame for an attached surface is drawn immediately;
            // a frame arriving while detached waits for the next attach,
            // which draws it through drawLatest.
            drawLatest()
        },
    )
}
