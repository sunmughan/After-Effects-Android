package ai.effectcraft.android.engine

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
 * The bootstrap .efx-draft is not a canonical project and is never opened
 * as a session: draft-only state keeps its explicit "Draft preview not
 * rendered" label instead of a faked frame.
 */
class NativeSession private constructor(
    private var handle: Long,
    private var projectJson: String,
    private var maxSide: Int,
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
    }

    @Volatile
    private var cancelled = false

    @Synchronized
    fun isOpen(): Boolean = handle != 0L

    @Synchronized
    fun currentMaxSide(): Int = maxSide

    /** Renders a frame through the session, or null when unavailable/cancelled/closed. */
    @Synchronized
    fun render(compositionId: Long, frame: Long): ByteArray? {
        val sessionHandle = handle
        if (sessionHandle == 0L || cancelled || compositionId < 0 || frame < 0) return null
        val key = RenderedFrameCache.key(projectJson, compositionId, frame, maxSide)
        return RenderedFrameCache.getOrRender(key) {
            runCatching { renderSessionFrameNative(sessionHandle, compositionId, frame) }
                .getOrNull()?.takeIf { it.size >= 8 }
        }
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
