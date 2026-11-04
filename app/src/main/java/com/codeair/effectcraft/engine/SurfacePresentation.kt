package com.codeair.effectcraft.engine

import java.nio.ByteBuffer

/**
 * The RGBA frame envelope the native side returns: 8 big-endian header
 * bytes (width, height) followed by width × height × 4 RGBA bytes. One
 * parser lives here so the Bitmap preview and the Surface preview reject
 * malformed payloads identically, instead of each inventing its own check.
 */
object RenderedFrameEnvelope {
    const val MAX_PREVIEW_SIDE = 2048

    data class Dimensions(val width: Int, val height: Int)

    /** Returns the dimensions only when the payload is exactly well-formed. */
    fun parse(bytes: ByteArray?): Dimensions? {
        if (bytes == null || bytes.size < 8) return null
        val width = ByteBuffer.wrap(bytes, 0, 4).int
        val height = ByteBuffer.wrap(bytes, 4, 4).int
        if (width !in 1..MAX_PREVIEW_SIDE || height !in 1..MAX_PREVIEW_SIDE) return null
        if (bytes.size != 8 + width * height * 4) return null
        return Dimensions(width, height)
    }
}

/**
 * Pure state machine for the editor's Surface/Texture presentation path.
 * The AndroidView wrapper is a thin shell over this: it forwards
 * SurfaceTextureListener callbacks here and draws only when
 * [takeNeedsRender] says a fresh frame is owed. No Android types appear
 * here, so every lifecycle transition is JVM-testable.
 *
 * States: detached (no surface), attached (surface live, frames drawn),
 * lost (a session/device loss retired the surface while it was attached;
 * the last size is remembered so recovery re-attaches at the same size).
 * A resize to a different size, a fresh attach, and a recovery all owe
 * exactly one re-render; destroying the surface clears the loss flag.
 */
class SurfacePresentation {
    enum class State { DETACHED, ATTACHED, LOST }

    var state: State = State.DETACHED
        private set
    var surfaceWidth: Int = 0
        private set
    var surfaceHeight: Int = 0
        private set
    private var needsRender = false

    val isAttached: Boolean get() = state == State.ATTACHED
    val isLost: Boolean get() = state == State.LOST

    fun onSurfaceAvailable(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        state = State.ATTACHED
        surfaceWidth = width
        surfaceHeight = height
        needsRender = true
    }

    fun onSurfaceDestroyed() {
        state = State.DETACHED
        surfaceWidth = 0
        surfaceHeight = 0
        needsRender = false
    }

    fun onSurfaceResized(width: Int, height: Int) {
        if (state != State.ATTACHED || width <= 0 || height <= 0) return
        if (width != surfaceWidth || height != surfaceHeight) {
            surfaceWidth = width
            surfaceHeight = height
            needsRender = true
        }
    }

    /** A session/device loss retires an attached surface; a detached one is untouched. */
    fun onSessionLost() {
        if (state == State.ATTACHED) {
            state = State.LOST
            needsRender = false
        }
    }

    /** Recovery re-attaches at the remembered size and owes a re-render. */
    fun onSessionRecovered() {
        if (state == State.LOST) {
            state = State.ATTACHED
            needsRender = true
        }
    }

    /** Consumes the pending re-render flag. */
    fun takeNeedsRender(): Boolean {
        val owed = needsRender
        needsRender = false
        return owed
    }
}
