package com.codeair.effectcraft.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
