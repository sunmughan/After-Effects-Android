package com.codeair.effectcraft.engine

import android.graphics.Bitmap

/**
 * Owns the decoded bitmap for one TexturePreview. Recomposition with the
 * same immutable frame byte-array reuses pixels; a new frame recycles the
 * previous bitmap before retaining its replacement. close() is idempotent.
 *
 * This is deliberately a one-frame presentation cache, separate from the
 * native RGBA byte LRU: it bounds decoded Java heap memory to one preview
 * bitmap per mounted SurfacePreview rather than allocating on every draw.
 */
class RenderedFrameBitmapCache : AutoCloseable {
    private var source: ByteArray? = null
    var bitmap: Bitmap? = null
        private set

    fun update(bytes: ByteArray?): Bitmap? {
        if (source === bytes) return bitmap
        val next = RenderedFrameBitmap.decode(bytes)
        bitmap?.takeUnless { it.isRecycled }?.recycle()
        source = bytes
        bitmap = next
        return next
    }

    override fun close() {
        source = null
        bitmap?.takeUnless { it.isRecycled }?.recycle()
        bitmap = null
    }
}
