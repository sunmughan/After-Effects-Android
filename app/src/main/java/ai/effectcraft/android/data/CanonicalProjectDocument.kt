package ai.effectcraft.android.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Strict import boundary for upstream canonical EffectCraft project documents.
 * Imported bytes are cached only after native schema parsing succeeds.
 */
object CanonicalProjectDocument {
    const val MAX_IMPORT_BYTES = 64 * 1024 * 1024

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): String {
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
        }
        return output.toString(Charsets.UTF_8.name())
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
