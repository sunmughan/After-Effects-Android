package com.codeair.effectcraft.data

import java.io.File

/**
 * Removes only abandoned EffectCraft sequence staging archives.
 *
 * Sequence rendering writes to cache before copying a completed, validated ZIP
 * to SAF. A process kill can bypass the export coroutine's finally block and
 * leave a cache ZIP behind. Startup cleanup is age-gated so it never touches a
 * recently-created archive (including one owned by another live export).
 */
object StaleSequenceExportCleanup {
    const val FILE_PREFIX = "effectcraft-sequence-"
    const val FILE_SUFFIX = ".zip"
    const val DEFAULT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

    data class Result(
        val removed: Int,
        val retained: Int,
        val failed: List<String>
    )

    fun cleanup(
        cacheDirectory: File,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
    ): Result {
        require(maxAgeMillis > 0L) { "Staging archive retention must be positive" }
        if (!cacheDirectory.isDirectory) return Result(removed = 0, retained = 0, failed = emptyList())

        var removed = 0
        var retained = 0
        val failed = mutableListOf<String>()
        cacheDirectory.listFiles().orEmpty().forEach { candidate ->
            if (!candidate.isFile || !candidate.name.startsWith(FILE_PREFIX) ||
                !candidate.name.endsWith(FILE_SUFFIX)) return@forEach

            val modified = candidate.lastModified()
            // Unknown timestamps are retained rather than risking deletion.
            val age = if (modified <= 0L || modified > nowMillis) 0L else nowMillis - modified
            if (age < maxAgeMillis) {
                retained++
            } else if (candidate.delete()) {
                removed++
            } else {
                failed += candidate.name
            }
        }
        return Result(removed, retained, failed)
    }
}
