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
        if (!available) return null
        return runCatching { renderEcprojFrameNative(json, compositionId, frame, maxSide.toLong()) }
            .getOrNull()?.takeIf { it.size >= 8 }
    }

    /** Sets canonical Transform/Position X and Y while preserving the existing Z value. */
    fun setLayerPositionEcproj(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerPositionEcprojNative(json, compositionId, layerId, x, y) }.getOrNull()
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
    private external fun setLayerOpacityEcprojNative(json: String, compositionId: Long, layerId: Long, opacityPercent: Double): String

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
