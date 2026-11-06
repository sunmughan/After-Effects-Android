package com.codeair.effectcraft

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codeair.effectcraft.data.CanonicalProjectDocument
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's real XmlPullParser/XmlSerializer, not JVM android.jar stubs. */
@RunWith(AndroidJUnit4::class)
class CanonicalProjectDocumentInstrumentedTest {
    @Test
    fun ecprojJsonImportStripsUtf8Bom() {
        val source = "\uFEFF  \n{\"name\":\"BOM-safe\",\"enabled\":true}"
        val restored = JSONObject(CanonicalProjectDocument.toCanonicalJson(source))
        assertEquals("BOM-safe", restored.getString("name"))
        assertEquals(true, restored.getBoolean("enabled"))
    }

    @Test
    fun ecprojxRoundTripPreservesNestedCanonicalValuesAndUnicode() {
        val source = JSONObject().apply {
            put("name", "Mobile composition")
            put("version", 1)
            put("enabled", true)
            put("nullable", JSONObject.NULL)
            put("nested", JSONObject().apply {
                put("opacity", 12.5)
                put("labels", JSONArray().put("कला").put("motion & design"))
            })
        }
        val xml = CanonicalProjectDocument.toXml(source.toString())
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<EffectCraftProject encoding=\"json\" version=\"1\">"))

        val restored = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))
        assertEquals(source.getString("name"), restored.getString("name"))
        assertEquals(source.getInt("version"), restored.getInt("version"))
        assertEquals(source.getBoolean("enabled"), restored.getBoolean("enabled"))
        assertTrue(restored.isNull("nullable"))
        val nested = restored.getJSONObject("nested")
        assertEquals(12.5, nested.getDouble("opacity"), 0.0)
        assertEquals("कला", nested.getJSONArray("labels").getString(0))
        assertEquals("motion & design", nested.getJSONArray("labels").getString(1))
    }

    @Test
    fun ecprojxAcceptsLegalTrailingWhitespaceAndComments() {
        val xml = CanonicalProjectDocument.toXml("""{"name":"Whitespace-safe"}""") + "\n<!-- export note -->\n"
        val restored = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))
        assertEquals("Whitespace-safe", restored.getString("name"))
    }

    @Test
    fun ecprojxRejectsUnsupportedVersionAndTrailingDocumentContent() {
        val unsupported = """<?xml version="1.0"?><EffectCraftProject encoding="json" version="2"><obj/></EffectCraftProject>"""
        val versionError = runCatching { CanonicalProjectDocument.toCanonicalJson(unsupported) }.exceptionOrNull()
        assertTrue(versionError is IllegalArgumentException)
        assertTrue(versionError?.message.orEmpty().contains("version"))

        val trailing = """<?xml version="1.0"?><EffectCraftProject encoding="json" version="1"><obj/></EffectCraftProject><extra/>"""
        val trailingError = runCatching { CanonicalProjectDocument.toCanonicalJson(trailing) }.exceptionOrNull()
        assertNotNull("A second XML document element must be rejected", trailingError)
    }
}
