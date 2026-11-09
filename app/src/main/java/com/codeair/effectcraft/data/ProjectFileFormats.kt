package com.codeair.effectcraft.data

/**
 * Extension-level guardrails for the canonical import flow.
 *
 * Content parsing remains authoritative for .ecproj/.ecprojx (and extensionless
 * documents); this classifier only gives known unsupported formats a precise,
 * actionable error before reading or caching their bytes.
 */
object ProjectFileFormats {
    enum class Kind {
        EFFECTCRAFT_JSON,
        EFFECTCRAFT_XML,
        EFFEKSEER_PROJECT,
        ADOBE_AFTER_EFFECTS,
        ANDROID_DRAFT,
        UNKNOWN
    }

    fun classify(displayName: String): Kind = when (
        displayName.substringAfterLast('.', "").lowercase()
    ) {
        "ecproj" -> Kind.EFFECTCRAFT_JSON
        "ecprojx" -> Kind.EFFECTCRAFT_XML
        "efkproj" -> Kind.EFFEKSEER_PROJECT
        "aep" -> Kind.ADOBE_AFTER_EFFECTS
        "efx-draft" -> Kind.ANDROID_DRAFT
        else -> Kind.UNKNOWN
    }

    /** Returns a user-facing reason for known non-canonical files, or null.
     * `.aep`/`.aepx` are importable (the .aep importer), so they return null. */
    fun importError(displayName: String): String? = when (classify(displayName)) {
        Kind.EFFEKSEER_PROJECT ->
            "Effekseer .efkproj is not supported yet; no Effekseer parser/converter is integrated."
        Kind.ANDROID_DRAFT ->
            "This is an Android offline draft (.efx-draft). Use Open offline project instead of canonical import."
        else -> null
    }
}
