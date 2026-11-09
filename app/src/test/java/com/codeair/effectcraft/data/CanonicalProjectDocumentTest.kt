package com.codeair.effectcraft.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class CanonicalProjectDocumentTest {
    @Test
    fun stripsUtf8BomFromJsonImportAndPreservesLeadingWhitespace() {
        val source = "\uFEFF  \n{\"project\":\"EffectCraft\"}"
        val canonical = CanonicalProjectDocument.toCanonicalJson(source)
        assertEquals("EffectCraft", JSONObject(canonical).getString("project"))
    }

    @Test
    fun readsUtf8DocumentWithinConfiguredLimit() {
        val source = "{\"project\":\"EffectCraft\"}"
        assertEquals(source, CanonicalProjectDocument.readUtf8(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            maxBytes = source.toByteArray(Charsets.UTF_8).size
        ))
    }

    @Test
    fun reportsMonotonicReadProgressAndFinalByteCount() {
        val source = """{"project":"EffectCraft"}"""
        val progress = mutableListOf<Long>()
        val result = CanonicalProjectDocument.readUtf8(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            onProgress = progress::add
        )
        assertEquals(source, result)
        assertTrue(progress.isNotEmpty())
        assertEquals(source.toByteArray(Charsets.UTF_8).size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> after > before })
    }

    @Test
    fun rejectsDocumentBeyondImportLimit() {
        val input = ByteArrayInputStream(ByteArray(9) { 0x61 })
        val error = runCatching { CanonicalProjectDocument.readUtf8(input, maxBytes = 8) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("import limit"))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingBytes() {
        // C3 must be followed by a continuation byte; 28 is an ASCII '('.
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        val error = runCatching {
            CanonicalProjectDocument.readUtf8(ByteArrayInputStream(malformed))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("valid UTF-8"))
    }

    @Test
    fun ecprojRejectsDuplicateMembersAtEveryObjectDepth() {
        val duplicate = """{"name":"Scene","nested":{"uid":1,"uid":2}}"""
        val failure = runCatching { CanonicalProjectDocument.toCanonicalJson(duplicate) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("Duplicate JSON object member <uid>"))
    }

    @Test
    fun ecprojRejectsDuplicateMembersWithEquivalentEscapedNames() {
        // JSON escape decoding must happen before member-name comparison.
        val duplicate = """{"name":"Scene","\u006eame":"Other"}"""
        val failure = runCatching { CanonicalProjectDocument.toCanonicalJson(duplicate) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("Duplicate JSON object member <name>"))
    }

    @Test
    fun ecprojDuplicateScannerIgnoresStringContentsAndSeparateArrayObjects() {
        val valid = """{"note":"\"uid\":1, \"uid\":2","items":[{"uid":1},{"uid":2}]}"""
        val canonical = JSONObject(CanonicalProjectDocument.toCanonicalJson(valid))
        assertEquals(2, canonical.getJSONArray("items").length())
        assertEquals("\"uid\":1, \"uid\":2", canonical.getString("note"))
    }

    @Test
    fun ecprojRejectsDuplicateMembersWithEquivalentEscapedControlCharacters() {
        // Both JSON spellings decode to the same tab-containing member name.
        val duplicate = """{"tab\tkey":1,"tab\u0009key":2}"""
        val failure = runCatching { CanonicalProjectDocument.toCanonicalJson(duplicate) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("Duplicate JSON object member"))
    }

    @Test
    fun ecprojxRoundTripsNestedJsonTypesAndEscaping() {
        val source = JSONObject()
            .put("project", "EffectCraft <motion> & \"quoted\"")
            .put("version", 1)
            .put("enabled", true)
            .put("nullable", JSONObject.NULL)
            .put("nested", JSONObject()
                .put("unicode", "कला 🎬")
                .put("values", JSONArray().put(0).put(-1.25).put(false).put(JSONObject.NULL)))
        val xml = CanonicalProjectDocument.toXml(source.toString())

        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("EffectCraftProject"))
        val restored = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))
        assertEquals(source.getString("project"), restored.getString("project"))
        assertEquals(source.getInt("version"), restored.getInt("version"))
        assertEquals(source.getBoolean("enabled"), restored.getBoolean("enabled"))
        assertTrue(restored.isNull("nullable"))
        val nested = restored.getJSONObject("nested")
        assertEquals("कला 🎬", nested.getString("unicode"))
        val values = nested.getJSONArray("values")
        assertEquals(0, values.getInt(0))
        assertEquals(-1.25, values.getDouble(1), 0.0)
        assertEquals(false, values.getBoolean(2))
        assertTrue(values.isNull(3))
    }

    @Test
    fun ecprojxRejectsUnsupportedVersionAndTrailingRoot() {
        val wrongVersion = """<?xml version="1.0"?><EffectCraftProject encoding="json" version="2"><obj/></EffectCraftProject>"""
        val versionError = runCatching { CanonicalProjectDocument.toCanonicalJson(wrongVersion) }.exceptionOrNull()
        assertTrue(versionError is IllegalArgumentException)
        assertTrue(versionError?.message.orEmpty().contains("version"))

        val trailingRoot = """<?xml version="1.0"?><EffectCraftProject encoding="json" version="1"><obj/></EffectCraftProject><extra/>"""
        val trailingError = runCatching { CanonicalProjectDocument.toCanonicalJson(trailingRoot) }.exceptionOrNull()
        assertTrue(trailingError != null)
        assertTrue(trailingError?.message.orEmpty().contains("after EffectCraft XML project"))
    }

    @Test
    fun ecprojxAcceptsLeadingCommentWithoutXmlDeclaration() {
        // XML declarations are optional and comments may precede the root.
        // Content sniffing must route this valid document to the XML parser,
        // not the JSON parser.
        val xml = """<!-- exported by EffectCraft -->
            <EffectCraftProject encoding="json" version="1"><obj><m n="name"><str>Scene</str></m></obj></EffectCraftProject>"""
        val canonical = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))
        assertEquals("Scene", canonical.getString("name"))
    }

    @Test
    fun ecprojxAcceptsProcessingInstructionAfterRoot() {
        // XML permits processing instructions after the document element.
        // Exporters may use them for non-project metadata; they are not a
        // second project payload and must not make a valid .ecprojx fail.
        val xml = """<?xml version="1.0"?>
            <EffectCraftProject encoding="json" version="1">
              <obj><m n="name"><str>Scene</str></m></obj>
            </EffectCraftProject>
            <?effectcraft-export complete?>"""
        val canonical = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))
        assertEquals("Scene", canonical.getString("name"))
    }

    @Test
    fun ecprojxRejectsDuplicateObjectMembersInsteadOfSilentlyOverwriting() {
        val xml = """<?xml version="1.0"?>
            <EffectCraftProject encoding="json" version="1">
              <obj>
                <m n="name"><str>first</str></m>
                <m n="name"><str>second</str></m>
              </obj>
            </EffectCraftProject>"""
        val failure = runCatching { CanonicalProjectDocument.toCanonicalJson(xml) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("Duplicate EffectCraft XML object member <name>"))
    }

    @Test
    fun validatesNativeEnvelopeAndCompositionMetadata() {
        val metadata = """{"valid":true,"compositions":[{"id":1,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":5,"layers":[]}],"items":[]}"""
        assertEquals("Main", CanonicalProjectDocument.validateNativeEnvelope(metadata).compositions.single().name)
    }

    @Test
    fun rejectsNativeProjectWithoutComposition() {
        val metadata = """{"valid":true,"compositions":[],"items":[]}"""
        val error = runCatching { CanonicalProjectDocument.validateNativeEnvelope(metadata) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("no compositions"))
    }

    @Test
    fun rejectsInvalidCompositionMetadata() {
        val metadata = """{"valid":true,"compositions":[{"id":1,"name":"Main","width":0,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":5,"layers":[]}],"items":[]}"""
        val error = runCatching { CanonicalProjectDocument.validateNativeEnvelope(metadata) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("dimensions"))
    }
}
