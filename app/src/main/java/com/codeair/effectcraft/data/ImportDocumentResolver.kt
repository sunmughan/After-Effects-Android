package com.codeair.effectcraft.data

import org.json.JSONObject
import java.io.File

/**
 * One entry point for every document the import picker can hand over,
 * and one canonical .ecproj JSON string out:
 *
 * - plain .ecproj JSON or .ecprojx XML: normalized by
 *   [CanonicalProjectDocument.toCanonicalJson], unchanged in meaning;
 * - an After Effects interchange document (JSON): converted by
 *   [AeInterchangeConverter];
 * - a ZIP bundle: unpacked by [ProjectBundleZip], then the project
 *   document inside takes one of the two paths above, with bundle
 *   media resolved to the extracted files.
 *
 * A raw .aep binary is rejected with the honest reason (direct .aep
 * import is not in this build yet — it is being built from the
 * official EffectCraft .aep importer; the ExtendScript export is the
 * interim route) instead of a native parse failure. Garbage bytes are
 * rejected the same way. The native inspector remains the final judge
 * downstream.
 */
object ImportDocumentResolver {

    /** What [resolve] returned, so the caller can message it honestly. */
    data class ResolvedImport(
        val canonicalJson: String,
        /** True when the document came from an After Effects interchange export. */
        val wasAeInterchange: Boolean
    )

    /**
     * Resolves picked [bytes] to a canonical .ecproj document.
     *
     * @param mediaBaseDir parent directory for extracted bundle media;
     *   a fresh sub-directory is created per bundle import.
     * @throws IllegalArgumentException with a user-facing reason for
     *   anything that cannot be imported.
     */
    fun resolve(bytes: ByteArray, mediaBaseDir: File): ResolvedImport {
        if (bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'X'.code.toByte()
        ) {
            throw IllegalArgumentException(
                "Direct .aep import is not in this build yet — it is being built from the official " +
                    "EffectCraft .aep importer. For now, export the project from After Effects with " +
                    "the included ExtendScript (docs/ae-export/ExportToEffectCraft.jsx) and import " +
                    "the .zip it creates."
            )
        }
        if (ProjectBundleZip.isZip(bytes)) {
            val destDir = File(mediaBaseDir, "bundle-${System.nanoTime()}")
            val unpacked = ProjectBundleZip.unpack(bytes, destDir)
            val document = unpacked.projectDocument.toString(Charsets.UTF_8)
            return resolveDocument(document) { relative ->
                File(unpacked.mediaDir, relative).takeIf { it.exists() }?.absolutePath
            }
        }
        val document = bytes.toString(Charsets.UTF_8)
        return resolveDocument(document) { null }
    }

    private fun resolveDocument(
        document: String,
        mediaResolver: (String) -> String?
    ): ResolvedImport {
        val probe = document.removePrefix("\uFEFF").trimStart()
        if (probe.startsWith("<?xml") || probe.startsWith("<EffectCraftProject")) {
            // .ecprojx: toCanonicalJson parses the XML eagerly and throws
            // a precise error for malformed input.
            return ResolvedImport(CanonicalProjectDocument.toCanonicalJson(document), false)
        }
        try {
            JSONObject(document.removePrefix("\uFEFF"))
        } catch (failure: Exception) {
            throw IllegalArgumentException("Not a readable project document: ${failure.message}")
        }
        return if (AeInterchange.isInterchange(document)) {
            val project = AeInterchange.parse(document)
            ResolvedImport(AeInterchangeConverter.toCanonicalJson(project, mediaResolver), true)
        } else {
            ResolvedImport(CanonicalProjectDocument.toCanonicalJson(document), false)
        }
    }
}
