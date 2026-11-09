package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Byte-level JSON helpers (port of the official `footage` json tests). */
class AepFootageTest {

    @Test
    fun jsonStringReadsEscapes() {
        val json = "{\"a\":1,\"fullpath\":\"C:\\\\dir\\\\f\\u00e9.png\",\"b\":\"x\"}"
            .toByteArray(Charsets.UTF_8)
        assertEquals("C:\\dir\\fé.png", AepFootage.jsonString(json, "fullpath"))
        assertEquals("x", AepFootage.jsonString(json, "b"))
        assertNull(AepFootage.jsonString(json, "missing"))
    }

    @Test
    fun jsonStringToleratesSpaces() {
        val json = "{\"a\" : \"spaced\"}".toByteArray(Charsets.UTF_8)
        assertEquals("spaced", AepFootage.jsonString(json, "a"))
    }

    @Test
    fun jsonStringIgnoresKeyTextAsValue() {
        val json = "{\"a\":\"fullpath\",\"fullpath\":\"x\"}".toByteArray(Charsets.UTF_8)
        assertEquals("x", AepFootage.jsonString(json, "fullpath"))
    }

    @Test
    fun jsonStringDecodesSurrogatePairs() {
        val json = "{\"a\":\"x\\ud83d\\ude00y\"}".toByteArray(Charsets.UTF_8)
        assertEquals("x\uD83D\uDE00y", AepFootage.jsonString(json, "a"))
        val loneHigh = "{\"a\":\"\\ud83dz\"}".toByteArray(Charsets.UTF_8)
        assertEquals("\uFFFDz", AepFootage.jsonString(json = loneHigh, key = "a"))
        val loneLow = "{\"a\":\"\\ude00\"}".toByteArray(Charsets.UTF_8)
        assertEquals("\uFFFD", AepFootage.jsonString(json = loneLow, key = "a"))
    }

    @Test
    fun damagedJsonNeverCrashes() {
        val damaged = listOf(
            "{\"fullpath\":\"",
            "{\"fullpath\":\"abc",
            "{\"fullpath\":\"ab\\",
            "{\"fullpath\":\"\\u12\"}",
            "{\"fullpath\":\"\\uzzzz\"}",
            "{\"fullpath\":5}",
            "{\"fullpath\"}",
            "{\"fullpath\":}",
            ""
        )
        for (json in damaged) {
            // Must return null or a string, never throw.
            AepFootage.jsonString(json.toByteArray(Charsets.UTF_8), "fullpath")
            AepFootage.jsonBool(json.toByteArray(Charsets.UTF_8), "fullpath")
        }
    }

    @Test
    fun jsonBoolReadsValues() {
        val json = "{\"a\":true,\"b\":false,\"c\":1}".toByteArray(Charsets.UTF_8)
        assertEquals(true, AepFootage.jsonBool(json, "a"))
        assertEquals(false, AepFootage.jsonBool(json, "b"))
        assertNull(AepFootage.jsonBool(json, "c"))
        assertNull(AepFootage.jsonBool(json, "missing"))
    }
}
