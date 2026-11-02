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
    private external fun inspectEcprojNative(json: String): String

    @JvmStatic
    private external fun renameLayerEcprojNative(json: String, compositionId: Long, layerId: Long, name: String): String
}
