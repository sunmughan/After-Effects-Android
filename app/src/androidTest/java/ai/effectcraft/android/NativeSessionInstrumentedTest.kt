package ai.effectcraft.android

import ai.effectcraft.android.engine.NativeProjectBridge
import ai.effectcraft.android.engine.NativeSession
import ai.effectcraft.android.engine.RenderedFramePng
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator proof for the JNI session surface: the fixture below is a real
 * canonical project (one empty 320x180 composition) produced by the pinned
 * upstream model, so a successful open + frame render here proves the
 * session symbols resolve and the session renders on the emulator ABI.
 * Draft (.efx-draft) JSON is deliberately not used: it is not a canonical
 * project and must never be opened as a session.
 */
class NativeSessionInstrumentedTest {

    companion object {
        // Real canonical project: one empty 320x180 composition, produced by
        // the pinned upstream model (Project::default + Comp, id 1). Kept as
        // a raw string so the test needs no asset file or import flow.
        const val FIXTURE_JSON = """
{
  "savedBy": "0.19.0",
  "schema": 2,
  "settings": {
    "bit_depth": "Bpc8",
    "linearize": false,
    "working_space": null,
    "blend_linear": false,
    "time_display": "Timecode",
    "frame_start": 0,
    "audio_sample_rate": 48000,
    "gpu_acceleration": true,
    "color_engine": "Adobe",
    "hdr": "Clip",
    "output_space": null,
    "working_gamma": 2.2
  },
  "items": {
    "1": {
      "id": 1,
      "name": "Preview",
      "label": "Sandstone",
      "comment": "",
      "parent": null,
      "kind": {
        "type": "Comp",
        "width": 320,
        "height": 180,
        "pixel_aspect": 1.0,
        "frame_rate": {
          "num": 30,
          "den": 1
        },
        "duration": 762048000000,
        "display_start": 0,
        "background": [
          0.0,
          0.0,
          0.0
        ],
        "work_area": [
          0,
          762048000000
        ],
        "layers": [],
        "markers": [],
        "shutter_angle": 180.0,
        "shutter_phase": -90.0,
        "motion_blur_samples": 16,
        "motion_blur_adaptive_limit": 128,
        "renderer": "Classic3D",
        "hide_shy": false,
        "enable_motion_blur": true,
        "enable_frame_blending": true,
        "draft_3d": false,
        "preserve_frame_rate": false,
        "preserve_resolution": false,
        "poster_time": 0,
        "global_light": {
          "angle": {
            "uid": 0,
            "match": "globalLightAngle",
            "name": "Global Light Angle",
            "value": {
              "t": "Scalar",
              "v": 120.0
            },
            "ui": {
              "kind": "Angle"
            },
            "spatial": false,
            "hold_only": false,
            "three_d_only": false,
            "two_d_only": false,
            "shown_dims": 0,
            "static_only": false
          },
          "altitude": {
            "uid": 0,
            "match": "globalLightAltitude",
            "name": "Global Light Altitude",
            "value": {
              "t": "Scalar",
              "v": 30.0
            },
            "ui": {
              "kind": "Angle"
            },
            "spatial": false,
            "hold_only": false,
            "three_d_only": false,
            "two_d_only": false,
            "shown_dims": 0,
            "static_only": false
          }
        }
      }
    }
  },
  "next_id": 2
}
"""
        const val FIXTURE_COMPOSITION_ID = 1L
    }

    private fun frameDimensions(bytes: ByteArray): Pair<Int, Int> {
        assertTrue(bytes.size >= 8)
        val width = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        val height = ((bytes[4].toInt() and 0xff) shl 24) or ((bytes[5].toInt() and 0xff) shl 16) or
            ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        return width to height
    }

    @Test
    fun pngExportPreservesRgbaPixelsAndDimensions() {
        val frame = byteArrayOf(
            0, 0, 0, 1, // width
            0, 0, 0, 1, // height
            255.toByte(), 0, 0, 255.toByte() // opaque red RGBA
        )
        val output = ByteArrayOutputStream()
        val dimensions = RenderedFramePng.write(frame, output)
        assertEquals(1, dimensions.width)
        assertEquals(1, dimensions.height)
        val png = output.toByteArray()
        assertTrue(png.size > 8)
        assertEquals(0x89, png[0].toInt() and 0xff)
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull(bitmap)
        bitmap!!
        try {
            assertEquals(1, bitmap.width)
            assertEquals(1, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun pngExportRejectsMalformedAndOversizedFramesBeforeBitmapAllocation() {
        assertTrue(runCatching { RenderedFramePng.write(byteArrayOf(0, 0), ByteArrayOutputStream()) }.isFailure)
        val zeroWidth = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        assertTrue(runCatching { RenderedFramePng.write(zeroWidth, ByteArrayOutputStream()) }.isFailure)
        val oversized = byteArrayOf(0, 0, 0x20, 0, 0, 0x20, 0, 0)
        assertTrue(runCatching { RenderedFramePng.write(oversized, ByteArrayOutputStream()) }.isFailure)
    }

    @Test
    fun sessionOpenRejectsInvalidProject() {
        assertTrue(NativeProjectBridge.isAvailable())
        assertNull(NativeSession.open("{}"))
        assertNull(NativeSession.open("not json"))
    }

    @Test
    fun sessionReportsBackendAndRecoversAfterSimulatedLoss() {
        assertTrue(NativeProjectBridge.isAvailable())
        val session = NativeSession.open(FIXTURE_JSON, 160)
        assertNotNull("Session must open for the canonical fixture", session)
        session!!
        try {
            val status = session.backendStatus()
            assertNotNull("Backend status must be reported", status)
            assertTrue(status!!.backend == "gpu" || status.backend == "cpu")
            if (status.backend == "gpu") {
                assertTrue("GPU status must name its adapter", !status.adapter.isNullOrEmpty())
            } else {
                assertTrue("CPU status must explain the fallback", !status.reason.isNullOrEmpty())
            }
            // Each leg below renders a fresh frame number: the shared frame
            // cache is keyed on project + composition + frame + size, so
            // reusing a frame would serve a cached frame and never exercise
            // the native fallback/recovery path being tested.
            val frame = session.render(FIXTURE_COMPOSITION_ID, 0)
            assertNotNull(frame)
            assertEquals(160 to 90, frameDimensions(frame!!))
            session.simulateGpuLoss()
            val lost = session.backendStatus()
            assertNotNull(lost)
            assertEquals("cpu", lost!!.backend)
            assertTrue(lost.lost)
            assertTrue(!lost.reason.isNullOrEmpty())
            // Loss must not stop rendering: the CPU fallback serves the frame.
            val fallback = session.render(FIXTURE_COMPOSITION_ID, 1)
            assertNotNull("CPU fallback must render after device loss", fallback)
            assertEquals(160 to 90, frameDimensions(fallback!!))
            // Recovery is best-effort: with no device on this host the
            // session honestly stays CPU-backed. Either way it must keep
            // rendering afterwards.
            session.recoverGpu()
            val recovered = session.backendStatus()
            assertNotNull(recovered)
            assertTrue(recovered!!.backend == "gpu" || recovered.backend == "cpu")
            val after = session.render(FIXTURE_COMPOSITION_ID, 2)
            assertNotNull(after)
            assertEquals(160 to 90, frameDimensions(after!!))
            // Teardown + recreation after a loss must also keep working.
            session.close()
            assertTrue(!session.isOpen())
            val reopened = NativeSession.open(FIXTURE_JSON, 160)
            assertNotNull("Session must reopen after loss + close", reopened)
            reopened!!
            try {
                val again = reopened.render(FIXTURE_COMPOSITION_ID, 3)
                assertNotNull(again)
                assertEquals(160 to 90, frameDimensions(again!!))
            } finally {
                reopened.close()
            }
        } finally {
            session.close()
            session.close()
        }
    }

    @Test
    fun sessionRendersResizesCancelsAndCloses() {
        assertTrue(NativeProjectBridge.isAvailable())
        val session = NativeSession.open(FIXTURE_JSON, 160)
        assertNotNull("Session must open for the canonical fixture", session)
        session!!
        try {
            assertTrue(session.isOpen())
            val frame = session.render(FIXTURE_COMPOSITION_ID, 0)
            assertNotNull("Session must render the fixture composition", frame)
            assertEquals(160 to 90, frameDimensions(frame!!))
            assertEquals(8 + 160 * 90 * 4, frame.size)
            assertTrue(session.resize(80))
            assertEquals(80, session.currentMaxSide())
            val small = session.render(FIXTURE_COMPOSITION_ID, 0)
            assertNotNull(small)
            assertEquals(80 to 45, frameDimensions(small!!))
            assertNull("Unknown composition must not render", session.render(999_999, 0))
            session.cancel()
            assertNull("Cancelled session must not render", session.render(FIXTURE_COMPOSITION_ID, 0))
        } finally {
            session.close()
            session.close()
            assertTrue(!session.isOpen())
            assertNull("Closed session must not render", session.render(FIXTURE_COMPOSITION_ID, 0))
        }
    }
}
