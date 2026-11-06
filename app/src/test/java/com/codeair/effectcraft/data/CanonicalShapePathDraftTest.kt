package com.codeair.effectcraft.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanonicalShapePathDraftTest {
    private fun fixture(closed: Boolean = false) = JSONObject().apply {
        put("vertices", org.json.JSONArray().put(org.json.JSONArray().put(1).put(2))
            .put(org.json.JSONArray().put(3).put(4)).put(org.json.JSONArray().put(5).put(6)))
        put("in_tangents", org.json.JSONArray().put(org.json.JSONArray().put(0).put(0))
            .put(org.json.JSONArray().put(0).put(0)).put(org.json.JSONArray().put(0).put(0)))
        put("out_tangents", org.json.JSONArray().put(org.json.JSONArray().put(0).put(0))
            .put(org.json.JSONArray().put(0).put(0)).put(org.json.JSONArray().put(0).put(0)))
        put("closed", closed)
        put("custom", "preserved")
        put("feather", org.json.JSONArray().put(JSONObject().put("segment", 1).put("t", 0.5).put("radius", 4).put("tension", 0)))
    }.toString()

    @Test fun coordinateEditsPreservePathMetadataAndTangentArrays() {
        val source = fixture()
        val vertices = CanonicalShapePathDraft.vertices(source)!!
        val edited = CanonicalShapePathDraft.setVertices(source, vertices.toMutableList().apply {
            this[1] = CanonicalShapePathDraft.Vertex(-12.5, 44.0)
        })!!
        val root = JSONObject(edited)
        assertEquals(-12.5, root.getJSONArray("vertices").getJSONArray(1).getDouble(0), 0.0)
        assertEquals(44.0, root.getJSONArray("vertices").getJSONArray(1).getDouble(1), 0.0)
        assertEquals(3, root.getJSONArray("in_tangents").length())
        assertEquals("preserved", root.getString("custom"))
        assertEquals(1, root.getJSONArray("feather").getJSONObject(0).getInt("segment"))
    }

    @Test fun addAndRemoveVertexKeepTangentsAlignedAndPruneInvalidFeatherSegments() {
        val added = JSONObject(CanonicalShapePathDraft.appendVertex(fixture())!!)
        assertEquals(4, added.getJSONArray("vertices").length())
        assertEquals(4, added.getJSONArray("in_tangents").length())
        assertEquals(4, added.getJSONArray("out_tangents").length())
        val point = added.getJSONArray("vertices").getJSONArray(3)
        assertEquals(25.0, point.getDouble(0), 0.0)
        assertEquals(26.0, point.getDouble(1), 0.0)

        // Put feather on the newly-added final segment so removing that
        // vertex must discard the now-out-of-range feather reference.
        added.getJSONArray("feather").put(0, JSONObject().put("segment", 2).put("t", 0.5).put("radius", 4).put("tension", 0))
        val removed = JSONObject(CanonicalShapePathDraft.removeLastVertex(added.toString())!!)
        assertEquals(3, removed.getJSONArray("vertices").length())
        assertEquals(3, removed.getJSONArray("in_tangents").length())
        assertEquals(3, removed.getJSONArray("out_tangents").length())
        assertEquals(0, removed.getJSONArray("feather").length())
    }

    @Test fun tangentHandleEditsAreFinitePositionalAndPreserveOtherPathData() {
        val source = fixture(closed = true)
        val incoming = CanonicalShapePathDraft.tangentOffsets(source, "in_tangents")!!.toMutableList()
        incoming[1] = CanonicalShapePathDraft.Vertex(-3.25, 7.5)
        val withIncoming = CanonicalShapePathDraft.setTangentOffsets(source, "in_tangents", incoming)!!
        val outgoing = CanonicalShapePathDraft.tangentOffsets(withIncoming, "out_tangents")!!.toMutableList()
        outgoing[1] = CanonicalShapePathDraft.Vertex(8.0, -2.0)
        val edited = JSONObject(CanonicalShapePathDraft.setTangentOffsets(withIncoming, "out_tangents", outgoing)!!)
        assertEquals(-3.25, edited.getJSONArray("in_tangents").getJSONArray(1).getDouble(0), 0.0)
        assertEquals(7.5, edited.getJSONArray("in_tangents").getJSONArray(1).getDouble(1), 0.0)
        assertEquals(8.0, edited.getJSONArray("out_tangents").getJSONArray(1).getDouble(0), 0.0)
        assertEquals(-2.0, edited.getJSONArray("out_tangents").getJSONArray(1).getDouble(1), 0.0)
        assertEquals(3, edited.getJSONArray("vertices").length())
        assertEquals("preserved", edited.getString("custom"))
        assertEquals(1, edited.getJSONArray("feather").getJSONObject(0).getInt("segment"))
    }

    @Test fun tangentEditsRejectWrongFieldLengthAndNonFiniteValues() {
        val source = fixture()
        val offsets = CanonicalShapePathDraft.tangentOffsets(source, "in_tangents")!!
        assertNull(CanonicalShapePathDraft.tangentOffsets(source, "vertices"))
        assertNull(CanonicalShapePathDraft.setTangentOffsets(source, "vertices", offsets))
        assertNull(CanonicalShapePathDraft.setTangentOffsets(source, "in_tangents", offsets.dropLast(1)))
        assertNull(CanonicalShapePathDraft.setTangentOffsets(source, "out_tangents", offsets + offsets.last()))
        assertNull(CanonicalShapePathDraft.setTangentOffsets(source, "in_tangents", offsets.toMutableList().apply {
            this[0] = CanonicalShapePathDraft.Vertex(Double.POSITIVE_INFINITY, 0.0)
        }))
        val missing = JSONObject(source).apply { remove("in_tangents") }.toString()
        assertEquals(3, CanonicalShapePathDraft.tangentOffsets(missing, "in_tangents")!!.size)
    }

    @Test fun closedPathsRetainThreeVerticesAndInvalidOrNonFiniteEditsFailClosed() {
        val closed = fixture(closed = true)
        assertFalse(CanonicalShapePathDraft.canRemoveLastVertex(closed))
        assertNull(CanonicalShapePathDraft.removeLastVertex(closed))
        val points = CanonicalShapePathDraft.vertices(closed)!!
        assertNull(CanonicalShapePathDraft.setVertices(closed, points + CanonicalShapePathDraft.Vertex(Double.NaN, 0.0)))
        val full = JSONObject().put("vertices", org.json.JSONArray()).put("in_tangents", org.json.JSONArray()).put("out_tangents", org.json.JSONArray())
        repeat(CanonicalShapePathDraft.MAX_VERTICES) { i ->
            full.getJSONArray("vertices").put(org.json.JSONArray().put(i).put(i))
            full.getJSONArray("in_tangents").put(org.json.JSONArray().put(0).put(0))
            full.getJSONArray("out_tangents").put(org.json.JSONArray().put(0).put(0))
        }
        assertNull(CanonicalShapePathDraft.appendVertex(full.toString()))
        assertNull(CanonicalShapePathDraft.vertices("not-json"))
    }
}
