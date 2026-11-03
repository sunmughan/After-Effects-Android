package ai.effectcraft.android.engine

/**
 * Thin JNI boundary to the pinned upstream EffectCraft project model.
 *
 * The library is loaded lazily so the Compose shell remains launchable in development
 * environments that have not built the Android native target yet.
 */
object NativeProjectBridge {
    @Volatile
    private var loadAttempted = false

    private var available = false

    fun inspectEcproj(json: String): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { inspectEcprojNative(json) }.getOrNull()
    }

    fun isAvailable(): Boolean {
        ensureLoaded()
        return available
    }

    /** Returns a JSON envelope containing the edited canonical project or a validation error. */
    fun renameLayerEcproj(json: String, compositionId: Long, layerId: Long, name: String): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { renameLayerEcprojNative(json, compositionId, layerId, name) }.getOrNull()
    }

    /** Adds a real upstream Text layer and returns a canonical project JSON envelope. */
    fun addTextLayerEcproj(json: String, compositionId: Long, name: String): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { addTextLayerEcprojNative(json, compositionId, name) }.getOrNull()
    }

    /** Renders a canonical composition frame through the pinned upstream CPU compositor. */
    fun renderEcprojFrame(json: String, compositionId: Long, frame: Long, maxSide: Int = 512): ByteArray? {
        ensureLoaded()
        if (!available || frame < 0 || compositionId < 0 || maxSide !in 0..4096) return null
        val key = RenderedFrameCache.key(json, compositionId, frame, maxSide)
        return RenderedFrameCache.getOrRender(key) {
            runCatching { renderEcprojFrameNative(json, compositionId, frame, maxSide.toLong()) }
                .getOrNull()
        }
    }

    /** Sets canonical Transform/Position X and Y while preserving the existing Z value. */
    fun setLayerPositionEcproj(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerPositionEcprojNative(json, compositionId, layerId, x, y) }.getOrNull()
    }

    /** Sets canonical Transform/Scale X and Y percent while preserving Z. */
    fun setLayerScaleEcproj(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerScaleEcprojNative(json, compositionId, layerId, x, y) }.getOrNull()
    }

    /** Sets canonical Transform/Rotation in degrees. */
    fun setLayerRotationEcproj(json: String, compositionId: Long, layerId: Long, degrees: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerRotationEcprojNative(json, compositionId, layerId, degrees) }.getOrNull()
    }

    /**
     * Authors one canonical Transform/Opacity keyframe at [frame] (0–100 percent).
     * The first key seeds frame 0 with the layer's current opacity, so authoring
     * a later key never rewrites earlier frames; later keys interpolate linearly
     * and hold past the last key, matching [ai.effectcraft.android.data.PropertyTrackEditor].
     * Returns the edited-project envelope, or null when native is unavailable or the frame is invalid.
     */
    fun setLayerOpacityKeyframeEcproj(json: String, compositionId: Long, layerId: Long, frame: Long, opacityPercent: Double): String? {
        ensureLoaded()
        if (!available || frame < 0) return null
        return runCatching { setLayerOpacityKeyframeEcprojNative(json, compositionId, layerId, frame, opacityPercent) }.getOrNull()
    }

    /**
     * Removes the canonical Transform/Opacity keyframe at [frame]. The envelope
     * carries `removed: true` when a key was deleted and `removed: false` when
     * no key existed at that frame (the project is then returned unchanged).
     * Removing the last key collapses the property back to its static value.
     */
    fun removeLayerOpacityKeyframeEcproj(json: String, compositionId: Long, layerId: Long, frame: Long): String? {
        ensureLoaded()
        if (!available || frame < 0) return null
        return runCatching { removeLayerOpacityKeyframeEcprojNative(json, compositionId, layerId, frame) }.getOrNull()
    }

    /** Sets the upstream Transform/Opacity property (0–100 percent). */
    fun setLayerOpacityEcproj(json: String, compositionId: Long, layerId: Long, opacityPercent: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerOpacityEcprojNative(json, compositionId, layerId, opacityPercent) }.getOrNull()
    }

    /** Relinks a canonical footage item to an app-private offline media copy. */
    fun relinkFootageEcproj(json: String, itemId: Long, path: String): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { relinkFootageEcprojNative(json, itemId, path) }.getOrNull()
    }

    /** Updates the canonical EffectCraft video/visibility switch. */
    fun setLayerVisibilityEcproj(json: String, compositionId: Long, layerId: Long, visible: Boolean): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerVisibilityEcprojNative(json, compositionId, layerId, visible) }.getOrNull()
    }

    /** Reorders a canonical layer by a relative stack offset; upstream model clamps at stack edges. */
    fun moveLayerEcproj(json: String, compositionId: Long, layerId: Long, offset: Int): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { moveLayerEcprojNative(json, compositionId, layerId, offset.toLong()) }.getOrNull()
    }

    /** Removes a layer from the canonical composition while preserving all other IDs. */
    fun deleteLayerEcproj(json: String, compositionId: Long, layerId: Long): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { deleteLayerEcprojNative(json, compositionId, layerId) }.getOrNull()
    }

    private fun ensureLoaded() {
        if (loadAttempted) return
        synchronized(this) {
            if (loadAttempted) return
            available = runCatching {
                System.loadLibrary("effectcraft_android")
            }.isSuccess
            loadAttempted = true
        }
    }

    @JvmStatic
    private external fun setLayerPositionEcprojNative(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String

    @JvmStatic
    private external fun setLayerScaleEcprojNative(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String

    @JvmStatic
    private external fun setLayerRotationEcprojNative(json: String, compositionId: Long, layerId: Long, degrees: Double): String

    @JvmStatic
    private external fun setLayerOpacityEcprojNative(json: String, compositionId: Long, layerId: Long, opacityPercent: Double): String

    @JvmStatic
    private external fun setLayerOpacityKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, frame: Long, opacityPercent: Double): String

    @JvmStatic
    private external fun removeLayerOpacityKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, frame: Long): String

    @JvmStatic
    private external fun renderEcprojFrameNative(json: String, compositionId: Long, frame: Long, maxSide: Long): ByteArray

    @JvmStatic
    private external fun relinkFootageEcprojNative(json: String, itemId: Long, path: String): String

    @JvmStatic
    private external fun setLayerVisibilityEcprojNative(json: String, compositionId: Long, layerId: Long, visible: Boolean): String

    @JvmStatic
    private external fun moveLayerEcprojNative(json: String, compositionId: Long, layerId: Long, offset: Long): String

    @JvmStatic
    private external fun deleteLayerEcprojNative(json: String, compositionId: Long, layerId: Long): String

    @JvmStatic
    private external fun addTextLayerEcprojNative(json: String, compositionId: Long, name: String): String

    @JvmStatic
    private external fun inspectEcprojNative(json: String): String

    @JvmStatic
    private external fun renameLayerEcprojNative(json: String, compositionId: Long, layerId: Long, name: String): String
}
