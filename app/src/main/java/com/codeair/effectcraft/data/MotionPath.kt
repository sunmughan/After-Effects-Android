package com.codeair.effectcraft.data

/**
 * Motion-path math for spatial (position/anchor) tracks, mirroring the
 * upstream `effectcraft-keyframe` evaluation exactly so the overlay draws
 * the path the renderer follows: auto-Bezier tangents are Catmull-Rom
 * (next - prev) / 6, and each segment is the cubic Bezier through
 * p0 + out, p3 + in.
 */
object MotionPath {
    data class Point(val x: Double, val y: Double)

    fun isSpatialProperty(property: String): Boolean = property == "position" || property == "anchor"

    private fun componentsOf(key: CanonicalCurveKey): Triple<Double, Double, Double> {
        val c = key.components
        return Triple(c.getOrElse(0) { 0.0 }, c.getOrElse(1) { 0.0 }, c.getOrElse(2) { 0.0 })
    }

    /**
     * Effective spatial tangents of key [index] as (in, out): the stored
     * tangents when [CanonicalCurveKey.spatialAuto] is false, else the
     * upstream Catmull-Rom auto tangents — the out tangent is a sixth of
     * the way from the previous key to the next, the in tangent its
     * mirror, and a side facing a key at the same position gets none.
     */
    fun effectiveTangents(keys: List<CanonicalCurveKey>, index: Int): Pair<List<Double>, List<Double>> {
        val zero = listOf(0.0, 0.0, 0.0)
        if (index !in keys.indices || keys.size < 2) return Pair(zero, zero)
        val key = keys[index]
        if (!key.spatialAuto) return Pair(key.spatialIn, key.spatialOut)
        val a = componentsOf(keys[(index - 1).coerceAtLeast(0)])
        val c = componentsOf(keys[(index + 1).coerceAtMost(keys.lastIndex)])
        val p = componentsOf(key)
        val tout = listOf((c.first - a.first) / 6.0, (c.second - a.second) / 6.0, (c.third - a.third) / 6.0)
        val tin = if (index > 0 && a == p) zero else tout.map { -it }
        val toutFixed = if (index + 1 < keys.size && c == p) zero else tout
        return Pair(tin, toutFixed)
    }

    /**
     * Sampled motion path through the keys, one cubic Bezier per segment
     * with control points p0 + out and p3 + in. Empty for fewer than two
     * keys; the last key is always the final point.
     */
    fun samplePath(keys: List<CanonicalCurveKey>, stepsPerSegment: Int = 24): List<Point> {
        if (keys.size < 2) return emptyList()
        val points = mutableListOf<Point>()
        for (i in 0 until keys.lastIndex) {
            val p0 = componentsOf(keys[i])
            val p3 = componentsOf(keys[i + 1])
            val (_, out0) = effectiveTangents(keys, i)
            val (in1, _) = effectiveTangents(keys, i + 1)
            val c1 = Triple(p0.first + out0[0], p0.second + out0[1], p0.third + out0[2])
            val c2 = Triple(p3.first + in1[0], p3.second + in1[1], p3.third + in1[2])
            val steps = stepsPerSegment.coerceAtLeast(1)
            for (s in 0 until steps) {
                points += bezierPoint(p0, c1, c2, p3, s.toDouble() / steps)
            }
        }
        val last = componentsOf(keys.last())
        points += Point(last.first, last.second)
        return points
    }

    private fun bezierPoint(
        p0: Triple<Double, Double, Double>,
        c1: Triple<Double, Double, Double>,
        c2: Triple<Double, Double, Double>,
        p3: Triple<Double, Double, Double>,
        u: Double
    ): Point {
        val v = 1.0 - u
        val x = v * v * v * p0.first + 3 * v * v * u * c1.first + 3 * v * u * u * c2.first + u * u * u * p3.first
        val y = v * v * v * p0.second + 3 * v * v * u * c1.second + 3 * v * u * u * c2.second + u * u * u * p3.second
        return Point(x, y)
    }
}
