package com.codeair.effectcraft

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codeair.effectcraft.data.AeInterchange
import com.codeair.effectcraft.data.AeInterchangeConverter
import com.codeair.effectcraft.data.CanonicalProjectDocument
import com.codeair.effectcraft.data.ImportDocumentResolver
import com.codeair.effectcraft.engine.NativeProjectBridge
import com.codeair.effectcraft.engine.NativeSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Batch 3: the After Effects import path proved against the real
 * native inspector on the emulator. The unit tests assert the
 * converter's JSON shape; these tests prove the native side accepts
 * that JSON as a valid canonical project and opens a session on it —
 * the same inspect + open the app performs on import.
 */
@RunWith(AndroidJUnit4::class)
class AeInterchangeImportInstrumentedTest {

    private val interchangeJson = """
        {
          "format": "effectcraft-ae-interchange",
          "version": 1,
          "projectName": "My Project",
          "comps": [{
            "name": "Main", "width": 640, "height": 360,
            "frameRate": 30, "duration": 4.0,
            "background": [0, 0, 0],
            "layers": [
              {"name": "Title", "type": "text", "text": "Hello",
               "font": "Arial", "fontSize": 72, "color": [1, 1, 1, 1],
               "position": [320, 180], "opacity": 100,
               "startTime": 0, "inPoint": 0, "outPoint": 4,
               "keyframes": {
                 "position": [
                   {"time": 0, "value": [320, 180]},
                   {"time": 2, "value": [320, 90]}
                 ]
               }},
              {"name": "BG", "type": "solid", "color": [0.1, 0.2, 0.8],
               "width": 640, "height": 360, "position": [320, 180]},
              {"name": "Pic.png", "type": "footage", "file": "media/pic.png",
               "width": 64, "height": 64, "position": [100, 100]}
            ]
          }]
        }
    """.trimIndent()

    @Test
    fun convertedInterchangePassesNativeInspection() {
        assertTrue(NativeProjectBridge.isAvailable())
        val project = AeInterchange.parse(interchangeJson)
        val canonical = AeInterchangeConverter.toCanonicalJson(project) { null }

        val metadata = NativeProjectBridge.inspectEcproj(canonical)
        assertNotNull("Native inspector must accept the converted project", metadata)
        val info = CanonicalProjectDocument.validateNativeEnvelope(metadata!!)
        assertEquals(1, info.compositions.size)
        val comp = info.compositions[0]
        assertEquals("Main", comp.name)
        assertEquals(640, comp.width)
        assertEquals(360, comp.height)
        assertEquals(3, comp.layers.size)
        assertEquals("Title", comp.layers[0].name)
        assertEquals("BG", comp.layers[1].name)
        assertEquals("Pic.png", comp.layers[2].name)
    }

    @Test
    fun convertedInterchangeOpensANativeSession() {
        assertTrue(NativeProjectBridge.isAvailable())
        val project = AeInterchange.parse(interchangeJson)
        val canonical = AeInterchangeConverter.toCanonicalJson(project) { null }

        val session = NativeSession.open(canonical, 160)
        assertNotNull("Native session must open on the converted project", session)
        session!!.close()
    }

    @Test
    fun zipBundleWithInterchangeResolvesEndToEnd() {
        assertTrue(NativeProjectBridge.isAvailable())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mediaDir = File(context.cacheDir, "ae-import-test-${System.nanoTime()}")
        try {
            // A 1x1 red PNG as the bundle's media file.
            val png = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0,
                0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
                0, 0, 0, 0x0C, 0x49, 0x44, 0x41, 0x54,
                0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0, 0, 0x03, 0x01, 0x01, 0,
                0x18, 0xDD.toByte(), 0x8D.toByte(), 0xB4.toByte(),
                0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
            )
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(interchangeJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("media/pic.png"))
                zip.write(png)
                zip.closeEntry()
            }
            val resolved = ImportDocumentResolver.resolve(out.toByteArray(), mediaDir)
            assertEquals(true, resolved.wasAeInterchange)
            val canonical = resolved.canonicalJson

            val metadata = NativeProjectBridge.inspectEcproj(canonical)
            assertNotNull("Native inspector must accept the bundle's converted project", metadata)
            val info = CanonicalProjectDocument.validateNativeEnvelope(metadata!!)
            assertEquals(1, info.compositions.size)
            assertEquals(3, info.compositions[0].layers.size)
            // The media file was extracted and the footage path points at it.
            val footagePath = JSONObject(canonical)
                .getJSONObject("items")
                .let { items ->
                    val comp = items.getJSONObject("1").getJSONObject("kind")
                    val layer = comp.getJSONArray("layers").getJSONObject(2)
                    val footageId = layer.getJSONObject("source").getInt("item")
                    items.getJSONObject(footageId.toString()).getJSONObject("kind")
                }
            assertEquals(false, footagePath.getBoolean("missing"))
            assertTrue(File(footagePath.getString("path")).exists())
        } finally {
            mediaDir.deleteRecursively()
        }
    }
}
