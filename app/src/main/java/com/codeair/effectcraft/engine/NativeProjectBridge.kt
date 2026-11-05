package com.codeair.effectcraft.engine

import kotlin.math.absoluteValue

/**
 * Canonical upstream Transform properties with editable native keyframe tracks.
 * Keep this whitelist aligned with the Rust bridge's recursive Transform lookup.
 */
internal fun isSupportedTransformKeyframeProperty(property: String): Boolean =
    property in setOf("position", "scale", "rotation", "anchor", "orientation", "rotationX", "rotationY")

internal fun isSupportedCurveSelector(selector: String): Boolean =
    selector in setOf(
        "transform:position", "transform:scale", "transform:rotation", "transform:opacity",
        "transform:anchor", "transform:orientation", "transform:rotationX", "transform:rotationY"
    ) || (selector.startsWith("param:") && selector.removePrefix("param:").toLongOrNull()?.let { it >= 0 } == true)

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

    /** Authors a native Transform keyframe at a composition frame. */
    fun setLayerTransformKeyframeEcproj(json: String, compositionId: Long, layerId: Long, property: String, frame: Long, x: Double, y: Double, z: Double): String? {
        ensureLoaded()
        if (!available || frame < 0 || !isSupportedTransformKeyframeProperty(property)) return null
        return runCatching { setLayerTransformKeyframeEcprojNative(json, compositionId, layerId, property, frame, x, y, z) }.getOrNull()
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

    /** Sets canonical Transform/Anchor Point X and Y while preserving the existing Z value. */
    fun setLayerAnchorEcproj(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerAnchorEcprojNative(json, compositionId, layerId, x, y) }.getOrNull()
    }

    /** Sets canonical Transform/Orientation X, Y and Z in degrees. */
    fun setLayerOrientationEcproj(json: String, compositionId: Long, layerId: Long, x: Double, y: Double, z: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerOrientationEcprojNative(json, compositionId, layerId, x, y, z) }.getOrNull()
    }

    /** Sets canonical Transform/X Rotation in degrees. */
    fun setLayerRotationXEcproj(json: String, compositionId: Long, layerId: Long, degrees: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerRotationXEcprojNative(json, compositionId, layerId, degrees) }.getOrNull()
    }

    /** Sets canonical Transform/Y Rotation in degrees. */
    fun setLayerRotationYEcproj(json: String, compositionId: Long, layerId: Long, degrees: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerRotationYEcprojNative(json, compositionId, layerId, degrees) }.getOrNull()
    }

    /**
     * Sets the canonical layer lock switch. This is the only layer edit that
     * is allowed on a locked layer, so a locked layer can always be unlocked.
     */
    fun setLayerLockedEcproj(json: String, compositionId: Long, layerId: Long, locked: Boolean): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerLockedEcprojNative(json, compositionId, layerId, locked) }.getOrNull()
    }

    /** Sets the Source Text string of a canonical Text layer. */
    fun setTextLayerTextEcproj(json: String, compositionId: Long, layerId: Long, text: String): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setTextLayerTextEcprojNative(json, compositionId, layerId, text) }.getOrNull()
    }

    /** Sets the Source Text size of a canonical Text layer. */
    fun setTextLayerSizeEcproj(json: String, compositionId: Long, layerId: Long, size: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setTextLayerSizeEcprojNative(json, compositionId, layerId, size) }.getOrNull()
    }

    /** Sets the Source Text tracking of a canonical Text layer. */
    fun setTextLayerTrackingEcproj(json: String, compositionId: Long, layerId: Long, tracking: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setTextLayerTrackingEcprojNative(json, compositionId, layerId, tracking) }.getOrNull()
    }

    /** Sets one scalar shape-item or effect parameter, addressed by its native uid. */
    fun setLayerScalarPropertyEcproj(json: String, compositionId: Long, layerId: Long, uid: Long, value: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerScalarPropertyEcprojNative(json, compositionId, layerId, uid, value) }.getOrNull()
    }

    /**
     * Sets one vector shape-item or effect parameter, addressed by its native
     * uid. Vec2 parameters use X and Y and ignore Z; Vec3 parameters use all three.
     */
    fun setLayerVecPropertyEcproj(json: String, compositionId: Long, layerId: Long, uid: Long, x: Double, y: Double, z: Double): String? {
        ensureLoaded()
        if (!available) return null
        return runCatching { setLayerVecPropertyEcprojNative(json, compositionId, layerId, uid, x, y, z) }.getOrNull()
    }

    /** Sets a straight-RGBA color parameter by stable native uid; components are 0..1. */
    fun setLayerColorPropertyEcproj(json: String, compositionId: Long, layerId: Long, uid: Long, red: Double, green: Double, blue: Double, alpha: Double): String? {
        ensureLoaded()
        if (!available || uid < 0 || listOf(red, green, blue, alpha).any { !it.isFinite() || it !in 0.0..1.0 }) return null
        return runCatching { setLayerColorPropertyEcprojNative(json, compositionId, layerId, uid, red, green, blue, alpha) }.getOrNull()
    }
    
    /** Authors a canonical RGBA parameter keyframe at a composition frame. */
    fun setLayerColorKeyframeEcproj(json: String, compositionId: Long, layerId: Long, uid: Long, frame: Long, red: Double, green: Double, blue: Double, alpha: Double): String? {
        ensureLoaded()
        if (!available || uid < 0 || frame < 0 || listOf(red, green, blue, alpha).any { !it.isFinite() || it !in 0.0..1.0 }) return null
        return runCatching { setLayerColorKeyframeEcprojNative(json, compositionId, layerId, uid, frame, red, green, blue, alpha) }.getOrNull()
    }

    /**
     * Authors one canonical Transform/Opacity keyframe at [frame] (0–100 percent).
     * The first key seeds frame 0 with the layer's current opacity, so authoring
     * a later key never rewrites earlier frames; later keys interpolate linearly
     * and hold past the last key, matching [com.codeair.effectcraft.data.PropertyTrackEditor].
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

    /** Applies Linear, Easy Ease, directional Ease In/Out/In-Out, asymmetric Bezier or Hold to both or one side of an existing Transform key. Directional presets keep their own side regardless of any incoming_/outgoing_ qualifier. */
    fun setLayerTransformKeyframeInterpolationEcproj(json: String, compositionId: Long, layerId: Long, property: String, frame: Long, preset: String): String? {
        ensureLoaded()
        val base = setOf("linear", "easy_ease", "ease_in", "ease_out", "ease_in_out", "bezier", "hold")
        val presets = base + base.map { "incoming_$it" } + base.map { "outgoing_$it" }
        if (!available || frame < 0 || !isSupportedTransformKeyframeProperty(property) || preset !in presets) return null
        return runCatching { setLayerTransformKeyframeInterpolationEcprojNative(json, compositionId, layerId, property, frame, preset) }.getOrNull()
    }

    /**
     * Applies one custom Bezier handle to a curve key. [selector] is
     * "transform:position", "transform:scale", "transform:rotation",
     * "transform:opacity", or "param:<uid>" for a shape-item or effect
     * parameter. [side] is "incoming", "outgoing" or "both"; [speed] is in
     * value units per second and [influence] is a 0..1 fraction of the
     * adjacent segment. Returns the edited-project envelope, or null when
     * native is unavailable or any argument is invalid.
     */
    fun setLayerKeyframeBezierEcproj(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long, side: String, speed: Double, influence: Double): String? {
        ensureLoaded()
        if (!available || frame < 0 || side !in setOf("incoming", "outgoing", "both") || !speed.isFinite() || influence !in 0.0..1.0 || !isSupportedCurveSelector(selector)) return null
        return runCatching { setLayerKeyframeBezierEcprojNative(json, compositionId, layerId, selector, frame, side, speed, influence) }.getOrNull()
    }

    /**
     * Drags a curve key from [fromFrame] to [toFrame], keeping its value and
     * Bezier sides. Dragging past a neighbour reorders the track; dragging
     * exactly onto a neighbour replaces it. Returns the edited-project
     * envelope, or null when native is unavailable or any argument is invalid.
     */
    fun moveLayerKeyframeEcproj(json: String, compositionId: Long, layerId: Long, selector: String, fromFrame: Long, toFrame: Long): String? {
        ensureLoaded()
        if (!available || fromFrame < 0 || toFrame < 0 || !isSupportedCurveSelector(selector)) return null
        return runCatching { moveLayerKeyframeEcprojNative(json, compositionId, layerId, selector, fromFrame, toFrame) }.getOrNull()
    }

    /**
     * Toggles roving ("Rove Across Time") on one key of a spatial
     * property. [selector] is "transform:position" or "transform:anchor".
     * The roving run is re-timed by path length, so the toggle takes
     * effect immediately; first/last keys can never rove. Returns the
     * edited-project envelope, or null when native is unavailable or any
     * argument is invalid.
     */
    fun setLayerKeyframeRovingEcproj(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long, roving: Boolean): String? {
        ensureLoaded()
        if (!available || frame < 0 || !isSupportedCurveSelector(selector)) return null
        return runCatching { setLayerKeyframeRovingEcprojNative(json, compositionId, layerId, selector, frame, roving) }.getOrNull()
    }

    /**
     * Sets the spatial (motion-path) tangents of one key of a spatial
     * property, relative to the key value, in comp pixels. [spatialAuto]
     * selects the upstream Catmull-Rom auto-Bezier tangents instead of
     * the stored ones; zero tangents with [spatialAuto] false is the
     * straight (linear-path) preset. Tangents change path length, so the
     * roving run is re-timed. Returns the edited-project envelope, or
     * null when native is unavailable or any argument is invalid.
     */
    fun setLayerKeyframeSpatialTangentsEcproj(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long, inX: Double, inY: Double, inZ: Double, outX: Double, outY: Double, outZ: Double, spatialAuto: Boolean): String? {
        ensureLoaded()
        val tangents = listOf(inX, inY, inZ, outX, outY, outZ)
        if (!available || frame < 0 || !isSupportedCurveSelector(selector) || tangents.any { !it.isFinite() || it.absoluteValue > 1_000_000.0 }) return null
        return runCatching { setLayerKeyframeSpatialTangentsEcprojNative(json, compositionId, layerId, selector, frame, inX, inY, inZ, outX, outY, outZ, spatialAuto) }.getOrNull()
    }

    /**
     * Re-times the roving run on one curve property without changing any
     * key, for callers that edited the track in bulk. Returns the
     * edited-project envelope, or null when native is unavailable or any
     * argument is invalid.
     */
    fun retimeLayerRovingKeysEcproj(json: String, compositionId: Long, layerId: Long, selector: String): String? {
        ensureLoaded()
        if (!available || !isSupportedCurveSelector(selector)) return null
        return runCatching { retimeLayerRovingKeysEcprojNative(json, compositionId, layerId, selector) }.getOrNull()
    }

    /**
     * Authors one shape-item or effect scalar/vector parameter keyframe at
     * [frame], addressed by its native uid. The first key seeds frame 0 with
     * the parameter's current value. Returns the edited-project envelope,
     * or null when native is unavailable or any argument is invalid.
     */
    fun setLayerParamKeyframeEcproj(json: String, compositionId: Long, layerId: Long, uid: Long, frame: Long, x: Double, y: Double, z: Double): String? {
        ensureLoaded()
        if (!available || uid < 0 || frame < 0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        return runCatching { setLayerParamKeyframeEcprojNative(json, compositionId, layerId, uid, frame, x, y, z) }.getOrNull()
    }

    /** Removes a native Transform key at [frame]; removal is idempotent. */
    fun removeLayerTransformKeyframeEcproj(json: String, compositionId: Long, layerId: Long, property: String, frame: Long): String? {
        ensureLoaded()
        if (!available || frame < 0 || !isSupportedTransformKeyframeProperty(property)) return null
        return runCatching { removeLayerTransformKeyframeEcprojNative(json, compositionId, layerId, property, frame) }.getOrNull()
    }

    /** Removes a native key from any curve selector (Transform or param UID); idempotent. */
    fun removeLayerCurveKeyframeEcproj(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long): String? {
        ensureLoaded()
        if (!available || frame < 0 || !isSupportedCurveSelector(selector)) return null
        return runCatching { removeLayerCurveKeyframeEcprojNative(json, compositionId, layerId, selector, frame) }.getOrNull()
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
    private external fun setLayerTransformKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, property: String, frame: Long, x: Double, y: Double, z: Double): String

    @JvmStatic
    private external fun setLayerPositionEcprojNative(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String

    @JvmStatic
    private external fun setLayerScaleEcprojNative(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String

    @JvmStatic
    private external fun setLayerRotationEcprojNative(json: String, compositionId: Long, layerId: Long, degrees: Double): String

    @JvmStatic
    private external fun setLayerAnchorEcprojNative(json: String, compositionId: Long, layerId: Long, x: Double, y: Double): String

    @JvmStatic
    private external fun setLayerOrientationEcprojNative(json: String, compositionId: Long, layerId: Long, x: Double, y: Double, z: Double): String

    @JvmStatic
    private external fun setLayerRotationXEcprojNative(json: String, compositionId: Long, layerId: Long, degrees: Double): String

    @JvmStatic
    private external fun setLayerRotationYEcprojNative(json: String, compositionId: Long, layerId: Long, degrees: Double): String

    @JvmStatic
    private external fun setLayerLockedEcprojNative(json: String, compositionId: Long, layerId: Long, locked: Boolean): String

    @JvmStatic
    private external fun setTextLayerTextEcprojNative(json: String, compositionId: Long, layerId: Long, text: String): String

    @JvmStatic
    private external fun setTextLayerSizeEcprojNative(json: String, compositionId: Long, layerId: Long, size: Double): String

    @JvmStatic
    private external fun setTextLayerTrackingEcprojNative(json: String, compositionId: Long, layerId: Long, tracking: Double): String

    @JvmStatic
    private external fun setLayerScalarPropertyEcprojNative(json: String, compositionId: Long, layerId: Long, uid: Long, value: Double): String

    @JvmStatic
    private external fun setLayerVecPropertyEcprojNative(json: String, compositionId: Long, layerId: Long, uid: Long, x: Double, y: Double, z: Double): String

    @JvmStatic
    private external fun setLayerColorPropertyEcprojNative(json: String, compositionId: Long, layerId: Long, uid: Long, red: Double, green: Double, blue: Double, alpha: Double): String
    
    @JvmStatic
    private external fun setLayerColorKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, uid: Long, frame: Long, red: Double, green: Double, blue: Double, alpha: Double): String

    @JvmStatic
    private external fun setLayerOpacityEcprojNative(json: String, compositionId: Long, layerId: Long, opacityPercent: Double): String

    @JvmStatic
    private external fun setLayerOpacityKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, frame: Long, opacityPercent: Double): String

    @JvmStatic
    private external fun setLayerTransformKeyframeInterpolationEcprojNative(json: String, compositionId: Long, layerId: Long, property: String, frame: Long, preset: String): String

    @JvmStatic
    private external fun setLayerKeyframeBezierEcprojNative(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long, side: String, speed: Double, influence: Double): String

    @JvmStatic
    private external fun moveLayerKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, selector: String, fromFrame: Long, toFrame: Long): String

    private external fun setLayerKeyframeRovingEcprojNative(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long, roving: Boolean): String

    private external fun setLayerKeyframeSpatialTangentsEcprojNative(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long, inX: Double, inY: Double, inZ: Double, outX: Double, outY: Double, outZ: Double, spatialAuto: Boolean): String

    private external fun retimeLayerRovingKeysEcprojNative(json: String, compositionId: Long, layerId: Long, selector: String): String

    @JvmStatic
    private external fun setLayerParamKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, uid: Long, frame: Long, x: Double, y: Double, z: Double): String

    @JvmStatic
    private external fun removeLayerCurveKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, selector: String, frame: Long): String

    @JvmStatic
    private external fun removeLayerTransformKeyframeEcprojNative(json: String, compositionId: Long, layerId: Long, property: String, frame: Long): String

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
