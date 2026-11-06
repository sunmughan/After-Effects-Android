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

    @Test fun dragMutationMovesOnlySelectedVertexAndRejectsInvalidCoordinates() {
        val source = fixture()
        val moved = JSONObject(CanonicalShapePathDraft.moveVertex(
            source, 1, CanonicalShapePathDraft.Vertex(-8.0, 19.5)
        )!!)
        assertEquals(1.0, moved.getJSONArray("vertices").getJSONArray(0).getDouble(0), 0.0)
        assertEquals(-8.0, moved.getJSONArray("vertices").getJSONArray(1).getDouble(0), 0.0)
        assertEquals(19.5, moved.getJSONArray("vertices").getJSONArray(1).getDouble(1), 0.0)
        assertEquals(5.0, moved.getJSONArray("vertices").getJSONArray(2).getDouble(0), 0.0)
        assertEquals(3, moved.getJSONArray("in_tangents").length())
        assertEquals("preserved", moved.getString("custom"))
        assertNull(CanonicalShapePathDraft.moveVertex(source, -1, CanonicalShapePathDraft.Vertex(0.0, 0.0)))
        assertNull(CanonicalShapePathDraft.moveVertex(source, 0, CanonicalShapePathDraft.Vertex(Double.NaN, 0.0)))
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

    @Test fun draggingOneTangentHandleChangesOnlyItsOffsetAndPreservesPathMetadata() {
        val source = fixture()
        val moved = JSONObject(CanonicalShapePathDraft.moveTangentHandle(
            source, "out_tangents", 1, CanonicalShapePathDraft.Vertex(12.25, -6.5)
        )!!)
        val offsets = CanonicalShapePathDraft.tangentOffsets(moved.toString(), "out_tangents")!!
        assertEquals(0.0, offsets[0].x, 0.0)
        assertEquals(12.25, offsets[1].x, 0.0)
        assertEquals(-6.5, offsets[1].y, 0.0)
        assertEquals(0.0, offsets[2].y, 0.0)
        assertEquals(0.0, CanonicalShapePathDraft.tangentOffsets(moved.toString(), "in_tangents")!![1].x, 0.0)
        assertEquals(3.0, CanonicalShapePathDraft.vertices(moved.toString())!![1].x, 0.0)
        assertEquals("preserved", moved.getString("custom"))
        assertNull(CanonicalShapePathDraft.moveTangentHandle(source, "vertices", 1, CanonicalShapePathDraft.Vertex(1.0, 2.0)))
        assertNull(CanonicalShapePathDraft.moveTangentHandle(source, "in_tangents", 3, CanonicalShapePathDraft.Vertex(1.0, 2.0)))
        assertNull(CanonicalShapePathDraft.moveTangentHandle(source, "in_tangents", 0, CanonicalShapePathDraft.Vertex(Double.NaN, 2.0)))
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

    @Test fun cubicSegmentSubdivisionPreservesCurveAndRemapsFeatherPositions() {
        val source = JSONObject(fixture(closed = true)).apply {
            put("out_tangents", org.json.JSONArray()
                .put(org.json.JSONArray().put(2.0).put(1.0))
                .put(org.json.JSONArray().put(0.5).put(-1.0))
                .put(org.json.JSONArray().put(-1.0).put(0.5)))
            put("in_tangents", org.json.JSONArray()
                .put(org.json.JSONArray().put(-0.5).put(1.0))
                .put(org.json.JSONArray().put(-1.0).put(2.0))
                .put(org.json.JSONArray().put(1.0).put(-1.0)))
            put("feather", org.json.JSONArray()
                .put(JSONObject().put("segment", 0).put("t", 0.25).put("radius", 4))
                .put(JSONObject().put("segment", 0).put("t", 0.75).put("radius", 5))
                .put(JSONObject().put("segment", 1).put("t", 0.5).put("radius", 6)))
        }.toString()
        val splitAt = 0.4
        val originalPoints = CanonicalShapePathDraft.vertices(source)!!
        val originalIn = CanonicalShapePathDraft.tangentOffsets(source, "in_tangents")!!
        val originalOut = CanonicalShapePathDraft.tangentOffsets(source, "out_tangents")!!
        fun cubic(p0: CanonicalShapePathDraft.Vertex, p1: CanonicalShapePathDraft.Vertex,
                  p2: CanonicalShapePathDraft.Vertex, p3: CanonicalShapePathDraft.Vertex, t: Double): CanonicalShapePathDraft.Vertex {
            val u = 1.0 - t
            return CanonicalShapePathDraft.Vertex(
                u*u*u*p0.x + 3*u*u*t*p1.x + 3*u*t*t*p2.x + t*t*t*p3.x,
                u*u*u*p0.y + 3*u*u*t*p1.y + 3*u*t*t*p2.y + t*t*t*p3.y
            )
        }
        val p0 = originalPoints[0]
        val p3 = originalPoints[1]
        val p1 = CanonicalShapePathDraft.Vertex(p0.x + originalOut[0].x, p0.y + originalOut[0].y)
        val p2 = CanonicalShapePathDraft.Vertex(p3.x + originalIn[1].x, p3.y + originalIn[1].y)
        val originalCurve = (0..20).map { i -> cubic(p0, p1, p2, p3, i / 20.0) }
        val split = JSONObject(CanonicalShapePathDraft.insertVertexOnSegment(source, 0, splitAt)!!)
        val vertices = CanonicalShapePathDraft.vertices(split.toString())!!
        val incoming = CanonicalShapePathDraft.tangentOffsets(split.toString(), "in_tangents")!!
        val outgoing = CanonicalShapePathDraft.tangentOffsets(split.toString(), "out_tangents")!!
        assertEquals(4, vertices.size)
        assertEquals(4, incoming.size)
        assertEquals(4, outgoing.size)
        val inserted = vertices[1]
        val leftP1 = CanonicalShapePathDraft.Vertex(vertices[0].x + outgoing[0].x, vertices[0].y + outgoing[0].y)
        val leftP2 = CanonicalShapePathDraft.Vertex(inserted.x + incoming[1].x, inserted.y + incoming[1].y)
        val rightP1 = CanonicalShapePathDraft.Vertex(inserted.x + outgoing[1].x, inserted.y + outgoing[1].y)
        val rightP2 = CanonicalShapePathDraft.Vertex(vertices[2].x + incoming[2].x, vertices[2].y + incoming[2].y)
        originalCurve.forEachIndexed { i, expected ->
            val u = i / 20.0
            val actual = if (u <= splitAt) cubic(vertices[0], leftP1, leftP2, inserted, u / splitAt)
                else cubic(inserted, rightP1, rightP2, vertices[2], (u - splitAt) / (1.0 - splitAt))
            assertEquals(expected.x, actual.x, 1e-9)
            assertEquals(expected.y, actual.y, 1e-9)
        }
        val feather = split.getJSONArray("feather")
        assertEquals(0, feather.getJSONObject(0).getInt("segment"))
        assertEquals(0.625, feather.getJSONObject(0).getDouble("t"), 1e-12)
        assertEquals(1, feather.getJSONObject(1).getInt("segment"))
        assertEquals(0.5833333333333334, feather.getJSONObject(1).getDouble("t"), 1e-12)
        assertEquals(2, feather.getJSONObject(2).getInt("segment"))
        assertEquals("preserved", split.getString("custom"))
    }

    @Test fun cubicSubdivisionRejectsInvalidSegmentAndBoundaryParameters() {
        val source = fixture()
        assertNull(CanonicalShapePathDraft.insertVertexOnSegment(source, -1, 0.5))
        assertNull(CanonicalShapePathDraft.insertVertexOnSegment(source, 2, 0.5))
        assertNull(CanonicalShapePathDraft.insertVertexOnSegment(source, 0, 0.0))
        assertNull(CanonicalShapePathDraft.insertVertexOnSegment(source, 0, 1.0))
        assertNull(CanonicalShapePathDraft.insertVertexOnSegment(source, 0, Double.NaN))
        val full = JSONObject().put("vertices", org.json.JSONArray())
        repeat(CanonicalShapePathDraft.MAX_VERTICES) { i -> full.getJSONArray("vertices").put(org.json.JSONArray().put(i).put(i)) }
        assertNull(CanonicalShapePathDraft.insertVertexOnSegment(full.toString(), 0, 0.5))
    }

    @Test fun closureTogglePreservesGeometryAndRejectsTooFewVertices() {
        val source = fixture(closed = false)
        val closed = JSONObject(CanonicalShapePathDraft.setClosed(source, true)!!)
        assertTrue(closed.getBoolean("closed"))
        assertEquals(3, closed.getJSONArray("vertices").length())
        assertEquals(3, closed.getJSONArray("in_tangents").length())
        assertEquals(3, closed.getJSONArray("out_tangents").length())
        assertEquals("preserved", closed.getString("custom"))
        assertEquals(1, closed.getJSONArray("feather").getJSONObject(0).getInt("segment"))
        val opened = JSONObject(CanonicalShapePathDraft.setClosed(closed.toString(), false)!!)
        assertFalse(opened.getBoolean("closed"))
        assertEquals(closed.getJSONArray("vertices").toString(), opened.getJSONArray("vertices").toString())
        val twoVertices = JSONObject(source).apply {
            put("vertices", org.json.JSONArray().put(getJSONArray("vertices").get(0)).put(getJSONArray("vertices").get(1)))
        }.toString()
        assertNull(CanonicalShapePathDraft.setClosed(twoVertices, true))
        assertNotNull(CanonicalShapePathDraft.setClosed(twoVertices, false))
        assertNull(CanonicalShapePathDraft.setClosed("not-json", true))
    }


    @Test fun removeSelectedOpenVertexDropsCollapsedFeathersAndShiftsUntouchedSegments() {
        val source = JSONObject(fixture(closed = false)).apply {
            getJSONArray("vertices").put(org.json.JSONArray().put(7).put(8))
            getJSONArray("in_tangents").put(org.json.JSONArray().put(3).put(4))
            getJSONArray("out_tangents").put(org.json.JSONArray().put(5).put(6))
            put("feather", org.json.JSONArray()
                .put(JSONObject().put("segment", 0).put("t", 0.25).put("radius", 1))
                .put(JSONObject().put("segment", 1).put("t", 0.75).put("radius", 2))
                .put(JSONObject().put("segment", 2).put("t", 0.5).put("radius", 3)))
        }.toString()
        assertTrue(CanonicalShapePathDraft.canRemoveVertex(source, 1))
        val removed = JSONObject(CanonicalShapePathDraft.removeVertex(source, 1)!!)
        val vertices = CanonicalShapePathDraft.vertices(removed.toString())!!
        val incoming = CanonicalShapePathDraft.tangentOffsets(removed.toString(), "in_tangents")!!
        val outgoing = CanonicalShapePathDraft.tangentOffsets(removed.toString(), "out_tangents")!!
        assertEquals(listOf(1.0, 5.0, 7.0), vertices.map { it.x })
        assertEquals(listOf(0.0, 0.0, 3.0), incoming.map { it.x })
        assertEquals(listOf(0.0, 0.0, 5.0), outgoing.map { it.x })
        val feather = removed.getJSONArray("feather")
        assertEquals(1, feather.length())
        assertEquals(1, feather.getJSONObject(0).getInt("segment"))
        assertEquals(3, feather.getJSONObject(0).getInt("radius"))
        assertEquals("preserved", removed.getString("custom"))
        assertNull(CanonicalShapePathDraft.removeVertex(source, -1))
        assertNull(CanonicalShapePathDraft.removeVertex(source, 4))
    }

    @Test fun removeClosedPathFirstVertexRemapsWraparoundSegmentsAndHonorsMinimum() {
        val source = JSONObject(fixture(closed = true)).apply {
            getJSONArray("vertices").put(org.json.JSONArray().put(7).put(8))
            getJSONArray("in_tangents").put(org.json.JSONArray().put(3).put(4))
            getJSONArray("out_tangents").put(org.json.JSONArray().put(5).put(6))
            put("feather", org.json.JSONArray()
                .put(JSONObject().put("segment", 0).put("radius", 1))
                .put(JSONObject().put("segment", 1).put("radius", 2))
                .put(JSONObject().put("segment", 2).put("radius", 3))
                .put(JSONObject().put("segment", 3).put("radius", 4)))
        }.toString()
        val removed = JSONObject(CanonicalShapePathDraft.removeVertex(source, 0)!!)
        assertTrue(removed.getBoolean("closed"))
        assertEquals(3, removed.getJSONArray("vertices").length())
        assertEquals(3, CanonicalShapePathDraft.tangentOffsets(removed.toString(), "in_tangents")!!.size)
        val feather = removed.getJSONArray("feather")
        assertEquals(2, feather.length())
        assertEquals(0, feather.getJSONObject(0).getInt("segment"))
        assertEquals(2, feather.getJSONObject(0).getInt("radius"))
        assertEquals(1, feather.getJSONObject(1).getInt("segment"))
        assertEquals(3, feather.getJSONObject(1).getInt("radius"))
        assertFalse(CanonicalShapePathDraft.canRemoveVertex(removed.toString(), 0))
        assertNull(CanonicalShapePathDraft.removeVertex(removed.toString(), 0))
    }
}
