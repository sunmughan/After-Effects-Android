package com.codeair.effectcraft

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codeair.effectcraft.data.CanonicalProjectDocument
import com.codeair.effectcraft.data.ImportDocumentResolver
import com.codeair.effectcraft.data.aep.AepImportReport
import com.codeair.effectcraft.engine.NativeProjectBridge
import com.codeair.effectcraft.engine.NativeSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The raw .aep import path proved against the real native inspector
 * on the emulator. The unit tests assert the importer's loss report
 * and canonical JSON shape; these tests prove the native side accepts
 * that JSON as a valid canonical project and opens a session on it —
 * the same inspect + open the app performs on import.
 *
 * Fixture: synthetic.aep, made by the official EffectCraft
 * contributors in After Effects 2026 (MIT OR Apache-2.0; see
 * synthetic.aep.attribution): one composition "C" (64x48, 25 fps),
 * an 8x8 PNG image sequence, a solid, a folder, Levels and Glow
 * effects with expressions.
 */
@RunWith(AndroidJUnit4::class)
class AepImportInstrumentedTest {

    private fun syntheticBytes(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("aep/synthetic.aep").use { it.readBytes() }

    private fun importedCanonical(): String {
        val bytes = syntheticBytes()
        assertTrue(ImportDocumentResolver.isAep(bytes))
        return ImportDocumentResolver.importAep(bytes, allowLosses = true).canonicalJson
    }

    @Test
    fun importedAepPassesNativeInspection() {
        assertTrue(NativeProjectBridge.isAvailable())
        val bytes = syntheticBytes()
        val report = ImportDocumentResolver.analyzeAep(bytes)
        val losses = report.losses.associate { it.category to it.count }
        assertEquals(
            mapOf(
                AepImportReport.LossCategory.Effects to 2,
                AepImportReport.LossCategory.UnsupportedItems to 2,
                AepImportReport.LossCategory.ProjectSettings to 1
            ),
            losses
        )
        val canonical = ImportDocumentResolver.importAep(bytes, allowLosses = true).canonicalJson

        val metadata = NativeProjectBridge.inspectEcproj(canonical)
        assertNotNull("Native inspector must accept the imported .aep project", metadata)
        val info = CanonicalProjectDocument.validateNativeEnvelope(metadata!!)
        assertEquals(1, info.compositions.size)
        val comp = info.compositions[0]
        assertEquals("C", comp.name)
        assertEquals(64, comp.width)
        assertEquals(48, comp.height)
        assertEquals(2, comp.layers.size)
        assertEquals(setOf("shot_0001.png", "Solid"), comp.layers.map { it.name }.toSet())

        // The image sequence's files do not exist on the emulator:
        // the footage item is marked missing, never a failure.
        val items = JSONObject(canonical).getJSONObject("items")
        var footageMissing: Boolean? = null
        var solidFound = false
        val itemKeys = items.keys()
        while (itemKeys.hasNext()) {
            val kind = items.getJSONObject(itemKeys.next()).getJSONObject("kind")
            when (kind.optString("type")) {
                "Footage" -> footageMissing = kind.getBoolean("missing")
                "Solid" -> {
                    solidFound = true
                    assertEquals(64, kind.getInt("width"))
                    assertEquals(48, kind.getInt("height"))
                }
            }
        }
        assertEquals(true, footageMissing)
        assertTrue(solidFound)
    }

    @Test
    fun importedAepOpensANativeSession() {
        assertTrue(NativeProjectBridge.isAvailable())
        val canonical = importedCanonical()
        val session = NativeSession.open(canonical, 160)
        assertNotNull("Native session must open on the imported .aep project", session)
        session!!.close()
    }

    @Test
    fun importedAepRefusesWithoutLossAllowance() {
        val bytes = syntheticBytes()
        assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentResolver.resolve(bytes, InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        }
    }
}
