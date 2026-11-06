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

    fun canRemoveLastVertex(pathJson: String): Boolean = runCatching {
        val root = JSONObject(pathJson)
        val count = root.getJSONArray("vertices").length()
        count > if (root.optBoolean("closed", false)) 3 else 2
    }.getOrDefault(false)

    fun removeLastVertex(pathJson: String): String? = runCatching {
        val root = JSONObject(pathJson)
        val points = root.getJSONArray("vertices")
        val closed = root.optBoolean("closed", false)
        require(points.length() > if (closed) 3 else 2)
        val updated = vertices(pathJson)!!.dropLast(1)
        root.put("vertices", vertexArray(updated))
        listOf("in_tangents", "out_tangents").forEach { key ->
            root.optJSONArray(key)?.let { tangents ->
                require(tangents.length() == points.length())
                root.put(key, JSONArray().also { trimmed ->
                    for (index in 0 until tangents.length() - 1) trimmed.put(tangents.get(index))
                })
            }
        }
        // Feather points must reference a segment that still exists.
        root.optJSONArray("feather")?.let { feather ->
            val segmentCount = if (closed) updated.size else updated.size - 1
            root.put("feather", JSONArray().also { kept ->
                for (index in 0 until feather.length()) {
                    val point = feather.getJSONObject(index)
                    if (point.optInt("segment", -1) in 0 until segmentCount) kept.put(point)
                }
            })
        }
        root.toString()
    }.getOrNull()

    private fun vertexArray(vertices: List<Vertex>) = JSONArray().also { array ->
        vertices.forEach { array.put(JSONArray().put(it.x).put(it.y)) }
    }

    private fun zeroTangents(count: Int) = JSONArray().also { array ->
        repeat(count) { array.put(JSONArray().put(0.0).put(0.0)) }
    }
}
