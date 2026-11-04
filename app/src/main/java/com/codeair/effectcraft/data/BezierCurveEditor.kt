package com.codeair.effectcraft.data

import kotlin.math.abs
import kotlin.math.cbrt

/**
 * One side of a Bezier keyframe handle, in upstream terms: speed in value
 * units per second and influence as a 0..1 fraction of the adjacent segment
 * duration. Speed may be negative on a falling segment (its sign follows the
 * segment direction); influence is always 0..1. A null handle on a key side
 * means that side is linear, not Bezier.
 */
data class CurveHandle(val speed: Double, val influence: Double) {
    init {
        require(speed.isFinite()) { "Curve handle speed must be finite" }
        require(influence.isFinite() && influence in 0.0..1.0) { "Curve handle influence must be between 0 and 1" }
    }
}

/** Interpolation mode of one side of a curve key. */
enum class CurveInterp { LINEAR, BEZIER, HOLD }

/**
 * One key on a curve graph: a frame, a scalar value (for vector properties
 * the graph plots the first component), and the per-side handle state.
 * Handles are meaningful only on a side that has a neighbouring key; the
 * first key has no incoming side and the last key has no outgoing side.
 */
data class CurveKey(
    val frame: Int,
    val value: Double,
    val inHandle: CurveHandle? = null,
    val outHandle: CurveHandle? = null,
    val inInterp: CurveInterp = if (inHandle == null) CurveInterp.LINEAR else CurveInterp.BEZIER,
    val outInterp: CurveInterp = if (outHandle == null) CurveInterp.LINEAR else CurveInterp.BEZIER
) {
    init {
        require(frame >= 0) { "Curve key frame must be non-negative" }
        require(value.isFinite()) { "Curve key value must be finite" }
    }
}

/** A named property track on the curve graph, keys sorted by frame. */
data class CurveTrack(val property: String, val keys: List<CurveKey>)

/** One sampled point on a curve graph, in data coordinates. */
data class CurvePoint(val frame: Double, val value: Double)

/**
 * Pure, Compose-free curve graph editing and evaluation. The sampler ports
 * the upstream cubic Bezier time curve exactly: normalised speeds
 * (speed * segment seconds / value delta) with per-side influences, solved
 * with the same Newton-plus-bisection inverse as upstream.
 */
object BezierCurveEditor {

    /** Upstream default ease for a Bezier side that carries no handle data. */
    val defaultHandle = CurveHandle(speed = 0.0, influence = 1.0 / 6.0)

    fun upsert(track: CurveTrack, key: CurveKey): CurveTrack {
        require(track.property.isNotBlank()) { "Property name must not be blank" }
        val next = track.keys.filterNot { it.frame == key.frame } + key
        return track.copy(keys = next.sortedBy { it.frame })
    }

    /**
     * Drags the key at [fromFrame] to [toFrame]. The dragged key keeps its
     * value and handles. Dragging past a neighbour reorders the track (keys
     * stay sorted); dragging exactly onto a neighbour replaces that neighbour,
     * matching same-frame upsert semantics. Negative targets and missing
     * source keys are rejected.
     */
    fun move(track: CurveTrack, fromFrame: Int, toFrame: Int): CurveTrack {
        require(toFrame >= 0) { "Drag target frame must be non-negative" }
        val dragged = track.keys.firstOrNull { it.frame == fromFrame }
            ?: throw IllegalArgumentException("No curve key at frame $fromFrame")
        if (fromFrame == toFrame) return track.copy(keys = track.keys.sortedBy { it.frame })
        val next = track.keys
            .filterNot { it.frame == fromFrame || it.frame == toFrame } + dragged.copy(frame = toFrame)
        return track.copy(keys = next.sortedBy { it.frame })
    }

    /**
     * Sets (or, with a null handle, clears to linear) one side handle of the
     * key at [frame]. A side without a neighbouring key has no segment to
     * shape, so editing the incoming handle of the first key or the outgoing
     * handle of the last key is rejected.
     */
    fun withHandle(track: CurveTrack, frame: Int, side: String, handle: CurveHandle?): CurveTrack {
        require(side == "incoming" || side == "outgoing") { "Curve handle side must be incoming or outgoing" }
        val index = track.keys.indexOfFirst { it.frame == frame }
        require(index >= 0) { "No curve key at frame $frame" }
        if (side == "incoming") {
            require(index > 0) { "First curve key has no incoming handle" }
        } else {
            require(index < track.keys.lastIndex) { "Last curve key has no outgoing handle" }
        }
        val key = track.keys[index]
        val next = if (side == "incoming") {
            key.copy(inHandle = handle, inInterp = if (handle == null) CurveInterp.LINEAR else CurveInterp.BEZIER)
        } else {
            key.copy(outHandle = handle, outInterp = if (handle == null) CurveInterp.LINEAR else CurveInterp.BEZIER)
        }
        return track.copy(keys = track.keys.toMutableList().also { it[index] = next })
    }

    /**
     * Derives a handle from a pointer position in data coordinates (frame and
     * value) dragged from [keyFrame]/[keyValue] towards its neighbour. The
     * influence is the pointer's fractional distance along the segment; the
     * speed follows from the pointer's value offset through the upstream
     * normalised-speed relation. A pointer on the far side of the key value
     * yields a flat (zero-speed) handle instead of a negative speed.
     */
    fun handleFromPointer(
        keyFrame: Int,
        keyValue: Double,
        neighborFrame: Int,
        neighborValue: Double,
        pointerFrame: Double,
        pointerValue: Double,
        framesPerSecond: Double,
        side: String
    ): CurveHandle {
        require(framesPerSecond > 0.0 && framesPerSecond.isFinite()) { "Frames per second must be positive and finite" }
        require(side == "incoming" || side == "outgoing") { "Curve handle side must be incoming or outgoing" }
        val durationFrames = abs(neighborFrame - keyFrame).toDouble()
        if (durationFrames <= 0.0) return CurveHandle(0.0, 0.0)
        val along = if (side == "outgoing") pointerFrame - keyFrame else keyFrame - pointerFrame
        val influence = (along / durationFrames).coerceIn(0.0, 1.0)
        if (influence <= 0.0) return CurveHandle(0.0, 0.0)
        val deltaValue = neighborValue - keyValue
        if (deltaValue == 0.0) return CurveHandle(0.0, influence)
        val offsetRatio = (pointerValue - keyValue) / deltaValue
        if (offsetRatio <= 0.0) return CurveHandle(0.0, influence)
        val normalisedSpeed = offsetRatio / influence
        val durationSeconds = durationFrames / framesPerSecond
        return CurveHandle(speed = normalisedSpeed * deltaValue / durationSeconds, influence = influence)
    }

    /**
     * Samples the whole track, [samplesPerSegment] intervals per segment.
     * Hold segments hold the left value; linear segments interpolate
     * linearly; Bezier segments use the per-side handles (a missing handle
     * on a Bezier side falls back to the upstream default ease).
     */
    fun sample(track: CurveTrack, samplesPerSegment: Int = 16, framesPerSecond: Double = 30.0): List<CurvePoint> {
        require(samplesPerSegment >= 1) { "Samples per segment must be at least 1" }
        require(framesPerSecond > 0.0 && framesPerSecond.isFinite()) { "Frames per second must be positive and finite" }
        val keys = track.keys.sortedBy { it.frame }
        if (keys.isEmpty()) return emptyList()
        if (keys.size == 1) return listOf(CurvePoint(keys[0].frame.toDouble(), keys[0].value))
        val points = mutableListOf<CurvePoint>()
        for (segment in 0 until keys.lastIndex) {
            val left = keys[segment]
            val right = keys[segment + 1]
            val durationFrames = (right.frame - left.frame).toDouble()
            val durationSeconds = durationFrames / framesPerSecond
            val deltaValue = right.value - left.value
            for (step in 0 until samplesPerSegment) {
                val progress = step.toDouble() / samplesPerSegment
                val value = when {
                    left.outInterp == CurveInterp.HOLD -> left.value
                    left.outInterp == CurveInterp.LINEAR && right.inInterp == CurveInterp.LINEAR -> {
                        left.value + deltaValue * progress
                    }
                    deltaValue == 0.0 -> left.value
                    else -> {
                        val outHandle = left.outHandle ?: defaultHandle
                        val inHandle = right.inHandle ?: defaultHandle
                        val outSpeedN = outHandle.speed * durationSeconds / deltaValue
                        val inSpeedN = inHandle.speed * durationSeconds / deltaValue
                        val eased = easeProgress(outSpeedN, outHandle.influence, inSpeedN, inHandle.influence, progress)
                        left.value + deltaValue * eased
                    }
                }
                points.add(CurvePoint(left.frame + durationFrames * progress, value))
            }
        }
        points.add(CurvePoint(keys.last().frame.toDouble(), keys.last().value))
        return points
    }

    /** Upstream cubic Bezier in one dimension. */
    internal fun bez(p0: Double, p1: Double, p2: Double, p3: Double, u: Double): Double {
        val v = 1.0 - u
        return v * v * v * p0 + 3.0 * v * v * u * p1 + 3.0 * v * u * u * p2 + u * u * u * p3
    }

    internal fun bezDerivative(p0: Double, p1: Double, p2: Double, p3: Double, u: Double): Double {
        val v = 1.0 - u
        return 3.0 * v * v * (p1 - p0) + 6.0 * v * u * (p2 - p1) + 3.0 * u * u * (p3 - p2)
    }

    /** Upstream inverse: solves bez(x0..x3, u) = x for u in 0..1. */
    internal fun solveU(x0: Double, x1: Double, x2: Double, x3: Double, x: Double): Double {
        if (x <= x0) return 0.0
        if (x >= x3) return 1.0
        if (x1 == x3 && x2 == x0) {
            val progress = (x - x0) / (x3 - x0)
            return 0.5 + cbrt((progress - 0.5) * 0.25)
        }
        var u = (x - x0) / (x3 - x0)
        for (ignored in 0 until 8) {
            val f = bez(x0, x1, x2, x3, u) - x
            val d = bezDerivative(x0, x1, x2, x3, u)
            if (abs(d) < 1e-12) break
            if (abs(f / d) < 1e-12) return u
            val next = u - f / d
            if (next !in 0.0..1.0) break
            u = next
        }
        var lo = 0.0
        var hi = 1.0
        for (ignored in 0 until 60) {
            val mid = (lo + hi) * 0.5
            if (bez(x0, x1, x2, x3, mid) < x) lo = mid else hi = mid
        }
        return (lo + hi) * 0.5
    }

    /**
     * Upstream temporal ease progress: fraction of the value delta reached at
     * normalised time [x], given normalised out/in speeds and influences.
     * Influences are clamped to 0..1, matching upstream.
     */
    fun easeProgress(outSpeedN: Double, outInfluence: Double, inSpeedN: Double, inInfluence: Double, x: Double): Double {
        val i0 = outInfluence.coerceIn(0.0, 1.0)
        val i1 = inInfluence.coerceIn(0.0, 1.0)
        val u = solveU(0.0, i0, 1.0 - i1, 1.0, x)
        return bez(0.0, outSpeedN * i0, 1.0 - inSpeedN * i1, 1.0, u)
    }
}
