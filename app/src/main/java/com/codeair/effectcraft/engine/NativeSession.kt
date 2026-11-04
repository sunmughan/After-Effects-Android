package com.codeair.effectcraft.engine

import org.json.JSONObject

/**
 * One owner for a native render session: a parsed canonical project, its own
 * native MediaPool, and a preview size, living behind a JNI handle.
 *
 * Lifecycle: [open] parses once and returns null when the native library is
 * unavailable, the JSON is invalid, or the preview size is out of range.
 * [render] serves frames from the session (through the shared frame cache)
 * without re-parsing the project. [updateProject] swaps the parsed project
 * in place so canonical edits reach the next frame without reopening.
 * [resize] changes the preview size for subsequent frames. [cancel]
 * abandons the session's render stream: later renders return null and the
 * session must be [close]d. Cancellation is cooperative on the native side
 * — it discards in-flight results at the next check but cannot interrupt a
 * CPU frame already executing. [close] is idempotent and safe to call from
 * any thread; a closed session renders nothing.
 *
 * Render backend: the native side renders through the upstream wgpu GPU
 * compositor (Vulkan on Android) when a usable device exists, and falls
 * back to the upstream CPU compositor otherwise — never a fake frame.
 * [backendStatus] reports which backend is live and why CPU was chosen;
 * a real driver device loss retires the GPU handle and later frames keep
 * rendering on the CPU, and [recoverGpu] (or close + reopen) restores GPU
 * rendering when a device is available again. No GPU presence is ever
 * claimed on hosts without one: on a GPU-less device the status honestly
 * reports CPU plus the probe failure.
 *
 * The bootstrap .efx-draft is not a canonical project and is never opened
 * as a session: draft-only state keeps its explicit "Draft preview not
 * rendered" label instead of a faked frame.
 */
class NativeSession private constructor(
    @Volatile private var handle: Long,
    @Volatile private var projectJson: String,
    @Volatile private var maxSide: Int,
) : AutoCloseable {

    companion object {
        const val DEFAULT_MAX_SIDE = 512

        /** Opens a session, or returns null when the native side cannot. */
        fun open(projectJson: String, maxSide: Int = DEFAULT_MAX_SIDE): NativeSession? {
            if (!NativeProjectBridge.isAvailable()) return null
            if (maxSide !in 0..4096) return null
            val handle = runCatching { createSessionNative(projectJson, maxSide.toLong()) }.getOrNull()
                ?: return null
            if (handle <= 0L) return null
            return NativeSession(handle, projectJson, maxSide)
        }

        @JvmStatic
        private external fun createSessionNative(json: String, maxSide: Long): Long

        @JvmStatic
        private external fun renderSessionFrameNative(handle: Long, compositionId: Long, frame: Long): ByteArray

        @JvmStatic
        private external fun updateSessionProjectNative(handle: Long, json: String): Boolean

        @JvmStatic
        private external fun resizeSessionNative(handle: Long, maxSide: Long): Boolean

        @JvmStatic
        private external fun cancelSessionNative(handle: Long)

        @JvmStatic
        private external fun closeSessionNative(handle: Long): Boolean

        @JvmStatic
        private external fun backendStatusSessionNative(handle: Long): String

        @JvmStatic
        private external fun recoverGpuSessionNative(handle: Long): Boolean

        @JvmStatic
        private external fun simulateGpuLossSessionNative(handle: Long)
    }

    /**
     * Which compositor renders this session's frames. `backend` is "gpu"
     * when a live wgpu device is attached and "cpu" otherwise; `adapter`
     * names the device when GPU-backed; `reason` explains CPU fallback
     * (probe failure or device loss); `lost` is true after a device loss
     * until [recoverGpu] re-attaches.
     */
    data class BackendStatus(
        val backend: String,
        val adapter: String?,
        val reason: String?,
        val lost: Boolean,
    ) {
        val isGpu: Boolean get() = backend == "gpu"
    }

    @Volatile
    private var cancelled = false

    @Synchronized
    fun isOpen(): Boolean = handle != 0L

    @Synchronized
    fun currentMaxSide(): Int = maxSide

    /** Renders a frame through the session, or null when unavailable/cancelled/closed. */
    // Deliberately not synchronized: native render may take seconds. Holding the
    // Kotlin monitor across JNI would prevent cancel() from setting the native
    // cancellation flag until the frame had already completed.
    fun render(compositionId: Long, frame: Long): ByteArray? {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled || compositionId < 0 || frame < 0) return null
        val key = RenderedFrameCache.key(projectJson, compositionId, frame, maxSide)
        val rendered = RenderedFrameCache.getOrRender(key) {
            runCatching { renderSessionFrameNative(sessionHandle, compositionId, frame) }
                .getOrNull()?.takeIf { it.size >= 8 }
        }
        // A cancellation racing a cache hit/render must not publish a stale frame.
        return rendered?.takeUnless { cancelled }
    }

    /**
     * Swaps the session's project. A no-op when the JSON is unchanged.
     * Returns false when closed, cancelled, or rejected by the native side;
     * on rejection the previous project keeps rendering.
     */
    @Synchronized
    fun updateProject(json: String): Boolean {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled) return false
        if (json == projectJson) return true
        val accepted = runCatching { updateSessionProjectNative(sessionHandle, json) }.getOrDefault(false)
        if (accepted) projectJson = json
        return accepted
    }

    /** Changes the preview size for subsequent frames; 0 means full scale. */
    @Synchronized
    fun resize(newMaxSide: Int): Boolean {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled || newMaxSide !in 0..4096) return false
        val accepted = runCatching { resizeSessionNative(sessionHandle, newMaxSide.toLong()) }.getOrDefault(false)
        if (accepted) maxSide = newMaxSide
        return accepted
    }

    /**
     * Reports this session's live render backend, or null when closed,
     * cancelled, or the native side cannot answer. The first call may
     * probe for a GPU device and can block briefly: call from a background
     * dispatcher, never from composition.
     */
    @Synchronized
    fun backendStatus(): BackendStatus? {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled) return null
        val json = runCatching { backendStatusSessionNative(sessionHandle) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val obj = JSONObject(json)
            BackendStatus(
                backend = obj.getString("backend"),
                adapter = if (obj.isNull("adapter")) null else obj.optString("adapter"),
                reason = if (obj.isNull("reason")) null else obj.optString("reason"),
                lost = obj.optBoolean("lost", false),
            )
        }.getOrNull()
    }

    /**
     * Re-probes for a GPU device and re-attaches this session when one is
     * found. Returns true when the session ends up GPU-backed; false (the
     * session stays CPU-backed and keeps rendering) when no device is
     * available. A no-op success when already GPU-backed, false when
     * closed or cancelled. May block briefly on device probing: call from
     * a background dispatcher.
     */
    @Synchronized
    fun recoverGpu(): Boolean {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled) return false
        return runCatching { recoverGpuSessionNative(sessionHandle) }.getOrDefault(false)
    }

    /**
     * Diagnostic/test hook only, not wired to any UI: drops this session's
     * GPU device and marks it lost, modelling a driver device loss.
     * Rendering continues on the CPU; [recoverGpu] (or close + reopen)
     * restores GPU rendering when a device is available again.
     */
    @Synchronized
    fun simulateGpuLoss() {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled) return
        runCatching { simulateGpuLossSessionNative(sessionHandle) }
    }

    /** Abandons the render stream; the session still needs [close]. */
    @Synchronized
    fun cancel() {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled) return
        cancelled = true
        runCatching { cancelSessionNative(sessionHandle) }
    }

    /** Releases the native session. Idempotent; safe to call repeatedly. */
    @Synchronized
    override fun close() {
        val sessionHandle = handle
        if (sessionHandle == 0L) return
        handle = 0L
        runCatching { closeSessionNative(sessionHandle) }
    }
}
