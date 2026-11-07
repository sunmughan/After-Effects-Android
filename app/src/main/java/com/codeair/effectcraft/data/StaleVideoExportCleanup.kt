package com.codeair.effectcraft.data

import java.io.File

/**
 * Removes only abandoned EffectCraft MP4 staging files. Video rendering
 * stages under cache before a completed, validated MP4 is copied to SAF; a
 * process kill bypasses the export coroutine's finally block and leaves a
 * partial MP4 behind. Startup cleanup is age-gated so it never touches a
 * recently-created file (including one owned by another live export), and
 * the caller reports the recovery instead of silently dropping it.
 * Mirrors [StaleSequenceExportCleanup] for the video staging prefix.
 */
object StaleVideoExportCleanup {
    const val FILE_PREFIX = "effectcraft-video-"
    const val FILE_SUFFIX = ".mp4"
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
        require(maxAgeMillis > 0L) { "Staging video retention must be positive" }
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
