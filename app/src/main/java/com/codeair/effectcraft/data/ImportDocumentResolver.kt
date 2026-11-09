package com.codeair.effectcraft.data

import com.codeair.effectcraft.data.aep.AepImportReport
import com.codeair.effectcraft.data.aep.AepImporter
import com.codeair.effectcraft.data.aep.AepxReader
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
 *   media resolved to the extracted files;
 * - a raw .aep binary or an .aepx document: imported by [AepImporter]
 *   (the official EffectCraft .aep importer, ported from
 *   storytold/effectcraft v0.21.1+). The import is honest about what
 *   it drops: [analyzeAep] returns the loss report first, and the
 *   caller shows it before the user allows the losses; [importAep]
 *   refuses a lossy file until they do.
 *
 * Garbage bytes are rejected with the reason. The native inspector
 * remains the final judge downstream.
 */
object ImportDocumentResolver {

    /** What [resolve] returned, so the caller can message it honestly. */
    data class ResolvedImport(
        val canonicalJson: String,
        /** True when the document came from an After Effects interchange export. */
        val wasAeInterchange: Boolean,
        /** True when the document came from a raw .aep/.aepx file. */
        val wasAep: Boolean = false,
        /** The .aep loss report, when [wasAep]. */
        val aepReport: AepImportReport.ImportReport? = null
    )

    /** True when [bytes] are a raw .aep (RIFX) or an .aepx document. */
    fun isAep(bytes: ByteArray): Boolean =
        AepxReader.isAepx(bytes) || (bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'X'.code.toByte())

    /**
     * Dry run for a raw .aep/.aepx: parses the file and returns the loss
     * report, before the user allows the losses.
     * @throws IllegalArgumentException with a user-facing reason for a file
     *   that cannot be read at all.
     */
    fun analyzeAep(bytes: ByteArray): AepImportReport.ImportReport =
        AepImporter.analyze(bytes).report

    /**
     * Imports a raw .aep/.aepx. With [allowLosses] false and any loss in
     * the report, throws instead of silently dropping content — the
     * caller shows the report from [analyzeAep] first and calls again
     * with the user's allowance.
     * @throws IllegalArgumentException with a user-facing reason for a file
     *   that cannot be read at all, or a refusal while losses are unallowed.
     */
    fun importAep(bytes: ByteArray, allowLosses: Boolean): ResolvedImport =
        when (val result = AepImporter.import(bytes, allowLosses)) {
            is AepImporter.ImportResult.Imported -> ResolvedImport(
                canonicalJson = result.canonicalJson,
                wasAeInterchange = false,
                wasAep = true,
                aepReport = result.report
            )
            is AepImporter.ImportResult.Refused -> throw IllegalArgumentException(
                "Import refused: ${result.report.summary()}"
            )
        }

    /**
     * Resolves picked [bytes] to a canonical .ecproj document.
     *
     * @param mediaBaseDir parent directory for extracted bundle media;
     *   a fresh sub-directory is created per bundle import.
     * @throws IllegalArgumentException with a user-facing reason for
     *   anything that cannot be imported.
     */
    fun resolve(bytes: ByteArray, mediaBaseDir: File): ResolvedImport {
        if (isAep(bytes)) {
            // The loss gate lives in the UI (analyzeAep + the loss dialog);
            // resolve() without an explicit allowance refuses a lossy file
            // instead of silently dropping content.
            return importAep(bytes, allowLosses = false)
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
