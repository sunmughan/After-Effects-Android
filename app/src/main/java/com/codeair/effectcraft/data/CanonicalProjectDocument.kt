package com.codeair.effectcraft.data

import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer
import org.xmlpull.v1.XmlPullParserException
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Strict import boundary for upstream canonical EffectCraft project documents.
 * Imported bytes are cached only after native schema parsing succeeds.
 *
 * EffectCraft's lossless .ecprojx XML is an element-per-value representation
 * of its .ecproj JSON. This converter handles that documented interchange
 * format; it does not parse Effekseer .efkproj or Adobe .aep files.
 */
object CanonicalProjectDocument {
    const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    private const val XML_ROOT = "EffectCraftProject"

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_IMPORT_BYTES, onProgress: (Long) -> Unit = {}): String {
        require(maxBytes > 0) { "Import byte limit must be positive" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maxBytes.toLong()) {
                "Project document exceeds the $maxBytes-byte import limit"
            }
            output.write(buffer, 0, count)
            onProgress(output.size().toLong())
        }
        // Charset's convenience conversion silently replaces malformed byte
        // sequences with U+FFFD. That changes project keys/paths before schema
        // validation and can make a corrupt import appear valid. Decode strictly
        // so the caller can reject the original bytes and avoid caching them.
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Project document is not valid UTF-8", failure)
        }
    }

    /** Normalizes a supported .ecproj JSON or .ecprojx XML document to canonical JSON. */
    fun toCanonicalJson(document: String): String {
        // UTF-8 exports may carry a BOM. Remove only that prefix for JSON;
        // retain ordinary leading whitespace so the JSON parser handles it.
        val withoutBom = document.removePrefix("\uFEFF")
        val probe = withoutBom.trimStart()
        // XML declarations are optional. A legal .ecprojx document may begin
        // with a comment before its root, so dispatch on the first markup
        // delimiter rather than only the declaration/root prefix. The strict
        // parser below still rejects unrelated XML roots and malformed input.
        return if (probe.startsWith("<")) {
            parseXmlProject(probe).toString()
        } else {
            withoutBom
        }
    }

    /** Produces EffectCraft's lossless .ecprojx XML interchange representation. */
    fun toXml(json: String): String {
        val project = JSONObject(json)
        val output = StringWriter()
        val serializer = KXmlSerializer()
        serializer.setOutput(output)
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, XML_ROOT)
            .attribute(null, "encoding", "json")
            .attribute(null, "version", "1")
        writeXmlValue(serializer, project)
        serializer.endTag(null, XML_ROOT)
        serializer.endDocument()
        return output.toString()
    }

    private fun parseXmlProject(xml: String): JSONObject {
        val parser = KXmlParser()
        parser.setInput(StringReader(xml))
        parser.nextTag()
        require(parser.eventType == XmlPullParser.START_TAG && parser.name == XML_ROOT) {
            "Unsupported XML project root"
        }
        require(parser.getAttributeValue(null, "encoding") == "json") {
            "Unsupported EffectCraft XML encoding"
        }
        require(parser.getAttributeValue(null, "version") == "1") {
            "Unsupported EffectCraft XML interchange version"
        }
        parser.nextTag()
        val value = readXmlValue(parser)
        require(value is JSONObject) { "EffectCraft XML project root must contain an object" }
        parser.nextTag()
        require(parser.eventType == XmlPullParser.END_TAG && parser.name == XML_ROOT) {
            "Malformed EffectCraft XML project root"
        }
        try {
            var trailingEvent = parser.next()
            while (trailingEvent != XmlPullParser.END_DOCUMENT) {
                // XML permits whitespace, comments and processing instructions
                // after the document element. Reject a second root or non-whitespace
                // character data without rejecting legal exporter metadata.
                when (trailingEvent) {
                    XmlPullParser.TEXT -> require(parser.text.isBlank()) {
                        "Unexpected content after EffectCraft XML project"
                    }
                    XmlPullParser.COMMENT, XmlPullParser.PROCESSING_INSTRUCTION -> Unit
                    else -> error("Unexpected content after EffectCraft XML project")
                }
                trailingEvent = parser.next()
            }
        } catch (error: XmlPullParserException) {
            throw IllegalArgumentException("Unexpected content after EffectCraft XML project", error)
        }
        return value
    }

    /** Reads one typed value; on return the parser is positioned on that value's END_TAG. */
    private fun readXmlValue(parser: XmlPullParser): Any {
        require(parser.eventType == XmlPullParser.START_TAG) { "Expected an EffectCraft XML value" }
        val tag = parser.name
        if (parser.isEmptyElementTag) {
            parser.next()
            return when (tag) {
                "obj" -> JSONObject()
                "arr" -> JSONArray()
                "str" -> ""
                "null" -> JSONObject.NULL
                else -> error("Empty <$tag/> is not a valid EffectCraft XML value")
            }
        }
        return when (tag) {
            "obj" -> {
                val result = JSONObject()
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> {
                            require(parser.name == "m") { "Expected <m> inside EffectCraft object" }
                            val key = parser.getAttributeValue(null, "n")
                                ?: error("EffectCraft object member has no n attribute")
                            require(!result.has(key)) {
                                "Duplicate EffectCraft XML object member <$key>"
                            }
                            parser.nextTag()
                            val value = readXmlValue(parser)
                            parser.nextTag()
                            require(parser.eventType == XmlPullParser.END_TAG && parser.name == "m") {
                                "Malformed EffectCraft object member <$key>"
                            }
                            result.put(key, value)
                        }
                        XmlPullParser.END_TAG -> {
                            require(parser.name == "obj") { "Malformed EffectCraft object" }
                            break
                        }
                        XmlPullParser.TEXT -> require(parser.text.isBlank()) { "Unexpected text inside EffectCraft object" }
                    }
                }
                result
            }
            "arr" -> {
                val result = JSONArray()
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> result.put(readXmlValue(parser))
                        XmlPullParser.END_TAG -> {
                            require(parser.name == "arr") { "Malformed EffectCraft array" }
                            break
                        }
                        XmlPullParser.TEXT -> require(parser.text.isBlank()) { "Unexpected text inside EffectCraft array" }
                    }
                }
                result
            }
            "str" -> parser.nextText()
            "num" -> BigDecimal(parser.nextText().trim())
            "bool" -> parser.nextText().trim().also {
                require(it == "true" || it == "false") { "Invalid EffectCraft boolean: $it" }
            }.toBoolean()
            "null" -> {
                parser.nextTag()
                require(parser.eventType == XmlPullParser.END_TAG && parser.name == "null") { "Malformed EffectCraft null" }
                JSONObject.NULL
            }
            else -> error("Unsupported EffectCraft XML value <$tag>")
        }
    }

    private fun writeXmlValue(serializer: XmlSerializer, value: Any?) {
        when {
            value == null || value === JSONObject.NULL -> serializer.startTag(null, "null").endTag(null, "null")
            value is JSONObject -> {
                serializer.startTag(null, "obj")
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    serializer.startTag(null, "m").attribute(null, "n", key)
                    writeXmlValue(serializer, value.get(key))
                    serializer.endTag(null, "m")
                }
                serializer.endTag(null, "obj")
            }
            value is JSONArray -> {
                serializer.startTag(null, "arr")
                for (index in 0 until value.length()) writeXmlValue(serializer, value.get(index))
                serializer.endTag(null, "arr")
            }
            value is String -> serializer.startTag(null, "str").text(value).endTag(null, "str")
            value is Boolean -> serializer.startTag(null, "bool").text(value.toString()).endTag(null, "bool")
            value is Number -> serializer.startTag(null, "num").text(value.toString()).endTag(null, "num")
            else -> error("Unsupported JSON value for EffectCraft XML: ${value::class.java.name}")
        }
    }

    fun validateNativeEnvelope(metadataJson: String): CanonicalProjectInfo {
        val project = CanonicalProjectInfo.parse(metadataJson)
        require(project.compositions.isNotEmpty()) { "Canonical project contains no compositions" }
        project.compositions.forEach { composition ->
            require(composition.width > 0 && composition.height > 0) {
                "Composition ${composition.name} has invalid dimensions"
            }
            require(composition.frameRateNum > 0 && composition.frameRateDen > 0) {
                "Composition ${composition.name} has an invalid frame rate"
            }
            require(composition.durationSeconds.isFinite() && composition.durationSeconds > 0.0) {
                "Composition ${composition.name} has an invalid duration"
            }
        }
        return project
    }
}
