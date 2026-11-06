package com.codeair.effectcraft.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe, local ShapePath vertex-list operations for the canonical inspector.
 * The upstream ShapePath JSON remains the source of truth; edits preserve all
 * unrelated fields (closed state, feather metadata and custom extensions).
 */
object CanonicalShapePathDraft {
    const val MAX_VERTICES = 4096

    data class Vertex(val x: Double, val y: Double)

    fun vertices(pathJson: String): List<Vertex>? = runCatching {
        val array = JSONObject(pathJson).getJSONArray("vertices")
        List(array.length()) { index ->
            val point = array.getJSONArray(index)
            Vertex(point.getDouble(0), point.getDouble(1))
        }
    }.getOrNull()

    /** Move one vertex while preserving positional tangent and feather metadata. */
    fun moveVertex(pathJson: String, index: Int, vertex: Vertex): String? = runCatching {
        require(vertex.x.isFinite() && vertex.y.isFinite())
        val current = vertices(pathJson)!!.toMutableList()
        require(index in current.indices)
        current[index] = vertex
        setVertices(pathJson, current) ?: error("Invalid ShapePath vertex replacement")
    }.getOrNull()

    fun setVertices(pathJson: String, vertices: List<Vertex>): String? = runCatching {
        require(vertices.isNotEmpty() && vertices.size <= MAX_VERTICES)
        require(vertices.all { it.x.isFinite() && it.y.isFinite() })
        val root = JSONObject(pathJson)
        val original = root.getJSONArray("vertices")
        require(original.length() == vertices.size)
        val replacement = JSONArray()
        vertices.forEach { replacement.put(JSONArray().put(it.x).put(it.y)) }
        root.put("vertices", replacement)
        root.toString()
    }.getOrNull()

    /** Toggle path closure without rewriting vertices, tangents, feather or extension metadata. */
    fun setClosed(pathJson: String, closed: Boolean): String? = runCatching {
        val root = JSONObject(pathJson)
        val count = root.getJSONArray("vertices").length()
        require(count >= if (closed) 3 else 2) { "A closed ShapePath requires at least three vertices" }
        root.put("closed", closed)
        root.toString()
    }.getOrNull()

    /**
     * Insert a vertex on an existing cubic segment using de Casteljau subdivision.
     * The original curve is geometrically preserved: both resulting segments
     * receive the exact split control points, and positional feather metadata
     * is remapped to the corresponding half of the split segment.
     *
     * Segment indices follow vertices: open paths have n-1 segments; closed
     * paths include the final segment from the last vertex back to vertex 0.
     * t must be strictly inside (0,1), avoiding zero-length subdivisions.
     */
    fun insertVertexOnSegment(pathJson: String, segmentIndex: Int, t: Double): String? = runCatching {
        require(t.isFinite() && t > 0.0 && t < 1.0)
        val root = JSONObject(pathJson)
        val points = vertices(pathJson) ?: error("Invalid ShapePath vertices")
        require(points.size in 2..MAX_VERTICES)
        require(points.size < MAX_VERTICES)
        val closed = root.optBoolean("closed", false)
        val segmentCount = if (closed) points.size else points.size - 1
        require(segmentIndex in 0 until segmentCount)
        val startIndex = segmentIndex
        val endIndex = (segmentIndex + 1) % points.size
        val p0 = points[startIndex]
        val p3 = points[endIndex]
        val incoming = tangentOffsets(pathJson, "in_tangents") ?: error("Invalid incoming tangents")
        val outgoing = tangentOffsets(pathJson, "out_tangents") ?: error("Invalid outgoing tangents")
        val p1 = Vertex(p0.x + outgoing[startIndex].x, p0.y + outgoing[startIndex].y)
        val p2 = Vertex(p3.x + incoming[endIndex].x, p3.y + incoming[endIndex].y)
        fun lerp(a: Vertex, b: Vertex, fraction: Double) = Vertex(
            a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction
        )
        val a = lerp(p0, p1, t)
        val b = lerp(p1, p2, t)
        val c = lerp(p2, p3, t)
        val d = lerp(a, b, t)
        val e = lerp(b, c, t)
        val split = lerp(d, e, t)

        val insertionIndex = segmentIndex + 1
        val nextPoints = points.toMutableList().apply { add(insertionIndex, split) }
        val nextIncoming = incoming.toMutableList().apply {
            this[endIndex] = Vertex(c.x - p3.x, c.y - p3.y)
            add(insertionIndex, Vertex(d.x - split.x, d.y - split.y))
        }
        val nextOutgoing = outgoing.toMutableList().apply {
            this[startIndex] = Vertex(a.x - p0.x, a.y - p0.y)
            add(insertionIndex, Vertex(e.x - split.x, e.y - split.y))
        }
        root.put("vertices", vertexArray(nextPoints))
        root.put("in_tangents", vertexArray(nextIncoming))
        root.put("out_tangents", vertexArray(nextOutgoing))

        root.optJSONArray("feather")?.let { feather ->
            for (index in 0 until feather.length()) {
                val point = feather.getJSONObject(index)
                val oldSegment = point.optInt("segment", -1)
                when {
                    oldSegment > segmentIndex -> point.put("segment", oldSegment + 1)
                    oldSegment == segmentIndex && point.has("t") -> {
                        val oldT = point.optDouble("t", Double.NaN)
                        if (oldT.isFinite() && oldT in 0.0..1.0) {
                            if (oldT > t) {
                                point.put("segment", segmentIndex + 1)
                                point.put("t", (oldT - t) / (1.0 - t))
                            } else {
                                point.put("t", oldT / t)
                            }
                        }
                    }
                }
            }
        }
        root.toString()
    }.getOrNull()

    /**
     * Reverse path direction without changing the represented cubic geometry.
     * Vertex order is reversed and incoming/outgoing handles are exchanged;
     * feather segment indices and local t coordinates follow the reversed
     * segment orientation. Unknown JSON metadata is preserved.
     */
    fun reversePath(pathJson: String): String? = runCatching {
        val root = JSONObject(pathJson)
        val points = vertices(pathJson) ?: error("Invalid ShapePath vertices")
        require(points.size in 2..MAX_VERTICES)
        val closed = root.optBoolean("closed", false)
        val incoming = tangentOffsets(pathJson, "in_tangents") ?: error("Invalid incoming tangents")
        val outgoing = tangentOffsets(pathJson, "out_tangents") ?: error("Invalid outgoing tangents")
        root.put("vertices", vertexArray(points.reversed()))
        root.put("in_tangents", vertexArray(outgoing.reversed()))
        root.put("out_tangents", vertexArray(incoming.reversed()))
        val segmentCount = if (closed) points.size else points.size - 1
        root.optJSONArray("feather")?.let { feather ->
            for (index in 0 until feather.length()) {
                val point = feather.getJSONObject(index)
                val oldSegment = point.optInt("segment", -1)
                if (oldSegment !in 0 until segmentCount) continue
                val newSegment = if (closed) {
                    ((points.size - 2 - oldSegment) % points.size + points.size) % points.size
                } else {
                    points.size - 2 - oldSegment
                }
                point.put("segment", newSegment)
                if (point.has("t")) {
                    val oldT = point.optDouble("t", Double.NaN)
                    if (oldT.isFinite() && oldT in 0.0..1.0) point.put("t", 1.0 - oldT)
                }
            }
        }
        root.toString()
    }.getOrNull()

    fun appendVertex(pathJson: String): String? = runCatching {
        val root = JSONObject(pathJson)
        val points = root.getJSONArray("vertices")
        require(points.length() in 1 until MAX_VERTICES)
        val last = points.getJSONArray(points.length() - 1)
        val next = Vertex(last.getDouble(0) + 20.0, last.getDouble(1) + 20.0)
        val updated = vertices(pathJson)!!.toMutableList().apply { add(next) }
        // ShapePath tangent arrays are positional. Keep them aligned on add.
        listOf("in_tangents", "out_tangents").forEach { key ->
            val tangents = root.optJSONArray(key)
            if (tangents == null) {
                root.put(key, zeroTangents(updated.size))
            } else {
                require(tangents.length() == points.length())
                tangents.put(JSONArray().put(0.0).put(0.0))
            }
        }
        root.put("vertices", vertexArray(updated))
        root.toString()
    }.getOrNull()

    /**
     * Returns per-vertex relative handle offsets. Missing arrays are treated as
     * zero handles (the canonical default); malformed positional arrays fail closed.
     */
    fun tangentOffsets(pathJson: String, field: String): List<Vertex>? = runCatching {
        require(field == "in_tangents" || field == "out_tangents")
        val root = JSONObject(pathJson)
        val count = root.getJSONArray("vertices").length()
        val array = root.optJSONArray(field) ?: return@runCatching List(count) { Vertex(0.0, 0.0) }
        require(array.length() == count)
        List(count) { index ->
            val pair = array.getJSONArray(index)
            require(pair.length() == 2)
            val x = pair.getDouble(0)
            val y = pair.getDouble(1)
            require(x.isFinite() && y.isFinite())
            Vertex(x, y)
        }
    }.getOrNull()

    /** Move one visible Bezier handle endpoint relative to its owning vertex. */
    fun moveTangentHandle(pathJson: String, field: String, index: Int, offset: Vertex): String? = runCatching {
        require(offset.x.isFinite() && offset.y.isFinite())
        val points = vertices(pathJson) ?: error("Invalid ShapePath vertices")
        require(index in points.indices)
        val offsets = tangentOffsets(pathJson, field)?.toMutableList() ?: error("Invalid tangent array")
        offsets[index] = offset
        setTangentOffsets(pathJson, field, offsets) ?: error("Invalid tangent replacement")
    }.getOrNull()

    /** Updates one tangent array without disturbing vertices or other path metadata. */
    fun setTangentOffsets(pathJson: String, field: String, offsets: List<Vertex>): String? = runCatching {
        require(field == "in_tangents" || field == "out_tangents")
        val root = JSONObject(pathJson)
        require(offsets.size == root.getJSONArray("vertices").length())
        require(offsets.all { it.x.isFinite() && it.y.isFinite() })
        root.put(field, JSONArray().also { array ->
            offsets.forEach { array.put(JSONArray().put(it.x).put(it.y)) }
        })
        root.toString()
    }.getOrNull()

    fun canRemoveVertex(pathJson: String, index: Int): Boolean = runCatching {
        val root = JSONObject(pathJson)
        val count = root.getJSONArray("vertices").length()
        val minimum = if (root.optBoolean("closed", false)) 3 else 2
        count > minimum && index in 0 until count
    }.getOrDefault(false)

    fun canRemoveLastVertex(pathJson: String): Boolean =
        vertices(pathJson)?.lastIndex?.let { canRemoveVertex(pathJson, it) } ?: false

    /**
     * Remove one anchor while keeping positional tangent arrays aligned.
     * Feather points on the two segments that collapse into a new bridge
     * are discarded (their original parametric geometry no longer exists);
     * untouched segment references are shifted deterministically.
     */
    fun removeVertex(pathJson: String, index: Int): String? = runCatching {
        val root = JSONObject(pathJson)
        val points = vertices(pathJson) ?: error("Invalid ShapePath vertices")
        val closed = root.optBoolean("closed", false)
        val minimum = if (closed) 3 else 2
        require(points.size > minimum && index in points.indices)
        val oldSegmentCount = if (closed) points.size else points.size - 1
        val updated = points.toMutableList().apply { removeAt(index) }
        root.put("vertices", vertexArray(updated))

        listOf("in_tangents", "out_tangents").forEach { key ->
            root.optJSONArray(key)?.let { tangents ->
                require(tangents.length() == points.size)
                root.put(key, JSONArray().also { trimmed ->
                    for (vertexIndex in 0 until tangents.length()) {
                        if (vertexIndex != index) trimmed.put(tangents.get(vertexIndex))
                    }
                })
            }
        }

        root.optJSONArray("feather")?.let { feather ->
            val affected = if (closed && index == 0) setOf(oldSegmentCount - 1, 0)
                else setOf(index - 1, index).filter { it in 0 until oldSegmentCount }.toSet()
            val newSegmentCount = if (closed) updated.size else updated.size - 1
            root.put("feather", JSONArray().also { kept ->
                for (featherIndex in 0 until feather.length()) {
                    val point = feather.getJSONObject(featherIndex)
                    val oldSegment = point.optInt("segment", -1)
                    if (oldSegment !in 0 until oldSegmentCount || oldSegment in affected) continue
                    val newSegment = when {
                        closed && index == 0 -> oldSegment - 1
                        oldSegment > index -> oldSegment - 1
                        else -> oldSegment
                    }
                    if (newSegment in 0 until newSegmentCount) {
                        point.put("segment", newSegment)
                        kept.put(point)
                    }
                }
            })
        }
        root.toString()
    }.getOrNull()

    fun removeLastVertex(pathJson: String): String? =
        vertices(pathJson)?.lastIndex?.let { removeVertex(pathJson, it) }
    private fun vertexArray(vertices: List<Vertex>) = JSONArray().also { array ->
        vertices.forEach { array.put(JSONArray().put(it.x).put(it.y)) }
    }

    private fun zeroTangents(count: Int) = JSONArray().also { array ->
        repeat(count) { array.put(JSONArray().put(0.0).put(0.0)) }
    }
}
