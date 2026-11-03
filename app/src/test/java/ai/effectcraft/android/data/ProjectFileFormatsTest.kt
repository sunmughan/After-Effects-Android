package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFileFormatsTest {
    @Test
    fun recognizesSupportedEffectCraftInterchangeExtensionsCaseInsensitively() {
        assertEquals(ProjectFileFormats.Kind.EFFECTCRAFT_JSON, ProjectFileFormats.classify("scene.ecproj"))
        assertEquals(ProjectFileFormats.Kind.EFFECTCRAFT_XML, ProjectFileFormats.classify("Scene.ECPROJX"))
        assertNull(ProjectFileFormats.importError("scene.ecproj"))
        assertNull(ProjectFileFormats.importError("scene.ecprojx"))
    }

    @Test
    fun reportsEffekseerAndAdobeCompatibilityBoundariesPrecisely() {
        assertEquals(ProjectFileFormats.Kind.EFFEKSEER_PROJECT, ProjectFileFormats.classify("scene.efkproj"))
        assertEquals(ProjectFileFormats.Kind.ADOBE_AFTER_EFFECTS, ProjectFileFormats.classify("scene.aep"))
        assertEquals(
            "Effekseer .efkproj is not supported yet; no Effekseer parser/converter is integrated.",
            ProjectFileFormats.importError("scene.efkproj")
        )
        assertEquals(
            "Adobe .aep is proprietary and cannot be imported without a compatible licensed parser/converter.",
            ProjectFileFormats.importError("scene.aep")
        )
    }

    @Test
    fun keepsAndroidDraftsOutOfCanonicalImportAndAllowsUnknownContentDetection() {
        assertEquals(ProjectFileFormats.Kind.ANDROID_DRAFT, ProjectFileFormats.classify("draft.efx-draft"))
        assertEquals(
            "This is an Android offline draft (.efx-draft). Use Open offline project instead of canonical import.",
            ProjectFileFormats.importError("draft.efx-draft")
        )
        assertEquals(ProjectFileFormats.Kind.UNKNOWN, ProjectFileFormats.classify("download"))
        assertNull(ProjectFileFormats.importError("download"))
    }
}
